package me.ash.reader.infrastructure.rss

import com.rometools.rome.feed.synd.SyndFeed
import me.ash.reader.domain.model.article.Article
import java.io.IOException

/** RSS 探测结果，feedUrl 为最终应保存和同步的真实 Feed 地址。 */
data class DiscoveredFeed(
    val feedUrl: String,
    val feed: SyndFeed,
    val discoveredFromPage: Boolean,
    val etag: String? = null,
    val lastModified: String? = null,
)

internal data class ParsedFeedResponse(
    val feed: SyndFeed,
    val etag: String?,
    val lastModified: String?,
)

/** RSS 刷新结果。304 必须与“成功解析但暂时没有文章”严格区分。 */
data class RssQueryResult(
    val articles: List<Article>,
    val notModified: Boolean = false,
    val successful: Boolean = true,
    val etag: String? = null,
    val lastModified: String? = null,
    val failure: Exception? = null,
)

enum class FeedFetchFailureReason {
    /** 网站整页验证阻止了 Feed 读取。 */
    BLOCKED_RESPONSE,
    /** 服务端返回非成功 HTTP 状态。 */
    HTTP_ERROR,
    /** 请求返回了网页而非 Feed。 */
    HTML_RESPONSE,
    /** Feed XML 或语义无法解析。 */
    INVALID_CONTENT,
    /** 请求返回不受当前 Feed 解析器支持的格式。 */
    UNSUPPORTED_FORMAT,
}

class FeedFetchException(
    val reason: FeedFetchFailureReason,
    val statusCode: Int? = null,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
