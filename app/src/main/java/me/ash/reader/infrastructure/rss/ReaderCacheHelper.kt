package me.ash.reader.infrastructure.rss

import androidx.annotation.CheckResult
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.infrastructure.content.FullContentCacheException
import me.ash.reader.infrastructure.content.FullContentException
import me.ash.reader.infrastructure.content.FullContentFailureReason
import me.ash.reader.infrastructure.di.IODispatcher
import timber.log.Timber

class ReaderCacheHelper @Inject constructor(
    private val contentCache: ReaderContentCache,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val rssHelper: RssHelper,
) {
    /** 严格写入入口：只有原子发布完成才算缓存成功，存储异常继续传播。 */
    suspend fun writeContentToCache(content: String, article: Article) = contentCache.write(content, article)

    /** 分开返回有效正文与缓存写入失败；仅存储失败允许前台继续阅读。 */
    suspend fun cacheFullContent(content: String, article: Article): ReaderFullContentResult {
        return try {
            writeContentToCache(content, article)
            currentCoroutineContext().ensureActive()
            ReaderFullContentResult(content)
        } catch (error: FullContentCacheException) {
            // 已提取的正文继续交给前台；失败原因既返回给调用者，也保留完整日志。
            currentCoroutineContext().ensureActive()
            Timber.w(error, "全文未能保存离线缓存：account=%s article=%s", article.accountId, article.id)
            ReaderFullContentResult(content, cacheWriteFailure = error)
        }
    }

    /** 缓存缺失保持未命中语义；真实存储失败与取消分别交回上层。 */
    @CheckResult
    suspend fun readFullContent(article: Article): Result<String> {
        return try {
            Result.success(contentCache.read(article))
        } catch (error: CancellationException) {
            // 切换文章或取消 Worker 必须终止上层请求。
            throw error
        } catch (error: Exception) {
            // 未命中由抓取入口处理，存储故障保留具体异常类型。
            Result.failure(error)
        }
    }

    /** 抓取结果携带独立的离线保存状态，前台和后台按各自契约消费。 */
    private suspend fun fetchFullContentInternal(
        article: Article,
        allowDynamicFallback: Boolean,
    ): Result<ReaderFullContentResult> = withContext(ioDispatcher) {
        try {
            // 后台批量预取禁止启动 WebView，前台仍使用已有动态正文提取流程。
            val content = rssHelper.parseFullContent(
                link = article.link,
                title = article.title,
                allowDynamicFallback = allowDynamicFallback,
            )
            if (content.isBlank()) {
                throw FullContentException(FullContentFailureReason.NO_CONTENT, "未提取到有效正文")
            }
            Result.success(cacheFullContent(content, article))
        } catch (error: CancellationException) {
            // 取消不能被包装为下载失败或已有正文成功。
            throw error
        } catch (error: Exception) {
            // 网络及提取故障仍是真实失败，只有明确的缓存写入故障有独立结果。
            Result.failure(error)
        }
    }

    /** 阅读页获得正文与离线保存状态；仅缺失或空缓存触发重新抓取。 */
    @CheckResult
    suspend fun readOrFetchFullContentForReading(article: Article): Result<ReaderFullContentResult> {
        val cached = readFullContent(article)
        if (cached.isSuccess) return cached.map { ReaderFullContentResult(it) }
        val failure = cached.exceptionOrNull()
        if (failure !is ReaderCacheMissException) return Result.failure(requireNotNull(failure))
        return fetchFullContentInternal(article, allowDynamicFallback = true)
    }

    /** AI 附件消费正文文本；离线写入状态由结构化读取入口和缓存日志保留。 */
    @CheckResult
    suspend fun readOrFetchFullContent(article: Article): Result<String> =
        readOrFetchFullContentForReading(article).map { it.content }

    /** 后台成功必须有有效缓存；读写、抓取失败均记录真实原因并返回失败。 */
    suspend fun checkOrFetchFullContent(article: Article): Boolean {
        val cached = readFullContent(article)
        if (cached.isSuccess) return true
        val failure = cached.exceptionOrNull()
        if (failure !is ReaderCacheMissException) {
            Timber.w(failure, "全文缓存读取失败：account=%s article=%s", article.accountId, article.id)
            return false
        }
        return fetchFullContentInternal(article, allowDynamicFallback = false).fold(
            onSuccess = { it.cacheWriteFailure == null },
            onFailure = {
                Timber.w(it, "后台全文抓取失败：account=%s article=%s", article.accountId, article.id)
                false
            },
        )
    }

    /** 归档后仍按被归档文章的账户删除，使用与读写一致的互斥入口。 */
    suspend fun deleteCacheFor(articleId: String, accountId: Int): Boolean = contentCache.delete(articleId, accountId)
}
