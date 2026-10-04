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

class LocalSourceServiceRssHubFallbackTest : LocalSourceServiceTestFixture() {
    @Test
    fun `RSS 304 在同步入口直接短路且不触发恢复和缓存写入`() {
        runBlocking {
            val feed = feed(url = "https://example.com/feed.xml")
            val preDate = Date(1_786_000_000_000L)
            val cache =
                RssHttpCache(
                    feedId = feed.id,
                    feedUrl = feed.url,
                    etag = "\"etag-v1\"",
                    lastModified = "Tue, 18 Aug 2026 12:00:00 GMT",
                    updatedAt = 1L,
                )
            whenever(rssHttpCacheDao.query(feed.id)).thenReturn(cache)
            whenever(
                rssHelper.queryRssXmlConditional(
                    feed = eq(feed),
                    latestLink = isNull(),
                    preDate = eq(preDate),
                    etag = eq(cache.etag),
                    lastModified = eq(cache.lastModified),
                )
            ).thenReturn(
                RssQueryResult(
                    articles = emptyList(),
                    notModified = true,
                    etag = cache.etag,
                    lastModified = cache.lastModified,
                )
            )

            val result = service.fetchForSync(feed, preDate)

            assertEquals(true, result.notModified)
            assertEquals(emptyList<Article>(), result.feedWithArticle.articles)
            verify(subscriptionRepository, never()).sourceUrl(feed.id)
            verify(rssHttpCacheDao, never()).upsert(any())
            verify(rssHelper, never()).queryRssIconLink(any())
            verifyNoInteractions(rssHubResolver)
        }
    }

    @Test
    fun `RSSHub 固定地址失效后重新匹配并更新订阅地址`() {
        runBlocking {
            val feed = feed(url = "https://old-rsshub.example.com/example/user/42")
            val sourceUrl = "https://example.com/user/42"
            val recoveredUrl = "https://new-rsshub.example.com/example/user/42"
            val preDate = Date(1_786_000_000_000L)
            val entry = SyndEntryImpl().apply {
                title = "Recovered article"
                link = "https://example.com/article/1"
            }
            val syndFeed = SyndFeedImpl().apply { entries = listOf(entry) }
            val article = mock<Article>()
            whenever(rssHelper.queryRssHubXmlConditional(eq(feed), isNull(), eq(preDate), anyOrNull(), anyOrNull()))
                .thenReturn(RssQueryResult(emptyList(), successful = false, failure = java.io.IOException("offline")))
            whenever(subscriptionRepository.descriptor(feed.id))
                .thenReturn(RssHubSubscriptionDescriptor(originalInput = sourceUrl))
            whenever(rssHubResolver.probe(sourceUrl)).thenReturn(
                listOf(
                    RssHubProbeResult(
                        match =
                            RssHubRouteMatch(
                                route = route(),
                                feedUrl = recoveredUrl,
                                parameters = mapOf("id" to "42"),
                            ),
                        state = CandidateState.AVAILABLE,
                        feed = syndFeed,
                    )
                )
            )
            whenever(
                rssHelper.buildArticlesFromSyndEntries(
                    eq(feed.copy(url = recoveredUrl)),
                    eq(feed.accountId),
                    eq(listOf(entry)),
                    eq(preDate),
                )
            ).thenReturn(listOf(article))

            val result = service.fetch(feed, preDate)

            assertEquals(recoveredUrl, result.feed.url)
            assertEquals(1, result.articles.size)
            assertSame(article, result.articles.single())
            verify(feedDao).update(feed.copy(url = recoveredUrl))
        }
    }

