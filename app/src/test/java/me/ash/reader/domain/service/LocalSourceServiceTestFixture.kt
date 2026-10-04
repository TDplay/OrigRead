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

/** 来源抓取测试共享的依赖边界，各场景仍调用真实 LocalSourceService。 */
abstract class LocalSourceServiceTestFixture {
    protected val feedDao = mock<FeedDao>()
    protected val articleDao = mock<ArticleDao>()
    protected val rssHelper = mock<RssHelper>()
    protected val rssHttpCacheDao = mock<RssHttpCacheDao>()
    protected val websiteHelper = mock<WebsiteHelper>()
    protected val jsonSourceHelper = mock<JsonSourceHelper>()
    protected val rssHubResolver = mock<RssHubResolver>()
    protected val subscriptionRepository = mock<RssHubSubscriptionRepository>()
    protected val service =
        LocalSourceService(
            feedDao = feedDao,
            articleDao = articleDao,
            rssHelper = rssHelper,
            rssHttpCacheDao = rssHttpCacheDao,
            websiteHelper = websiteHelper,
            jsonSourceHelper = jsonSourceHelper,
            rssHubResolver = rssHubResolver,
            rssHubSubscriptionRepository = subscriptionRepository,
        )


    protected fun feed(url: String) =
        Feed(
            id = "1\$feed-id",
            name = "RSSHub source",
            url = url,
            groupId = "1\$group-id",
            accountId = 1,
            icon = "https://example.com/icon.png",
            sourceType = SourceType.RSS,
        )

    protected fun route() =
        RssHubRouteDefinition(
            id = "dynamic-user",
            name = "Dynamic user",
            host = "example.com",
            pathPrefix = "/user",
            target = "/example/user/:id",
            sourcePathTemplate = "/user/:id",
        )
}
