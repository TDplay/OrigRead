package me.ash.reader.domain.service

import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.ArticleMeta
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderDTO
import me.ash.reader.ui.ext.spacerDollar
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** 单来源和整账户共用的收藏差集必须分批，并把失败暴露给调用方。 */
class GoogleReaderStatusSyncTest {
    /** 超过旧 SQLite 绑定预算的收藏与取消收藏差集均不能遗漏或形成超长 IN。 */
    @Test fun largeStarDiffsUseSafeBatchesAndPreserveEveryIdentity() = runBlocking<Unit> {
        val newlyStarred = (1..DIFF_SIZE).map { "new-$it" }.toSet()
        val newlyUnstarred = (1..DIFF_SIZE).map { "old-$it" }.toSet()
        val writes = mutableListOf<Pair<Set<String>, Boolean>>()
        val dao = mock<ArticleDao>()
        whenever(dao.markAsStarredByIdSet(any(), any(), any())).thenAnswer { call ->
            assertEquals(ACCOUNT_ID, call.getArgument<Int>(0))
            writes += call.getArgument<Set<String>>(1) to call.getArgument<Boolean>(2)
            call.getArgument<Set<String>>(1).size
        }
        val service = readerService(dao)
        coroutineScope { service.reconcileGoogleReaderStars(syncState(newlyStarred, newlyUnstarred)) }
        assertTrue("SQLite 查询须为账户与状态两个绑定预留空间", writes.all { it.first.size <= SAFE_ID_COUNT })
        assertEquals(newlyStarred.map { ACCOUNT_ID spacerDollar it }.toSet(),
            writes.filter { it.second }.flatMap { it.first }.toSet())
        assertEquals(newlyUnstarred.map { ACCOUNT_ID spacerDollar it }.toSet(),
            writes.filterNot { it.second }.flatMap { it.first }.toSet())
    }

    /** 外层使用 supervisorScope 时，数据库错误仍须由收藏同步调用抛出。 */
    @Test fun failedStarWriteIsReturnedToTheSyncCaller() = runBlocking<Unit> {
        val failure = IllegalStateException("database write failed")
        val uncaught = mutableListOf<Throwable>()
        val dao = mock<ArticleDao>()
        whenever(dao.markAsStarredByIdSet(any(), any(), any())).thenThrow(failure)
        val service = readerService(dao)
        withContext(CoroutineExceptionHandler { _, error -> uncaught += error }) {
            supervisorScope {
                val observed = runCatching {
                    service.reconcileGoogleReaderStars(syncState(setOf("article"), emptySet()))
                }.exceptionOrNull()
                coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.joinAll()
                assertTrue(observed is IllegalStateException)
                assertEquals(failure.message, observed?.message)
                assertTrue("错误应抛给调用方，而非只交给未捕获异常处理器", uncaught.isEmpty())
            }
        }
    }

    /** 已读文章也可能收藏，未读列表不能充当本地收藏快照。 */
    @Test fun feedSyncReconcilesStarsIndependentlyOfReadState() = runBlocking<Unit> {
        val account = Account(ACCOUNT_ID, "Reader", AccountType.GoogleReader)
        val accountService = mock<AccountService>()
        whenever(accountService.getAccountById(ACCOUNT_ID)).thenReturn(account)
        val feed = Feed(id = "feed", name = "News", url = "https://example.com/feed", groupId = "group",
            accountId = ACCOUNT_ID, isNotification = false)
        val feedDao = mock<FeedDao>()
        whenever(feedDao.queryById(feed.id)).thenReturn(feed)
        val dao = mock<ArticleDao>()
        val unreadId = ACCOUNT_ID spacerDollar "unread"
        val starredId = ACCOUNT_ID spacerDollar "read-starred"
        whenever(dao.queryMetadataByFeedId(ACCOUNT_ID, feed.id, true))
            .thenReturn(listOf(ArticleMeta(unreadId, isUnread = true, isStarred = false)))
        whenever(dao.queryMetadataByFeedId(ACCOUNT_ID, feed.id, false))
            .thenReturn(listOf(ArticleMeta(starredId, isUnread = false, isStarred = true)))
        val api = mock<GoogleReaderAPI>()
        val emptyIds = GoogleReaderDTO.ItemIds(emptyList(), null)
        whenever(api.getItemIdsForFeed(feedId = feed.id, filterRead = true)).thenReturn(emptyIds)
        whenever(api.getItemIdsForFeed(feedId = feed.id, filterRead = false)).thenReturn(emptyIds)
        whenever(api.getStarredItemIds()).thenReturn(emptyIds)
        val service = spy(readerService(dao, accountService, feedDao))
        doReturn(api).whenever(service).getGoogleReaderAPI(account)
        service.syncGoogleReaderFeed(ACCOUNT_ID, feed.id)
        verify(dao).markAsStarredByIdSet(ACCOUNT_ID, setOf(starredId), false)
    }

    /** API Deferred 已完成，本例只验证真正的本地差集和写入执行。 */
    private fun syncState(remoteStars: Set<String>, localStars: Set<String>) = GoogleReaderSyncState(
        account = Account(ACCOUNT_ID, "Google Reader", AccountType.GoogleReader), api = mock(), preDate = Date(),
        localIds = remoteStars + localStars, localUnread = emptySet(), localStarred = localStars, localRead = emptySet(),
        remoteUnread = CompletableDeferred(emptySet()), remoteStarred = CompletableDeferred(remoteStars),
        remoteRead = CompletableDeferred(emptySet()),
    )

    /** 仅替换数据库及未使用的外部边界，收藏同步使用真实 service 实现。 */
    private fun readerService(dao: ArticleDao, accounts: AccountService = mock(), feeds: FeedDao = mock()) = GoogleReaderRssService(
        context = mock(), articleDao = dao, feedDao = feeds, rssHelper = mock(), notificationHelper = mock(),
        groupDao = mock(), ioDispatcher = Dispatchers.Unconfined, mainDispatcher = Dispatchers.Unconfined,
        defaultDispatcher = Dispatchers.Unconfined, workManager = mock(), accountService = accounts, syncLogger = mock(),
    )

    private companion object {
        /** 测试账户与状态写入的身份范围。 */
        const val ACCOUNT_ID = 7
        /** 差集超过旧分块大小，确保至少产生两批。 */
        const val DIFF_SIZE = 1_100
        /** SQLite 最小参数预算 999 扣除账户与状态两个绑定。 */
        const val SAFE_ID_COUNT = 997
    }
}
