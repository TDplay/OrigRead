package me.ash.reader.domain.repository

import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.util.Date

/** 已读、收藏状态更新及统计查询。 */
interface ArticleStateQueries {

    @Query(
        """
        UPDATE article SET isStarred = :isStarred
        WHERE accountId = :accountId
        AND id in (:ids)
        """
    )
    fun markAsStarredByIdSet(
        accountId: Int,
        ids: Set<String>,
        isStarred: Boolean,
    ): Int

    @Query(
        """
        UPDATE article SET isUnread = :isUnread
        WHERE accountId = :accountId
        AND id in (:ids)
        """
    )
    fun markAsReadByIdSet(
        accountId: Int,
        ids: Set<String>,
        isUnread: Boolean,
    ): Int

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT count(1)
        FROM article
        WHERE feedId = :feedId
        AND isStarred = :isStarred
        AND accountId = :accountId
        """
    )
    fun countByFeedIdWhenIsStarred(
        accountId: Int,
        feedId: String,
        isStarred: Boolean,
    ): Int

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT count(1)
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.isStarred = :isStarred
        AND a.accountId = :accountId
        """
    )
    fun countByGroupIdWhenIsStarred(
        accountId: Int,
        groupId: String,
        isStarred: Boolean,
    ): Int

    @Transaction
    @Query(
        """
        UPDATE article SET isUnread = :isUnread
        WHERE accountId = :accountId
        AND date < :before
        AND isUnread != :isUnread
        """
    )
    suspend fun markAllAsRead(
        accountId: Int,
        isUnread: Boolean,
        before: Date,
    )

    @Transaction
    @Query(
        """
        UPDATE article SET isUnread = :isUnread
        WHERE feedId IN (
            SELECT id FROM feed
            WHERE groupId = :groupId
        )
        AND accountId = :accountId
        AND isUnread != :isUnread
        AND date < :before
        """
    )
    suspend fun markAllAsReadByGroupId(
        accountId: Int,
        groupId: String,
        isUnread: Boolean,
        before: Date,
    )

    @Transaction
    @Query(
        """
        UPDATE article SET isUnread = :isUnread
        WHERE feedId = :feedId
        AND accountId = :accountId
        AND isUnread != :isUnread
        AND date < :before
        """
    )
    suspend fun markAllAsReadByFeedId(
        accountId: Int,
        feedId: String,
        isUnread: Boolean,
        before: Date,
    )

    @Transaction
    @Query(
        """
        UPDATE article SET isUnread = :isUnread
        WHERE id = :articleId
        AND accountId = :accountId
        """
    )
    suspend fun markAsReadByArticleId(
        accountId: Int,
        articleId: String,
        isUnread: Boolean,
    )

    @Query(
        """
        UPDATE article SET isStarred = :isStarred
        WHERE id = :articleId
        AND accountId = :accountId
        """
    )
    suspend fun markAsStarredByArticleId(
        accountId: Int,
        articleId: String,
        isStarred: Boolean,
    )

    @Transaction
    @Query(
        """
        SELECT feedId, COUNT(*) AS important
        FROM article
        WHERE isUnread = :isUnread
        AND accountId = :accountId
        GROUP BY feedId
        """
    )
    fun queryImportantCountWhenIsUnread(
        accountId: Int,
        isUnread: Boolean,
    ): Flow<Map<@MapColumn("feedId") String, @MapColumn("important") Int>>

    @Transaction
    @Query(
        """
        SELECT feedId, COUNT(*) AS important
        FROM article
        WHERE isStarred = :isStarred
        AND accountId = :accountId
        GROUP BY feedId
        """
    )
    fun queryImportantCountWhenIsStarred(
        accountId: Int,
        isStarred: Boolean,
    ): Flow<Map<@MapColumn("feedId") String, @MapColumn("important") Int>>

    @Transaction
    @Query(
        """
        SELECT feedId, COUNT(*) AS important
        FROM article
        WHERE accountId = :accountId
        GROUP BY feedId
        """
    )
    fun queryImportantCountWhenIsAll(accountId: Int):
            Flow<Map<@MapColumn("feedId") String, @MapColumn("important") Int>>
}
