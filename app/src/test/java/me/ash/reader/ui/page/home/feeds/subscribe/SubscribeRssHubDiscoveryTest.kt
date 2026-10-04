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
class SubscribeRssHubDiscoveryTest : SubscriptionPipelineFixture() {
    @Test
    fun `scheme omitted and protocol relative RSSHub URLs use the dedicated pipeline`() = runBlocking {
        val route = "/zhihu/hot"
        val fullUrl = "https://rsshub.app$route"
        whenever(rssHubResolver.probeRoute(route, "https://rsshub.app"))
            .thenReturn(listOf(rssHubSuccess(route, createFeed("News"), "https://rsshub.app")))
        listOf("rsshub.app$route", "//rsshub.app$route", "HTTPS://RSSHUB.APP$route").forEach { input ->
            val viewModel = createViewModel()
            viewModel.showDrawer()
            val idle = viewModel.subscribeState.value as SubscribeState.Idle
            idle.linkState.edit { replace(0, length, input) }
            viewModel.searchFeed()
            testDispatcher.scheduler.advanceUntilIdle()
            val configure = viewModel.subscribeState.value as SubscribeState.Configure
            assertEquals(fullUrl, configure.feedLink)
            assertEquals(SourceCandidateKind.RSSHUB, configure.candidates.first().kind)
        }
        verifyNoInteractions(rssHelper, websiteHelper, jsonSourceHelper)
    }

    @Test
    fun `explicit rsshub endpoint uses route resolver without html discovery and sets stage checking rsshub`() = runBlocking {
        val rssHubUrl = "https://rsshub.app/bilibili/user/video/2267573"
        val routePath = "/bilibili/user/video/2267573"
        val feed = createFeed("Bilibili RSSHub")
        whenever(rssHubResolver.probeRoute(routePath, "https://rsshub.app"))
            .thenReturn(listOf(rssHubSuccess(routePath, feed, "https://rsshub.app")))

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, rssHubUrl) }

        val emittedStates = mutableListOf<SubscribeState>()
        val collectJob = kotlinx.coroutines.CoroutineScope(testDispatcher).launch {
            viewModel.subscribeState.collect { emittedStates.add(it) }
        }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()
        collectJob.cancel()

        // 验证初始状态为 Fetching 且 stage 对齐为 CHECKING_RSSHUB
        val fetchingState = emittedStates.filterIsInstance<SubscribeState.Fetching>().firstOrNull()
        assertNotNull("Should transition through Fetching state", fetchingState)
        assertEquals(SearchStage.CHECKING_RSSHUB, fetchingState!!.stage)

        // Resolver 负责实例 failover；JSON、普通 RSS helper 与网页爬虫不参与显式 RSSHub 输入。
        verifyNoInteractions(jsonSourceHelper)
        verifyNoInteractions(websiteHelper)
        verifyNoInteractions(rssHelper)

        val state = viewModel.subscribeState.value
        assertTrue("State must be Configure, was $state", state is SubscribeState.Configure)
        val configure = state as SubscribeState.Configure
        assertEquals(SourceType.RSS, configure.sourceType)
        assertEquals(SourceCandidateKind.RSSHUB, configure.candidates.first().kind)
        assertEquals(routePath, configure.candidates.first().rssHubRoutePath)
    }

    /** 远端账号遇到已知 RSSHub route 时只能把它当普通 RSS，不能生成 Local-only RSSHub 候选。 */
    @Test
    fun `rsshub scheme is preserved and never falls through to website`() = runBlocking {
        val input = "rsshub://bilibili/user/dynamic/1161918898"
        val routePath = "/bilibili/user/dynamic/1161918898"
        val instance = "https://mirror.example.com"
        val resolvedUrl = "$instance$routePath"
        val feed = createFeed("Bilibili Dynamic")
        whenever(rssHubResolver.probeRoute(routePath, null))
            .thenReturn(listOf(rssHubSuccess(routePath, feed, instance)))

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, input) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        verifyNoInteractions(rssHelper)
        verifyNoInteractions(jsonSourceHelper)
        verifyNoInteractions(websiteHelper)
        val state = viewModel.subscribeState.value as SubscribeState.Configure
        assertEquals(input, state.sourcePageUrl)
        assertEquals(resolvedUrl, state.feedLink)
        assertEquals(SourceCandidateKind.RSSHUB, state.candidates.first().kind)
        assertEquals(routePath, state.candidates.first().rssHubRoutePath)
    }

    @Test
    fun `rsshub scheme failure never falls through to json or dynamic website`() = runBlocking {
        val input = "rsshub://bilibili/user/dynamic/1161918898"
        val routePath = "/bilibili/user/dynamic/1161918898"
        val instance = "https://unavailable.example.com"
        whenever(rssHubResolver.probeRoute(routePath, null))
            .thenReturn(
                listOf(
                    RssHubProbeResult(
                        match =
                            RssHubRouteMatch(
                                route =
                                    RssHubRouteDefinition(
                                        id = "direct:$routePath",
                                        name = "RSSHub",
                                        host = "rsshub",
                                        pathPrefix = routePath,
                                        target = routePath,
                                    ),
                                feedUrl = "$instance$routePath",
                            ),
                        state = CandidateState.NETWORK_UNAVAILABLE,
                        message = "blocked",
                        routePath = routePath,
                        instanceBaseUrl = instance,
                        failureReason = RssHubFailureReason.BLOCKED,
                    )
                )
            )

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, input) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        verifyNoInteractions(rssHelper)
        verifyNoInteractions(jsonSourceHelper)
        verifyNoInteractions(websiteHelper)
        val state = viewModel.subscribeState.value as SubscribeState.Idle
        assertEquals("Localized Notice", state.errorMessage)
        assertEquals(input, state.linkState.text.toString())
    }

    /** RSS-like URL 只影响顺序；RSS 解析失败后必须继续 JSON，并在 JSON 证明成功时短路。 */
}
