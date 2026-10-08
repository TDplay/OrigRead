package me.ash.reader.infrastructure.source

import java.net.URI
import java.util.Locale

import me.ash.reader.infrastructure.rsshub.RssHubInputParser

/**
 * 用户输入 URL 的非排他性探测倾向。
 * Hint 只调整 RSS / JSON 的探测顺序，最终来源类型必须由真实解析结果确认。
 */
enum class SourceInputHint {
    RSS_LIKELY,
    JSON_LIKELY,
    GENERIC,
}

/** URL 是否更像 RSS / Atom。仅用于探测排序，不表示已确认来源类型。 */
fun isRssLikelyUrl(url: String): Boolean = rssHintScore(url) > 0

/** URL 是否更像 JSON / REST API。仅用于探测排序，不表示已确认来源类型。 */
fun isJsonLikelyUrl(url: String): Boolean = jsonHintScore(url) > 0

/** 已知 Feed 托管入口，不是任意带 .xml 后缀的网页；失败不能冒充动态网站。 */
internal fun isFeedburnerFeedUrl(url: String): Boolean = runCatching {
    val uri = URI(url.trim())
    val host = uri.host.orEmpty().lowercase(Locale.ROOT)
    val path = uri.rawPath.orEmpty().trim('/')
    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
        host in setOf("feeds.feedburner.com", "feeds2.feedburner.com") &&
        path.isNotBlank() && !path.startsWith("~")
}.getOrDefault(false)


private fun rssHintScore(url: String): Int =
    runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase().trimEnd('/')
        val query = uri.query.orEmpty().lowercase()

        var score = 0
        if (isFeedburnerFeedUrl(url) || host == "feedproxy.google.com") score += 5
        if (
            path.endsWith(".xml") ||
                path.endsWith(".rss") ||
                path.endsWith(".atom") ||
                path.endsWith(".rdf")
        ) {
            score += 4
        }
        if (path.contains("/feeds/posts/default")) score += 4
        if (
            query.contains("format=rss") ||
                query.contains("format=atom") ||
                query.contains("format=xml") ||
                query.contains("output=rss") ||
                query.contains("output=atom") ||
                query.contains("type=rss") ||
                query.contains("type=atom")
        ) {
            score += 4
        }
        if (
            path.endsWith("/feed") ||
                path == "/feed" ||
                path.endsWith("/rss") ||
                path == "/rss" ||
                path.endsWith("/atom") ||
                path == "/atom"
        ) {
            score += 1
        }
        score
    }.getOrDefault(0)

private fun jsonHintScore(url: String): Int =
    runCatching {
        val uri = URI(url)
        val path = uri.path.orEmpty().lowercase()
        val query = uri.query.orEmpty().lowercase()

        var score = 0
        if (path.contains("/wp-json/")) score += 5
        if (path.endsWith(".json")) score += 4
        if (query.contains("format=json") || query.contains("output=json")) score += 4
        if (path.startsWith("/api/") || path.contains("/api/")) score += 2
        score
    }.getOrDefault(0)

/**
 * 识别用户输入是否直接位于已知 RSSHub 实例 route 下。
 * 已知 RSSHub route 与 Feed 托管入口有专门的失败策略；普通 URL 后缀仍只是提示。
 */
fun isKnownRssHubEndpoint(url: String, knownInstances: List<String> = emptyList()): Boolean =
    RssHubInputParser.parseExplicit(url, knownInstances) != null

/**
 * 提取非排他性 Hint。
 * 普通地址冲突时只调整尝试顺序；已知托管 Feed 的失败策略由发现入口单独处理。
 */
fun sourceInputHint(url: String): SourceInputHint {
    val rssScore = rssHintScore(url)
    val jsonScore = jsonHintScore(url)
    return when {
        rssScore > jsonScore -> SourceInputHint.RSS_LIKELY
        jsonScore > rssScore -> SourceInputHint.JSON_LIKELY
        else -> SourceInputHint.GENERIC
    }
}
