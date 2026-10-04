package me.ash.reader.domain.service

import android.util.Log
import androidx.paging.PagingSource
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rometools.rome.feed.synd.SyndFeed
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.source.SourceUrlNormalizer
import me.ash.reader.ui.ext.spacerDollar

abstract class AbstractRssRepository(
    private val articleDao: ArticleDao,
    private val groupDao: GroupDao,
    private val feedDao: FeedDao,
    private val workManager: WorkManager,
    private val rssHelper: RssHelper,
    private val notificationHelper: NotificationHelper,
    private val dispatcherIO: CoroutineDispatcher,
    private val dispatcherDefault: CoroutineDispatcher,
    private val accountService: AccountService,
) {

    /**
     * 本地来源创建必须串行化。
     *
     * 订阅页的“已订阅”检查只能优化交互，不能承担数据一致性：用户连续点击确认、
     * 或两个入口同时提交时，都可能在检查后分别生成不同 UUID。这里作为最终进程内兜底，
     * 保证“检查同 URL -> 插入 Feed”是一个不可并发穿透的临界区。
     */
    private val subscriptionMutex = Mutex()

    internal val articleStore: ArticleDao get() = articleDao
    internal val groupStore: GroupDao get() = groupDao
    internal val feedStore: FeedDao get() = feedDao
    internal val feedParser: RssHelper get() = rssHelper
    internal val syncWorkManager: WorkManager get() = workManager

    internal suspend fun <T> withSubscriptionLock(block: suspend (accountId: Int) -> T): T =
        subscriptionMutex.withLock {
            block(accountService.getCurrentAccountId())
        }

    /** 导入和普通本地订阅共享串行边界，已捕获的账户不得改取 UI 当前账户。 */
    internal suspend fun <T> withSubscriptionLock(accountId: Int, block: suspend (Int) -> T): T =
        subscriptionMutex.withLock { block(accountId) }

    internal suspend fun findExistingFeed(accountId: Int, url: String): Feed? {
        feedDao.queryByLink(accountId, url).firstOrNull()?.let { return it }
        val key = SourceUrlNormalizer.comparisonKey(url)
        return feedDao.queryAll(accountId).firstOrNull { feed ->
            SourceUrlNormalizer.comparisonKey(feed.url) == key
        }
    }

    open val importSubscription: Boolean = true
    open val addSubscription: Boolean = true
    open val moveSubscription: Boolean = true
    open val deleteSubscription: Boolean = true
    open val updateSubscription: Boolean = true

    open suspend fun validCredentials(account: Account): Boolean = true

    open suspend fun clearAuthorization() {}

    /** 具体协议必须实现固定账户的提交入口，不能退回读取当前账户的旧入口。 */
    open suspend fun subscribe(request: SubscriptionRequest): SubscriptionResult =
        error("此账户不支持添加订阅")

    /** 旧入口在调用时捕获账户，具体协议覆盖行为保持不变。 */
    open suspend fun subscribe(
        feedLink: String, searchedFeed: SyndFeed, groupId: String,
        isNotification: Boolean, isFullContent: Boolean, isBrowser: Boolean,
    ) {
        subscribeInitialRss(
            SubscriptionRequest(
                accountId = accountService.getCurrentAccountId(), groupId = groupId,
                feedLink = feedLink, searchedFeed = searchedFeed, notification = isNotification,
                fullContent = isFullContent, browser = isBrowser,
            )
        )
    }

    /** 创建分组使用入口账户；订阅探测期间即使切换界面账户也不改变写入归属。 */
    open suspend fun addGroup(
        destFeed: Feed?, newGroupName: String, accountId: Int = accountService.getCurrentAccountId(),
    ): String {
        return accountId.spacerDollar(UUID.randomUUID().toString()).also {
            groupDao.insert(Group(id = it, name = newGroupName, accountId = accountId))
        }
    }

    abstract suspend fun sync(
        accountId: Int,
        feedId: String?,
        groupId: String?,
    ): ListenableWorker.Result

    /** 阅读状态修改按已有范围分派，保留来源、分组和单篇的优先顺序。 */
    open suspend fun markAsRead(
        groupId: String?, feedId: String?, articleId: String?, before: Date?, isUnread: Boolean,
    ) = articleDao.markReadSelection(
        ReadSelection(accountService.getCurrentAccountId(), groupId, feedId, articleId, before, isUnread)
    )

    open suspend fun batchMarkAsRead(
        articleIds: Set<String>,
        isUnread: Boolean,
        accountId: Int = accountService.getCurrentAccountId(),
    ) {
        articleIds
            .takeIf { it.isNotEmpty() }
            ?.chunked(500)
            ?.forEachIndexed { index, it ->
                articleDao.markAsReadByIdSet(accountId, it.toSet(), isUnread)
            }
    }

    open suspend fun syncReadStatus(
        articleIds: Set<String>,
        isUnread: Boolean,
        account: Account? = null,
    ): Set<String> {
        /* no-op */
        return emptySet()
    }

    /** 收藏使用入口捕获的目标账户，排队期间切换账户不会改变 DAO 写入范围。 */
    open suspend fun markAsStarred(account: Account, articleId: String, isStarred: Boolean) {
        val accountId = requireNotNull(account.id) { "收藏账户缺少持久化 ID" }
        articleDao.markAsStarredByArticleId(accountId, articleId, isStarred)
    }

    /** 后台清理使用任务账户，避免切换界面账户后删除其它账户文章。 */
    suspend fun clearKeepArchivedArticles(
        accountId: Int = accountService.getCurrentAccountId(),
    ): List<Article> = clearAccountArchives(
        requireNotNull(accountService.getAccountById(accountId)) { "清理账户不存在：$accountId" }
    )

    fun cancelSync() {
        SyncWorker.cancelPeriodicWork(workManager)
        SyncWorker.cancelOneTimeWork(workManager)
    }

    open fun doSyncOneTime(
        accountId: Int = accountService.getCurrentAccountId(),
        feedId: String? = null,
        groupId: String? = null,
    ) {
        SyncWorker.enqueueOneTimeWork(
            workManager,
            workDataOf("accountId" to accountId, "feedId" to feedId, "groupId" to groupId),
        )
    }

    fun initSync() = initializeSync(accountService.getCurrentAccount())

    fun pullGroups(): Flow<MutableList<Group>> =
        groupDao.queryAllGroup(accountService.getCurrentAccountId()).flowOn(dispatcherIO)

    fun pullFeeds(): Flow<MutableList<GroupWithFeed>> =
        groupDao
            .queryAllGroupWithFeedAsFlow(accountService.getCurrentAccountId())
            .flowOn(dispatcherIO)

    /** 查询门面保留既有列表契约，SQL 分派由独立的范围查询流程负责。 */
    fun pullArticles(
        groupId: String?, feedId: String?, isStarred: Boolean, isUnread: Boolean,
        sortAscending: Boolean = false,
    ): PagingSource<Int, ArticleWithFeed> = articleDao.selectArticlePage(
        ArticlePageQuery(accountService.getCurrentAccountId(), groupId, feedId, isStarred, isUnread, sortAscending)
    )


    @OptIn(ExperimentalCoroutinesApi::class)
    fun pullImportant(isStarred: Boolean, isUnread: Boolean): Flow<Map<String, Int>> {
        val accountId = accountService.getCurrentAccountId()
        Log.i(
            "RLog",
            "pullImportant: accountId: ${accountId}, isStarred: ${isStarred}, isUnread: ${isUnread}",
        )
        return when {
            isStarred -> articleDao.queryImportantCountWhenIsStarred(accountId, true)
            isUnread -> articleDao.queryImportantCountWhenIsUnread(accountId, true)
            else -> articleDao.queryImportantCountWhenIsAll(accountId)
        }
    }

    suspend fun findFeedById(id: String): Feed? = feedDao.queryById(id)

    suspend fun findGroupById(id: String): Group? = groupDao.queryById(id)

    suspend fun findArticleById(id: String): ArticleWithFeed? = articleDao.queryById(id)

    suspend fun isFeedExist(url: String): Boolean =
        findExistingFeed(accountService.getCurrentAccountId(), url) != null

    suspend fun queryAllGroupWithFeeds(): List<GroupWithFeed> =
        groupDao.queryAllGroupWithFeed(accountService.getCurrentAccountId())

    open suspend fun renameGroup(group: Group) {
        groupDao.update(group)
    }

    open suspend fun renameFeed(feed: Feed) {
        updateFeed(feed)
    }

    open suspend fun moveFeed(originGroupId: String, feed: Feed) {
        updateFeed(feed)
    }

    open suspend fun changeFeedUrl(feed: Feed) {
        updateFeed(feed)
    }

    internal suspend fun updateFeed(feed: Feed) {
        feedDao.update(feed)
    }

    open suspend fun deleteGroup(group: Group, onlyDeleteNoStarred: Boolean? = false) =
        deleteStoredGroup(group, onlyDeleteNoStarred)

    open suspend fun deleteFeed(feed: Feed, onlyDeleteNoStarred: Boolean? = false) =
        deleteStoredFeed(feed, onlyDeleteNoStarred)

    suspend fun deleteArticles(group: Group? = null, feed: Feed? = null, includeStarred: Boolean = false) =
        deleteStoredArticles(group, feed, includeStarred)

    suspend fun deleteAccountArticles(accountId: Int) {
        articleDao.deleteByAccountId(accountId)
    }

    suspend fun groupParseFullContent(group: Group, isFullContent: Boolean) {
        feedDao.updateIsFullContentByGroupId(
            accountService.getCurrentAccountId(),
            group.id,
            isFullContent,
        )
    }

    suspend fun groupOpenInBrowser(group: Group, isBrowser: Boolean) {
        feedDao.updateIsBrowserByGroupId(accountService.getCurrentAccountId(), group.id, isBrowser)
    }

    suspend fun groupAllowNotification(group: Group, isNotification: Boolean) {
        feedDao.updateIsNotificationByGroupId(
            accountService.getCurrentAccountId(),
            group.id,
            isNotification,
        )
    }

    suspend fun groupMoveToTargetGroup(group: Group, targetGroup: Group) {
        feedDao.updateTargetGroupIdByGroupId(
            accountService.getCurrentAccountId(),
            group.id,
            targetGroup.id,
        )
    }

    /** 全文检索与列表使用相同账户和范围，搜索条件始终通过 Room 参数绑定。 */
    fun searchArticles(
        content: String, groupId: String?, feedId: String?, isStarred: Boolean,
        isUnread: Boolean, sortAscending: Boolean = false,
    ): PagingSource<Int, ArticleWithFeed> = articleDao.selectSearchPage(
        ArticlePageQuery(
            accountService.getCurrentAccountId(), groupId, feedId, isStarred, isUnread, sortAscending,
            content = content,
        )
    )

    suspend fun queryUnreadFullContentArticles(accountId: Int = accountService.getCurrentAccountId()) =
        articleDao.queryUnreadFullContentArticles(accountId)
}
