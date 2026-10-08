package me.ash.reader.infrastructure.rss

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.net.HttpTextDecoder
import me.ash.reader.infrastructure.net.HttpTextKind
import me.ash.reader.infrastructure.source.isFeedburnerFeedUrl

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** 优先实际解析 Feed；网页 alternate 采用最终跳转 URL 作为相对链接基址。 */
internal suspend fun RssHelper.discoverSourceFeed(inputUrl: String): DiscoveredFeed =
    withContext(ioDispatcher) {
        val directError = try {
            return@withContext discoverCandidate(inputUrl, fromPage = false)
        } catch (cancelled: CancellationException) {
            // 超时和主动取消必须停止发现，不能继续请求网页。
            throw cancelled
        } catch (error: Exception) {
            // 直接 XML 解析失败允许进入既有网页发现流程。
            error
        }
        // 已知 Feed 托管地址不探测平台根目录的 /feed、/rss，更不能把错误页当来源。
        if (isFeedburnerFeedUrl(inputUrl)) throw directError
        val candidates = try {
            discoverPageCandidates(inputUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 辅助网页发现失败不能覆盖第一次 Feed 请求的 HTTP/解析异常。
            if (error !== directError) directError.addSuppressed(error)
            throw directError
        }
        for (candidateUrl in candidates) {
            currentCoroutineContext().ensureActive()
            try {
                return@withContext discoverCandidate(candidateUrl, fromPage = true)
            } catch (cancelled: CancellationException) {
                // 当前候选取消后不再遍历其它地址。
                throw cancelled
            } catch (_: Exception) {
                // 网页声明和常用路径只作为候选，必须逐个实际验证。
            }
        }
        throw directError
    }

/** XML 自带图标保留，额外图标请求不占用来源发现预算。 */
private suspend fun RssHelper.discoverCandidate(url: String, fromPage: Boolean): DiscoveredFeed {
    val parsed = parseFeedUrlWithValidators(url, iconSourceUrl = "")
    return DiscoveredFeed(
        feedUrl = url, feed = parsed.feed, discoveredFromPage = fromPage,
        etag = parsed.etag, lastModified = parsed.lastModified,
    )
}

/** 在可取消的响应体读取范围内解析网页，并关闭整个 HTTP 响应。 */
private suspend fun RssHelper.discoverPageCandidates(url: String): List<String> =
    withFeedResponse(okHttpClient, websitePageRequest(url)) { response ->
        val finalUrl = response.request.url.toString()
        val html = HttpTextDecoder.decode(
            bytes = response.body.bytes(), contentType = response.header("Content-Type"), kind = HttpTextKind.HTML,
        )
        val document = Jsoup.parse(html, finalUrl)
        (alternateFeedCandidates(document) + buildCommonFeedCandidates(finalUrl)).distinct()
    }

/** rel/type 声明仅提供发现线索，最终有效性由 Feed 解析确认。 */
private fun alternateFeedCandidates(document: Document): List<String> =
    document.select("link[rel~=alternate][href]").asSequence()
        .filter { element ->
            val type = element.attr("type").lowercase()
            listOf("rss", "atom", "rdf", "xml").any(type::contains)
        }
        .map { it.absUrl("href") }.filter { it.isNotBlank() }.toList()

/** 沿用常见同域 Feed 路径，所有结果仍须通过真实 XML 解析。 */
private fun buildCommonFeedCandidates(inputUrl: String): List<String> {
    val uri = runCatching { URI(inputUrl) }.getOrNull() ?: return emptyList()
    val scheme = uri.scheme ?: return emptyList()
    val authority = uri.rawAuthority ?: return emptyList()
    val origin = "$scheme://$authority"
    return listOf(
        "$origin/feed", "$origin/feed/", "$origin/rss", "$origin/rss.xml",
        "$origin/atom.xml", "$origin/feed.xml", "$origin/index.xml",
    )
}
