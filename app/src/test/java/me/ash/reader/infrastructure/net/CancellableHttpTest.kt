package me.ash.reader.infrastructure.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用真实本机 HTTP 连接检查响应头和响应体阶段的取消，超时只限定测试等待。 */
class CancellableHttpTest {
    /** 没有响应头时，取消直接关闭 Call，不能等待正常网络超时。 */
    @Test fun cancellationStopsWaitingForHeaders() = runBlocking<Unit> {
        val arrived = CompletableDeferred<Unit>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.complete(Unit)
                return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
        }
        server.start()
        try {
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/headers")).build())
            val job = launch(Dispatchers.IO) { call.useCancellable { it.body.string() } }
            withTimeout(CANCEL_WAIT_MILLIS) { arrived.await(); job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(call.isCanceled())
        } finally {
            // 关闭测试服务器及本例 socket，不把测试请求留给后续用例。
            server.shutdown()
        }
    }

    /** 响应头已收到但正文仍阻塞时，响应体也处于同一取消范围。 */
    @Test fun cancellationClosesTheResponseBody() = runBlocking<Unit> {
        val headers = CompletableDeferred<Unit>()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("Delayed body").setBodyDelay(BODY_DELAY_SECONDS, TimeUnit.SECONDS))
        server.start()
        try {
            val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).also { headers.complete(Unit) }
            }.build()
            val call = client.newCall(Request.Builder().url(server.url("/body")).build())
            val job = launch { withContext(Dispatchers.IO) { call.useCancellable { it.body.string() } } }
            withTimeout(CANCEL_WAIT_MILLIS) { headers.await(); job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(call.isCanceled())
        } finally {
            // 延迟响应只属于本机测试服务器，结束后释放连接。
            server.shutdown()
        }
    }

    private companion object {
        /** 取消须在正文延迟结束前完成，防止仅靠正常请求超时通过。 */
        const val CANCEL_WAIT_MILLIS = 1_000L
        const val BODY_DELAY_SECONDS = 2L
    }
}
