package me.ash.reader.ui.page.home.feeds.subscribe

import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.io.IOException
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.OpmlService
import me.ash.reader.domain.service.SubscriptionRequest
import me.ash.reader.domain.service.SubscriptionResult
import org.mockito.kotlin.argumentCaptor
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.discovery.FeedDiscoveryCatalog
import me.ash.reader.infrastructure.json.JsonSourceHelper
import me.ash.reader.infrastructure.json.JsonSourceProbeResult
import me.ash.reader.infrastructure.rss.DiscoveredFeed
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rsshub.RssHubFailureReason
import me.ash.reader.infrastructure.rsshub.RssHubProbeResult
import me.ash.reader.infrastructure.rsshub.RssHubResolver
import me.ash.reader.infrastructure.rsshub.RssHubRouteDefinition
import me.ash.reader.infrastructure.rsshub.RssHubRouteMatch
import me.ash.reader.infrastructure.rsshub.RssHubSettings
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.source.SourceCandidateKind
import me.ash.reader.infrastructure.website.CandidateState
import me.ash.reader.infrastructure.website.WebsiteHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/** 固定发现与提交的对应来源场景，保留旧测试的行为断言。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscribePipelineUnitTest : SubscriptionPipelineFixture() {
    @Test
    fun `generic page resolves to json and short-circuits without probing rsshub`() = runBlocking {
        val pageUrl = "https://example.com/blog"
        val feed = createFeed("JSON Posts")

        // 原生 RSS 探测失败
        whenever(rssHelper.discoverFeed(any())).thenThrow(IOException("No RSS"))

        // JSON 探测命中
        whenever(jsonSourceHelper.probe(any())).thenReturn(
            JsonSourceProbeResult(
                rule = mock(),
                endpointUrl = "https://example.com/wp-json/wp/v2/posts",
                feed = feed,
            )
        )

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, pageUrl) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        // 验证 RSSHub 和 Website 绝不被探测（已被 JSON 提前短路）
        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(websiteHelper)

        val state = viewModel.subscribeState.value
        assertTrue("State must be Configure, was $state", state is SubscribeState.Configure)
        val configure = state as SubscribeState.Configure
        assertEquals(SourceType.JSON, configure.sourceType)
        assertEquals("https://example.com/wp-json/wp/v2/posts", configure.feedLink)
        assertEquals(SourceCandidateKind.JSON, configure.candidates.first().kind)
    }

    /**
     * 原生 RSS 优先短路验证：通用网页发现原生 RSS 时，立即在 RSS 处短路，
     * 绝不执行 JSON、RSSHub 或网页探测。
     */
    @Test
    fun `generic page with confirmed direct rss short-circuits before json and rsshub`() = runBlocking {
        val pageUrl = "https://example.com/news"
        val feed = createFeed("Direct RSS")

        whenever(rssHelper.discoverFeed(any())).thenReturn(
            DiscoveredFeed(
                feedUrl = "https://example.com/feed.xml",
                feed = feed,
                discoveredFromPage = false,
                etag = "etag-123",
                lastModified = "Wed, 21 Oct 2026 07:28:00 GMT",
            )
        )

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, pageUrl) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        // 验证 JSON、RSSHub 和 Website 均被短路，绝不产生交互
        verifyNoInteractions(jsonSourceHelper)
        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(websiteHelper)

        val state = viewModel.subscribeState.value
        assertTrue("State must be Configure", state is SubscribeState.Configure)
        val configure = state as SubscribeState.Configure
        assertEquals(SourceType.RSS, configure.sourceType)
        assertEquals("https://example.com/feed.xml", configure.feedLink)
        assertEquals(SourceCandidateKind.RSS_DIRECT, configure.candidates.first().kind)
    }

    /** 明确的 RSSHub 实例 URL 必须走 logical route resolver，并保持 CHECKING_RSSHUB。 */
    @Test
    fun `Radar UI keeps completed HTTP diagnostics alongside the unfinished budget notice`() = runBlocking {
        val page = "https://example.com/news"
        val instance = "https://hub.example.com"
        val match = RssHubRouteMatch(RssHubRouteDefinition("hot", "News", "example.com", "/news", "/zhihu/hot"),
            feedUrl = "$instance/zhihu/hot")
        val placeholder = RssHubProbeResult(match, CandidateState.NETWORK_UNAVAILABLE)
        val http = RssHubProbeResult(match, CandidateState.NETWORK_UNAVAILABLE,
            instanceBaseUrl = instance, failureReason = RssHubFailureReason.HTTP_ERROR, statusCode = 503)
        val incomplete = RssHubProbeResult(match, CandidateState.TIMEOUT,
            failureReason = RssHubFailureReason.PROBE_BUDGET_EXHAUSTED)
        whenever(rssHelper.discoverFeed(page)).thenThrow(IOException("no feed"))
        whenever(rssHubResolver.localRouteDiagnostics(page)).thenReturn(listOf(placeholder))
        whenever(rssHubResolver.probe(page)).thenReturn(listOf(http, incomplete))
        whenever(websiteHelper.inspect(eq(page), any())).thenThrow(IllegalStateException("no website source"))
        whenever(websiteHelper.inspectDynamic(eq(page), any())).thenThrow(IllegalStateException("no dynamic source"))
        val viewModel = createViewModel()
        viewModel.showDrawer()
        (viewModel.subscribeState.value as SubscribeState.Idle).linkState.edit { replace(0, length, page) }
        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()
        val state = viewModel.subscribeState.value as SubscribeState.Idle
        assertEquals(listOf(http, incomplete), state.rssHubResults)
        assertEquals(503, state.rssHubResults.first().statusCode)
        assertEquals(instance, state.rssHubResults.first().instanceBaseUrl)
        assertEquals(me.ash.reader.R.string.rsshub_probe_incomplete_notice,
            rssHubFailureSummary(state.rssHubResults)?.resourceId)
    }

}
