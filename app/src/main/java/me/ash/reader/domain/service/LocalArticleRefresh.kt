package me.ash.reader.domain.service

import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.article.contentUpdate

    /**
     * JSON/API 的服务端内容可能在同一文章 URL 下补充或修正正文。
     * 刷新时更新远端内容字段，但保留本地阅读状态和首次入库时间，避免：
     * 1. WordPress 从 excerpt 修正为 content 后旧文章永远无法升级；
     * 2. 刷新正文把已读/收藏/稍后读重置；
     * 3. 仅因内容刷新修改 updateAt，干扰 keepArchived 的保留周期。
     */
internal suspend fun LocalRssService.updateJsonArticlesAndInsertNew(
        feed: Feed,
        fetchedArticles: List<me.ash.reader.domain.model.article.Article>,
    ): List<me.ash.reader.domain.model.article.Article> {
        if (fetchedArticles.isEmpty()) return emptyList()

        val existingByLink =
            articleDao.queryArticlesByLinksChunked(
                linkList = fetchedArticles.map { it.link },
                feedId = feed.id,
                accountId = feed.accountId,
            ).associateBy { it.link }

        val newArticles = mutableListOf<me.ash.reader.domain.model.article.Article>()
        val updatedArticles = mutableListOf<me.ash.reader.domain.model.article.Article>()
        fetchedArticles.forEach { fetched ->
            val existing = existingByLink[fetched.link]
            if (existing == null) {
                newArticles += fetched
            } else {
                updatedArticles += mergeJsonArticleRefresh(existing, fetched)
            }
        }

        if (updatedArticles.isNotEmpty()) {
            articleDao.updateContent(*updatedArticles.map { it.contentUpdate() }.toTypedArray())
        }
        if (newArticles.isNotEmpty()) {
            articleDao.insertList(newArticles)
        }
        return newArticles
    }

    /** 网站来源刷新时更新已有文章的列表元数据，同时保留本地阅读状态和正文缓存。 */
internal suspend fun LocalRssService.updateWebsiteArticlesAndInsertNew(
        feed: Feed,
        fetchedArticles: List<me.ash.reader.domain.model.article.Article>,
    ): List<me.ash.reader.domain.model.article.Article> {
        if (fetchedArticles.isEmpty()) return emptyList()

        val existingByLink =
            articleDao.queryArticlesByLinksChunked(
                linkList = fetchedArticles.map { it.link },
                feedId = feed.id,
                accountId = feed.accountId,
            ).associateBy { it.link }

        val newArticles = mutableListOf<me.ash.reader.domain.model.article.Article>()
        val updatedArticles = mutableListOf<me.ash.reader.domain.model.article.Article>()
        fetchedArticles.forEach { fetched ->
            val existing = existingByLink[fetched.link]
            if (existing == null) {
                newArticles += fetched
            } else {
                updatedArticles += mergeWebsiteArticleRefresh(existing, fetched)
            }
        }

        if (updatedArticles.isNotEmpty()) {
            articleDao.updateContent(*updatedArticles.map { it.contentUpdate() }.toTypedArray())
        }
        if (newArticles.isNotEmpty()) {
            articleDao.insertList(newArticles)
        }
        return newArticles
    }


internal fun mergeWebsiteArticleRefresh(
    existing: me.ash.reader.domain.model.article.Article,
    fetched: me.ash.reader.domain.model.article.Article,
): me.ash.reader.domain.model.article.Article =
    existing.copy(
        date = fetched.date,
        title = fetched.title,
        author = fetched.author,
        rawDescription = fetched.rawDescription.ifBlank { existing.rawDescription },
        shortDescription = fetched.shortDescription.ifBlank { existing.shortDescription },
        img = fetched.img ?: existing.img,
        link = fetched.link,
        feedId = existing.feedId,
        accountId = existing.accountId,
        // id / isUnread / isStarred / isReadLater / updateAt / fullContent 均保留 existing。
    )

internal fun mergeJsonArticleRefresh(
    existing: me.ash.reader.domain.model.article.Article,
    fetched: me.ash.reader.domain.model.article.Article,
): me.ash.reader.domain.model.article.Article =
    existing.copy(
        date = fetched.date,
        title = fetched.title,
        author = fetched.author,
        rawDescription = fetched.rawDescription,
        shortDescription = fetched.shortDescription,
        img = fetched.img ?: existing.img,
        link = fetched.link,
        feedId = existing.feedId,
        accountId = existing.accountId,
        // id / isUnread / isStarred / isReadLater / updateAt / fullContent 均保留 existing。
    )
