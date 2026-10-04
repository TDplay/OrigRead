package me.ash.reader.domain.repository

import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFilterMetadata
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed

// 分块保留 SQLite 参数余量，链接完整查询和专用去重查询共用。
private const val ARTICLE_LINK_QUERY_CHUNK_SIZE = 800

/** 文章身份、链接去重与完整内容查询。 */
interface ArticleLookupQueries {

    /** Edition 同机同步需要按当前账户导出完整文章快照；只返回 Article 本体，不加载 Feed 关系。 */
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        ORDER BY date ASC, id ASC
        """
    )
    suspend fun queryAllByAccountId(accountId: Int): List<Article>

    /** 过滤记录只读取匹配与展示字段；保持完整账户查询的顺序，最终条数仍由页面决定。 */
    @Query(
        """
        SELECT id, feedId, title, date FROM article
        WHERE accountId = :accountId
        ORDER BY date ASC, id ASC
        """
    )
    suspend fun queryFilterMetadataByAccountId(accountId: Int): List<ArticleFilterMetadata>

    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        AND feedId = :feedId
        """
    )
    suspend fun queryAllByFeedId(accountId: Int, feedId: String): List<Article>

    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT a.id, a.date, a.title, a.author, a.rawDescription,
        a.shortDescription, a.fullContent, a.img, a.link, a.feedId,
        a.accountId, a.isUnread, a.isStarred, a.isReadLater, a.updateAt
        FROM article AS a LEFT JOIN feed AS b
        ON a.feedId = b.id
        WHERE a.feedId = :feedId
        AND a.accountId = :accountId
        ORDER BY date DESC LIMIT 1
        """
    )
    suspend fun queryLatestByFeedId(accountId: Int, feedId: String): Article?

    @Query(
        """
        SELECT * from article
        WHERE link in (:linkList)
        AND feedId = :feedId
        AND accountId = :accountId
        """
    )
    suspend fun queryArticlesByLinks(
        linkList: List<String>,
        feedId: String,
        accountId: Int,
    ): List<Article>

    /**
     * SQLite/Room 的 IN 参数存在绑定数量上限。大 Feed 统一分块查询，避免数千篇历史文章
     * 在一次刷新时因为参数过多直接失败；800 还为 feedId/accountId 等参数留足余量。
     */
    @Transaction
    suspend fun queryArticlesByLinksChunked(
        linkList: List<String>,
        feedId: String,
        accountId: Int,
    ): List<Article> {
        if (linkList.isEmpty()) return emptyList()
        return linkList
            .distinct()
            .chunked(ARTICLE_LINK_QUERY_CHUNK_SIZE)
            .flatMap { chunk ->
                queryArticlesByLinks(
                    linkList = chunk,
                    feedId = feedId,
                    accountId = accountId,
                )
            }
    }

    /** RSS 入库去重只需链接；账户、来源及 IN 条件与完整文章查询一致。 */
    @Query(
        """
        SELECT link FROM article
        WHERE link in (:linkList)
        AND feedId = :feedId
        AND accountId = :accountId
        """
    )
    suspend fun queryExistingLinks(
        linkList: List<String>,
        feedId: String,
        accountId: Int,
    ): List<String>

    /** 沿用链接去重和 800 条分块，只合并已入库链接，不改变本批新文章的重复项。 */
    @Transaction
    suspend fun queryExistingLinksChunked(
        linkList: List<String>,
        feedId: String,
        accountId: Int,
    ): Set<String> {
        if (linkList.isEmpty()) return emptySet()
        return linkList
            .distinct()
            .chunked(ARTICLE_LINK_QUERY_CHUNK_SIZE)
            .flatMap { chunk ->
                queryExistingLinks(linkList = chunk, feedId = feedId, accountId = accountId)
            }
            .toSet()
    }

    @Query(
        """
        SELECT a.*
        FROM article AS a
        LEFT JOIN feed AS f ON a.feedId = f.id
        WHERE f.accountId = :accountId
        AND f.isFullContent = 1
        AND a.isUnread = 1
        """
    )
    suspend fun queryUnreadFullContentArticles(
        accountId: Int,
    ): List<Article>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE id = :id
        """
    )
    suspend fun queryById(id: String): ArticleWithFeed?
}
