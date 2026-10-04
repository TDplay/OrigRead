package me.ash.reader.domain.service

import java.util.Date
import javax.inject.Inject
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.feed.normalizeRssReadingMode
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.json.JsonSourceHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.RssHttpCache
import me.ash.reader.infrastructure.rss.RssHttpCacheDao
import me.ash.reader.infrastructure.rsshub.RssHubResolver
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.website.WebsiteHelper
import me.ash.reader.infrastructure.website.WebsiteRule
import kotlinx.coroutines.CancellationException

/**
 * 本地资讯来源同步入口，根据来源类型分派具体抓取逻辑。
 */
class LocalSourceService @Inject constructor(
    private val feedDao: FeedDao,
    private val articleDao: ArticleDao,
    private val rssHelper: RssHelper,
    private val rssHttpCacheDao: RssHttpCacheDao,
    private val websiteHelper: WebsiteHelper,
    private val jsonSourceHelper: JsonSourceHelper,
    private val rssHubResolver: RssHubResolver,
    private val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
) {

    data class SyncFetchResult(
        val feedWithArticle: FeedWithArticle,
        val notModified: Boolean = false,
        val failure: Exception? = null,
        val pendingCache: RssHttpCache? = null,
        /** 本次网站响应选中的范围规则；其他来源与无需清理的网站为 null。 */
        val websiteCleanupRule: WebsiteRule? = null,
    )

    /** JSON 订阅在入库前保留成功解析的规则，避免刷新重新按域名选错规则。 */
    fun bindJsonSubscription(feedId: String, sourcePageUrl: String, result: me.ash.reader.infrastructure.json.JsonSourceProbeResult) =
        jsonSourceHelper.bindSubscription(feedId, sourcePageUrl, result)

    /** 修复只更新经过用户确认的已有 JSON 来源，不经普通重复订阅入口改写绑定。 */
    suspend fun rebindJsonSubscription(request: me.ash.reader.infrastructure.json.JsonRebindRequest) {
        val current = requireNotNull(feedDao.queryById(request.feed.id)) { "JSON 来源已不存在" }
        require(current.accountId == request.feed.accountId && current.sourceType == SourceType.JSON) {
            "JSON 来源归属或类型已改变"
        }
        require(current.url == request.feed.url) { "JSON 来源地址已改变，请重新打开来源设置" }
        jsonSourceHelper.replaceSubscription(request) {
            check(feedDao.updateSourceUrl(current.id, request.probe.endpointUrl) == 1) { "JSON 来源已不存在" }
        }
    }

    /**
     * 按来源类型抓取一次，网络和解析错误由具体来源明确上报。
     */
    suspend fun fetch(feed: Feed, preDate: Date = Date()): FeedWithArticle =
        when (feed.sourceType) {
            SourceType.RSS -> fetchRss(feed, preDate)
            SourceType.WEBSITE -> fetchWebsiteWithRssRecovery(feed, preDate).feedWithArticle
            SourceType.JSON -> jsonSourceHelper.fetch(feed, preDate)
        }

    suspend fun fetchForSync(feed: Feed, preDate: Date = Date()): SyncFetchResult =
        when (feed.sourceType) {
            SourceType.RSS -> fetchRssForSync(feed, preDate)
            SourceType.WEBSITE -> fetchWebsiteWithRssRecovery(feed, preDate)
            SourceType.JSON -> SyncFetchResult(jsonSourceHelper.fetch(feed, preDate))
        }

    /** 单次抓取与同步共用失败语义；站点图标由现有手动补全入口处理。 */
    private suspend fun fetchRss(feed: Feed, preDate: Date): FeedWithArticle {
        val result = fetchRssForSync(feed, preDate)
        result.failure?.let { throw it }
        return result.feedWithArticle
    }

    private suspend fun fetchRssForSync(feed: Feed, preDate: Date): SyncFetchResult {
        val cache = rssHttpCacheDao.query(feed.id)?.takeIf { it.feedUrl == feed.url }
        val queried = queryRssForSync(feed, cache, preDate)
        if (queried.notModified) {
            return SyncFetchResult(
                feedWithArticle = FeedWithArticle(feed = feed, articles = emptyList()),
                notModified = true,
            )
        }

        var effectiveFeed = feed
        var articles = queried.articles
        var recoveredSuccessfully = false
        if (articles.isEmpty() && !queried.successful) {
            recoverRssHubFeed(feed, preDate)?.let { recovered ->
                effectiveFeed = recovered.feed
                articles = recovered.articles
                recoveredSuccessfully = true
            }
        }

        // 抓取只返回待提交缓存；文章入库事务成功后才允许使用这次响应的 validator。
        val pendingCache = if (queried.successful || recoveredSuccessfully) {
                RssHttpCache(
                    feedId = feed.id,
                    feedUrl = effectiveFeed.url,
                    etag = queried.etag.takeIf { queried.successful },
                    lastModified = queried.lastModified.takeIf { queried.successful },
                    updatedAt = System.currentTimeMillis(),
                )
        } else null
        return SyncFetchResult(
            failure = queried.failure.takeIf { !queried.successful && !recoveredSuccessfully },
            pendingCache = pendingCache,
            feedWithArticle =
                FeedWithArticle(
                    feed = effectiveFeed.copy(
                        isNotification = feed.isNotification && articles.isNotEmpty()
                    ),
                    articles = articles,
                )
        )
    }

    /** 只传入已经提交的 validator；无稳定链接也不截断当前响应。 */
    private suspend fun queryRssForSync(feed: Feed, cache: RssHttpCache?, fetchedAt: Date) =
        if (isRssHubFeed(feed)) rssHelper.queryRssHubXmlConditional(
            feed = feed, latestLink = null, preDate = fetchedAt,
            etag = cache?.etag, lastModified = cache?.lastModified,
        ) else rssHelper.queryRssXmlConditional(
            feed = feed, latestLink = null, preDate = fetchedAt,
            etag = cache?.etag, lastModified = cache?.lastModified,
        )

    private fun isRssHubFeed(feed: Feed): Boolean =
        rssHubSubscriptionRepository.descriptor(feed.id) != null

    /** 已固定的 RSSHub 地址失效时按 logical route 重选实例；旧版只有 sourceUrl 时仍兼容 Radar 重匹配。 */
    private suspend fun recoverRssHubFeed(feed: Feed, preDate: Date): FeedWithArticle? {
        val descriptor =
            rssHubSubscriptionRepository.descriptor(feed.id)
                ?: return null
        val results =
            if (!descriptor.routePath.isNullOrBlank()) {
                rssHubResolver.probeRouteForRecovery(
                    routePath = descriptor.routePath,
                    boundInstance = descriptor.lastResolvedInstance ?: descriptor.preferredInstance,
                )
            } else {
                rssHubResolver.probe(descriptor.originalInput)
            }
        val recovered =
            results
                .firstOrNull { result -> result.available }
                ?: return null
        val recoveredUrl = recovered.match.feedUrl ?: return null
        val recoveredFeed = feed.copy(
            url = recoveredUrl,
            name = feed.name.ifBlank { recovered.feed?.title?.takeIf(String::isNotBlank) ?: feed.name },
        )
        if (recoveredFeed != feed) feedDao.update(recoveredFeed)
        rssHubSubscriptionRepository.record(
            feed.id,
            descriptor.copy(
                routePath = recovered.routePath ?: descriptor.routePath,
                lastResolvedInstance = recovered.instanceBaseUrl ?: descriptor.lastResolvedInstance,
                lastResolvedUrl = recoveredUrl,
            ),
        )
        val articles =
            rssHelper.buildArticlesFromSyndEntries(
                feed = recoveredFeed,
                accountId = feed.accountId,
                entries = requireNotNull(recovered.feed).entries,
                preDate = preDate,
            )
        return FeedWithArticle(feed = recoveredFeed, articles = articles)
    }

    /** 抓取普通网站文章列表，并复用原有通知与入库链路。 */
    private suspend fun fetchWebsite(feed: Feed, preDate: Date): SyncFetchResult {
        val articles = websiteHelper.fetchArticles(feed, preDate)
        return SyncFetchResult(
            feedWithArticle = FeedWithArticle(
                feed = feed.copy(isNotification = feed.isNotification && articles.isNotEmpty()),
                articles = articles,
            ),
            websiteCleanupRule = articles.cleanupRule,
        )
    }

    /**
     * 兼容旧版把真实 RSS/Atom URL 错存成 Website 的空来源。
     *
     * 正常 Website 先按原逻辑刷新；只有刷新失败且该来源当前 0 篇文章时，才对同一 URL
     * 做一次 direct RSS 解析。解析出非空结构化 Feed 后原地改为 RSS，已有文章的网站绝不改类型。
     */
    private suspend fun fetchWebsiteWithRssRecovery(feed: Feed, preDate: Date): SyncFetchResult =
        try {
            fetchWebsite(feed, preDate)
        } catch (error: CancellationException) {
            throw error
        } catch (websiteError: Exception) {
            val recovered = recoverMisclassifiedWebsiteRss(feed, preDate) ?: throw websiteError
            SyncFetchResult(recovered)
        }

    private suspend fun recoverMisclassifiedWebsiteRss(feed: Feed, preDate: Date): FeedWithArticle? {
        if (articleDao.countByFeedId(feed.accountId, feed.id) > 0) return null

        val discovered =
            try {
                rssHelper.parseFeedDirect(feedUrl = feed.url, iconSourceUrl = "")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return null
            }
        if (discovered.entries.isNullOrEmpty()) return null

        val recoveredFeed =
            feed.copy(
                name = discovered.title?.takeIf { it.isNotBlank() } ?: feed.name,
                icon = discovered.icon?.url ?: feed.icon,
                sourceType = SourceType.RSS,
            ).normalizeRssReadingMode()
        val articles =
            rssHelper.buildArticlesFromSyndEntries(
                feed = recoveredFeed,
                accountId = feed.accountId,
                entries = discovered.entries,
                preDate = preDate,
            )

        feedDao.update(recoveredFeed)
        return FeedWithArticle(
            feed = recoveredFeed.copy(isNotification = feed.isNotification && articles.isNotEmpty()),
            articles = articles,
        )
    }
}
