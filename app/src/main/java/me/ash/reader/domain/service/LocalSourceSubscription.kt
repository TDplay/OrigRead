package me.ash.reader.domain.service

import java.util.UUID
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.rss.RssHttpCache
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.spacerDollar

/** 固定账户串行保存本地来源，配置先写成功，文章与 HTTP validator 在短事务中提交。 */
internal suspend fun LocalRssService.saveSourceSubscription(request: SubscriptionRequest): SubscriptionResult =
    withSubscriptionLock(request.accountId) {
        val account = requireNotNull(accountService.getAccountById(request.accountId)) { "订阅账户不存在" }
        require(account.type.id == me.ash.reader.domain.model.account.AccountType.Local.id) { "此来源只支持本地账户" }
        require(groupDao.queryById(request.groupId)?.accountId == request.accountId) { "订阅分组不属于目标账户" }
        val existing = findExistingSource(request)
        if (existing != null) return@withSubscriptionLock SubscriptionResult(existing.id, created = false)
        val feed = request.buildLocalFeed()
        val articles = rssHelper.buildArticlesFromSyndEntries(feed, feed.accountId, request.searchedFeed.entries)
        val filtered = articleFilterEngine.filterBeforeInsert(articles, feed.name)
        saveSourceConfiguration(feed.id, request)
        val cache = request.initialHttpCache(feed)
        if (cache == null) localSubscriptionDao.insertFeedWithArticles(feed, filtered)
        else localSubscriptionDao.insertFeedArticlesAndCache(feed, filtered, cache)
        SubscriptionResult(feed.id, created = true)
    }

/** RSSHub 同一路由只在目标账户内查重，其他来源沿用保守 URL 比较。 */
private suspend fun LocalRssService.findExistingSource(request: SubscriptionRequest): Feed? {
    val route = request.rssHub?.routePath
    val routed = route?.let(rssHubSubscriptionRepository::findFeedIdsByRoute)
        ?.firstNotNullOfOrNull { feedDao.queryById(it)?.takeIf { feed -> feed.accountId == request.accountId } }
    return routed ?: findExistingFeed(request.accountId, request.feedLink)
}

/** 来源 ID 只为新建记录生成，原有来源和文章身份保持不变。 */
private fun SubscriptionRequest.buildLocalFeed() = Feed(
    id = accountId.spacerDollar(UUID.randomUUID().toString()),
    name = searchedFeed.title.decodeHTML() ?: searchedFeed.title,
    url = feedLink, groupId = groupId, accountId = accountId,
    icon = searchedFeed.icon?.url ?: searchedFeed.icon?.link,
    isBrowser = browser, isNotification = notification, isFullContent = fullContent,
    sourceType = sourceType,
)

/** 配置写入失败直接中止新增，避免保存缺少后续刷新规则的来源。 */
private fun LocalRssService.saveSourceConfiguration(feedId: String, request: SubscriptionRequest) {
    if (request.sourceType == SourceType.JSON) {
        val probe = requireNotNull(request.jsonProbe) { "JSON 订阅缺少成功探测规则" }
        localSourceService.bindJsonSubscription(feedId, request.sourcePageUrl, probe)
    }
    request.rssHub?.let { rssHubSubscriptionRepository.record(feedId, it) }
    if (request.sourceType == SourceType.WEBSITE) {
        websiteHelper.setDynamicRenderingEnabled(feedId, request.dynamicRendering)
    }
}

/** 首次订阅的 validator 必须与所代表的文章一同提交。 */
private fun SubscriptionRequest.initialHttpCache(feed: Feed): RssHttpCache? {
    if (sourceType != SourceType.RSS || (etag.isNullOrBlank() && lastModified.isNullOrBlank())) return null
    return RssHttpCache(feed.id, feed.url, etag, lastModified, System.currentTimeMillis())
}
