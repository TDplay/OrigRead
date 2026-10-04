package me.ash.reader.infrastructure.rss

import java.io.FileNotFoundException
import me.ash.reader.infrastructure.content.FullContentCacheException

/** 有效正文与离线保存是两个结果；写盘失败不能让已获取的正文失去可读性。 */
data class ReaderFullContentResult(
    val content: String,
    val cacheWriteFailure: FullContentCacheException? = null,
)

/** 仅缺失或空白缓存触发重新抓取，权限和其它读盘错误仍明确报错。 */
internal class ReaderCacheMissException(message: String, cause: Throwable? = null) : FileNotFoundException(message) {
    init { initCause(cause) }
}
