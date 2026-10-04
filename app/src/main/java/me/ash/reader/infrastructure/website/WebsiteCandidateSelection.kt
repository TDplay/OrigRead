package me.ash.reader.infrastructure.website

import java.util.Date
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import org.jsoup.nodes.Document
import timber.log.Timber

/** 一次页面解析的不可变上下文，避免规则遍历依赖可变的界面状态。 */
internal data class WebsiteCandidateContext(
    val feed: Feed,
    val document: Document,
    val fetchedAt: Date,
    val forceFullScan: Boolean = false,
    val allowLowConfidence: Boolean = false,
)

internal data class WebsiteCandidateBatch(
    val candidates: List<WebsiteParseCandidate>,
    val automaticFullScan: Boolean,
)

internal data class WebsiteCandidateSelection(
    val candidate: WebsiteParseCandidate,
    val batch: WebsiteCandidateBatch,
)

/** 固定模式只接受指定规则；自动模式按质量评分选择真实结果。 */
internal fun WebsiteHelper.selectBestCandidate(context: WebsiteCandidateContext): WebsiteCandidateSelection {
    val batch = buildCandidateBatch(context)
    val preferredId = preferenceRepository.get(context.feed.id)?.preferredRuleId
    val accepted = batch.candidates.filter { it.diagnostics.accepted }
    if (preferredId != null) {
        val fixed = accepted.firstOrNull { it.rule.id == preferredId }
            ?: error("固定的网站规则已失效或停用，请重新选择：$preferredId")
        return WebsiteCandidateSelection(fixed, batch)
    }
    val selected = accepted.maxByOrNull { it.diagnostics.rankingScore }
        ?: batch.candidates.filter {
            context.allowLowConfidence && WebsiteCandidateScorer.isSafeDynamicFallback(it.diagnostics)
        }.maxByOrNull { it.diagnostics.rankingScore }
        ?: error("当前网站的解析规则均未通过健康检查：${context.feed.url}")
    return WebsiteCandidateSelection(selected, batch)
}

/** 固定规则保留身份；仅自动选择模式允许失效人工规则继续智能识别。 */
internal fun WebsiteHelper.buildCandidateBatch(context: WebsiteCandidateContext): WebsiteCandidateBatch {
    val preference = preferenceRepository.get(context.feed.id)
    val preferredId = preference?.preferredRuleId
    if (preferredId != null) return fixedCandidateBatch(context, preference)
    val manualCandidates = ruleRepository.findRules(context.feed.url).map {
        parseRuleCandidate(it, context, preference)
    }
    if (manualCandidates.any { it.diagnostics.accepted }) {
        return WebsiteCandidateBatch(manualCandidates, automaticFullScan = false)
    }
    if (manualCandidates.isNotEmpty()) {
        Timber.w("网站匹配规则全部失效，自动选择模式重新识别：%s", context.feed.url)
    }
    return automaticCandidateBatch(context, preference)
}

/** 固定自动规则直接复用同一缓存身份，不能被同域人工规则抢占或自动替换。 */
private fun WebsiteHelper.fixedCandidateBatch(
    context: WebsiteCandidateContext, preference: WebsiteParsePreference,
): WebsiteCandidateBatch {
    val preferredId = requireNotNull(preference.preferredRuleId)
    val automatic = preferredId.startsWith(AutomaticWebsiteListDetector.RULE_ID_PREFIX)
    val rule = if (automatic) {
        preference.cachedAutomaticRule?.takeIf {
            it.id == preferredId && AutomaticWebsiteListDetector.isReusableRule(it)
        }
    } else {
        ruleRepository.findRules(context.feed.url).firstOrNull { it.id == preferredId }
    }
    requireNotNull(rule) { "固定的网站规则已删除、停用或不兼容：$preferredId" }
    return WebsiteCandidateBatch(listOf(parseRuleCandidate(rule, context, preference)), automaticFullScan = false)
}

/** 成功解析后才记录选择与稳定性历史，失败不覆盖原来的来源偏好。 */
internal fun WebsiteHelper.parseAndRecordSelection(context: WebsiteCandidateContext): List<Article> {
    val selection = selectBestCandidate(context)
    val candidate = selection.candidate
    val feedId = context.feed.id
    selectedRuleIds[feedId] = candidate.rule.id
    if (AutomaticWebsiteListDetector.isReusableRule(candidate.rule)) {
        if (preferenceRepository.get(feedId)?.cachedAutomaticRule?.id != candidate.rule.id) {
            preferenceRepository.saveAutomaticRule(feedId, candidate.rule)
        }
        preferenceRepository.recordAutomaticSelection(
            feedId = feedId,
            selectedRuleId = candidate.rule.id,
            observedRuleIds = selection.batch.candidates.mapTo(linkedSetOf()) { it.rule.id },
            fullScan = selection.batch.automaticFullScan,
            observedAt = context.fetchedAt.time,
        )
    }
    preferenceRepository.saveLastSelection(feedId, candidate)
    return candidate.articles
}
