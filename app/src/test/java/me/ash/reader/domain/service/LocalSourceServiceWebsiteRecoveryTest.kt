package me.ash.reader.domain.service

import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.json.JsonSourceHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.RssHttpCache
import me.ash.reader.infrastructure.rss.RssHttpCacheDao
import me.ash.reader.infrastructure.rss.RssQueryResult
import me.ash.reader.infrastructure.rsshub.RssHubProbeResult
import me.ash.reader.infrastructure.rsshub.RssHubResolver
import me.ash.reader.infrastructure.rsshub.RssHubRouteDefinition
import me.ash.reader.infrastructure.rsshub.RssHubRouteMatch
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionDescriptor
import me.ash.reader.infrastructure.website.CandidateState
import me.ash.reader.infrastructure.website.WebsiteHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class LocalSourceServiceWebsiteRecoveryTest : LocalSourceServiceTestFixture() {
    @Test
    fun `旧版空 Website 刷新失败后可原地恢复为 RSS`() {
        runBlocking {
            val preDate = Date(1_786_000_000_000L)
            val websiteFeed =
                feed(url = "https://example.com/forum.php?mod=rss").copy(
                    name = "旧版误分类来源",
                    sourceType = SourceType.WEBSITE,
                    isFullContent = true,
                    isBrowser = true,
                )
            val entry =
                SyndEntryImpl().apply {
                    title = "中文测试主题"
                    link = "https://example.com/thread-1.html"
                }
            val discovered =
                SyndFeedImpl().apply {
                    title = "吾爱破解 - 52pojie.cn"
                    entries = listOf(entry)
                }
            val article = mock<Article>()

            whenever(websiteHelper.fetchArticles(websiteFeed, preDate))
                .thenThrow(IllegalStateException("当前网站的解析规则均未通过健康检查"))
            whenever(articleDao.countByFeedId(websiteFeed.accountId, websiteFeed.id)).thenReturn(0)
            whenever(rssHelper.parseFeedDirect(websiteFeed.url, "")).thenReturn(discovered)

            val recoveredFeed =
                websiteFeed.copy(
                    name = "吾爱破解 - 52pojie.cn",
                    sourceType = SourceType.RSS,
                    isFullContent = false,
                    isBrowser = false,
                )
            whenever(
                rssHelper.buildArticlesFromSyndEntries(
                    eq(recoveredFeed),
                    eq(websiteFeed.accountId),
                    eq(listOf(entry)),
                    eq(preDate),
                )
            ).thenReturn(listOf(article))

            val result = service.fetchForSync(websiteFeed, preDate).feedWithArticle

            assertEquals(SourceType.RSS, result.feed.sourceType)
            assertEquals("吾爱破解 - 52pojie.cn", result.feed.name)
            assertFalse(result.feed.isFullContent)
            assertFalse(result.feed.isBrowser)
            assertSame(article, result.articles.single())
            verify(feedDao).update(recoveredFeed)
        }
    }

    @Test
    fun `真实 RSS 即使本轮没有新文章也会完成 Website 类型恢复`() {
        runBlocking {
            val preDate = Date(1_786_000_000_000L)
            val websiteFeed = feed("https://example.com/feed.xml").copy(sourceType = SourceType.WEBSITE)
            val entry = SyndEntryImpl().apply { title = "旧文章"; link = "https://example.com/old" }
            val discovered = SyndFeedImpl().apply { title = "真实 RSS"; entries = listOf(entry) }

            whenever(websiteHelper.fetchArticles(websiteFeed, preDate))
                .thenThrow(IllegalStateException("当前网站的解析规则均未通过健康检查"))
            whenever(articleDao.countByFeedId(websiteFeed.accountId, websiteFeed.id)).thenReturn(0)
            whenever(rssHelper.parseFeedDirect(websiteFeed.url, "")).thenReturn(discovered)
            whenever(rssHelper.buildArticlesFromSyndEntries(any(), eq(websiteFeed.accountId), eq(listOf(entry)), eq(preDate)))
                .thenReturn(emptyList())

            val result = service.fetchForSync(websiteFeed, preDate).feedWithArticle

            assertEquals(SourceType.RSS, result.feed.sourceType)
            assertEquals("真实 RSS", result.feed.name)
            assertEquals(emptyList<Article>(), result.articles)
            verify(feedDao).update(result.feed)
        }
    }

    @Test
    fun `已有文章的 Website 刷新失败时不会自动改成 RSS`() {
        runBlocking {
            val preDate = Date(1_786_000_000_000L)
            val websiteFeed = feed("https://example.com/news").copy(sourceType = SourceType.WEBSITE)
            val websiteError = IllegalStateException("当前网站的解析规则均未通过健康检查")

            whenever(websiteHelper.fetchArticles(websiteFeed, preDate)).thenThrow(websiteError)
            whenever(articleDao.countByFeedId(websiteFeed.accountId, websiteFeed.id)).thenReturn(1)

            val thrown = runCatching { service.fetchForSync(websiteFeed, preDate) }.exceptionOrNull()

            assertSame(websiteError, thrown)
            verify(rssHelper, never()).parseFeedDirect(any(), any(), any())
            verify(feedDao, never()).update(any())
        }
    }
}
