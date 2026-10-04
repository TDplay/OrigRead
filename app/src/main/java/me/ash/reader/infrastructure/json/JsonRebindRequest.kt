package me.ash.reader.infrastructure.json

import me.ash.reader.domain.model.feed.Feed

/** 用户确认的原来源、探测页面与成功候选；始终保留原 feedId。 */
data class JsonRebindRequest(val feed: Feed, val sourcePageUrl: String, val probe: JsonSourceProbeResult)
