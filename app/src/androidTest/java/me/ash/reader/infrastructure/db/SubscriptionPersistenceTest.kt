package me.ash.reader.infrastructure.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.contentUpdate
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.rss.RssHttpCache
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实内存 Room 验证事务和部分更新，不打开用户 Reader 数据库。 */
@RunWith(AndroidJUnit4::class)
class SubscriptionPersistenceTest {
    private lateinit var database: AndroidDatabase
    private val feed = Feed(id = "feed", name = "RSS", url = "https://example.com/feed", groupId = "group", accountId = ACCOUNT_ID)

    /** 每例建立独立数据库与真实来源外键。 */
    @Before fun setUp() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AndroidDatabase::class.java,
        ).build()
        database.groupDao().insert(Group("group", "Test", ACCOUNT_ID))
        database.feedDao().insert(feed)
    }

    /** 每例释放独立连接。 */
    @After fun tearDown() { database.close() }

    /** 第二条文章冲突时，本批插入及 validator 都必须回滚。 */
    @Test fun articleFailureDoesNotAdvanceHttpValidator() = runBlocking<Unit> {
        val before = RssHttpCache(feed.id, feed.url, "v1", null, 1L)
        database.rssHttpCacheDao().upsert(before)
        val incoming = listOf(article("collision", "https://example.com/1"), article("collision", "https://example.com/2"))
        try {
            database.articleDao().insertRssWithCache(incoming, feed, before.copy(etag = "v2"))
            fail("独立链接的主键碰撞不能转成成功")
        } catch (_: SQLiteConstraintException) {
            // 真实主键错误离开事务后，原缓存及全部旧数据仍在。
        }
        assertEquals(before, database.rssHttpCacheDao().query(feed.id))
        assertTrue(database.articleDao().queryAllByAccountId(ACCOUNT_ID).isEmpty())
    }

    /** 成功批次和 validator 一同提交，重复稳定 ID 的无链接文章不会混为一个空 URL。 */
    @Test fun linklessStableIdsDoNotCollapseAndRepeatFetchDoesNotInsertAgain() = runBlocking<Unit> {
        val first = article("guid-a", "")
        val second = article("guid-b", "")
        val cache = RssHttpCache(feed.id, feed.url, "v2", null, 2L)
        val inserted = database.articleDao().insertRssWithCache(listOf(first, second, first), feed, cache)
        assertEquals(listOf(first, second), inserted)
        assertEquals(cache, database.rssHttpCacheDao().query(feed.id))
        assertTrue(database.articleDao().insertListIfNotExist(listOf(first, second), feed).isEmpty())
    }

    /** 清理前将稳定身份归档，与删除共同提交，不能只留下一个空链接。 */
    @Test fun archiveDeletionPreservesLinklessStableIdentity() = runBlocking<Unit> {
        val stored = article("guid-a", "").copy(isUnread = false)
        database.articleDao().insertList(listOf(stored))
        database.articleDao().archiveAndDelete(listOf(stored))
        assertTrue(database.articleDao().queryAllByAccountId(ACCOUNT_ID).isEmpty())
        val archived = database.feedDao().queryArchivedArticles(feed.id).single()
        assertEquals(stored.id, archived.articleId)
        assertEquals("", archived.link)
    }

    /** 网络前的旧快照不能覆盖用户随后产生的已读、收藏、稍后读和阅读时间。 */
    @Test fun contentUpdatePreservesConcurrentReadingState() = runBlocking<Unit> {
        val before = article("article", "https://example.com/1")
        database.articleDao().insertList(listOf(before))
        val afterUserAction = before.copy(isUnread = false, isStarred = true, isReadLater = true, updateAt = Date(READ_AT))
        database.articleDao().update(afterUserAction)
        database.articleDao().updateContent(before.copy(title = "New content", rawDescription = "New body").contentUpdate())
        val stored = database.articleDao().queryAllByAccountId(ACCOUNT_ID).single()
        assertEquals("New content", stored.title)
        assertEquals("New body", stored.rawDescription)
        assertFalse(stored.isUnread)
        assertTrue(stored.isStarred)
        assertTrue(stored.isReadLater)
        assertEquals(Date(READ_AT), stored.updateAt)
    }

    /** 最小文章哨兵保留真实来源关系。 */
    private fun article(id: String, link: String) = Article(
        id = id, date = Date(0), title = id, rawDescription = "Body", shortDescription = "Body",
        link = link, feedId = feed.id, accountId = ACCOUNT_ID,
    )

    private companion object {
        /** 本例独立账户及并发阅读时间哨兵。 */
        const val ACCOUNT_ID = 1
        const val READ_AT = 42L
    }
}
