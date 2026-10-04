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
import me.ash.reader.infrastructure.net.useCancellable
import okhttp3.internal.commonIsSuccessful
import okio.IOException
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

    /** 请求并解析单个 Feed 地址，站点图标优先按原始网页地址查找。 */
internal suspend fun RssHelper.parseFeedUrlWithValidators(
        feedUrl: String,
        iconSourceUrl: String,
        client: OkHttpClient = okHttpClient,
    ): ParsedFeedResponse {
        return withFeedResponse(client, Request.Builder().url(feedUrl).build()) { response ->
            val feed =
                parseFeedResponse(response)
                    .also {
                        require(it.title?.isNotBlank() == true || it.entries.isNotEmpty()) {
                            "Feed 内容为空或格式无效"
                        }
                        val iconLink =
                            try {
                                queryRssIconLink(iconSourceUrl)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                null
                            }
                        if (!iconLink.isNullOrBlank()) {
                            it.icon = SyndImageImpl().apply {
                                link = iconLink
                                url = iconLink
                            }
                        }
                    }
            ParsedFeedResponse(
                feed = feed,
                etag = response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() },
                lastModified = response.header("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

/** 所有 Feed 请求复用同一取消管理，响应体读取结束后立即关闭响应。 */
internal suspend fun <T> RssHelper.withFeedResponse(
    client: OkHttpClient, request: Request, block: suspend (Response) -> T,
): T = withContext(ioDispatcher) {
    client.newCall(request).useCancellable(block)
}
