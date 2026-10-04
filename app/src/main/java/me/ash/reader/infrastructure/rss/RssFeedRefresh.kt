package me.ash.reader.infrastructure.rss

import android.util.Log
import java.util.Date
import kotlinx.coroutines.CancellationException
import me.ash.reader.domain.model.feed.Feed
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 请求只携带已经成功提交的缓存，不在网络阶段写入数据库。 */
internal data class RssRefreshRequest(
    val feed: Feed,
    val latestLink: String?,
    val preDate: Date,
    val etag: String? = null,
    val lastModified: String? = null,
)

/** 网络失败、304 和解析成功分别返回，取消始终中断当前来源。 */
internal suspend fun RssHelper.queryRssXmlInternal(
    query: RssRefreshRequest, client: OkHttpClient = okHttpClient,
): RssQueryResult = try {
    val request = Request.Builder().url(query.feed.url).apply {
        query.etag?.takeIf(String::isNotBlank)?.let { header("If-None-Match", it) }
        query.lastModified?.takeIf(String::isNotBlank)?.let { header("If-Modified-Since", it) }
    }.build()
    withFeedResponse(client, request) { parseRefreshResponse(query, it) }
} catch (cancelled: CancellationException) {
    // 当前来源取消不能成为空 Feed 或普通失败。
    throw cancelled
} catch (error: Exception) {
    // 返回原始异常让上层按来源汇总，不能误报无更新。
    Log.e("RLog", "queryRssXml[${query.feed.name}]: ${error.message}", error)
    RssQueryResult(articles = emptyList(), successful = false, failure = error)
}

/** 空链接不能成为截止点，缺少可打开链接的条目仍按 GUID/Atom ID 转换。 */
private suspend fun RssHelper.parseRefreshResponse(query: RssRefreshRequest, response: Response): RssQueryResult {
    if (response.code == HTTP_NOT_MODIFIED) return RssQueryResult(
        articles = emptyList(), notModified = true, successful = true,
        etag = response.header("ETag") ?: query.etag,
        lastModified = response.header("Last-Modified") ?: query.lastModified,
    )
    val entries = parseFeedResponse(response, preserveWireFeed = true).entries.asSequence()
        .takeWhile { query.latestLink.isNullOrBlank() || query.latestLink != it.link }.toList()
    val articles = buildArticlesFromSyndEntries(
        feed = query.feed, accountId = query.feed.accountId, entries = entries, preDate = query.preDate,
    )
    return RssQueryResult(
        articles = articles,
        etag = response.header("ETag")?.trim()?.takeIf(String::isNotEmpty),
        lastModified = response.header("Last-Modified")?.trim()?.takeIf(String::isNotEmpty),
    )
}

/** HTTP 304 表示使用本机已经提交的表示。 */
private const val HTTP_NOT_MODIFIED = 304
