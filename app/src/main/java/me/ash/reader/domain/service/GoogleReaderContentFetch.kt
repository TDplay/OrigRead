package me.ash.reader.domain.service

import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ash.reader.R
import me.ash.reader.domain.model.article.Article
import me.ash.reader.infrastructure.html.Readability
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.isValidItemId
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.ofFeedStreamIdToId
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.shortId
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderDTO
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.isFuture
import me.ash.reader.ui.ext.spacerDollar

/** 沿用远端每批文章数与并行请求数。 */
private const val READER_CONTENT_BATCH_SIZE = 100
private const val READER_CONTENT_CONCURRENCY = 8
/** 摘要显示长度与远端秒时间戳转换沿用现有约定。 */
private const val ARTICLE_PREVIEW_LENGTH = 280
private const val MILLIS_PER_SECOND = 1_000L

/** 按固定账户分块请求文章内容，调用方的协程范围负责全部请求生命周期。 */
internal fun GoogleReaderRssService.fetchGoogleReaderContents(
    request: GoogleReaderContentRequest, scope: CoroutineScope,
): List<Deferred<List<Article>>> {
    if (request.itemIds.isEmpty()) return emptyList()
    val fetchedAt = Date()
    val semaphore = Semaphore(READER_CONTENT_CONCURRENCY)
    return request.itemIds.chunked(READER_CONTENT_BATCH_SIZE).map { ids ->
        scope.async(ioDispatcher) {
            semaphore.withPermit {
                val result = request.api.getItemsContents(ids)
                result.items.orEmpty().filter { it.id?.isValidItemId() == true }.map {
                    mapGoogleReaderArticle(it, request, GoogleReaderArticleTime(fetchedAt, result.updated))
                }
            }
        }
    }
}

private data class GoogleReaderArticleTime(val fetchedAt: Date, val remoteUpdated: Long?)

/** 上游文章身份只投影到任务账户，元数据转换保持既有字段语义。 */
private fun GoogleReaderRssService.mapGoogleReaderArticle(
    item: GoogleReaderDTO.Item, request: GoogleReaderContentRequest, time: GoogleReaderArticleTime,
): Article {
    val id = requireNotNull(item.id).shortId
    val url = item.canonical?.firstOrNull()?.href ?: item.alternate?.firstOrNull()?.href ?: item.origin?.htmlUrl ?: ""
    return Article(
        id = request.accountId.spacerDollar(id),
        date = item.published?.let { Date(it * MILLIS_PER_SECOND) }
            ?.takeIf { !it.isFuture(time.fetchedAt) } ?: time.fetchedAt,
        title = item.title.decodeHTML() ?: context.getString(R.string.empty),
        author = item.author, rawDescription = item.summary?.content ?: "",
        shortDescription = Readability.parseToText(item.summary?.content, url).take(ARTICLE_PREVIEW_LENGTH),
        img = rssHelper.findThumbnail(item.summary?.content), link = url,
        feedId = request.accountId.spacerDollar(requireNotNull(item.origin?.streamId?.ofFeedStreamIdToId())),
        accountId = request.accountId, isUnread = id in request.unreadIds, isStarred = id in request.starredIds,
        updateAt = time.remoteUpdated?.let { Date(it * MILLIS_PER_SECOND) }
            ?: item.crawlTimeMsec?.let { Date(it.toLong()) } ?: time.fetchedAt,
    )
}

/** 沿用远端 continuation 分页读取，不改变本轮要求之外的远端兼容策略。 */
internal suspend fun fetchReaderItemIds(
    fetch: suspend (String?) -> GoogleReaderDTO.ItemIds?,
): List<String> {
    var result = fetch(null)
    val ids = result?.itemRefs?.mapNotNull { it.id }?.toMutableList() ?: return emptyList()
    while (result?.continuation != null) {
        result = fetch(result.continuation)
        result?.itemRefs?.mapNotNull { it.id }?.let(ids::addAll)
    }
    return ids
}
