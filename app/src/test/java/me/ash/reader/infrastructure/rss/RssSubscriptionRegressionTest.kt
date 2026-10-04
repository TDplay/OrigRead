package me.ash.reader.infrastructure.rss

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** 用真实 XML 和本机 HTTP 验证文章身份及跳转后的 alternate 地址。 */
class RssSubscriptionRegressionTest {
    /** 页面跳到子目录后，相对 alternate 以最终页面地址为基址。 */
    @Test fun redirectedPageResolvesAlternateAgainstItsFinalUrl() = runBlocking<Unit> {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/entry" -> MockResponse().setResponseCode(302).setHeader("Location", "/nested/page")
                "/nested/page" -> MockResponse().setHeader("Content-Type", "text/html").setBody(
                    "<html><head><link rel=\"alternate\" type=\"application/atom+xml\" href=\"rss.xml\"></head></html>",
                )
                "/nested/rss.xml" -> MockResponse().setBody(ATOM_BODY)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val result = helper().discoverFeed(server.url("/entry").toString())
            assertEquals(server.url("/nested/rss.xml").toString(), result.feedUrl)
            assertTrue(result.discoveredFromPage)
            assertEquals("Atom News", result.feed.title)
        } finally {
            // 仅关闭本用例服务器，不依赖公网网站响应。
            server.shutdown()
        }
    }

    /** 实际网络与转换器保留，只替换 Android 和全文等本例未使用的边界依赖。 */
    private fun helper() = RssHelper(
        context = mock<Context>(), ioDispatcher = Dispatchers.IO, okHttpClient = OkHttpClient(),
        contentExtractionService = mock(), dynamicArticleContentService = mock(),
        articleWebSessionManager = mock<ArticleWebSessionManager> {
            on { desktopHttpUserAgent } doReturn "Mozilla/5.0 OrigRead-Test"
        },
    )

    private companion object {
        /** 两个不可解引用的 Atom 身份，刻意省略可打开的 link。 */
        const val ATOM_BODY = """<feed xmlns="http://www.w3.org/2005/Atom">
            <id>urn:news:feed</id><title>Atom News</title><updated>2026-10-01T00:00:00Z</updated>
            <entry><id>urn:news:a</id><title>A</title><updated>2026-10-01T00:00:00Z</updated></entry>
            <entry><id>urn:news:b</id><title>B</title><updated>2026-10-01T00:00:00Z</updated></entry>
            </feed>"""
    }
}
