package me.ash.reader.domain.service

import com.rometools.rome.feed.synd.SyndFeed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.json.JsonSourceProbeResult
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionDescriptor

/** 提交时携带探测账户、分组和来源配置，业务层不再依赖提交过程中变化的当前账户。 */
data class SubscriptionRequest(
    val accountId: Int,
    val groupId: String,
    val feedLink: String,
    val searchedFeed: SyndFeed,
    val sourceType: SourceType = SourceType.RSS,
    val notification: Boolean = false,
    val fullContent: Boolean = false,
    val browser: Boolean = false,
    val dynamicRendering: Boolean = false,
    val sourcePageUrl: String = feedLink,
    val etag: String? = null,
    val lastModified: String? = null,
    val jsonProbe: JsonSourceProbeResult? = null,
    val rssHub: RssHubSubscriptionDescriptor? = null,
)

/** 逐项订阅结果：新建和已存在必须分别统计。 */
data class SubscriptionResult(val feedId: String, val created: Boolean)
