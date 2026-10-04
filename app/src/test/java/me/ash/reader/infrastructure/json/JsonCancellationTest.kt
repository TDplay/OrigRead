package me.ash.reader.infrastructure.json

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

/** 真实响应体取消后应结束整个 JSON 探测，不能转向后续规则或内置 WordPress。 */
class JsonCancellationTest {
    /** 第一个导入规则读正文期间取消，第二规则不得发出 HTTP 请求。 */
    @Test fun bodyCancellationStopsTheRemainingProbeRules() = runBlocking<Unit> {
        val headers = CompletableDeferred<Unit>()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("[]").setBodyDelay(BODY_DELAY_SECONDS, TimeUnit.SECONDS))
        server.start()
        try {
            val repository = mock<JsonRuleRepository>()
            whenever(repository.findRules(any())).thenReturn(listOf(rule("first"), rule("second")))
            whenever(repository.resolveEndpoint(any(), any())).thenAnswer {
                URI(it.getArgument<String>(0)).resolve(it.getArgument<String>(1)).toString()
            }
            val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).also { headers.complete(Unit) }
            }.build()
            val helper = JsonSourceHelper(repository, JsonArticleParser(), client, Dispatchers.IO)
            val job = launch { helper.probe(server.url("/page").toString()) }
            withTimeout(CANCEL_WAIT_MILLIS) { headers.await(); job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
            assertEquals("/api/first", server.takeRequest().path)
        } finally {
            // 正文延迟只在测试服务器发生，结束时关闭该连接。
            server.shutdown()
        }
    }

    /** 同一页面匹配两条可解析规则，以便观察是否错误继续探测。 */
    private fun rule(id: String) = JsonRule(
        id = id, name = id, hosts = listOf("localhost"), endpoint = "/api/$id",
        itemsPath = "$", titlePath = "$.title", linkPath = "$.link",
    )

    private companion object {
        /** 必须在延迟正文完成前退出，验证主动取消而非正常网络超时。 */
        const val CANCEL_WAIT_MILLIS = 1_000L
        const val BODY_DELAY_SECONDS = 2L
    }
}
