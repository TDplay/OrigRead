package me.ash.reader.domain.repository

import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.article.ArticleMeta
import me.ash.reader.domain.model.article.ArticleWithFeed
import java.util.Date

/** 文章状态与最近未读元数据查询。 */
interface ArticleMetadataQueries {

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataAll(
        accountId: Int, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND isUnread = :isUnread
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataAll(
        accountId: Int, isUnread: Boolean, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND date < :before
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataAll(
        accountId: Int, before: Date, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND isUnread = :isUnread
        AND date < :before
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataAll(
        accountId: Int, isUnread: Boolean, before: Date, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND feedId = :feedId
        AND isUnread = :isUnread
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataByFeedId(
        accountId: Int, feedId: String, isUnread: Boolean, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND feedId = :feedId
        AND isUnread = :isUnread
        AND date < :before
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataByFeedId(
        accountId: Int,
        feedId: String,
        isUnread: Boolean,
        before: Date,
        sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT a.id, a.isUnread, a.isStarred
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.accountId = :accountId
        AND a.isUnread = :isUnread
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN a.date END ASC,
            CASE WHEN :sortAscending = 0 THEN a.date END DESC
        """
    )
    fun queryMetadataByGroupIdWhenIsUnread(
        accountId: Int, groupId: String, isUnread: Boolean, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT a.id, a.isUnread, a.isStarred
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.accountId = :accountId
        AND a.isUnread = :isUnread
        AND a.date < :before
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN a.date END ASC,
            CASE WHEN :sortAscending = 0 THEN a.date END DESC
        """
    )
    fun queryMetadataByGroupIdWhenIsUnread(
        accountId: Int,
        groupId: String,
        isUnread: Boolean,
        before: Date,
        sortAscending: Boolean = false
    ): List<ArticleMeta>

    /**
     * query the latest unread articles from account with id, limit count
     */
    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        AND isUnread = 1
        ORDER BY date desc
        LIMIT :limit
        """
    )
    fun queryLatestUnreadArticles(accountId: Int, limit: Int = 15): Flow<List<ArticleWithFeed>>

    /**
     * query the latest unread articles from feed with id, limit count
     */
    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE feedId = :feedId
        AND isUnread = 1
        ORDER BY date desc
        LIMIT :limit
        """
    )
    fun queryLatestUnreadArticlesFromFeed(feedId: String, limit: Int = 15): Flow<List<ArticleWithFeed>>

    /**
     * query the latest unread articles from group with id, limit count
     */
    @Transaction
    @Query(
        """
        SELECT a.* FROM article AS a
        LEFT JOIN feed AS f ON a.feedId = f.id
        WHERE f.groupId = :groupId
        AND a.isUnread = 1
        ORDER BY a.date DESC
        LIMIT :limit
        """
    )
    fun queryLatestUnreadArticlesFromGroup(groupId: String, limit: Int = 15): Flow<List<ArticleWithFeed>>

    /**
     * query the latest unread articles from account with id, limit count
     */
    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        AND isUnread = 1
        ORDER BY date desc
        LIMIT :limit
        """
    )
    fun queryLatestUnreadArticleFlow(accountId: Int, limit: Int): Flow<List<ArticleWithFeed>>
}
