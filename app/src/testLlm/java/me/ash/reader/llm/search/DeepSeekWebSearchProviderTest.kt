package me.ash.reader.llm.search

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.ai.AiHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekWebSearchProviderTest {
    private val profile =
        WebSearchProviderProfile(id = "deepseek-test", kind = WebSearchProviderKind.DEEPSEEK)

    @Test
    fun `long article title fits the budget and HTTP uses the frozen query`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(jsonResponse(payload(result().put("content", ""))))
            val configured = profile.copy(endpoint = server.url("/custom/search").toString())
            val prepared = prepareSearch("A".repeat(200), "latest updates", configured)
            val request = requireNotNull(prepared.request)
            val response =
                DeepSeekWebSearchProvider(AiHttpClient(), Dispatchers.IO).search(
                    configured,
                    "test-search-key",
                    request,
                )

            assertEquals("A".repeat(111) + " — latest updates", prepared.query)
            assertEquals(prepared.query, request.query)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
                ?: throw AssertionError("Expected one DeepSeek search request")
            assertEquals("POST", recorded.method)
            assertEquals("/custom/search", recorded.requestUrl?.encodedPath)
            assertEquals("Bearer test-search-key", recorded.getHeader("Authorization"))
            assertEquals("application/json", recorded.getHeader("Accept"))
            assertTrue(recorded.getHeader("Content-Type").orEmpty().startsWith("application/json"))
            val body = JSONObject(recorded.body.readUtf8())
            assertEquals(3, body.length())
            assertEquals(prepared.query, body.getString("query"))
            assertEquals(7, body.getInt("count"))
            assertFalse(body.getBoolean("need_content"))
            assertEquals("long evidence", response.results.single().content)
            assertEquals(null, response.results.single().publishedAt)
            assertTrue(response.toContextItems().single().content.contains("long evidence"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `title budget counts Unicode code points without splitting emoji`() {
        val input = "🚀".repeat(10)
        val prepared = prepareSearch("🌍".repeat(200), input)
        val query = requireNotNull(prepared.query)

        assertEquals("🌍".repeat(115) + " — " + input, query)
        assertEquals(128, query.codePointCount(0, query.length))
        assertEquals(query, prepared.request?.query)
        assertEquals(query, buildDeepSeekSearchBody(requireNotNull(prepared.request)).getString("query"))
    }

    @Test
    fun `all 128 input code points are sent without adding a title`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(jsonResponse(payload(result())))
            val configured = profile.copy(endpoint = server.url("/search").toString())
            val input = "😀".repeat(128)
            val prepared = prepareSearch("Article context", input, configured)
            val request = requireNotNull(prepared.request).copy(includeContent = true)
            val response =
                DeepSeekWebSearchProvider(AiHttpClient(), Dispatchers.IO).search(
                    configured,
                    "test-search-key",
                    request,
                )

            assertEquals(input, prepared.query)
            assertEquals(input, request.query)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
                ?: throw AssertionError("Expected a 128-character DeepSeek request")
            val body = JSONObject(recorded.body.readUtf8())
            assertEquals(input, body.getString("query"))
            assertTrue(body.getBoolean("need_content"))
            assertEquals("long evidence\n\npage evidence", response.results.single().content)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `overlong user input stays intact and fails before HTTP`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val configured = profile.copy(endpoint = server.url("/search").toString())
            val adapter = DeepSeekWebSearchProvider(AiHttpClient(), Dispatchers.IO)
            listOf(129, 600).forEach { count ->
                val input = "😀".repeat(count)
                val prepared = prepareSearch("Article context", input, configured)
                assertEquals(input, prepared.query)
                assertEquals(input, prepared.request?.query)
                server.enqueue(jsonResponse(payload()))
                val failure = runCatching {
                    adapter.search(configured, "test-search-key", requireNotNull(prepared.request))
                }.exceptionOrNull()
                assertTrue("Overlong input must be rejected", failure is IllegalArgumentException)
                assertTrue(failure?.message.orEmpty().contains("128"))
            }
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `short queries retain context and do not duplicate an existing article title`() {
        assertEquals("OrigRead — latest updates", prepareSearch("OrigRead", "latest updates").query)
        assertEquals("latest OrigRead version", prepareSearch("OrigRead", "latest OrigRead version").query)
        assertEquals("latest updates", prepareSearch(null, "latest updates").query)
        val nearlyFullInput = "问".repeat(126)
        assertEquals(nearlyFullInput, prepareSearch("Article context", nearlyFullInput).query)
    }

    @Test
    fun `other search providers keep the original query composition`() {
        val title = "A".repeat(400)
        val input = "B".repeat(200)
        WebSearchProviderKind.entries.filter { it != WebSearchProviderKind.DEEPSEEK }.forEach { kind ->
            val prepared = prepareSearch(title, input, WebSearchProviderProfile(kind = kind))
            assertEquals(kind.name, buildSearchQuery(title, input), prepared.query)
            assertEquals(prepared.query, prepared.request?.query)
        }
    }

    @Test
    fun `response keeps ordered evidence optional content and nullable dates`() {
        val first = result()
        val second = result()
            .put("title", "Second result")
            .put("url", "https://example.com/second")
            .put("date", "2026-10-17T00:00:00Z")
        val withoutContent = parseDeepSeekSearchResponse(profile, payload(first, second))
        val withContent = parseDeepSeekSearchResponse(profile, payload(first, second), includeContent = true)

        assertEquals(profile.id, withoutContent.providerId)
        assertEquals(WebSearchBackendKind.RAW_SEARCH, withoutContent.backendKind)
        assertEquals(listOf("Search result", "Second result"), withoutContent.results.map { it.title })
        assertEquals("short preview", withoutContent.results.first().snippet)
        assertEquals("long evidence", withoutContent.results.first().content)
        assertEquals("example.com", withoutContent.results.first().source)
        assertEquals(null, withoutContent.results.first().publishedAt)
        assertEquals("2026-10-17T00:00:00Z", withoutContent.results.last().publishedAt)
        assertEquals("long evidence\n\npage evidence", withContent.results.first().content)
        val third = result().apply { remove("date") }
        val withMissingDate = parseDeepSeekSearchResponse(profile, payload(third))
        assertEquals(null, withMissingDate.results.single().publishedAt)
        assertTrue(parseDeepSeekSearchResponse(profile, payload()).results.isEmpty())
    }

    @Test
    fun `malformed response envelopes fail instead of reporting no results`() {
        listOf(
            "not json",
            "{}",
            """{"results":null}""",
            """{"results":{}}""",
            """{"results":[null]}""",
        ).forEach { invalid ->
            val failure = runCatching { parseDeepSeekSearchResponse(profile, invalid) }.exceptionOrNull()
            assertTrue("Invalid envelope must fail: $invalid", failure is WebSearchException)
        }
    }

    @Test
    fun `invalid result fields and non-web URLs are rejected`() {
        listOf(
            result().apply { remove("summary") },
            result().put("summary", JSONObject()),
            result().put("content", JSONObject.NULL),
            result().put("title", 42),
            result().put("date", false),
            result().put("url", "javascript:alert(1)"),
        ).forEach { invalid ->
            val failure = runCatching {
                parseDeepSeekSearchResponse(profile, payload(invalid))
            }.exceptionOrNull()
            assertTrue("Invalid result must fail", failure is WebSearchException)
        }
    }

    @Test
    fun `HTTP failures are not accepted as empty successful searches`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val configured = profile.copy(endpoint = server.url("/search").toString())
            val adapter = DeepSeekWebSearchProvider(AiHttpClient(), Dispatchers.IO)
            listOf(401, 429, 500).forEach { status ->
                server.enqueue(jsonResponse(payload()).setResponseCode(status))
                val failure = runCatching {
                    adapter.search(configured, "test-search-key", WebSearchRequest(query = "latest news"))
                }.exceptionOrNull()
                assertTrue(failure is WebSearchException)
                assertTrue(failure?.message.orEmpty().contains("HTTP $status"))
            }
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun prepareSearch(
        title: String?,
        input: String,
        provider: WebSearchProviderProfile = profile,
    ): WebSearchPreparedRequest =
        buildWebSearchPreparedRequest(
            decision = WebSearchDecision(WebSearchRequestStatus.TRIGGERED, required = true),
            articleTitle = title,
            userInput = input,
            configuredProviders = listOf(provider),
            defaultProviderId = provider.id,
            maxResults = 7,
        )

    private fun result(): JSONObject =
        JSONObject()
            .put("rerank_score", 2.4)
            .put("title", "Search result")
            .put("url", "https://example.com/article")
            .put("snippet", "short preview")
            .put("summary", "long evidence")
            .put("content", "page evidence")
            .put("site_name", "Example")
            .put("site_icon", JSONObject.NULL)
            .put("date", JSONObject.NULL)
            .put("authority", 3)

    private fun payload(vararg results: JSONObject): String =
        JSONObject().put("results", JSONArray().apply { results.forEach { put(it) } }).toString()

    private fun jsonResponse(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
