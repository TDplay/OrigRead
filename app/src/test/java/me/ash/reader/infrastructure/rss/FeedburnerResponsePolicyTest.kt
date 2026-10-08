package me.ash.reader.infrastructure.rss

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** 合成 HTTP 响应通过正式 Feed 读取器；不访问公网。 */
class FeedburnerResponsePolicyTest {
    @Test fun feedFailureDoesNotRequestHostRootOrBrowserPage() = runBlocking<Unit> {
        var requests = 0
        val helper = helper(OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(503).message("Service Unavailable")
                .body("Unavailable".toResponseBody("text/plain".toMediaType())).build()
        }.build())
        try {
            helper.discoverFeed("https://feeds2.feedburner.com/slashfilm")
            fail("A failed Feed must not become a successful website source")
        } catch (error: FeedFetchException) {
            assertEquals(503, error.statusCode)
            assertEquals(FeedFetchFailureReason.HTTP_ERROR, error.reason)
        }
        assertEquals(1, requests)
    }

    @Test fun secondaryPageFailurePreservesOriginalFeedError() = runBlocking<Unit> {
        var requests = 0
        val secondary = IOException("Page lookup failed")
        val helper = helper(OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            if (requests > 1) throw secondary
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(403).message("Forbidden")
                .body("Forbidden".toResponseBody("text/plain".toMediaType())).build()
        }.build())
        try {
            helper.discoverFeed("https://example.com/news")
            fail("Expected original Feed error")
        } catch (error: FeedFetchException) {
            assertEquals(403, error.statusCode)
            // Coroutine stack-trace recovery may copy an IOException and retain it as the cause.
            assertTrue(error.suppressed.any { suppressed ->
                generateSequence(suppressed) { it.cause }.any { it === secondary }
            })
        }
    }

    private fun helper(client: OkHttpClient) = RssHelper(
        context = mock<Context>(), ioDispatcher = Dispatchers.IO, okHttpClient = client,
        contentExtractionService = mock(), dynamicArticleContentService = mock(),
        articleWebSessionManager = mock<ArticleWebSessionManager> {
            on { desktopHttpUserAgent } doReturn "OrigRead-Test"
        },
    )
}
