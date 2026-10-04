package me.ash.reader.infrastructure.rss

import android.content.Context
import android.util.Log
import com.rometools.modules.mediarss.MediaEntryModule
import com.rometools.modules.mediarss.MediaModule
import com.rometools.modules.mediarss.types.UrlReference
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndImageImpl
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.net.URI
import java.util.*
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.content.ContentExtractionService
import me.ash.reader.infrastructure.content.DynamicArticleContentService
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import me.ash.reader.infrastructure.content.FullContentException
import me.ash.reader.infrastructure.content.FullContentFailureClassifier
import me.ash.reader.infrastructure.content.FullContentFailureReason
import me.ash.reader.infrastructure.html.Readability
import me.ash.reader.infrastructure.net.HttpTextDecoder
import me.ash.reader.infrastructure.net.HttpTextKind
import me.ash.reader.infrastructure.rsshub.RssHubHttpClients
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.extractDomain
import me.ash.reader.ui.ext.isFuture
import me.ash.reader.ui.ext.spacerDollar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.executeAsync
import okhttp3.internal.commonIsSuccessful
import okio.IOException
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Some operations on RSS. */
class RssHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher internal val ioDispatcher: CoroutineDispatcher,
    internal val okHttpClient: OkHttpClient,
    internal val contentExtractionService: ContentExtractionService,
    internal val dynamicArticleContentService: DynamicArticleContentService,
    internal val articleWebSessionManager: ArticleWebSessionManager,
) {
    internal val rssHubHttpClients = RssHubHttpClients(okHttpClient)


    /** 探测沿用真实 XML 验证与网页 alternate 发现。 */
    @Throws(Exception::class)
    suspend fun searchFeed(feedLink: String): SyndFeed = discoverFeed(feedLink).feed

    /** Feed 图标不参与订阅发现有效性判断。 */
    @Throws(Exception::class)
    suspend fun discoverFeed(inputUrl: String): DiscoveredFeed = discoverSourceFeed(inputUrl)

    /** 直接解析指定 Feed，不执行网页发现；可传入自定义图标来源与短超时客户端。 */
    suspend fun parseFeedDirect(
        feedUrl: String,
        iconSourceUrl: String,
        client: OkHttpClient = okHttpClient,
    ): SyndFeed = withContext(ioDispatcher) {
        parseFeedUrlWithValidators(feedUrl, iconSourceUrl, client).feed
    }

    /** 直接解析指定 Feed，默认以自身地址为图标来源并使用默认网络客户端。 */
    suspend fun parseFeedDirect(feedUrl: String): SyndFeed =
        parseFeedDirect(feedUrl, feedUrl, okHttpClient)


    /** 全文提取沿用静态内容与动态页面既定策略。 */
    @Throws(Exception::class)
    suspend fun parseFullContent(link: String, title: String, allowDynamicFallback: Boolean = true): String =
        extractArticleContent(link, title, allowDynamicFallback)

    suspend fun queryRssXml(
        feed: Feed,
        latestLink: String?,
        preDate: Date = Date(),
    ): List<Article> {
        val result = queryRssXmlInternal(RssRefreshRequest(feed, latestLink, preDate))
        result.failure?.let { throw it }
        return result.articles
    }

    /**
     * 后续刷新专用：携带已保存的 HTTP validator，并把 304 暴露给上层短路整条同步链。
     * validator 的持久化由文章入库事务负责，RssHelper 只处理 HTTP/XML/CPU。
     */
    suspend fun queryRssXmlConditional(
        feed: Feed,
        latestLink: String?,
        preDate: Date = Date(),
        etag: String? = null,
        lastModified: String? = null,
    ): RssQueryResult =
        queryRssXmlInternal(RssRefreshRequest(feed, latestLink, preDate, etag, lastModified))

    /** RSSHub 刷新沿用条件请求及文章转换，只替换网络策略。普通 RSS 保留原客户端。 */
    suspend fun queryRssHubXmlConditional(
        feed: Feed,
        latestLink: String?,
        preDate: Date = Date(),
        etag: String? = null,
        lastModified: String? = null,
    ): RssQueryResult = queryRssXmlInternal(
        RssRefreshRequest(feed, latestLink, preDate, etag, lastModified),
        client = rssHubHttpClients.forUrl(feed.url),
    )


    /** 按原顺序转换 RSS/Atom，源内身份不与可打开 URL 混用。 */
    suspend fun buildArticlesFromSyndEntries(feed: Feed, accountId: Int, entries: List<SyndEntry>, preDate: Date = Date()): List<Article> =
        convertSyndEntries(RssArticleMappingContext(feed, accountId, preDate), entries)

    /** 转换单条文章，保留已有阅读内容与摘要提取语义。 */
    fun buildArticleFromSyndEntry(feed: Feed, accountId: Int, syndEntry: SyndEntry, preDate: Date = Date()): Article =
        convertSyndEntry(RssArticleMappingContext(feed, accountId, preDate), syndEntry)

    /** RSS enclosure 和媒体模块优先用于缩略图。 */
    fun findThumbnail(syndEntry: SyndEntry): String? = thumbnailFromEntry(syndEntry)

    /** HTML 图像地址解码实体后用于缩略图。 */
    fun findThumbnail(text: String?): String? = thumbnailFromText(text)

    suspend fun queryRssIconLink(feedLink: String?): String? {
        if (feedLink.isNullOrEmpty()) return null
        return withContext(ioDispatcher) {
            val iconFinder = BestIconFinder(okHttpClient)
            val domain = feedLink.extractDomain()
            iconFinder.findBestIcon(domain ?: feedLink).also {
                Log.i("RLog", "queryRssIconByLink: get $it from $domain")
            }
        }
    }

    suspend fun saveRssIcon(feedDao: FeedDao, feed: Feed, iconLink: String) {
        feedDao.update(feed.copy(icon = iconLink))
    }

    /** alternate 发现使用浏览器风格 UA，后台发现不携带 WebView Cookie。 */
    internal fun websitePageRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", articleWebSessionManager.desktopHttpUserAgent)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .header("Upgrade-Insecure-Requests", "1")
        .build()

    /**
     * 正文网页使用浏览器风格请求头，并复用用户在内置 WebView 中已经取得的站点 Cookie。
     * Feed/XML 请求仍走普通 response()，避免把网页登录状态扩散到后台订阅同步链。
     */
    internal fun articleRequest(url: String): Request {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", articleWebSessionManager.httpUserAgent)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                )
                .header("Upgrade-Insecure-Requests", "1")
                .apply {
                    articleWebSessionManager.cookieHeader(url)?.let { cookie ->
                        header("Cookie", cookie)
                    }
                }
                .build()
        return request
    }

}
