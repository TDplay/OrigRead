package me.ash.reader.domain.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.spacerDollar

/** 兼容最低 Android 版本使用的 SQLite 参数预算。 */
private const val READER_STATUS_SQLITE_PARAMETERS = 999
/** 状态 UPDATE 除文章 ID 外还绑定账户与目标状态。 */
private const val READER_STATUS_FIXED_PARAMETERS = 2
/** 所有差集完整分批写入，为固定参数保留空间。 */
private const val READER_STATUS_QUERY_CHUNK = READER_STATUS_SQLITE_PARAMETERS - READER_STATUS_FIXED_PARAMETERS

/** 收藏差集固定账户并分批写入；子任务失败必须抛给同步调用方。 */
internal suspend fun GoogleReaderRssService.reconcileGoogleReaderStars(state: GoogleReaderSyncState): Unit = coroutineScope {
    launch {
        val ids = (state.localIds - state.localStarred).intersect(state.remoteStarred.await())
            .map { state.accountId spacerDollar it }.toSet()
        ids.chunked(READER_STATUS_QUERY_CHUNK).forEach {
            articleDao.markAsStarredByIdSet(state.accountId, it.toSet(), true)
        }
    }
    launch {
        val ids = (state.localStarred - state.remoteStarred.await())
            .map { state.accountId spacerDollar it }.toSet()
        ids.chunked(READER_STATUS_QUERY_CHUNK).forEach {
            articleDao.markAsStarredByIdSet(state.accountId, it.toSet(), false)
        }
    }
}

/** 远端已读与未读差集保持原有语义，更新不读取当前界面账户。 */
internal fun GoogleReaderRssService.reconcileGoogleReaderRead(
    state: GoogleReaderSyncState, scope: CoroutineScope,
) {
    scope.launch {
        val ids = state.remoteRead.await().intersect(state.localUnread)
            .map { state.accountId spacerDollar it }
        ids.chunked(READER_STATUS_QUERY_CHUNK).forEach {
            articleDao.markAsReadByIdSet(state.accountId, it.toSet(), false)
        }
    }
    scope.launch {
        val ids = state.localRead.intersect(state.remoteUnread.await()).map { state.accountId spacerDollar it }
        ids.chunked(READER_STATUS_QUERY_CHUNK).forEach {
            articleDao.markAsReadByIdSet(state.accountId, it.toSet(), true)
        }
    }
}
