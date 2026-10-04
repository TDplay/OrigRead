package me.ash.reader.domain.service

import me.ash.reader.infrastructure.json.JsonRebindRequest

/** 复用现有账户订阅锁，原地修复与新增来源串行，不改文章和阅读状态。 */
internal suspend fun LocalRssService.rebindJsonSource(request: JsonRebindRequest) =
    withSubscriptionLock(request.feed.accountId) { localSourceService.rebindJsonSubscription(request) }
