package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.foundation.text.input.TextFieldState
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.discovery.FeedCatalogUrlMatch
import me.ash.reader.infrastructure.rss.DiscoveredFeed
import me.ash.reader.infrastructure.rss.FeedFetchException
import me.ash.reader.infrastructure.rss.FeedFetchFailureReason
import me.ash.reader.infrastructure.source.SourceInputHint
import me.ash.reader.infrastructure.source.isFeedburnerFeedUrl
import me.ash.reader.infrastructure.source.sourceInputHint
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/** 只验证客户端的来源失败策略，不声称复现公网 Slashfilm 响应。 */
class FeedburnerDiscoveryPolicyTest : SubscriptionPipelineFixture() {
    @Test fun knownFeedHostsAreExactAndDoNotIncludeArticleTrackingLinks() {
        listOf("https://feeds2.feedburner.com/slashfilm", "https://feeds.feedburner.com/slashfilm")
            .forEach { url ->
                assertTrue(isFeedburnerFeedUrl(url))
                assertEquals(SourceInputHint.RSS_LIKELY, sourceInputHint(url))
            }
        listOf("https://feeds2.feedburner.com/", "https://feeds2.feedburner.com/~r/slashfilm/post",
            "https://feeds2.feedburner.com.evil.example/slashfilm", "https://example.com/feed.xml",
            "ftp://feeds2.feedburner.com/slashfilm").forEach { assertFalse(it, isFeedburnerFeedUrl(it)) }
    }

    @Test fun timeoutAndBadResponsesStayAsFeedErrorsInsteadOfDynamicCandidates() = runBlocking {
        val errors = listOf(SocketTimeoutException("Read timed out"),
            FeedFetchException(FeedFetchFailureReason.HTTP_ERROR, 403, "Feed request failed with HTTP 403"),
            FeedFetchException(FeedFetchFailureReason.INVALID_CONTENT, 200, "Feed content is invalid"))
        val vm = createViewModel()
        for (error in errors) {
            doThrow(error).whenever(rssHelper).discoverFeed(URL)
            val result = vm.discoverSources(options())
            assertSame(error, result.error)
            assertTrue(result.candidates.isEmpty())
            vm.applyDiscoveryResult(options(), result)
            assertEquals(error.message, (vm.subscribeState.value as SubscribeState.Idle).errorMessage)
        }
        verifyNoInteractions(jsonSourceHelper, rssHubResolver, websiteHelper)
    }

    @Test fun successfulFeedKeepsOriginalUrlAndValidators() = runBlocking {
        whenever(rssHelper.discoverFeed(URL)).thenReturn(DiscoveredFeed(
            feedUrl = URL, feed = createFeed("Slashfilm fixture"), discoveredFromPage = false,
            etag = "fixture-v1", lastModified = "Thu, 08 Oct 2026 00:00:00 GMT",
        ))
        val result = createViewModel().discoverSources(options())
        assertEquals(URL, result.candidates.single().feedLink)
        assertEquals("fixture-v1", result.candidates.single().etag)
        assertNull(result.error)
        verifyNoInteractions(jsonSourceHelper, rssHubResolver, websiteHelper)
    }

    private fun options() = SubscribeDiscoveryContext(
        account = requireNotNull(accountFlow.value), idle = SubscribeState.Idle(linkState = TextFieldState(URL)),
        feedLink = URL, firstGroupId = "group_1", catalog = FeedCatalogUrlMatch(), explicitRoute = null,
    )

    private companion object { const val URL = "https://feeds2.feedburner.com/slashfilm" }
}
