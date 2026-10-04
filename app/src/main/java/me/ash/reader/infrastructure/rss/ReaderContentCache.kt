package me.ash.reader.infrastructure.rss

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.infrastructure.content.FullContentCacheException
import me.ash.reader.infrastructure.di.IODispatcher

/** 只负责正文文件 I/O；所有注入实例共享互斥，网络抓取不占用文件锁。 */
@Singleton
class ReaderContentCache @Inject constructor(
    @ApplicationContext context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val cacheDir = context.cacheDir.resolve("readability")
    private val cacheMutex = Mutex()

    /** 仅读取已发布文件；不存在、空文件或纯空白文件均视为未命中。 */
    suspend fun read(article: Article): String = accessCache("读取") {
        val file = cacheFile(article.id, article.accountId)
        val content = try {
            Files.newBufferedReader(file.toPath(), Charsets.UTF_8).use { it.readText() }
        } catch (error: NoSuchFileException) {
            // 文件缺失允许重新抓取；其它 I/O 错误由统一入口保留存储失败原因。
            throw ReaderCacheMissException("全文缓存不存在：${file.path}", error)
        }
        if (content.isBlank()) throw ReaderCacheMissException("全文缓存为空：${file.path}")
        content
    }

    /** 完整正文先写临时文件，再一次发布；失败不会截断已有有效正文。 */
    suspend fun write(content: String, article: Article): Unit = accessCache("写入") {
        require(content.isNotBlank()) { "不能缓存空白正文" }
        writeAtomically(cacheFile(article.id, article.accountId), content)
    }

    /** 归档删除与读写共用互斥，避免删除正在发布的同一缓存条目。 */
    suspend fun delete(articleId: String, accountId: Int): Boolean = accessCache("删除") {
        Files.deleteIfExists(cacheFile(articleId, accountId).toPath())
    }

    /** 只串行化短暂文件操作；本地存储异常统一携带类型，取消不包装为成功。 */
    private suspend fun <T> accessCache(operation: String, action: suspend () -> T): T = withContext(ioDispatcher) {
        cacheMutex.withLock {
            try {
                currentCoroutineContext().ensureActive()
                action()
            } catch (error: ReaderCacheMissException) {
                // 缓存未命中与存储故障具有不同语义，调用方仅对未命中发起抓取。
                throw error
            } catch (error: IOException) {
                // 保留读写、同步、关闭及原子替换的真实失败，不误归为网络问题。
                throw FullContentCacheException("全文缓存${operation}失败", error)
            } catch (error: SecurityException) {
                // 目录权限失败也是本地存储故障，不能伪装为未命中。
                throw FullContentCacheException("全文缓存${operation}无权限", error)
            }
        }
    }

    /** 同目录保证同一文件系统；同步并关闭成功后才替换最终文件，不降级为普通覆盖。 */
    private suspend fun writeAtomically(file: File, content: String) {
        val directory = file.toPath().parent
        Files.createDirectories(directory)
        val staging = Files.createTempFile(directory, file.name, STAGING_SUFFIX)
        try {
            staging.toFile().outputStream().use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            Files.move(staging, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            // 失败或取消只清理本次临时文件；清理失败附到原异常，旧正文始终保留。
            try {
                Files.deleteIfExists(staging)
            } catch (cleanupError: Exception) {
                // 不覆盖原写入错误，同时保留临时文件清理失败的诊断信息。
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    /** 沿用已有版本与账户目录，保留其它账户和旧的有效缓存。 */
    private fun cacheFile(articleId: String, accountId: Int): File =
        cacheDir.resolve(accountId.toString()).resolve(getFileNameFor(articleId))

    /** 每次独立创建摘要实例，避免并发计算缓存文件名串用摘要状态。 */
    @OptIn(ExperimentalStdlibApi::class)
    private fun getFileNameFor(articleId: String): String {
        val bytes = "$CACHE_VERSION:$articleId".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.toHexString() + ".html"
    }

    private companion object {
        /** 沿用正文清洗版本，原子写入不使已有效的正文失效。 */
        const val CACHE_VERSION = 4
        /** 未发布正文的临时后缀，读取入口始终忽略这些文件。 */
        const val STAGING_SUFFIX = ".tmp"
    }
}
