package me.ash.reader.domain.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.android.AndroidApp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 首次网站订阅实际调用 Android 转换器、本地服务和 Room，不等待下一次同步。 */
@RunWith(AndroidJUnit4::class)
class LocalRssServiceWebsiteSubscriptionTest {
    /** 使用独立且非当前账户提交，验证文章来自预览并保持指定来源与账户归属。 */
    @Test fun websiteSubscriptionPersistsProbeArticlesImmediately() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AndroidApp
        val database = app.androidDatabase
        val account = Account(TEST_ACCOUNT_ID, "Website subscription test", AccountType.Local)
        val group = Group("website-subscription-test-group", "Test", TEST_ACCOUNT_ID)
        database.accountDao().insert(account)
        database.groupDao().insert(group)
        try {
            val entry = SyndEntryImpl().apply {
                title = "Probe article"; link = "https://news.example.com/article/1"; publishedDate = Date(PUBLISHED_AT)
            }
            val searchedFeed = SyndFeedImpl().apply {
                title = "Example News"; link = "https://news.example.com/"; entries = listOf(entry)
            }
            val result = app.localRssService.subscribe(SubscriptionRequest(
                accountId = TEST_ACCOUNT_ID, groupId = group.id, feedLink = searchedFeed.link,
                searchedFeed = searchedFeed, sourceType = SourceType.WEBSITE,
            ))
            val storedFeed = requireNotNull(database.feedDao().queryById(result.feedId))
            val storedArticles = database.articleDao().queryAllByFeedId(TEST_ACCOUNT_ID, result.feedId)
            assertTrue(result.created)
            assertEquals(SourceType.WEBSITE, storedFeed.sourceType)
            assertEquals(TEST_ACCOUNT_ID, storedFeed.accountId)
            assertEquals(1, storedArticles.size)
            assertEquals(result.feedId, storedArticles.single().feedId)
            assertEquals(entry.link, storedArticles.single().link)
            assertEquals(entry.title, storedArticles.single().title)
        } finally {
            // 独立分组删除会级联清理本例的来源及文章，再移除测试账户。
            database.groupDao().delete(group)
            database.accountDao().delete(account)
        }
    }

    private companion object {
        /** 固定独立账户与文章日期，既不切当前账户，也不依赖设备时钟。 */
        const val TEST_ACCOUNT_ID = 20261004
        const val PUBLISHED_AT = 1_700_000_000_000L
    }
}
