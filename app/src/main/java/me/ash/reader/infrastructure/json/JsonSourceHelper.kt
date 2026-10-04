package me.ash.reader.infrastructure.json

import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.net.useCancellable
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

data class JsonSourceProbeResult(
    val rule: JsonRule,
    val endpointUrl: String,
    val feed: SyndFeed,
    val importedRule: Boolean = false,
)

/** 执行 JSON/API 规则的网络探测与后续同步。 */
@Singleton
class JsonSourceHelper @Inject constructor(
    private val ruleRepository: JsonRuleRepository,
    private val parser: JsonArticleParser,
    okHttpClient: OkHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val client = okHttpClient.newBuilder()
        .connectTimeout(JSON_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(JSON_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(JSON_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** 每条规则都验证真实响应，取消后不再尝试下一条规则。 */
    suspend fun probe(inputUrl: String): JsonSourceProbeResult? = withContext(ioDispatcher) {
        WordPressJsonRuleFactory.createFromEndpoint(inputUrl)?.let { directRule ->
            tryProbeRule(inputUrl, directRule)?.let {
                return@withContext it
            }
        }

        val importedRuleResult = ruleRepository.findRules(inputUrl).firstNotNullOfOrNull { rule ->
            tryProbeRule(inputUrl, rule)?.copy(importedRule = true)
        }
        if (importedRuleResult != null) return@withContext importedRuleResult

        // 标准 WordPress 不要求用户提前导入规则。兼容根目录和子目录安装，
        // 只有真实 REST 响应能够解析出文章时才判定候选成立。
        WordPressJsonRuleFactory.createCandidates(inputUrl).firstNotNullOfOrNull { rule ->
            tryProbeRule(inputUrl, rule)
        }
    }

    /** 来源绑定优先于旧订阅的唯一精确匹配，不能按域名猜测成功规则。 */
    suspend fun fetch(feed: Feed, preDate: Date = Date()): FeedWithArticle = withContext(ioDispatcher) {
        val rule = ruleRepository.subscriptionRule(feed.id, feed.url)
            ?: ruleRepository.findRuleForEndpoint(feed.url)
            ?: WordPressJsonRuleFactory.createFromEndpoint(feed.url)
            ?: error("未找到 ${feed.url} 对应的 JSON 来源规则")
        val articles = executeRule(JsonExecutionRequest(feed.url, rule, feed, preDate))
        FeedWithArticle(feed.copy(isNotification = feed.isNotification && articles.isNotEmpty()), articles)
    }

    /** 探测阶段和同步阶段共用同一执行入口，保证保存后不会换一种解析方式。 */
    private suspend fun probeRule(inputUrl: String, rule: JsonRule): JsonSourceProbeResult {
        val sourceUrl =
            if (rule.sourceKind == JsonSourceKind.API) {
                ruleRepository.resolveEndpoint(inputUrl, rule.endpoint)
            } else {
                // 内嵌 JSON 属于当前页面；endpoint="." 仅用于保持规则格式统一。
                inputUrl
            }
        val articles = executeRule(JsonExecutionRequest(sourceUrl, rule, temporaryFeed(rule.name, sourceUrl), Date()))
        return JsonSourceProbeResult(rule, sourceUrl, articles.toSyndFeed(rule.name, inputUrl))
    }

    /** 网络和解析共用同一请求上下文，保证预览和刷新字段转换一致。 */
    private suspend fun executeRule(request: JsonExecutionRequest): List<Article> =
        if (request.rule.sourceKind == JsonSourceKind.API) requestJson(request)
        else requestEmbedded(request)

    /** 真实 JSON 响应在协程可取消范围内读取并按固定规则解析。 */
    private suspend fun requestJson(request: JsonExecutionRequest): List<Article> =
        client.newCall(Request.Builder().url(request.sourceUrl).build()).useCancellable { response ->
            check(response.isSuccessful) { "JSON API 请求失败：HTTP ${response.code}" }
            parser.parse(response.body.string(), request.rule, request.feed, request.sourceUrl, request.fetchedAt)
        }

    /** 静态 HTML 仅读取指定框架的内嵌 JSON，响应体取消不留下一条规则继续运行。 */
    private suspend fun requestEmbedded(request: JsonExecutionRequest): List<Article> =
        client.newCall(Request.Builder().url(request.sourceUrl).build()).useCancellable { response ->
            check(response.isSuccessful) { "网页请求失败：HTTP ${response.code}" }
            val html = response.body.string()
            val content = if (request.rule.sourceKind == JsonSourceKind.NEXT_DATA) {
                EmbeddedJsonExtractor.extractNextData(html, request.sourceUrl)
            } else EmbeddedJsonExtractor.extractNuxtData(html, request.sourceUrl)
            require(!content.isNullOrBlank()) { "网页中未找到对应的内嵌 JSON 数据" }
            parser.parse(content, request.rule, request.feed, request.sourceUrl, request.fetchedAt)
        }

    /** 规则尝试只隔离普通解析失败；取消后不能继续下一条规则。 */
    private suspend fun tryProbeRule(inputUrl: String, rule: JsonRule): JsonSourceProbeResult? {
        currentCoroutineContext().ensureActive()
        return try {
            probeRule(inputUrl, rule)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 单条规则失败继续既有探测顺序，最后无候选时由订阅页报告探测失败。
            Timber.w(error, "JSON 规则 [%s] 探测失败", rule.name)
            null
        }
    }

    /** 保存探测规则与实际请求地址，供订阅后的刷新及完整配置备份使用。 */
    fun bindSubscription(feedId: String, sourcePageUrl: String, result: JsonSourceProbeResult) =
        ruleRepository.bindSubscription(feedId, sourcePageUrl, result)

    private fun temporaryFeed(name: String, endpoint: String) = Feed(
        id = "json-probe-${UUID.randomUUID()}", name = name, url = endpoint,
        groupId = "", accountId = 0, sourceType = SourceType.JSON,
    )

    private fun List<Article>.toSyndFeed(name: String, sourceUrl: String): SyndFeed =
        SyndFeedImpl().apply {
            feedType = "json"
            title = name
            link = sourceUrl
            entries = this@toSyndFeed.map { article ->
                SyndEntryImpl().apply {
                    title = article.title
                    link = article.link
                    author = article.author
                    publishedDate = article.date
                    description = SyndContentImpl().apply { value = article.rawDescription }
                }
            }
        }
}


/** 一次规则执行的来源、规则与抓取时间快照。 */
private data class JsonExecutionRequest(val sourceUrl: String, val rule: JsonRule, val feed: Feed, val fetchedAt: Date)

/** 沿用现有 JSON 探测超时，取消绑定独立于超时配置。 */
private const val JSON_CONNECT_TIMEOUT_SECONDS = 3L
private const val JSON_READ_TIMEOUT_SECONDS = 6L
private const val JSON_CALL_TIMEOUT_SECONDS = 8L
