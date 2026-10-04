package me.ash.reader.llm.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 搜索结果复用来源 URL 比较规则，也必须保留非根路径末尾斜杠的语义。 */
class WebSearchUrlNormalizationTest {
    /** 仅合并有保证等价的 URL，协议、www、业务参数和路径斜杠继续区分。 */
    @Test fun searchResponseConservativelyDeduplicatesEquivalentUrls() {
        val response = WebSearchResponse(
            providerId = "exa", providerName = "Exa", backendKind = WebSearchBackendKind.RAW_SEARCH,
            results = listOf(
                WebSearchResult("First", " HTTPS://EXAMPLE.COM:443/story/?utm_source=app#section "),
                WebSearchResult("Duplicate", "https://example.com/story/?fbclid=abc"),
                WebSearchResult("Trailing slash remains distinct", "https://example.com/story?fbclid=abc"),
                WebSearchResult("Http remains distinct", "http://example.com/story"),
                WebSearchResult("Www remains distinct", "https://www.example.com/story"),
                WebSearchResult("Business query remains distinct", "https://example.com/story?lang=zh"),
            ),
        )
        val normalized = response.deduplicateResultsByUrl()
        assertEquals(5, normalized.results.size)
        assertEquals("First", normalized.results.first().title)
        assertTrue(normalized.results.any { it.title == "Trailing slash remains distinct" })
        assertTrue(normalized.results.any { it.url == "http://example.com/story" })
        assertTrue(normalized.results.any { it.url == "https://www.example.com/story" })
        assertTrue(normalized.results.any { it.url.endsWith("?lang=zh") })
    }
}
