package me.ash.reader.infrastructure.json

import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** 同一来源的旧绑定恢复和确认重绑串行，不限制不同来源的网络并发。 */
    private val subscriptionLocks = ConcurrentHashMap<String, Mutex>()

    /** 仅协调同一 feedId 的配置和网络快照，避免旧刷新覆盖刚确认的新绑定。 */
    private fun subscriptionLock(feedId: String): Mutex = subscriptionLocks.computeIfAbsent(feedId) { Mutex() }

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

    /** 已有来源修复展示所有真实通过的候选，由用户明确选择后再替换绑定。 */
    suspend fun probeCandidates(inputUrl: String): List<JsonSourceProbeResult> = withContext(ioDispatcher) {
        val imported = ruleRepository.findRules(inputUrl)
        val builtIn = listOfNotNull(WordPressJsonRuleFactory.createFromEndpoint(inputUrl)) +
            WordPressJsonRuleFactory.createCandidates(inputUrl)
        (imported + builtIn).distinctBy(JsonRule::id).mapNotNull { rule ->
            tryProbeRule(inputUrl, rule)?.copy(importedRule = rule in imported)
        }
    }

    /** 重绑失败的补偿只作用于这个来源，避免恢复整个规则文件覆盖并发配置。 */
    suspend fun replaceSubscription(request: JsonRebindRequest, updateSource: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        subscriptionLock(request.feed.id).withLock {
            currentCoroutineContext().ensureActive()
            withContext(kotlinx.coroutines.NonCancellable + ioDispatcher) {
                persistReplacement(request, updateSource)
            }
        }
    }

    /** 短提交区只包括规则文件和地址更新，不包含网络探测。 */
    private suspend fun persistReplacement(request: JsonRebindRequest, updateSource: suspend () -> Unit) {
        val previous = ruleRepository.subscriptionBinding(request.feed.id)
        ruleRepository.bindSubscription(request.feed.id, request.sourcePageUrl, request.probe)
        try {
            updateSource()
        } catch (error: Exception) {
            // 绑定与来源地址属于一次确认；数据库失败恢复原绑定并完整暴露补偿异常。
            try {
                ruleRepository.restoreSubscriptionBinding(request.feed.id, previous)
            } catch (restoreError: Exception) {
                error.addSuppressed(restoreError)
            }
            throw error
        }
    }

    /** 来源绑定优先于旧订阅的唯一精确匹配，不能按域名猜测成功规则。 */
    suspend fun fetch(feed: Feed, preDate: Date = Date()): FeedWithArticle = withContext(ioDispatcher) {
        subscriptionLock(feed.id).withLock {
            val boundRule = ruleRepository.subscriptionRule(feed.id, feed.url)
            val articles = if (boundRule != null) executeRule(JsonExecutionRequest(feed.url, boundRule, feed, preDate))
                else fetchLegacySubscription(feed, preDate)
            FeedWithArticle(feed.copy(isNotification = feed.isNotification && articles.isNotEmpty()), articles)
        }
    }

    /** 无绑定旧来源用真实响应验证候选；唯一成功才保存，多个成功明确要求用户选择。 */
    private suspend fun fetchLegacySubscription(feed: Feed, fetchedAt: Date): List<Article> {
        val imported = ruleRepository.findRulesForEndpoint(feed.url)
        val candidates = imported.ifEmpty { listOfNotNull(WordPressJsonRuleFactory.createFromEndpoint(feed.url)) }
        require(candidates.isNotEmpty()) { "未找到 ${feed.url} 对应的 JSON 来源规则" }
        val failures = mutableListOf<Exception>()
        val successes = candidates.mapNotNull { rule ->
            try {
                rule to executeRule(JsonExecutionRequest(feed.url, rule, feed, fetchedAt))
            } catch (error: CancellationException) {
                // 恢复验证被取消后不能继续尝试其他规则或写入绑定。
                throw error
            } catch (error: Exception) {
                // 仅在旧数据恢复阶段验证全部候选，失败原因保留日志和最终异常。
                failures.add(error)
                Timber.w(error, "旧 JSON 来源规则 [%s] 验证失败", rule.name)
                null
            }
        }
        if (successes.isEmpty()) throw IllegalStateException("JSON 来源规则均验证失败，请重新探测来源：${feed.url}")
            .also { error -> failures.forEach(error::addSuppressed) }
        require(successes.size == 1) { "JSON 来源匹配多条有效规则，请在来源设置重新探测并选择：${feed.url}" }
        currentCoroutineContext().ensureActive()
        val (rule, articles) = successes.single()
        ruleRepository.bindSubscription(feed.id, feed.url,
            JsonSourceProbeResult(rule, feed.url, articles.toSyndFeed(rule.name, feed.url), rule in imported))
        return articles
    }

    /** 探测阶段和同步阶段共用同一执行入口，保证保存后不会换一种解析方式。 */
    private suspend fun probeRule(inputUrl: String, rule: JsonRule): JsonSourceProbeResult {
        val sourceUrl = rule.requestEndpoint(inputUrl, ruleRepository::resolveEndpoint)
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
