package me.ash.reader.domain.service

import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.infrastructure.preference.SyncIntervalPreference

/** 同步目录变化仅清理任务账户内的孤儿实体，收藏保留条件沿用原实现。 */
internal suspend fun AbstractRssRepository.removeOrphanedSubscriptions(
    accountId: Int, groups: List<Group>, feeds: List<Feed>,
) {
    val groupIds = groups.mapTo(hashSetOf()) { it.id }
    groupStore.queryAll(accountId).filter { it.id !in groupIds }.forEach { deleteStoredGroup(it, true) }
    val feedIds = feeds.mapTo(hashSetOf()) { it.id }
    feedStore.queryAll(accountId).filter { it.id !in feedIds }.forEach { deleteStoredFeed(it, true) }
}

/** 同步初始化保留原有启动与周期调度策略。 */
internal fun AbstractRssRepository.initializeSync(account: Account) {
        SyncWorker.migrateLegacyPeriodicWork(syncWorkManager)
        account.let {
            val syncOnStart = it.syncOnStart.value
            if (syncOnStart) {
                doSyncOneTime(it.id!!)
            }
            if (it.syncInterval.value != SyncIntervalPreference.Manually.value) {
                SyncWorker.enqueuePeriodicWork(account = it, workManager = syncWorkManager)
                WidgetUpdateWorker.enqueuePeriodicWork(
                    workManager = syncWorkManager,
                    syncInterval = it.syncInterval,
                    syncOnlyWhenCharging = it.syncOnlyWhenCharging,
                    syncOnlyOnWiFi = it.syncOnlyOnWiFi,
                )
            } else {
                SyncWorker.cancelPeriodicWork(syncWorkManager)
                WidgetUpdateWorker.cancelPeriodicWork(syncWorkManager)
            }
        }
    }


/** 同步移除孤儿分组只访问实体所属账户，保留含收藏来源。 */
internal suspend fun AbstractRssRepository.deleteStoredGroup(group: Group, onlyDeleteNoStarred: Boolean? = false) {
        val accountId = group.accountId
        if (
            onlyDeleteNoStarred == true &&
                articleStore.countByGroupIdWhenIsStarred(accountId, group.id, true) > 0
        ) {
            return
        }
        deleteArticles(group = group, includeStarred = true)
        feedStore.deleteByGroupId(accountId, group.id)
        groupStore.delete(group)
    }

/** 同步移除孤儿来源沿用收藏保留条件，避免切换账户影响判断。 */
internal suspend fun AbstractRssRepository.deleteStoredFeed(feed: Feed, onlyDeleteNoStarred: Boolean? = false) {
        if (
            onlyDeleteNoStarred == true &&
                articleStore.countByFeedIdWhenIsStarred(
                    feed.accountId,
                    feed.id,
                    true,
                ) > 0
        ) {
            return
        }
        deleteArticles(feed = feed, includeStarred = true)
        feedStore.delete(feed)
    }

/** 分组或来源删除使用实体账户，不能依赖当前界面账户。 */
internal suspend fun AbstractRssRepository.deleteStoredArticles(
        group: Group? = null,
        feed: Feed? = null,
        includeStarred: Boolean = false,
    ) {
        when {
            group != null ->
                articleStore.deleteByGroupId(
                    group.accountId,
                    group.id,
                    includeStarred,
                )

            feed != null ->
                articleStore.deleteByFeedId(
                    feed.accountId,
                    feed.id,
                    includeStarred,
                )
        }
    }
