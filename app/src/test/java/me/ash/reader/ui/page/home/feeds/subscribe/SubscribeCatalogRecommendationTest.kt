package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.foundation.text.input.TextFieldState
import java.io.IOException
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.discovery.FeedCatalogEntry
import me.ash.reader.infrastructure.discovery.FeedCatalogUrlMatch
import me.ash.reader.infrastructure.source.SourceCandidateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** 目录提示只是未验证建议，不影响真实候选、RSSHub 多选和失败状态。 */
class SubscribeCatalogRecommendationTest : SubscriptionPipelineFixture() {
    private val input = "https://example.com/news"
    private val suggestions = listOf(FeedCatalogEntry("catalog", "Catalog", "https://example.com/catalog.xml"))

    /** 直接 RSS 和页面发现 RSS 都清空展示字段，实际选中候选保持原样。 */
    @Test fun bothNativeRssKindsClearCatalogRecommendations() {
        listOf(SourceCandidateKind.RSS_DIRECT, SourceCandidateKind.RSS_DISCOVERED).forEach { kind ->
            val viewModel = createViewModel()
            val probe = SubscribeCandidateProbe(createFeed(), "$input/feed.xml", SourceType.RSS, kind)
            viewModel.applyDiscoveryResult(options(), SubscribeDiscoveryResult(listOf(probe)))
            val state = viewModel.subscribeState.value as SubscribeState.Configure
            assertTrue(state.catalogMatches.isEmpty())
            assertEquals(0, state.catalogMatchCount)
            assertEquals(probe.feedLink, state.feedLink)
            assertEquals(kind, state.candidates.single().kind)
        }
    }

    /** RSSHub 同样使用 SourceType.RSS，不能因此清空建议或破坏多路由选择。 */
    @Test fun rssHubRecommendationsAndMultipleRouteSelectionRemainAvailable() {
        val viewModel = createViewModel()
        val probes = listOf("hot", "latest").map { route ->
            SubscribeCandidateProbe(createFeed(route), "https://hub.example.com/$route", SourceType.RSS,
                SourceCandidateKind.RSSHUB, rssHubRoutePath = "/$route")
        }
        viewModel.applyDiscoveryResult(options(), SubscribeDiscoveryResult(probes))
        val first = viewModel.subscribeState.value as SubscribeState.Configure
        assertEquals(suggestions, first.catalogMatches)
        assertEquals(suggestions.size, first.catalogMatchCount)
        viewModel.toggleRssHubCandidate(first.candidates.last().id)
        val multiple = viewModel.subscribeState.value as SubscribeState.Configure
        assertEquals(first.candidates.map { it.id }.toSet(), multiple.selectedCandidateIds)
        assertEquals(first.candidates.last().feedLink, multiple.feedLink)
    }

    /** 原生及后续探测都失败时仍留在输入页，目录项不能伪装成成功候选。 */
    @Test fun failedDiscoveryRetainsInputAndUnverifiedRecommendations() = runBlocking<Unit> {
        whenever(feedDiscoveryCatalog.matchUrl(input)).thenReturn(FeedCatalogUrlMatch(suggestions = suggestions))
        whenever(rssHelper.discoverFeed(input)).thenThrow(IOException("No native RSS"))
        whenever(jsonSourceHelper.probe(input)).thenThrow(IllegalStateException("No JSON source"))
        whenever(rssHubResolver.localRouteDiagnostics(input)).thenReturn(emptyList())
        whenever(rssHubResolver.probe(input)).thenReturn(emptyList())
        whenever(websiteHelper.inspect(eq(input), any())).thenThrow(IllegalStateException("No website source"))
        whenever(websiteHelper.inspectDynamic(eq(input), any())).thenThrow(IllegalStateException("Dynamic discovery failed"))
        val viewModel = createViewModel()
        viewModel.showDrawer()
        (viewModel.subscribeState.value as SubscribeState.Idle).linkState.edit { replace(0, length, input) }
        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()
        val state = viewModel.subscribeState.value as SubscribeState.Idle
        assertEquals("Dynamic discovery failed", state.errorMessage)
        assertEquals(input, state.linkState.text.toString())
        assertEquals(suggestions, state.catalogMatches)
        verify(rssService, never()).subscribe(any())
    }

    /** 明确填充 idle 的目录字段，覆盖配置发布读取的真实数据位置。 */
    private fun options() = SubscribeDiscoveryContext(
        account = requireNotNull(accountFlow.value),
        idle = SubscribeState.Idle(linkState = TextFieldState(input), catalogMatches = suggestions),
        feedLink = input, firstGroupId = "group_1",
        catalog = FeedCatalogUrlMatch(suggestions = suggestions), explicitRoute = null,
    )
}
