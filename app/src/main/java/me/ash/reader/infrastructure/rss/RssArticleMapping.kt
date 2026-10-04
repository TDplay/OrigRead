package me.ash.reader.infrastructure.rss

import com.rometools.modules.mediarss.MediaEntryModule
import com.rometools.modules.mediarss.MediaModule
import com.rometools.modules.mediarss.types.UrlReference
import com.rometools.rome.feed.synd.SyndEntry
import java.net.URI
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.infrastructure.html.Readability
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.isFuture
import me.ash.reader.ui.ext.spacerDollar
import org.jsoup.parser.Parser

/** 一批 RSS 文章转换使用的来源身份与抓取时间。 */
internal data class RssArticleMappingContext(val feed: Feed, val accountId: Int, val preDate: Date)

/** RSS 描述中的 enclosure 标签用于识别图像。 */
val enclosureTagRegex = """<enclosure\b[^>]*>""".toRegex(RegexOption.IGNORE_CASE)
/** enclosure 的 URL 属性保留引号配对并解码实体。 */
val enclosureUrlRegex = """\burl\s*=\s*(["'])(.*?)\1""".toRegex(RegexOption.IGNORE_CASE)
/** MIME 类型避免把音视频 enclosure 当作封面。 */
val enclosureTypeRegex = """\btype\s*=\s*(["'])(.*?)\1""".toRegex(RegexOption.IGNORE_CASE)
/** HTML 图像提取跳过内联 data URL，避免将大块 Base64 写入数据库。 */
val imgRegex = """img.*?src=(["'])((?!data).*?)\1""".toRegex(RegexOption.DOT_MATCHES_ALL)

    /**
     * 大 Feed 的 Readability/DOM 转换是 CPU 工作，不应占用 IO dispatcher 串行跑数百次。
     * 小 Feed 维持单 worker；大 Feed 最多 4 个分片并发，并按分片原顺序 flatten。
     */
internal suspend fun RssHelper.convertSyndEntries(context: RssArticleMappingContext, entries: List<SyndEntry>): List<Article> =
        withContext(Dispatchers.Default) {
            val feed = context.feed
            val accountId = context.accountId
            val preDate = context.preDate
            if (entries.size < LARGE_FEED_PARALLEL_THRESHOLD) {
                return@withContext entries.map { entry ->
                    buildArticleFromSyndEntry(feed, accountId, entry, preDate)
                }
            }

            val workerCount =
                minOf(
                    MAX_RSS_CPU_WORKERS,
                    Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                    entries.size,
                )
            if (workerCount <= 1) {
                return@withContext entries.map { entry ->
                    buildArticleFromSyndEntry(feed, accountId, entry, preDate)
                }
            }
            val chunkSize = (entries.size + workerCount - 1) / workerCount
            coroutineScope {
                entries
                    .chunked(chunkSize)
                    .map { chunk ->
                        async {
                            chunk.map { entry ->
                                buildArticleFromSyndEntry(feed, accountId, entry, preDate)
                            }
                        }
                    }
                    .awaitAll()
                    .flatten()
            }
        }

/** 源内身份只用于本地主键，可打开链接和原有内容提取互不混用。 */
internal fun RssHelper.convertSyndEntry(context: RssArticleMappingContext, syndEntry: SyndEntry): Article {
        val feed = context.feed
        val accountId = context.accountId
        val preDate = context.preDate
        val desc = syndEntry.description?.value
        val content =
            syndEntry.contents
                .takeIf { it.isNotEmpty() }
                ?.let { it.joinToString("\n") { it.value } }
        return Article(
            // GUID/Atom ID 是源内身份，不能放入可打开 URL；缺省仍沿用本地随机 ID。
            id = accountId.spacerDollar(syndEntry.uri?.takeIf { it.isNotBlank() }?.let { sourceId ->
                UUID.nameUUIDFromBytes("${feed.id}\u0000$sourceId".toByteArray()).toString()
            } ?: UUID.randomUUID().toString()),
            accountId = accountId,
            feedId = feed.id,
            date =
                (syndEntry.publishedDate ?: syndEntry.updatedDate)?.takeIf { !it.isFuture(preDate) }
                    ?: preDate,
            title = syndEntry.title.decodeHTML() ?: feed.name,
            author = syndEntry.author,
            rawDescription = content ?: desc ?: "",
            shortDescription = Readability.parseToText(desc ?: content, syndEntry.link).take(280),
            img = findThumbnail(syndEntry) ?: findThumbnail(content ?: desc),
            link = syndEntry.link ?: "",
            updateAt = preDate,
        )
    }

