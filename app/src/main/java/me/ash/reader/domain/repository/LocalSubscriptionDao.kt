package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Transaction
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed

/** 本地首次订阅在同一事务中验证分组归属并保存来源与探测文章。 */
@Dao
interface LocalSubscriptionDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertFeed(feed: Feed)

    @Insert
    suspend fun insertArticles(articles: List<Article>)

    @androidx.room.Query("SELECT accountId FROM `group` WHERE id = :groupId")
    suspend fun queryGroupAccountId(groupId: String): Int?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertCache(cache: me.ash.reader.infrastructure.rss.RssHttpCache)

    @Insert
    suspend fun insertGroups(groups: List<me.ash.reader.domain.model.group.Group>)

    /** 批量导入的分组与来源共同提交，失败不留下半批次数据。 */
    @Transaction
    suspend fun insertOpml(groups: List<me.ash.reader.domain.model.group.Group>, feeds: List<Feed>) {
        if (groups.isNotEmpty()) insertGroups(groups)
        require(feeds.all { queryGroupAccountId(it.groupId) == it.accountId }) { "导入分组不属于目标账户" }
        feeds.forEach { insertFeed(it) }
    }

    /** 首次探测文章和 validator 同时提交，避免首次订阅也出现条件请求提前推进。 */
    @Transaction
    suspend fun insertFeedArticlesAndCache(feed: Feed, articles: List<Article>, cache: me.ash.reader.infrastructure.rss.RssHttpCache) {
        insertFeedWithArticles(feed, articles)
        insertCache(cache)
    }

    @Transaction
    suspend fun insertFeedWithArticles(feed: Feed, articles: List<Article>) {
        require(queryGroupAccountId(feed.groupId) == feed.accountId) { "订阅分组不属于目标账户" }
        insertFeed(feed)
        // 重复链接和无链接稳定身份可以去重；其它主键冲突仍让事务明确失败。
        val distinctArticles = articles.distinctBy { if (it.link.isBlank()) it.id else it.link }
        if (distinctArticles.isNotEmpty()) insertArticles(distinctArticles)
    }
}
