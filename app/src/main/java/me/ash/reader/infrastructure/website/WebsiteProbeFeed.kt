package me.ash.reader.infrastructure.website

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndFeedImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndImageImpl
import java.net.URI
import java.util.Date
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import org.jsoup.Jsoup

/** 探测页面的原始来源身份与最终解析基址。 */
internal data class WebsiteProbePage(
    val sourceUrl: String,
    val documentBaseUrl: String,
    val html: String,
    val fetchedAt: Date,
    val allowLowConfidence: Boolean = false,
)

/** 网站候选转换为统一 SyndFeed，完整保留已有动态页面风险反馈。 */
internal fun WebsiteHelper.buildInspectableFeed(page: WebsiteProbePage): SyndFeed {
    val probeFeed = Feed(
        id = "website-probe:${page.sourceUrl.hashCode().toUInt().toString(16)}",
        name = "Website Probe", url = page.sourceUrl, groupId = "website-probe",
        accountId = 0, sourceType = SourceType.WEBSITE,
    )
    ensureAutomaticParsingAllowed(probeFeed, page.html)
    val document = Jsoup.parse(page.html, page.documentBaseUrl)
    val context = WebsiteCandidateContext(
        probeFeed, document, page.fetchedAt,
        forceFullScan = true, allowLowConfidence = page.allowLowConfidence,
    )
    val selection = inspectSelection(context)
    val iconUrl = document.selectFirst("link[rel~=(?i)^(shortcut )?icon$]")
        ?.absUrl("href")?.takeIf { it.isNotBlank() }
    return SyndFeedImpl().apply {
        feedType = "website"
        title = document.title().ifBlank { URI(page.sourceUrl).host ?: document.location() }
        link = page.sourceUrl
        description = document.selectFirst("meta[name=description]")?.attr("content") ?: ""
        entries = selection?.candidate?.articles.orEmpty().map { article ->
            SyndEntryImpl().apply {
                title = article.title
                link = article.link
                author = article.author
                publishedDate = article.date
                description = SyndContentImpl().apply { value = article.rawDescription }
            }
        }
        if (iconUrl != null) icon = SyndImageImpl().apply { url = iconUrl }
    }
}

/** 动态渲染成功但没有可靠列表时保留既有风险候选；其它失败原样上报。 */
private fun WebsiteHelper.inspectSelection(context: WebsiteCandidateContext): WebsiteCandidateSelection? =
    try {
        selectBestCandidate(context)
    } catch (error: IllegalStateException) {
        // 此分支是已有动态探测策略，不能掩盖固定规则或网络错误。
        if (!context.allowLowConfidence ||
            !error.message.orEmpty().startsWith("当前网站的解析规则均未通过健康检查：")
        ) throw error
        null
    }
