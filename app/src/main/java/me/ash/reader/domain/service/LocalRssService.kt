package me.ash.reader.domain.service

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import com.rometools.rome.feed.synd.SyndFeed
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.repository.LocalSubscriptionDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.filter.ArticleFilterEngine
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.RssHttpCache
import me.ash.reader.infrastructure.rss.RssHttpCacheDao
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionDescriptor
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.website.WebsiteHelper
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.spacerDollar
import timber.log.Timber

private const val TAG = "LocalRssService"

data class WebsiteReparseResult(
    val fetchedCount: Int,
    val updatedCount: Int,
)

@Singleton
class LocalRssService
@Inject
constructor(
    @ApplicationContext private val context: Context,
    internal val articleDao: ArticleDao,
    internal val feedDao: FeedDao,
    internal val rssHelper: RssHelper,
    internal val localSourceService: LocalSourceService,
    internal val websiteHelper: WebsiteHelper,
    internal val articleFilterEngine: ArticleFilterEngine,
    internal val notificationHelper: NotificationHelper,
    internal val groupDao: GroupDao,
    @IODispatcher internal val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val workManager: WorkManager,
    internal val accountService: AccountService,
    internal val syncLogger: SyncLogger,
    internal val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
    internal val localSubscriptionDao: LocalSubscriptionDao,
    private val rssHttpCacheDao: RssHttpCacheDao,
) :
    AbstractRssRepository(
        articleDao,
        groupDao,
        feedDao,
        workManager,
        rssHelper,
        notificationHelper,
        ioDispatcher,
        defaultDispatcher,
        accountService,
    ) {

    /** 公共仓库旧入口立即捕获账户，后续保存只使用固定请求中的账户。 */
    override suspend fun subscribe(
        feedLink: String,
        searchedFeed: SyndFeed,
        groupId: String,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean,
    ) {
        subscribe(SubscriptionRequest(
            accountId = accountService.getCurrentAccountId(), groupId = groupId,
            feedLink = feedLink, searchedFeed = searchedFeed,
            notification = isNotification, fullContent = isFullContent, browser = isBrowser,
        ))
    }

    /** 固定账户的本地来源提交入口，配置与文章保存交给按职责拆分的实现。 */
    override suspend fun subscribe(request: SubscriptionRequest): SubscriptionResult =
        saveSourceSubscription(request)

    override suspend fun deleteFeed(feed: Feed, onlyDeleteNoStarred: Boolean?) {
        super.deleteFeed(feed, onlyDeleteNoStarred)
        if (feedDao.queryById(feed.id) == null) {
            rssHubSubscriptionRepository.remove(feed.id)
        }
    }

    /** 所有来源独立提交后汇总真实结果，部分失败不推进账户完成时间。 */
    override suspend fun sync(accountId: Int, feedId: String?, groupId: String?): ListenableWorker.Result =
        syncLocalSources(accountId, feedId, groupId)

    /** 重新解析来源中已经存在的文章，不新增文章，也不改变阅读状态。 */
    suspend fun reparseWebsiteArticles(feedId: String): WebsiteReparseResult {
        val feed = feedDao.queryById(feedId) ?: error("订阅源不存在：$feedId")
        require(feed.sourceType == SourceType.WEBSITE) { "只有网站来源支持重新解析文章" }

        val fetchedArticles = localSourceService.fetchForSync(feed, Date()).feedWithArticle.articles
        val existingLinks = articleDao
            .queryAllByFeedId(feed.accountId, feed.id)
            .mapTo(hashSetOf(), me.ash.reader.domain.model.article.Article::link)
        val matchedArticles = fetchedArticles.filter { it.link in existingLinks }
        updateWebsiteArticlesAndInsertNew(feed = feed, fetchedArticles = matchedArticles)
        return WebsiteReparseResult(
            fetchedCount = fetchedArticles.size,
            updatedCount = matchedArticles.size,
        )
    }

}