    @Test
    fun `普通 RSS 无恢复元数据时保持原同步行为`() {
        runBlocking {
            val feed = feed(url = "https://example.com/feed.xml")
            val articles = listOf(mock<Article>())
            whenever(rssHelper.queryRssXmlConditional(eq(feed), isNull(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(RssQueryResult(articles))

            val result = service.fetch(feed)

            assertEquals(feed.url, result.feed.url)
            assertEquals(articles, result.articles)
            verify(subscriptionRepository, never()).sourceUrl(feed.id)
            verifyNoInteractions(rssHubResolver)
        }
    }

    @Test
    fun `RSSHub refresh failure preserves cache and is reported separately from an empty successful feed`(): Unit = runBlocking {
        val feed = feed("https://hub.example.com/zhihu/hot").copy(icon = null)
        val failure = java.io.EOFException("connection closed")
        whenever(subscriptionRepository.descriptor(feed.id))
            .thenReturn(RssHubSubscriptionDescriptor("rsshub://zhihu/hot", "/zhihu/hot"))
        whenever(rssHelper.queryRssHubXmlConditional(eq(feed), isNull(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(RssQueryResult(emptyList(), successful = false, failure = failure))
        whenever(rssHubResolver.probeRouteForRecovery("/zhihu/hot", null)).thenReturn(emptyList())

        val result = service.fetchForSync(feed)

        assertSame(failure, result.failure)
        assertEquals(feed.url, result.feedWithArticle.feed.url)
        verify(rssHttpCacheDao, never()).upsert(any())
        verify(feedDao, never()).update(any())
        verify(rssHelper, never()).queryRssIconLink(any())
        verify(rssHelper, never()).queryRssXmlConditional(any(), anyOrNull(), any(), anyOrNull(), anyOrNull())
        Unit
    }

    @Test
    fun `RSSHub route recovery uses the automatic pool while retaining credential binding context`(): Unit = runBlocking {
        val feed = feed("https://old.example.com/tiddlywiki/releases")
        val descriptor =
            RssHubSubscriptionDescriptor(
                originalInput = "rsshub://tiddlywiki/releases",
                routePath = "/tiddlywiki/releases",
                preferredInstance = "https://original.example.com",
                lastResolvedInstance = "https://old.example.com",
                lastResolvedUrl = feed.url,
            )
        whenever(subscriptionRepository.descriptor(feed.id)).thenReturn(descriptor)
        whenever(rssHelper.queryRssHubXmlConditional(eq(feed), isNull(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(RssQueryResult(emptyList(), successful = false, failure = java.io.IOException("offline")))
        whenever(rssHubResolver.probeRouteForRecovery("/tiddlywiki/releases", "https://old.example.com"))
            .thenReturn(emptyList())

        service.fetchForSync(feed)

        verify(rssHubResolver).probeRouteForRecovery("/tiddlywiki/releases", "https://old.example.com")
        Unit
    }

    @Test
    fun `empty successful RSSHub refresh does not trigger failover`() = runBlocking {
        val feed = feed("https://hub.example.com/zhihu/hot")
        whenever(subscriptionRepository.descriptor(feed.id))
            .thenReturn(RssHubSubscriptionDescriptor("rsshub://zhihu/hot", "/zhihu/hot"))
        whenever(rssHelper.queryRssHubXmlConditional(eq(feed), isNull(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(RssQueryResult(emptyList(), etag = "etag"))

        val result = service.fetchForSync(feed)

        assertEquals(null, result.failure)
        verifyNoInteractions(rssHubResolver)
    }

    @Test
    fun `route recovery fills an empty name but preserves the user name`(): Unit = runBlocking {
        listOf("", "My chosen name").forEach { originalName ->
            val feed = feed("https://old.example.com/zhihu/hot").copy(name = originalName)
            val recoveredUrl = "https://new.example.com/zhihu/hot"
            val syndFeed = SyndFeedImpl().apply { title = "Recovered title"; entries = emptyList() }
            whenever(subscriptionRepository.descriptor(feed.id))
                .thenReturn(RssHubSubscriptionDescriptor("rsshub://zhihu/hot", "/zhihu/hot"))
            whenever(rssHelper.queryRssHubXmlConditional(eq(feed), isNull(), any(), anyOrNull(), anyOrNull()))
                .thenReturn(RssQueryResult(emptyList(), successful = false, failure = java.io.IOException("offline")))
            whenever(rssHubResolver.probeRouteForRecovery("/zhihu/hot", null)).thenReturn(listOf(
                RssHubProbeResult(match = RssHubRouteMatch(route(), feedUrl = recoveredUrl),
                    state = CandidateState.AVAILABLE, feed = syndFeed)))
            whenever(rssHelper.buildArticlesFromSyndEntries(any(), any(), any(), any())).thenReturn(emptyList())
            val recovered = service.fetchForSync(feed).feedWithArticle.feed
            assertEquals(originalName.ifBlank { "Recovered title" }, recovered.name)
            assertEquals(recoveredUrl, recovered.url)
            verify(feedDao).update(recovered)
        }
    }
}
