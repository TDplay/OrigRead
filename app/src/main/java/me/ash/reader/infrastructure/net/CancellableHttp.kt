package me.ash.reader.infrastructure.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response
import okhttp3.executeAsync

/** 将协程取消绑定到请求及响应体读取，调用者在 IO dispatcher 上消费响应。 */
suspend fun <T> Call.useCancellable(block: suspend (Response) -> T): T = coroutineScope {
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            this@useCancellable.cancel()
        }
    }
    try {
        executeAsync().use { response ->
            currentCoroutineContext().ensureActive()
            block(response).also { currentCoroutineContext().ensureActive() }
        }
    } catch (error: Exception) {
        // 取消关闭 socket 后可能先抛 IOException，优先恢复所属协程的取消原因。
        currentCoroutineContext().ensureActive()
        throw error
    } finally {
        watcher.cancel()
    }
}
