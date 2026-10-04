package me.ash.reader.infrastructure.rss

import android.content.Context
import androidx.annotation.CheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.infrastructure.di.IODispatcher

class ReaderCacheHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val rssHelper: RssHelper,
) {
    private val cacheDir = context.cacheDir.resolve("readability")

    /** 沿用账户目录布局，后台任务和阅读页统一使用文章的账户归属。 */
    private fun cacheFile(articleId: String, accountId: Int): File =
        cacheDir.resolve(accountId.toString()).resolve(getFileNameFor(articleId))

    @OptIn(ExperimentalStdlibApi::class)
    private fun getFileNameFor(articleId: String): String {
        val bytes = "$CACHE_VERSION:$articleId".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.toHexString() + ".html"
    }

    /** 写入失败直接向调用者传播，不能把未落盘的正文当成缓存成功。 */
    suspend fun writeContentToCache(content: String, article: Article) = withContext(ioDispatcher) {
        val file = cacheFile(article.id, article.accountId)
        file.parentFile!!.mkdirs()
        file.writeText(content)
    }

    @CheckResult
    /** 缓存读取固定文章账户；不存在与真实读盘失败均保留具体异常。 */
    suspend fun readFullContent(article: Article): Result<String> = withContext(ioDispatcher) {
        try {
            Result.success(cacheFile(article.id, article.accountId).readText())
        } catch (error: CancellationException) {
            // 切换文章或取消 Worker 必须终止上层请求。
            throw error
        } catch (error: Exception) {
            // 缓存缺失由读写协调入口处理，其他 I/O 异常向阅读页报告。
            Result.failure(error)
        }
    }

    private suspend fun fetchFullContentInternal(
        article: Article,
        allowDynamicFallback: Boolean,
    ): Result<String> {
        return withContext(ioDispatcher) {
            try {
                val fullContent =
                    rssHelper.parseFullContent(
                        link = article.link,
                        title = article.title,
                        allowDynamicFallback = allowDynamicFallback,
                    )
                if (fullContent.isNotBlank()) {
                    writeContentToCache(fullContent, article)
                    Result.success(fullContent)
                } else Result.failure(IllegalStateException("未提取到有效正文"))
            } catch (error: CancellationException) {
                // 取消不能被包装为下载失败或继续访问下一个正文来源。
                throw error
            } catch (error: Exception) {
                // 网络、提取与缓存写入失败都保留原异常，交由阅读页或 Worker 处理。
                Result.failure(error)
            }
        }
    }

    @CheckResult
    suspend fun readOrFetchFullContent(article: Article): Result<String> {
        return withContext(ioDispatcher) {
            val result = readFullContent(article)
            if (result.isSuccess) return@withContext result
            if (result.exceptionOrNull() !is FileNotFoundException) return@withContext result
            fetchFullContentInternal(article, allowDynamicFallback = true)
        }
    }

    suspend fun checkOrFetchFullContent(article: Article): Boolean {
        return withContext(ioDispatcher) {
            val file = cacheFile(article.id, article.accountId)
            try {
                if (!file.exists()) {
                    // ReaderWorker 可能一次预取多篇文章，后台任务禁止启动 WebView。
                    return@withContext fetchFullContentInternal(article, allowDynamicFallback = false)
                        .fold(onFailure = { false }, onSuccess = { true })
                } else {
                    return@withContext true
                }
            } catch (error: SecurityException) {
                // 后台任务无法访问缓存时记录具体原因并交回既有重试结果。
                timber.log.Timber.w(error, "全文缓存访问失败：%s", article.id)
                return@withContext false
            }
        }
    }

    /** 归档后仍使用被归档文章的账户，只删除该账户下的对应正文。 */
    suspend fun deleteCacheFor(articleId: String, accountId: Int): Boolean = withContext(ioDispatcher) {
        val file = cacheFile(articleId, accountId)
        if (!file.exists()) return@withContext false
        check(file.delete()) { "无法删除全文缓存：${file.path}" }
        true
    }


    private companion object {
        /** 全文清洗规则变化时递增，使旧缓存自动失效并重新抓取。 */
        const val CACHE_VERSION = 4
    }
}
