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
class SubscribeHintPipelineTest : SubscriptionPipelineFixture() {
    @Test
    fun `rss hint falls back to json when rss proof fails`() = runBlocking {
        val rssLikeUrl = "https://example.com/api/feed.xml"
        whenever(rssHelper.discoverFeed(any())).thenThrow(IOException("Not actually RSS"))
        whenever(jsonSourceHelper.probe(any())).thenReturn(
            JsonSourceProbeResult(
                rule = mock(),
                endpointUrl = "https://example.com/api/posts",
                feed = createFeed("JSON behind RSS-like URL"),
            )
        )

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, rssLikeUrl) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(websiteHelper)
        val state = viewModel.subscribeState.value as SubscribeState.Configure
        assertEquals(SourceType.JSON, state.sourceType)
        assertEquals(SourceCandidateKind.JSON, state.candidates.first().kind)
    }

    /** 弱 /feed 与 /api/ 冲突时优先 JSON 只是调度优化；JSON 证明成功后无需发起 RSS 探测。 */
    @Test
    fun `api feed conflict tries json first and short circuits on proof`() = runBlocking {
        val ambiguousUrl = "https://example.com/api/v1/feed"
        whenever(jsonSourceHelper.probe(any())).thenReturn(
            JsonSourceProbeResult(
                rule = mock(),
                endpointUrl = ambiguousUrl,
                feed = createFeed("JSON API Feed"),
            )
        )

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, ambiguousUrl) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        verifyNoInteractions(rssHelper)
        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(websiteHelper)
        val state = viewModel.subscribeState.value as SubscribeState.Configure
        assertEquals(SourceType.JSON, state.sourceType)
        assertEquals(SourceCandidateKind.JSON, state.candidates.first().kind)
    }

    /** JSON-like URL 只影响顺序；JSON 解析失败后必须继续 RSS，并在 RSS 证明成功时短路。 */
    @Test
    fun `json hint falls back to rss when json proof fails`() = runBlocking {
        val jsonLikeUrl = "https://example.com/posts.json"
        whenever(jsonSourceHelper.probe(any())).thenReturn(null)
        whenever(rssHelper.discoverFeed(any())).thenReturn(
            DiscoveredFeed(
                feedUrl = jsonLikeUrl,
                feed = createFeed("RSS behind JSON-like URL"),
                discoveredFromPage = false,
            )
        )

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, jsonLikeUrl) }

        val emittedStates = mutableListOf<SubscribeState>()
        val collectJob = kotlinx.coroutines.CoroutineScope(testDispatcher).launch {
            viewModel.subscribeState.collect { emittedStates.add(it) }
        }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()
        collectJob.cancel()

        val fetchingState = emittedStates.filterIsInstance<SubscribeState.Fetching>().firstOrNull()
        assertNotNull("Should transition through Fetching state", fetchingState)
        assertEquals(SearchStage.CHECKING_JSON, fetchingState!!.stage)
        verifyNoInteractions(rssHubResolver)
        verifyNoInteractions(websiteHelper)

        val state = viewModel.subscribeState.value as SubscribeState.Configure
        assertEquals(SourceType.RSS, state.sourceType)
        assertEquals(SourceCandidateKind.RSS_DIRECT, state.candidates.first().kind)
    }

    /**
     * 非 Local 账号验证：非本地账号在 RSS 探测失败后直接结算，不探测 JSON 或 RSSHub。
     */
}
