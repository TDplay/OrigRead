package me.ash.reader.ui.page.home.feeds.subscribe

import kotlinx.coroutines.withTimeout
import me.ash.reader.R
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.rsshub.RssHubProbeResult
import me.ash.reader.infrastructure.source.SourceCandidateKind
import me.ash.reader.infrastructure.source.SourceInputHint

/** 延用已有探测预算，时间只约束对应阶段，不改造成全局任意上限。 */
internal const val STRUCTURED_PROBE_TIMEOUT_MS = 20_000L
/** 静态网站沿用既有 15 秒阶段预算。 */
private const val WEBSITE_PROBE_TIMEOUT_MS = 15_000L

/** 每个阶段返回新结果，避免失败信息和候选被隐式全局覆盖。 */
internal data class SubscribeDiscoveryResult(
    val candidates: List<SubscribeCandidateProbe> = emptyList(),
    val error: Throwable? = null,
    val rssHubResults: List<RssHubProbeResult> = emptyList(),
    val notice: String? = null,
)

/** 保留 RSS/JSON 优先、RSSHub 次之、静态网站再动态网站的现有顺序。 */
internal suspend fun SubscribeViewModel.discoverSources(options: SubscribeDiscoveryContext): SubscribeDiscoveryResult {
    if (options.explicitRoute != null) return discoverExplicitRoute(options)
    val jsonFirst = options.isLocal && options.hint == SourceInputHint.JSON_LIKELY
    val first = if (jsonFirst) probeJsonSource(options) else probeRssSource(options)
    if (first.candidates.isNotEmpty()) return first
    val second = if (jsonFirst) probeRssSource(options) else probeJsonSource(options)
    if (second.candidates.isNotEmpty()) return second
    if (!options.isLocal) return second.copy(error = second.error ?: first.error)
    val hub = probeRssHubSources(options)
    if (hub.candidates.isNotEmpty()) return hub
    val website = probeWebsiteSource(options, dynamic = false)
    if (SubscribeCandidateSelector.rank(website.candidates).isNotEmpty()) {
        return website.copy(rssHubResults = hub.rssHubResults, notice = hub.notice)
    }
    return probeWebsiteSource(options, dynamic = true).copy(rssHubResults = hub.rssHubResults, notice = hub.notice)
}

/** 成功 JSON 候选保留完整探测规则，跨域 API 和框架内嵌数据也可按原规则刷新。 */
private suspend fun SubscribeViewModel.probeJsonSource(options: SubscribeDiscoveryContext): SubscribeDiscoveryResult {
    if (!options.isLocal) return SubscribeDiscoveryResult()
    updateSearchStage(SearchStage.CHECKING_JSON)
    val result = runSuspendCatching { jsonSourceHelper.probe(options.feedLink) }
    val probe = result.getOrNull() ?: return SubscribeDiscoveryResult(error = result.exceptionOrNull())
    return SubscribeDiscoveryResult(listOf(SubscribeCandidateProbe(
        feed = probe.feed, feedLink = probe.endpointUrl, sourceType = SourceType.JSON,
        kind = SourceCandidateKind.JSON, jsonProbe = probe,
    )))
}

/** 普通网站仅在真实解析成功时成立，动态阶段保留原有人工低置信确认语义。 */
private suspend fun SubscribeViewModel.probeWebsiteSource(
    options: SubscribeDiscoveryContext, dynamic: Boolean,
): SubscribeDiscoveryResult {
    updateSearchStage(if (dynamic) SearchStage.CHECKING_DYNAMIC_WEBSITE else SearchStage.CHECKING_WEBSITE)
    val result = runSuspendCatching {
        withTimeout(if (dynamic) STRUCTURED_PROBE_TIMEOUT_MS else WEBSITE_PROBE_TIMEOUT_MS) {
            if (dynamic) websiteHelper.inspectDynamic(options.feedLink) else websiteHelper.inspect(options.feedLink)
        }
    }
    val feed = result.getOrNull() ?: return SubscribeDiscoveryResult(error = result.exceptionOrNull())
    return SubscribeDiscoveryResult(listOf(SubscribeCandidateProbe(
        feed = feed, feedLink = options.feedLink, sourceType = SourceType.WEBSITE,
        kind = if (dynamic) SourceCandidateKind.WEBSITE_DYNAMIC else SourceCandidateKind.WEBSITE,
        sourceNotice = if (dynamic) androidStringsHelper.getString(R.string.dynamic_website_source_notice) else null,
        browser = false, dynamicRendering = dynamic,
    )))
}

/** RSSHub 本地诊断和真实网络结果沿用同一路由合并规则。 */
private suspend fun SubscribeViewModel.probeRssHubSources(options: SubscribeDiscoveryContext): SubscribeDiscoveryResult {
    updateSearchStage(SearchStage.CHECKING_RSSHUB)
    val local = rssHubResolver.localRouteDiagnostics(options.feedLink)
    val probed = runSuspendCatching { rssHubResolver.probe(options.feedLink) }
    val results = mergeRssHubProbeResults(local, probed.getOrDefault(emptyList()))
    val candidates = results.mapNotNull { result ->
        val feed = result.feed?.takeIf { result.available } ?: return@mapNotNull null
        val link = result.match.feedUrl ?: return@mapNotNull null
        SubscribeCandidateProbe(
            feed, link, SourceType.RSS, SourceCandidateKind.RSSHUB,
            sourceNotice = androidStringsHelper.getString(R.string.rsshub_source_notice, result.match.route.name),
            rssHubRoutePath = result.routePath, rssHubInstanceBaseUrl = result.instanceBaseUrl,
        )
    }
    return SubscribeDiscoveryResult(candidates, probed.exceptionOrNull(), results, rssHubFailureNotice(results))
}

/** 显式 RSSHub 输入失败只展示路由错误，不改成普通网页来源。 */
private suspend fun SubscribeViewModel.discoverExplicitRoute(options: SubscribeDiscoveryContext): SubscribeDiscoveryResult {
    val explicit = requireNotNull(options.explicitRoute)
    val probed = runSuspendCatching {
        withTimeout(STRUCTURED_PROBE_TIMEOUT_MS) {
            if (!options.isLocal && explicit.preferredInstance != null && !rssHubSettingsRepository.current().enabled) {
                listOf(rssHubResolver.probeExplicitRoute(explicit.routePath, explicit.preferredInstance))
            } else rssHubResolver.probeRoute(explicit.routePath, explicit.preferredInstance)
        }
    }
    val results = probed.getOrDefault(emptyList())
    val available = results.firstOrNull { it.available && it.feed != null && it.match.feedUrl != null }
        ?: return SubscribeDiscoveryResult(error = probed.exceptionOrNull(), rssHubResults = results,
            notice = explicitRssHubFailureNotice(results))
    return SubscribeDiscoveryResult(listOf(SubscribeCandidateProbe(
        requireNotNull(available.feed), requireNotNull(available.match.feedUrl), SourceType.RSS,
        kind = if (options.isLocal) SourceCandidateKind.RSSHUB else SourceCandidateKind.RSS_DIRECT,
        rssHubRoutePath = explicit.routePath, rssHubPreferredInstanceBaseUrl = explicit.preferredInstance,
        rssHubInstanceBaseUrl = available.instanceBaseUrl,
    )), rssHubResults = results)
}
