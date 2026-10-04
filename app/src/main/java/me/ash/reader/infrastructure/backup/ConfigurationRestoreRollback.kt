package me.ash.reader.infrastructure.backup

import java.util.UUID
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group

/** 创建恢复前的配置 + Room 精确快照；密码和 JSON 仅驻留当前调用栈内存。 */
internal suspend fun ConfigurationBackupService.createRestoreRollbackSnapshot(): ConfigurationRestoreRollbackSnapshot {
    val accountId = accountService.getCurrentAccountId()
    val password = UUID.randomUUID().toString()
    return ConfigurationRestoreRollbackSnapshot(
        accountId = accountId,
        groups = groupDao.queryAll(accountId),
        feeds = feedDao.queryAll(accountId),
        configurationBackupJson = exportBackup(includeSecrets = true, password = password),
        jsonRulesSnapshot = jsonRuleRepository.exportBackup(),
        password = password,
    )
}

/**
 * 配置回滚后再精确恢复 Room 主键集合和实体内容。
 * 禁止使用 REPLACE 写 Group：SQLite REPLACE 可能触发外键级联删除，因此存在项使用 UPDATE，缺失项才 INSERT。
 */
internal suspend fun ConfigurationBackupService.restoreRoomRollbackSnapshot(snapshot: ConfigurationRestoreRollbackSnapshot) {
    val currentAccountId = accountService.getCurrentAccountId()
    check(currentAccountId == snapshot.accountId) { "配置恢复期间当前账户发生变化，无法安全回滚" }

    val currentGroups = groupDao.queryAll(snapshot.accountId)
    val currentGroupIds = currentGroups.mapTo(hashSetOf(), Group::id)
    val (missingGroups, existingGroups) = snapshot.groups.partition { it.id !in currentGroupIds }
    if (missingGroups.isNotEmpty()) groupDao.insertAll(missingGroups)
    if (existingGroups.isNotEmpty()) groupDao.updateAll(existingGroups)

    val currentFeeds = feedDao.queryAll(snapshot.accountId)
    val currentFeedIds = currentFeeds.mapTo(hashSetOf(), Feed::id)
    val (missingFeeds, existingFeeds) = snapshot.feeds.partition { it.id !in currentFeedIds }
    if (missingFeeds.isNotEmpty()) feedDao.insertAll(missingFeeds)
    if (existingFeeds.isNotEmpty()) feedDao.updateAll(existingFeeds)

    val snapshotFeedIds = snapshot.feeds.mapTo(hashSetOf(), Feed::id)
    val extraFeeds = feedDao.queryAll(snapshot.accountId).filter { it.id !in snapshotFeedIds }
    if (extraFeeds.isNotEmpty()) feedDao.delete(*extraFeeds.toTypedArray())

    val snapshotGroupIds = snapshot.groups.mapTo(hashSetOf(), Group::id)
    val extraGroups = groupDao.queryAll(snapshot.accountId).filter { it.id !in snapshotGroupIds }
    if (extraGroups.isNotEmpty()) groupDao.delete(*extraGroups.toTypedArray())
}
