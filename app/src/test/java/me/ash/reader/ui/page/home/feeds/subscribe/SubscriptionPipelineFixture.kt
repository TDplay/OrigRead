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

/** 发现链回归共享的真实状态与依赖边界；各场景分别保留原断言。 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class SubscriptionPipelineFixture {
    protected val accountFlow = MutableStateFlow<Account?>(Account(1, "Local", AccountType.Local))
    protected val testDispatcher = StandardTestDispatcher()

    protected lateinit var accountService: AccountService
    protected lateinit var rssRepository: AbstractRssRepository
    protected lateinit var rssService: RssService
    protected lateinit var rssHubSettingsRepository: RssHubSettingsRepository
    protected lateinit var opmlService: OpmlService
    protected lateinit var rssHelper: RssHelper
    protected lateinit var rssHubResolver: RssHubResolver
    protected lateinit var websiteHelper: WebsiteHelper
    protected lateinit var jsonSourceHelper: JsonSourceHelper
    protected lateinit var feedDiscoveryCatalog: FeedDiscoveryCatalog
    protected lateinit var androidStringsHelper: AndroidStringsHelper

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        accountService = mock()
        val currentAccount = Account(1, "Local", AccountType.Local)
        whenever(accountService.currentAccountFlow).thenReturn(accountFlow)
        selectAccount(currentAccount)

        rssRepository = mock()
        selectAccount(Account(1, "Local", AccountType.Local))
        runBlocking {
            whenever(rssRepository.isFeedExist(any())).thenReturn(false)
        }
        rssService = mock()
        whenever(rssService.get()).thenReturn(rssRepository)
        runBlocking {
            whenever(rssService.forAccount(any())).thenReturn(rssRepository)
            whenever(rssService.subscribe(any())).thenAnswer { invocation ->
                SubscriptionResult(invocation.getArgument<SubscriptionRequest>(0).feedLink, created = true)
            }
        }

        rssHubSettingsRepository = mock()
        whenever(rssHubSettingsRepository.current()).thenReturn(RssHubSettings())

        opmlService = mock()
        rssHelper = mock()
        rssHubResolver = mock()
        websiteHelper = mock()
        jsonSourceHelper = mock()
        feedDiscoveryCatalog = mock()
        whenever(feedDiscoveryCatalog.matchUrl(any())).thenReturn(me.ash.reader.infrastructure.discovery.FeedCatalogUrlMatch())
        androidStringsHelper = mock {
            on { getString(any()) } doReturn "Localized Notice"
            on { getString(any(), any()) } doReturn "Localized Notice"
            on { getString(any(), any(), any(), any()) } doReturn "Localized Notice"
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    protected fun createViewModel(): SubscribeViewModel {
        val vm =
            SubscribeViewModel(
                opmlService = opmlService,
                rssService = rssService,
                rssHelper = rssHelper,
                rssHubResolver = rssHubResolver,
                rssHubSettingsRepository = rssHubSettingsRepository,
                websiteHelper = websiteHelper,
                jsonSourceHelper = jsonSourceHelper,
                feedDiscoveryCatalog = feedDiscoveryCatalog,
                androidStringsHelper = androidStringsHelper,
                applicationScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
                accountService = accountService,
            )
        testDispatcher.scheduler.runCurrent()
        return vm
    }

    protected fun createFeed(title: String = "Test Feed"): SyndFeed =
        SyndFeedImpl().apply {
            this.title = title
            link = "https://example.com"
            entries =
                listOf(
                    SyndEntryImpl().apply {
                        this.title = "Sample Article"
                        link = "https://example.com/article/1"
                        publishedDate = Date()
                    }
                )
        }

    /** 账户实体、观察流和真实账户分组一起切换，避免旧 mock 掩盖账户归属检查。 */
    protected fun selectAccount(account: Account) {
        whenever(accountService.getCurrentAccount()).thenReturn(account)
        accountFlow.value = account
        if (::rssRepository.isInitialized) {
            val id = requireNotNull(account.id)
            whenever(rssRepository.pullGroups()).thenReturn(
                kotlinx.coroutines.flow.flowOf(mutableListOf(Group("group_$id", "Default Group", id))),
            )
        }
    }

    /** 从成功实例和路由生成探测结果，提交测试再独立核对保存的实际地址。 */
    protected fun rssHubSuccess(routePath: String, feed: SyndFeed, instance: String) =
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
                    feedUrl = instance + routePath,
                ),
            state = CandidateState.AVAILABLE,
            feed = feed,
            routePath = routePath,
            instanceBaseUrl = instance,
        )
}
