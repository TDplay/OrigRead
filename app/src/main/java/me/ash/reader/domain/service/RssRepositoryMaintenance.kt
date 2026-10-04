package me.ash.reader.domain.service

import java.util.Date
import java.util.UUID
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.infrastructure.preference.KeepArchivedPreference
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.spacerDollar

/** 阅读状态操作的既有范围和截止时间。 */
internal data class ReadSelection(
    val accountId: Int,
    val groupId: String?,
    val feedId: String?,
    val articleId: String?,
    val before: Date?,
    val isUnread: Boolean,
)

/** 修改阅读状态时保持分组、来源、单篇、全账户的优先级。 */
internal suspend fun ArticleDao.markReadSelection(selection: ReadSelection) {
    val before = selection.before ?: Date(Long.MAX_VALUE)
    when {
        selection.groupId != null ->
            markAllAsReadByGroupId(selection.accountId, selection.groupId, selection.isUnread, before)
        selection.feedId != null ->
            markAllAsReadByFeedId(selection.accountId, selection.feedId, selection.isUnread, before)
        selection.articleId != null ->
            markAsReadByArticleId(selection.accountId, selection.articleId, selection.isUnread)
        else -> markAllAsRead(selection.accountId, selection.isUnread, before)
    }
}

/** 基类旧入口的初始保存流程；本地和 Google Reader 的具体实现独立覆盖此入口。 */
internal suspend fun AbstractRssRepository.subscribeInitialRss(request: SubscriptionRequest) =
    withSubscriptionLock(request.accountId) {
        if (findExistingFeed(request.accountId, request.feedLink) != null) return@withSubscriptionLock
        val feed = Feed(
            id = request.accountId.spacerDollar(UUID.randomUUID().toString()),
            name = request.searchedFeed.title.decodeHTML()!!,
            url = request.feedLink, groupId = request.groupId, accountId = request.accountId,
            icon = request.searchedFeed.icon?.link, isBrowser = request.browser,
            isNotification = request.notification, isFullContent = request.fullContent, sourceType = SourceType.RSS,
        )
        val articles = request.searchedFeed.entries.map {
            feedParser.buildArticleFromSyndEntry(feed, request.accountId, it)
        }
        feedStore.insert(feed)
        articleStore.insertList(articles.map { it.copy(feedId = feed.id) })
    }

/** 已读且未收藏文章按指定账户清理，链接与稳定身份在删除前共同归档。 */
internal suspend fun AbstractRssRepository.clearAccountArchives(account: Account): List<Article> {
    if (account.keepArchived == KeepArchivedPreference.Always) return emptyList()
    val accountId = requireNotNull(account.id)
    val articles = articleStore.queryArchivedArticleBefore(
        accountId, Date(System.currentTimeMillis() - account.keepArchived.value),
    )
    articleStore.archiveAndDelete(articles)
    return articles
}
