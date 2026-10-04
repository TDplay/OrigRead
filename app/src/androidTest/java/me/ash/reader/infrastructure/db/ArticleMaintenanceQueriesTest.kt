package me.ash.reader.infrastructure.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFilterMetadata
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** 在设备上使用独立内存库固定专用查询的账户、排序、分块去重与事务语义。 */
@RunWith(AndroidJUnit4::class)
class ArticleMaintenanceQueriesTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(TEST_TIMEOUT_SECONDS)
    private lateinit var database: AndroidDatabase
    private val feed = feed(id = "target-feed", accountId = ACCOUNT_ID)
    private val otherFeed = feed(id = "other-feed", accountId = ACCOUNT_ID)

    // 使用内存库和独立种子，不打开应用的 Reader 用户数据库。
    @Before fun setUp() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AndroidDatabase::class.java).build()
        database.groupDao().insert(Group(id = "group", name = "测试分组", accountId = ACCOUNT_ID))
        database.feedDao().insert(feed, otherFeed)
    }

    // 每例释放自己的 Room 连接，避免共享数据库状态。
    @After fun tearDown() { database.close() }

    @Test fun accountQueryPreservesDateAndIdOrderAndCompletePayload() = runBlocking<Unit> {
        val tieB = article(1).copy(id = "b", date = Date(TIE_DATE))
        val tieA = article(2).copy(id = "a", date = Date(TIE_DATE))
        val earlier = article(0).copy(id = "z", date = Date(EARLIER_DATE))
        val otherAccount = article(3, accountId = OTHER_ACCOUNT_ID)
        database.articleDao().insertList(listOf(tieB, otherAccount, tieA, earlier))

        val complete = database.articleDao().queryAllByAccountId(ACCOUNT_ID)
        val metadata = database.articleDao().queryFilterMetadataByAccountId(ACCOUNT_ID)

        assertEquals(
            complete.map { ArticleFilterMetadata(id = it.id, feedId = it.feedId, title = it.title, date = it.date) },
            metadata,
        )
        assertEquals(listOf("z", "a", "b"), complete.map { it.id })
        assertEquals(listOf(EARLIER_DATE, TIE_DATE, TIE_DATE), complete.map { it.date.time })
        assertTrue(complete.all { it.rawDescription == COMPLETE_PAYLOAD })
        assertTrue(complete.all { it.accountId == ACCOUNT_ID })
    }

    @Test fun rssDedupPreservesScopeChunksOrderAndRemovesIncomingDuplicateLinks() = runBlocking<Unit> {
        val incoming = (0 until INCOMING_COUNT).map { article(it) }
        val existingIndices = setOf(0, QUERY_CHUNK_SIZE, INCOMING_COUNT - 1)
        val existing = existingIndices.map { incoming[it].copy(id = "existing-$it") }
        // Article 的外键只约束 feedId；另一账户同来源的合法行不能影响当前账户去重。
        val otherScope = listOf(
            incoming[1].copy(id = "other-feed-article", feedId = otherFeed.id),
            incoming[2].copy(id = "other-account-article", accountId = OTHER_ACCOUNT_ID),
        )
        database.articleDao().insertList(existing + otherScope)
        val repeated = listOf(
            article(INCOMING_COUNT).copy(id = "repeat-a", link = REPEATED_LINK),
            article(INCOMING_COUNT).copy(id = "repeat-b", link = REPEATED_LINK),
        )
        val expected = incoming.filterIndexed { index, _ -> index !in existingIndices } + repeated.first()

        val inserted = database.articleDao().insertListIfNotExist(incoming + repeated, feed)
        val complete = database.articleDao().queryArticlesByLinksChunked(
            linkList = (incoming + repeated).map { it.link },
            feedId = feed.id, accountId = ACCOUNT_ID,
        )

        assertEquals(expected, inserted)
        assertEquals(INCOMING_COUNT + 1, complete.size)
        assertEquals(1, complete.count { it.link == REPEATED_LINK })
        assertTrue(complete.all { it.rawDescription == COMPLETE_PAYLOAD })
        assertTrue(complete.none { row -> otherScope.any { it.id == row.id } })
        assertEquals(
            complete.map { it.link }.toSet(),
            database.articleDao().queryExistingLinksChunked(
                linkList = (incoming + repeated).map { it.link },
                feedId = feed.id, accountId = ACCOUNT_ID,
            ),
        )
        assertTrue(database.articleDao().insertListIfNotExist(emptyList(), feed).isEmpty())
    }

    @Test fun duplicatePrimaryKeyRollsBackEntireInsertion() = runBlocking<Unit> {
        val conflicting = listOf(article(0).copy(id = "conflict"), article(1).copy(id = "conflict"))
        try {
            database.articleDao().insertListIfNotExist(conflicting, feed)
            fail("主键冲突必须抛出，不能报告插入成功")
        } catch (_: SQLiteConstraintException) {
            // 维持原 ABORT 事务语义：后续冲突必须撤销本批已执行的插入。
        }
        assertTrue(database.articleDao().queryAllByAccountId(ACCOUNT_ID).isEmpty())
    }

    // 最小真实订阅种子，保留正常的组外键关系。
    private fun feed(id: String, accountId: Int) = Feed(
        id = id, name = id, url = "https://example.com/$id",
        groupId = "group", accountId = accountId,
    )

    // 完整正文作为哨兵，验证保留的完整查询仍能服务原调用方。
    private fun article(index: Int, feed: Feed = this.feed, accountId: Int = feed.accountId) = Article(
        id = "incoming-$index", date = Date(index.toLong()), title = "文章 $index",
        rawDescription = COMPLETE_PAYLOAD, shortDescription = "摘要",
        link = "https://example.com/article/$index", feedId = feed.id, accountId = accountId,
    )

    private companion object {
        // 单个设备用例最多执行一分钟。
        const val TEST_TIMEOUT_SECONDS = 60L
        // 当前账户和另一账户用于保护原查询的账户范围。
        const val ACCOUNT_ID = 1
        const val OTHER_ACCOUNT_ID = 2
        // 输入跨越原有 800 条边界并进入第三块，不缩小覆盖范围。
        const val QUERY_CHUNK_SIZE = 800
        const val INCOMING_COUNT = QUERY_CHUNK_SIZE * 2 + 3
        // 同日期下由 id 决定顺序；较早日期必须排在前面。
        const val EARLIER_DATE = 10L
        const val TIE_DATE = 20L
        // 同批相同非空链接只保存第一条，完整正文仍须可查询。
        const val REPEATED_LINK = "https://example.com/repeated"
        const val COMPLETE_PAYLOAD = "<p>完整正文保持不变</p>"
    }
}
