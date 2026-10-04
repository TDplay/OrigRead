package me.ash.reader.domain.service

import me.ash.reader.infrastructure.rss.ParsedOpmlDocument
import me.ash.reader.infrastructure.source.SourceUrlNormalizer

/** 导入在本地订阅锁内合并已有 URL，首次出现的分组保留；重复导入复用同名分组。 */
internal suspend fun LocalRssService.importOpml(accountId: Int, document: ParsedOpmlDocument): OpmlImportResult =
    withSubscriptionLock(accountId) {
        val existingGroups = groupDao.queryAll(accountId)
        val existingIds = existingGroups.mapTo(hashSetOf()) { it.id }
        val targets = linkedMapOf<String, me.ash.reader.domain.model.group.Group>()
        document.groups.forEach { groupWithFeed ->
            val group = groupWithFeed.group
            require(group.accountId == accountId) { "导入分组不属于目标账户：${group.id}" }
            // 默认分组先按身份复用；嵌套路径重名时复用本批第一次出现的目标分组。
            targets[group.id] = existingGroups.firstOrNull { it.id == group.id }
                ?: targets.values.firstOrNull { it.name == group.name }
                ?: existingGroups.firstOrNull { it.name == group.name }
                ?: group
        }
        val seen = feedDao.queryAll(accountId).mapTo(hashSetOf()) { SourceUrlNormalizer.comparisonKey(it.url) }
        val feeds = document.groups.flatMap { it.feeds }.filter {
            seen.add(SourceUrlNormalizer.comparisonKey(it.url))
        }.map { it.copy(groupId = requireNotNull(targets[it.groupId]).id) }
        val newGroups = targets.values.distinctBy { it.id }.filter { it.id !in existingIds }
        localSubscriptionDao.insertOpml(newGroups, feeds)
        OpmlImportResult(feeds.size, document.inputFeedCount - feeds.size)
    }
