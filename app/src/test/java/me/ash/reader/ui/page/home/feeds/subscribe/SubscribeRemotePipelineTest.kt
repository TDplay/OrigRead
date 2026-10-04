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
class SubscribeRemotePipelineTest : SubscriptionPipelineFixture() {
    @Test
    fun `known rsshub route becomes direct rss on non local account`() = runBlocking {
        val feverAccount = Account(2, "Fever Server", AccountType.Fever)
        selectAccount(feverAccount)
        whenever(rssHubSettingsRepository.current()).thenReturn(RssHubSettings(enabled = false))

        val rssHubUrl = "https://rsshub.app/bilibili/user/video/2267573"
        val routePath = "/bilibili/user/video/2267573"
        val feed = createFeed("RSSHub as plain RSS")
        whenever(rssHubResolver.probeExplicitRoute(routePath, "https://rsshub.app"))
            .thenReturn(rssHubSuccess(routePath, feed, "https://rsshub.app"))

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, rssHubUrl) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        verifyNoInteractions(jsonSourceHelper)
        verifyNoInteractions(websiteHelper)
        verifyNoInteractions(rssHelper)
        val state = viewModel.subscribeState.value as SubscribeState.Configure
        assertEquals(SourceType.RSS, state.sourceType)
        assertEquals(SourceCandidateKind.RSS_DIRECT, state.candidates.first().kind)
    }

    @Test
    fun `remote explicit RSSHub input can recover through a mirror as ordinary RSS`(): Unit = runBlocking {
        val account = Account(2, "Fever Server", AccountType.Fever)
        selectAccount(account)
        val input = "https://rsshub.app/zhihu/hot"
        val path = "/zhihu/hot"
        val mirror = "https://mirror.example.com"
        val feed = createFeed("Recovered hot list")
        org.mockito.kotlin.doAnswer { throw
            me.ash.reader.infrastructure.rss.FeedFetchException(
                me.ash.reader.infrastructure.rss.FeedFetchFailureReason.BLOCKED_RESPONSE, 403,
                "Feed request blocked by an anti-bot challenge")
        }.whenever(rssHelper).parseFeedDirect(input)
        whenever(rssHubResolver.probeRoute(path, "https://rsshub.app"))
            .thenReturn(listOf(rssHubSuccess(path, feed, mirror)))
        val vm = createViewModel()
        vm.showDrawer()
        (vm.subscribeState.value as SubscribeState.Idle).linkState.edit { replace(0, length, input) }
        vm.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()
        val state = vm.subscribeState.value as SubscribeState.Configure
        assertEquals(mirror + path, state.feedLink)
        assertEquals(SourceCandidateKind.RSS_DIRECT, state.candidates.single().kind)
        verifyNoInteractions(jsonSourceHelper, websiteHelper)
        vm.subscribe()
        testDispatcher.scheduler.advanceUntilIdle()
        val request = argumentCaptor<SubscriptionRequest>()
        verify(rssService).subscribe(request.capture())
        assertEquals(2, request.firstValue.accountId)
        assertEquals("group_2", request.firstValue.groupId)
        assertEquals(mirror + path, request.firstValue.feedLink)
        assertEquals(feed, request.firstValue.searchedFeed)
        assertEquals(null, request.firstValue.rssHub)
    }

    @Test
    fun `remote RSSHub failures show localized diagnostics instead of the internal exception`() = runBlocking {
        selectAccount(Account(2, "Fever Server", AccountType.Fever))
        val input = "https://rsshub.app/zhihu/hot"
        val path = "/zhihu/hot"
        org.mockito.kotlin.doAnswer { throw
            me.ash.reader.infrastructure.rss.FeedFetchException(
                me.ash.reader.infrastructure.rss.FeedFetchFailureReason.BLOCKED_RESPONSE, 403,
                "Feed request blocked by an anti-bot challenge")
        }.whenever(rssHelper).parseFeedDirect(input)
        val failure = rssHubSuccess(path, createFeed("unused"), "https://rsshub.app").copy(
            feed = null, state = CandidateState.NETWORK_UNAVAILABLE,
            failureReason = RssHubFailureReason.BLOCKED, statusCode = 403)
        whenever(rssHubResolver.probeRoute(path, "https://rsshub.app")).thenReturn(listOf(failure))
        val vm = createViewModel()
        vm.showDrawer()
        (vm.subscribeState.value as SubscribeState.Idle).linkState.edit { replace(0, length, input) }
        vm.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()
        val state = vm.subscribeState.value as SubscribeState.Idle
        assertEquals("Localized Notice", state.errorMessage)
        assertEquals(listOf(failure), state.rssHubResults)
    }

    @Test
    fun `disabled RSSHub still gives remote HTTP subscriptions localized direct errors`(): Unit = runBlocking {
        selectAccount(Account(2, "Fever Server", AccountType.Fever))
        whenever(rssHubSettingsRepository.current()).thenReturn(RssHubSettings(enabled = false))
        val input = "https://rsshub.app/zhihu/hot"
        val path = "/zhihu/hot"
        val failure = rssHubSuccess(path, createFeed("unused"), "https://rsshub.app").copy(
            feed = null, state = CandidateState.NETWORK_UNAVAILABLE,
            failureReason = RssHubFailureReason.CONNECTION_CLOSED)
        whenever(rssHubResolver.probeExplicitRoute(path, "https://rsshub.app")).thenReturn(failure)
        val vm = createViewModel()
        vm.showDrawer()
        (vm.subscribeState.value as SubscribeState.Idle).linkState.edit { replace(0, length, input) }
        vm.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()
        val state = vm.subscribeState.value as SubscribeState.Idle
        assertEquals("Localized Notice", state.errorMessage)
        assertEquals(listOf(failure), state.rssHubResults)
        verify(rssHubResolver, org.mockito.kotlin.never()).probeRoute(any(), any())
    }

    @Test
    fun `non-local account skips json and rsshub entirely when rss fails`() = runBlocking {
        val feverAccount = Account(2, "Fever Server", AccountType.Fever)
        selectAccount(feverAccount)

        val pageUrl = "https://example.com/generic-page"
        whenever(rssHelper.discoverFeed(any())).thenThrow(IOException("No feed found"))

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, pageUrl) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        // 非本地账号绝不进入 JSON 或 RSSHub 探测
        verifyNoInteractions(jsonSourceHelper)
        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(websiteHelper)
    }

}
