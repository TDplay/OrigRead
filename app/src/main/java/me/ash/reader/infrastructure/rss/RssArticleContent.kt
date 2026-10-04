package me.ash.reader.infrastructure.rss

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.content.FullContentException
import me.ash.reader.infrastructure.content.FullContentFailureClassifier
import me.ash.reader.infrastructure.content.FullContentFailureReason
import me.ash.reader.infrastructure.net.HttpTextDecoder
import me.ash.reader.infrastructure.net.HttpTextKind
import okhttp3.Response
import timber.log.Timber

/** 微信文章按既有策略由前台动态渲染，后台预取保持 HTTP。 */
private const val WECHAT_ARTICLE_HOST = "mp.weixin.qq.com"

private data class ArticleContentRequest(val link: String, val title: String, val allowDynamic: Boolean)

/** 全文提取保留既有动态策略，HTTP 响应消费复用协程取消管理。 */
internal suspend fun RssHelper.extractArticleContent(
    link: String, title: String, allowDynamicFallback: Boolean = true,
): String = withContext(ioDispatcher) {
    val uri = runCatching { URI(link) }.getOrNull()
    if (uri?.scheme !in setOf("http", "https")) {
        throw FullContentException(FullContentFailureReason.INVALID_URL, "Unsupported article URL")
    }
    val request = ArticleContentRequest(link, title, allowDynamicFallback)
    if (allowDynamicFallback && uri != null && isWeChatArticleUrl(uri)) {
        return@withContext dynamicContentOrNull(request, "", FullContentFailureReason.ACCESS_RESTRICTED)
            ?: throw FullContentException(
                FullContentFailureReason.ACCESS_RESTRICTED,
                "WeChat article remained restricted after browser rendering",
            )
    }
    try {
        withFeedResponse(okHttpClient, articleRequest(link)) { extractResponseContent(request, it) }
    } catch (cancelled: CancellationException) {
        // 主动取消不得包装为全文提取失败并触发后续尝试。
        throw cancelled
    } catch (failure: FullContentException) {
        // 已分类失败保留原因和上下文。
        throw failure
    } catch (failure: Throwable) {
        // 未分类的网络或提取错误按既有全文错误模型上报。
        throw FullContentException(
            reason = FullContentFailureClassifier.classifyThrowable(failure),
            message = "Article request failed", cause = failure,
        )
    }
}

/** 静态提取失败后仍按原有策略判断是否允许动态内容。 */
private suspend fun RssHelper.extractResponseContent(request: ArticleContentRequest, response: Response): String {
    if (!response.isSuccessful) return extractRestrictedResponse(request, response)
    val html = HttpTextDecoder.decode(
        bytes = response.body.bytes(), contentType = response.header("Content-Type"), kind = HttpTextKind.HTML,
    )
    contentExtractionService.extract(html, request.link, request.title)?.let { return it.html }
    val reason = FullContentFailureClassifier.classifyHtml(html)
    return dynamicContentOrNull(request, html, reason)
        ?: throw FullContentException(reason, "No valid article content was extracted")
}

/** 受限响应只有前台允许动态阅读时才请求渲染，普通 HTTP 失败直接暴露。 */
private suspend fun RssHelper.extractRestrictedResponse(request: ArticleContentRequest, response: Response): String {
    val reason = FullContentFailureClassifier.classifyHttpStatus(response.code)
    if (request.allowDynamic && reason == FullContentFailureReason.ACCESS_RESTRICTED) {
        dynamicContentOrNull(request, response.body.string(), reason)?.let { return it }
    }
    throw FullContentException(reason, "Article request failed with HTTP ${response.code}")
}

/** 既有动态路径仅隔离普通提取错误并记录原因，取消始终向上游传播。 */
private suspend fun RssHelper.dynamicContentOrNull(
    request: ArticleContentRequest, html: String, reason: FullContentFailureReason,
): String? {
    if (!request.allowDynamic) return null
    return try {
        dynamicArticleContentService.extract(
            url = request.link, expectedTitle = request.title, staticHtml = html,
            staticFailureReason = reason, allowRestrictedFallback = reason == FullContentFailureReason.ACCESS_RESTRICTED,
        )?.html
    } catch (cancelled: CancellationException) {
        // WebView 取消不能退回网络或变成空结果。
        throw cancelled
    } catch (error: Exception) {
        // 保留现有动态失败返回策略，同时明确记录实际渲染异常。
        Timber.w(error, "动态全文提取失败")
        null
    }
}

/** 微信域名及其子域名沿用同一前台阅读策略。 */
private fun isWeChatArticleUrl(uri: URI): Boolean {
    val host = uri.host.orEmpty().lowercase()
    return host == WECHAT_ARTICLE_HOST || host.endsWith(".$WECHAT_ARTICLE_HOST")
}
