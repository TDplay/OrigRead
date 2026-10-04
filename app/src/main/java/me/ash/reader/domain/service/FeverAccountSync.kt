package me.ash.reader.domain.service

import android.util.Log
import java.util.Date
import androidx.work.ListenableWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.infrastructure.rss.provider.fever.FeverAPI
import me.ash.reader.ui.ext.spacerDollar
import me.ash.reader.ui.ext.dollarLast

/** Fever 的目录、内容与状态同步使用同一账户 API。 */
internal suspend fun FeverRssService.syncFeverAccount(accountId: Int): ListenableWorker.Result = coroutineScope {
    try {
        val account = requireNotNull(accountService.getAccountById(accountId)) { "同步账户不存在" }
        require(account.type.id == AccountType.Fever.id) { "account type is invalid" }
        val api = getFeverAPI(account)
        val catalog = fetchFeverCatalog(api, accountId)
        groupDao.insertOrUpdate(catalog.groups)
        feedDao.insertOrUpdate(catalog.feeds)
        refreshFeverIcons(accountId)
        val fetchedAt = Date()
        val articles = fetchFeverArticles(
            FeverArticleRequest(api, accountId, account.lastArticleId?.dollarLast().orEmpty(), fetchedAt)
        )
        saveFeverArticles(accountId, articles.articles)
        syncFeverArticleStates(api, accountId)
        removeOrphanedSubscriptions(accountId, catalog.groups, catalog.feeds)
        accountService.update(account.copy(
            updateAt = Date(),
            lastArticleId = articles.lastSeenId.takeIf { it.isNotEmpty() }
                ?.let { accountId.spacerDollar(it) } ?: account.lastArticleId,
        ))
        ListenableWorker.Result.success()
    } catch (cancelled: CancellationException) {
        // 任务取消不转为普通同步失败。
        throw cancelled
    } catch (error: Exception) {
        // Fever 沿用失败结果，Worker 不执行成功后的清理。
        Log.e("RLog", "Fever 同步失败：${error.message}", error)
        ListenableWorker.Result.failure()
    }
}

/** 图标补全保留原有远端同步策略，查询范围固定为任务账户。 */
private suspend fun FeverRssService.refreshFeverIcons(accountId: Int) {
    val feeds = feedDao.queryNoIcon(accountId)
    feedDao.update(*feeds.map { it.copy(icon = rssHelper.queryRssIconLink(it.url)) }.toTypedArray())
}

/** 远端已读/收藏状态沿用现有 Fever 兼容语义，逐篇更新任务账户。 */
private suspend fun FeverRssService.syncFeverArticleStates(api: FeverAPI, accountId: Int) {
    val unread = api.getUnreadItems().unread_item_ids?.split(",")
    val starred = api.getSavedItems().saved_item_ids?.split(",")
    for (meta in articleDao.queryMetadataAll(accountId)) {
        val id = meta.id.dollarLast()
        val shouldBeUnread = unread?.contains(id)
        val shouldBeStarred = starred?.contains(id)
        if (meta.isUnread != shouldBeUnread) {
            articleDao.markAsReadByArticleId(accountId, meta.id, shouldBeUnread ?: true)
        }
        if (meta.isStarred != shouldBeStarred) {
            articleDao.markAsStarredByArticleId(accountId, meta.id, shouldBeStarred ?: false)
        }
    }
}
