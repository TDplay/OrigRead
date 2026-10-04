package me.ash.reader.domain.service

import java.util.Date
import kotlinx.coroutines.Deferred
import me.ash.reader.domain.model.account.Account
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI

/** 一轮远端刷新使用的固定账户、本地快照与并行读取的远端状态。 */
internal data class GoogleReaderSyncState(
    val account: Account,
    val api: GoogleReaderAPI,
    val preDate: Date,
    val localIds: Set<String>,
    val localUnread: Set<String>,
    val localStarred: Set<String>,
    val localRead: Set<String>,
    val remoteUnread: Deferred<Set<String>>,
    val remoteStarred: Deferred<Set<String>>,
    val remoteRead: Deferred<Set<String>>,
) {
    val accountId: Int get() = requireNotNull(account.id)
}

/** 分页内容请求的来源身份及远端状态投影。 */
internal data class GoogleReaderContentRequest(
    val itemIds: Set<String>,
    val api: GoogleReaderAPI,
    val accountId: Int,
    val unreadIds: Set<String>,
    val starredIds: Set<String>,
)
