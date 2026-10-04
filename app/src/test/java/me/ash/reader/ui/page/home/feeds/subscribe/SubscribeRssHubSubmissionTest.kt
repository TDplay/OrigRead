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
class SubscribeRssHubSubmissionTest : SubscriptionPipelineFixture() {
    @Test
    fun `explicit rsshub subscription keeps preferred instance separate from resolved fallback`(): Unit = runBlocking {
        val input = "https://rsshub.app/zhihu/hot"
        val routePath = "/zhihu/hot"
        val resolvedInstance = "https://mirror.example.com"
        val resolvedUrl = "$resolvedInstance$routePath"
        val feed = createFeed("Zhihu Hot")
        whenever(rssHubResolver.probeRoute(routePath, "https://rsshub.app"))
            .thenReturn(listOf(rssHubSuccess(routePath, feed, resolvedInstance)))

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, input) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        val configure = viewModel.subscribeState.value as SubscribeState.Configure
        val candidate = configure.candidates.single()
        assertEquals("https://rsshub.app", candidate.rssHubPreferredInstanceBaseUrl)
        assertEquals(resolvedInstance, candidate.rssHubInstanceBaseUrl)

        viewModel.subscribe()
        testDispatcher.scheduler.advanceUntilIdle()

        val request = argumentCaptor<SubscriptionRequest>()
        verify(rssService).subscribe(request.capture())
        val saved = request.firstValue
        assertEquals(1, saved.accountId)
        assertEquals("group_1", saved.groupId)
        assertEquals(resolvedUrl, saved.feedLink)
        assertEquals(feed, saved.searchedFeed)
        assertEquals(input, saved.rssHub?.originalInput)
        assertEquals(routePath, saved.rssHub?.routePath)
        assertEquals("https://rsshub.app", saved.rssHub?.preferredInstance)
        assertEquals(resolvedInstance, saved.rssHub?.lastResolvedInstance)
        Unit
    }

    @Test
    fun `rsshub scheme subscription keeps preferred instance null`(): Unit = runBlocking {
        val input = "rsshub://bilibili/user/dynamic/1161918898"
        val routePath = "/bilibili/user/dynamic/1161918898"
        val resolvedInstance = "https://mirror.example.com"
        val resolvedUrl = "$resolvedInstance$routePath"
        val feed = createFeed("Bilibili Dynamic")
        whenever(rssHubResolver.probeRoute(routePath, null))
            .thenReturn(listOf(rssHubSuccess(routePath, feed, resolvedInstance)))

        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idleState = viewModel.subscribeState.value as SubscribeState.Idle
        idleState.linkState.edit { replace(0, length, input) }

        viewModel.searchFeed()
        testDispatcher.scheduler.advanceUntilIdle()

        val configure = viewModel.subscribeState.value as SubscribeState.Configure
        val candidate = configure.candidates.single()
        assertEquals(null, candidate.rssHubPreferredInstanceBaseUrl)
        assertEquals(resolvedInstance, candidate.rssHubInstanceBaseUrl)

        viewModel.subscribe()
        testDispatcher.scheduler.advanceUntilIdle()

        val request = argumentCaptor<SubscriptionRequest>()
        verify(rssService).subscribe(request.capture())
        val saved = request.firstValue
        assertEquals(1, saved.accountId)
        assertEquals("group_1", saved.groupId)
        assertEquals(resolvedUrl, saved.feedLink)
        assertEquals(feed, saved.searchedFeed)
        assertEquals(input, saved.rssHub?.originalInput)
        assertEquals(routePath, saved.rssHub?.routePath)
        assertEquals(null, saved.rssHub?.preferredInstance)
        assertEquals(resolvedInstance, saved.rssHub?.lastResolvedInstance)
        Unit
    }

}
