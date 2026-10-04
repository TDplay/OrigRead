package me.ash.reader.domain.service

import java.util.Calendar
import java.util.Date
import androidx.work.ListenableWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.ui.ext.dollarLast
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.shortId
import timber.log.Timber

/** 远端时间戳采用秒。 */
private const val MILLIS_PER_SECOND = 1_000L

/** 整账户刷新保持原有并行读取与内容提交顺序，所有清理都固定任务账户。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun GoogleReaderRssService.syncGoogleReaderAccount(accountId: Int): ListenableWorker.Result =
    coroutineScope {
        try {
            val state = prepareGoogleReaderSync(accountId, this)
            reconcileGoogleReaderStars(state)
            reconcileGoogleReaderRead(state, this)
            val catalog = async { fetchGoogleReaderCatalog(state) }
            val toFetch = (listOf(state.remoteUnread, state.remoteStarred, state.remoteRead)
                .awaitAll().flatten() - state.localIds).toSet()
            val contents = fetchGoogleReaderContents(
                GoogleReaderContentRequest(
                    toFetch, state.api, accountId, state.remoteUnread.await(), state.remoteStarred.await(),
                ), this,
            )
            val subscriptions = catalog.await()
            refreshGoogleReaderIcons(subscriptions.values.flatten(), this)
            groupDao.insertOrUpdate(subscriptions.keys.toList())
            feedDao.insertOrUpdate(subscriptions.values.flatten())
            commitGoogleReaderContents(contents, accountId, this)
            removeOrphanedSubscriptions(accountId, subscriptions.keys.toList(), subscriptions.values.flatten())
            accountService.updateSyncTime(accountId, Date())
            ListenableWorker.Result.success()
        } catch (cancelled: CancellationException) {
            // 主动取消不得被改写为任务重试。
            throw cancelled
        } catch (error: Exception) {
            // 聚合协议失败沿用重试结果，Worker 不再对它执行成功后的清理。
            Timber.tag("RLog").e(error, "Google Reader 同步失败")
            syncLogger.log(error)
            ListenableWorker.Result.retry()
        }
    }

/** 凭据和本地元数据在任何远端状态修改前由固定账户读取。 */
private suspend fun GoogleReaderRssService.prepareGoogleReaderSync(
    accountId: Int, scope: CoroutineScope,
): GoogleReaderSyncState {
    val account = requireNotNull(accountService.getAccountById(accountId)) { "cannot find account" }
    require(account.type.id in setOf(AccountType.GoogleReader.id, AccountType.FreshRSS.id)) {
        "account type is invalid"
    }
    val api = getGoogleReaderAPI(account)
    api.refreshCredentialsIfNeeded()
    val preDate = Date()
    val since = Calendar.getInstance().apply { time = preDate; add(Calendar.MONTH, -1) }.time.time / MILLIS_PER_SECOND
    val unread = scope.async {
        fetchReaderItemIds { api.getUnreadItemIds(continuationId = it) }.map { it.shortId }.toSet()
    }
    val starred = scope.async {
        fetchReaderItemIds { api.getStarredItemIds(continuationId = it) }.map { it.shortId }.toSet()
    }
    val read = scope.async {
        fetchReaderItemIds {
            api.getReadItemIds(since = since, continuationId = it, useIt = account.type.id == AccountType.FreshRSS.id)
        }.map { it.shortId }.toSet()
    }
    val local = articleDao.queryMetadataAll(accountId)
    return GoogleReaderSyncState(
        account, api, preDate,
        local.map { it.id.dollarLast() }.toSet(),
        local.filter { it.isUnread }.map { it.id.dollarLast() }.toSet(),
        local.filter { it.isStarred }.map { it.id.dollarLast() }.toSet(),
        local.filter { !it.isUnread }.map { it.id.dollarLast() }.toSet(),
        unread, starred, read,
    )
}
