package me.ash.reader.domain.service

import androidx.paging.PagingSource
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.repository.ArticleDao

/** 页面查询捕获的范围与状态；排序沿用各个旧查询的既有契约。 */
internal data class ArticlePageQuery(
    val accountId: Int,
    val groupId: String?,
    val feedId: String?,
    val isStarred: Boolean,
    val isUnread: Boolean,
    val sortAscending: Boolean,
    val content: String = "",
)

/** 列表按分组、来源、账户的顺序选择同一组旧 Room 查询。 */
internal fun ArticleDao.selectArticlePage(query: ArticlePageQuery): PagingSource<Int, ArticleWithFeed> {
    query.groupId?.let { return selectGroupPage(query, it) }
    query.feedId?.let { return selectFeedPage(query, it) }
    return when {
        query.isStarred -> queryArticleWithFeedWhenIsStarred(query.accountId, true)
        query.isUnread -> queryArticleWithFeedWhenIsUnread(query.accountId, true, query.sortAscending)
        else -> queryArticleWithFeedWhenIsAll(query.accountId)
    }
}

/** 分组列表保留收藏优先与未读排序语义。 */
private fun ArticleDao.selectGroupPage(query: ArticlePageQuery, id: String) = when {
    query.isStarred -> queryArticleWithFeedByGroupIdWhenIsStarred(query.accountId, id, true)
    query.isUnread -> queryArticleWithFeedByGroupIdWhenIsUnread(query.accountId, id, true, query.sortAscending)
    else -> queryArticleWithFeedByGroupIdWhenIsAll(query.accountId, id)
}

/** 单来源列表与分组列表使用相同状态优先级。 */
private fun ArticleDao.selectFeedPage(query: ArticlePageQuery, id: String) = when {
    query.isStarred -> queryArticleWithFeedByFeedIdWhenIsStarred(query.accountId, id, true)
    query.isUnread -> queryArticleWithFeedByFeedIdWhenIsUnread(query.accountId, id, true, query.sortAscending)
    else -> queryArticleWithFeedByFeedIdWhenIsAll(query.accountId, id)
}

/** 搜索同样按范围分派，避免抽取过程中改变原有组合过滤条件。 */
internal fun ArticleDao.selectSearchPage(query: ArticlePageQuery): PagingSource<Int, ArticleWithFeed> {
    query.groupId?.let { return selectGroupSearch(query, it) }
    query.feedId?.let { return selectFeedSearch(query, it) }
    return when {
        query.isStarred -> searchArticleWhenIsStarred(query.accountId, query.content, true)
        query.isUnread -> searchArticleWhenIsUnread(query.accountId, query.content, true, query.sortAscending)
        else -> searchArticleWhenAll(query.accountId, query.content)
    }
}

/** 分组搜索继续使用现有参数化 SQL。 */
private fun ArticleDao.selectGroupSearch(query: ArticlePageQuery, id: String) = when {
    query.isStarred -> searchArticleByGroupIdWhenIsStarred(query.accountId, query.content, id, true)
    query.isUnread -> searchArticleByGroupIdWhenIsUnread(query.accountId, query.content, id, true, query.sortAscending)
    else -> searchArticleByGroupIdWhenAll(query.accountId, query.content, id)
}

/** 来源搜索保留收藏和未读页面的既有排序方式。 */
private fun ArticleDao.selectFeedSearch(query: ArticlePageQuery, id: String) = when {
    query.isStarred -> searchArticleByFeedIdWhenIsStarred(query.accountId, query.content, id, true)
    query.isUnread -> searchArticleByFeedIdWhenIsUnread(query.accountId, query.content, id, true, query.sortAscending)
    else -> searchArticleByFeedIdWhenAll(query.accountId, query.content, id)
}
