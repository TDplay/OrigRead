package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.model.feed.Feed

/** 按主键去重时保留 SQLite 绑定参数余量。 */
private const val ARTICLE_ID_QUERY_CHUNK_SIZE = 500

/** 文章 DAO 门面，内容写入和 HTTP validator 在同一事务提交。 */
@Dao
interface ArticleDao :
    ArticleSearchQueries,
    ArticlePagingQueries,
    ArticleMetadataQueries,
    ArticleStateQueries,
    ArticleMaintenanceQueries,
    ArticleLookupQueries {

    /** 译文有效性/孤儿清理只读取身份，所有账户及订阅仍须实际存在。调用方按 400 条分块。 */
    @Query("""
        SELECT a.accountId AS accountId, a.feedId AS feedId, a.id AS articleId
        FROM article a JOIN feed f ON f.id = a.feedId AND f.accountId = a.accountId
        JOIN account c ON c.id = a.accountId
        WHERE a.id IN (:articleIds)
    """)
    suspend fun queryTranslationOwners(articleIds: List<String>): List<me.ash.reader.infrastructure.translation.TranslationOwner>


    /** 来源类型兼容恢复只需要判断是否已有历史文章，避免为此加载整份文章列表。 */
    @Query(
        """
        SELECT COUNT(1) FROM article
        WHERE accountId = :accountId
        AND feedId = :feedId
        """
    )
    suspend fun countByFeedId(accountId: Int, feedId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg article: Article)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOnConflictIgnore(vararg article: Article)

    @Insert
    suspend fun insertList(articles: List<Article>)

    @Delete
    suspend fun delete(vararg article: Article)

    @Insert
    suspend fun insertArchives(archives: List<ArchivedArticle>)

    /** 归档身份与文章删除共同提交，归档失败不得丢失稳定身份。 */
    @Transaction
    suspend fun archiveAndDelete(articles: List<Article>) {
        if (articles.isEmpty()) return
        insertArchives(articles.map { ArchivedArticle(feedId = it.feedId, link = it.link, articleId = it.id) })
        delete(*articles.toTypedArray())
    }

    @Update
    suspend fun update(vararg article: Article)

    /** 内容刷新通过部分实体更新，避免覆盖并发产生的已读、收藏和稍后读状态。 */
    @Update(entity = Article::class)
    suspend fun updateContent(vararg content: me.ash.reader.domain.model.article.ArticleContentUpdate)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRssHttpCache(cache: me.ash.reader.infrastructure.rss.RssHttpCache)

    @Query("SELECT id FROM article WHERE id IN (:ids) AND feedId = :feedId AND accountId = :accountId")
    suspend fun queryExistingArticleIds(ids: List<String>, feedId: String, accountId: Int): List<String>

    /** 文章全部写入成功后才推进 HTTP validator，任一步失败均回滚。 */
    @Transaction
    suspend fun insertRssWithCache(
        articles: List<Article>,
        feed: Feed,
        cache: me.ash.reader.infrastructure.rss.RssHttpCache?,
    ): List<Article> {
        val inserted = insertListIfNotExist(articles, feed)
        cache?.let { upsertRssHttpCache(it) }
        return inserted
    }

    /** 在原事务内检查已入库链接并插入，保留新文章输入顺序及冲突时的回滚行为。 */
    @Transaction
    suspend fun insertListIfNotExist(articles: List<Article>, feed: Feed): List<Article> {
        if (articles.isEmpty()) return articles

        val existingLinks = queryExistingLinksChunked(
            linkList = articles.map { it.link }.filter { it.isNotBlank() },
            feedId = feed.id,
            accountId = feed.accountId
        )

        // 空链接不构成身份；稳定 GUID/Atom ID 已投影到来源范围的本地主键。
        val existingIds = articles.map { it.id }.chunked(ARTICLE_ID_QUERY_CHUNK_SIZE)
            .flatMap { queryExistingArticleIds(it, feed.id, feed.accountId) }.toSet()
        val seenLinklessIds = mutableSetOf<String>()
        val seenLinks = existingLinks.toMutableSet()
        return articles.filter { article ->
            // 既有同源 GUID 视作同一文章；批内不同链接的主键碰撞仍交给 ABORT 暴露。
            article.id !in existingIds &&
                if (article.link.isBlank()) seenLinklessIds.add(article.id) else seenLinks.add(article.link)
        }.also { if (it.isNotEmpty()) insertList(it) }
    }
}