/** enclosure 与 Media RSS 封面优先于正文图像。 */
internal fun RssHelper.thumbnailFromEntry(syndEntry: SyndEntry): String? {
        syndEntry.enclosures
            .orEmpty()
            .firstNotNullOfOrNull { enclosure ->
                enclosure.url?.trim()?.takeIf { url -> isImageEnclosure(enclosure.type, url) }
            }
            ?.let { return it }

        val mediaModule = syndEntry.getModule(MediaModule.URI) as? MediaEntryModule
        if (mediaModule != null) {
            return mediaThumbnail(mediaModule)
        }

        return null
    }

/** 媒体模块优先缩略图，缺失时读取明确的图像内容。 */
private fun mediaThumbnail(mediaModule: MediaEntryModule): String? {
        val candidates =
            buildList {
                    add(mediaModule.metadata)
                    addAll(mediaModule.mediaGroups.map { mediaGroup -> mediaGroup.metadata })
                    addAll(mediaModule.mediaContents.map { content -> content.metadata })
                }
                .flatMap { it.thumbnail.toList() }

        val thumbnail = candidates.firstOrNull()

        if (thumbnail != null) {
            return thumbnail.url.toString()
        } else {
            val imageMedia = mediaModule.mediaContents.firstOrNull { it.medium == "image" }
            if (imageMedia != null) {
                return (imageMedia.reference as? UrlReference)?.url.toString()
            }
        }
        return null
    }

/** 正文图像仅作封面候选，保留原有实体解码。 */
internal fun RssHelper.thumbnailFromText(text: String?): String? {
        text ?: return null
        enclosureTagRegex.findAll(text).forEach { match ->
            val tag = match.value
            val url = enclosureUrlRegex.find(tag)?.groupValues?.getOrNull(2)?.trim().orEmpty()
            val type = enclosureTypeRegex.find(tag)?.groupValues?.getOrNull(2)?.trim()
            if (url.isNotBlank() && isImageEnclosure(type, url)) {
                return Parser.unescapeEntities(url, false)
            }
        }
        // From https://gitlab.com/spacecowboy/Feeder
        // Using negative lookahead to skip data: urls, being inline base64
        // And capturing original quote to use as ending quote
        // Base64 encoded images can be quite large - and crash database cursors
        return imgRegex.find(text)
            ?.groupValues
            ?.get(2)
            // content:encoded 中的 HTML 属性常保留 `&amp;` 等实体。
            // 若直接把正则结果存进 Article.img，查询参数会变成 `amp;u`，图片代理因此拿不到真实 URL。
            ?.let { Parser.unescapeEntities(it, false) }
            ?.takeIf { !it.startsWith("data:") }
    }

/** MIME 缺失时沿用图片扩展名识别，有 MIME 时尊重媒体类型。 */
private fun isImageEnclosure(type: String?, url: String): Boolean {
        val normalizedType = type?.trim()?.lowercase().orEmpty()
        if (normalizedType.startsWith("image/")) return true
        if (normalizedType.isNotEmpty()) return false
        return runCatching {
            java.net.URI(url).path.orEmpty().lowercase()
                .matches(Regex(".*\\.(avif|bmp|gif|jpe?g|png|webp|svg)$"))
        }.getOrDefault(false)
    }


/** 大 Feed 沿用原有并行转换阈值。 */
private const val LARGE_FEED_PARALLEL_THRESHOLD = 80
/** 大 Feed 沿用原有 CPU worker 上限。 */
private const val MAX_RSS_CPU_WORKERS = 4
