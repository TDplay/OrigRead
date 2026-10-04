package me.ash.reader.infrastructure.rss

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rometools.rome.feed.synd.SyndEntryImpl
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.infrastructure.android.AndroidApp
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 在真实 Android 文本 API 和注入的 RssHelper 上验证身份转换，不替换转换器。 */
@RunWith(AndroidJUnit4::class)
class RssArticleIdentityTest {
    /** Atom ID 不可打开也须作为稳定身份，空截止链接不能提前停止读取。 */
    @Test fun linklessAtomEntriesKeepStableIndependentIdentities() = runBlocking<Unit> {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(ATOM_BODY))
        server.enqueue(MockResponse().setBody(ATOM_BODY))
        server.start()
        try {
            val feed = Feed(id = "feed", name = "Atom News", url = server.url("/atom").toString(), groupId = "group", accountId = 1)
            val first = helper.queryRssXmlConditional(feed, "", Date(FETCHED_AT))
            val second = helper.queryRssXmlConditional(feed, null, Date(FETCHED_AT))
            assertTrue(first.successful)
            assertEquals(2, first.articles.size)
            assertEquals(first.articles.map { it.id }, second.articles.map { it.id })
            assertEquals(2, first.articles.map { it.id }.toSet().size)
            assertTrue(first.articles.all { it.link.isEmpty() })
        } finally {
            // 本机响应仅属于本例，结束后释放连接。
            server.shutdown()
        }
    }

    /** 相同上游 GUID 在不同订阅及账户中不能形成相同本地主键。 */
    @Test fun stableSourceIdentityIsScopedToFeedAndAccount() {
        val entry = SyndEntryImpl().apply { uri = "urn:news:shared"; title = "Article" }
        val firstFeed = Feed(id = "feed", name = "News", url = "https://example.com/feed", groupId = "group", accountId = 1)
        val otherFeed = firstFeed.copy(id = "other-feed")
        val first = helper.buildArticleFromSyndEntry(firstFeed, firstFeed.accountId, entry)
        val second = helper.buildArticleFromSyndEntry(otherFeed, otherFeed.accountId, entry)
        val otherAccount = helper.buildArticleFromSyndEntry(firstFeed.copy(accountId = 2), 2, entry)
        assertEquals(3, setOf(first.id, second.id, otherAccount.id).size)
        assertEquals("", first.link)
    }

    private val helper get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AndroidApp).rssHelper

    private companion object {
        /** 文章时间早于抓取时间，避免未来日期修正影响身份断言。 */
        const val FETCHED_AT = 1_791_067_200_000L
        /** 两个不可解引用的 Atom 身份，刻意省略可打开的 link。 */
        const val ATOM_BODY = """<feed xmlns="http://www.w3.org/2005/Atom">
            <id>urn:news:feed</id><title>Atom News</title><updated>2026-10-01T00:00:00Z</updated>
            <entry><id>urn:news:a</id><title>A</title><updated>2026-10-01T00:00:00Z</updated></entry>
            <entry><id>urn:news:b</id><title>B</title><updated>2026-10-01T00:00:00Z</updated></entry>
            </feed>"""
    }
}
