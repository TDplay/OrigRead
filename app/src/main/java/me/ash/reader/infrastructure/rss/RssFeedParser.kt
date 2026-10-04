package me.ash.reader.infrastructure.rss

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import java.io.InputStream
import okio.IOException
import okhttp3.Response

/** 沿用已有错误响应预览范围与 ROME 异常因果链深度。 */
private const val FEED_RESPONSE_PREVIEW_BYTES = 64 * 1024L
private const val FEED_EXCEPTION_CAUSE_DEPTH = 8

/** MIME 与预览只用于失败分类，不能替代真实 XML 解析。 */
private data class FeedResponseHints(
    val html: Boolean, val challenge: Boolean, val json: Boolean, val jsonPrefix: Boolean,
)

/** Feed 成功必须由 XML reader 确认；HTTP、验证页与坏内容保持不同错误原因。 */
internal fun parseFeedResponse(response: Response, preserveWireFeed: Boolean = false): SyndFeed {
    if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) {
        throw response.feedFailure(FeedFetchFailureReason.BLOCKED_RESPONSE, "Feed request blocked by an anti-bot challenge")
    }
    val hints = inspectFeedResponse(response)
    validateFeedResponse(response, hints)
    return try {
        response.body.byteStream().use { parseSyndFeed(it, response.header("Content-Type"), preserveWireFeed) }
    } catch (error: IOException) {
        // 断流和超时保持网络异常身份。
        throw error
    } catch (error: Exception) {
        // ROME/JDOM 包装的响应体 IOException 仍是连接故障，不能误报成坏 XML。
        generateSequence(error as Throwable) { it.cause }.take(FEED_EXCEPTION_CAUSE_DEPTH)
            .filterIsInstance<IOException>().firstOrNull()?.let { throw it }
        throw classifyFeedFailure(response, hints, error)
    }
}

/** 只检查有界前缀，兼容错误 MIME、UTF-16 与较长 XML 序言。 */
private fun inspectFeedResponse(response: Response): FeedResponseHints {
    val preview = try {
        response.peekBody(FEED_RESPONSE_PREVIEW_BYTES).string()
    } catch (error: IOException) {
        // 错误页读取失败不能抹掉已收到的 HTTP 状态；成功响应的读取错误原样抛出。
        if (response.isSuccessful) throw error else ""
    }
    val feedRoot = Regex(
        """(?is)^\uFEFF?\s*(?:(?:<\?.*?\?>|<!--.*?-->)\s*)*<(?:rss|(?:[\w-]+:)?feed|(?:[\w-]+:)?RDF)\b"""
    ).containsMatchIn(preview)
    val html = !feedRoot && (
        response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true) ||
            Regex("""(?i)<!doctype\s+html|<html\b|<head\b|<body\b""").containsMatchIn(preview)
        )
    val jsonPrefix = !feedRoot && (preview.trimStart().startsWith('{') || preview.trimStart().startsWith('['))
    val json = !feedRoot && (response.header("Content-Type").orEmpty().contains("json", ignoreCase = true) || jsonPrefix)
    return FeedResponseHints(html, html && isFeedChallengePage(preview), json, jsonPrefix)
}

/** Turnstile 也能嵌在正常页面，仅整页验证特征才归类为阻断。 */
private fun isFeedChallengePage(preview: String): Boolean {
    val title = Regex("""(?is)<title\b[^>]*>\s*(.*?)\s*</title>""").find(preview)?.groupValues?.get(1).orEmpty()
    return preview.contains("/cdn-cgi/challenge-platform/", ignoreCase = true) ||
        preview.contains("cf-chl-", ignoreCase = true) ||
        title.matches(Regex("""(?i)Just a moment(?:\.{3}|…)?""")) ||
        title.equals("Attention Required! | Cloudflare", ignoreCase = true) ||
        (preview.contains("challenges.cloudflare.com", ignoreCase = true) &&
            title.matches(Regex("""(?i)(?:Verify you are human|Security verification|Checking your browser)[.!…]*""")))
}

/** HTTP 失败在解析前暴露；只有明确 JSON 前缀才直接拒绝非 XML 输出。 */
private fun validateFeedResponse(response: Response, hints: FeedResponseHints) {
    if (hints.challenge && !response.isSuccessful) {
        throw response.feedFailure(FeedFetchFailureReason.BLOCKED_RESPONSE, "Feed request blocked by an anti-bot challenge")
    }
    if (!response.isSuccessful) {
        throw response.feedFailure(FeedFetchFailureReason.HTTP_ERROR, "Feed request failed with HTTP ${response.code}")
    }
    if (hints.jsonPrefix) {
        throw response.feedFailure(FeedFetchFailureReason.UNSUPPORTED_FORMAT, "Use RSS or Atom output instead of JSON")
    }
}

/** XML 解析失败后依据前缀分类，保持原有原因优先级。 */
private fun classifyFeedFailure(response: Response, hints: FeedResponseHints, error: Exception): FeedFetchException =
    when {
        hints.challenge -> response.feedFailure(
            FeedFetchFailureReason.BLOCKED_RESPONSE, "Feed request blocked by an anti-bot challenge", error,
        )
        hints.html -> response.feedFailure(
            FeedFetchFailureReason.HTML_RESPONSE, "Server returned a web page instead of a feed", error,
        )
        hints.json -> response.feedFailure(
            FeedFetchFailureReason.UNSUPPORTED_FORMAT, "Use RSS or Atom output instead of JSON", error,
        )
        else -> response.feedFailure(FeedFetchFailureReason.INVALID_CONTENT, "Feed content is invalid", error)
    }

/** HTTP 状态和原始异常随分类结果传递，方便来源日志定位。 */
private fun Response.feedFailure(reason: FeedFetchFailureReason, message: String, cause: Throwable? = null) =
    FeedFetchException(reason = reason, statusCode = code, message = message, cause = cause)

/** 明确 charset 时尊重响应头，否则交由 XmlReader 检查 BOM 和 XML 声明。 */
internal fun parseSyndFeed(inputStream: InputStream, contentType: String?, preserveWireFeed: Boolean = false): SyndFeed =
    SyndFeedInput().apply { isPreserveWireFeed = preserveWireFeed }.build(
        if (contentType?.contains("charset=", ignoreCase = true) == true) XmlReader(inputStream, contentType)
        else XmlReader(inputStream)
    )
