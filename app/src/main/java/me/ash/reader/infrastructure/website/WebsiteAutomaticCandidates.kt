package me.ash.reader.infrastructure.website

import kotlinx.coroutines.CancellationException

/** 复用健康自动缓存，失效时按既有算法重新扫描；固定模式不会进入此流程。 */
internal fun WebsiteHelper.automaticCandidateBatch(
    context: WebsiteCandidateContext, preference: WebsiteParsePreference?,
): WebsiteCandidateBatch {
    val cachedRule = preference?.cachedAutomaticRule
    if (cachedRule != null && AutomaticWebsiteListDetector.isReusableRule(cachedRule)) {
        val cached = parseRuleCandidate(cachedRule, context, preference)
        if (cached.diagnostics.accepted) {
            if (!context.forceFullScan && !AutomaticRuleStabilityScorer.shouldRunFullScan(preference)) {
                return WebsiteCandidateBatch(listOf(cached), automaticFullScan = false)
            }
            return WebsiteCandidateBatch(
                (detectAutomaticCandidates(context, preference) + cached).distinctBy { it.rule.id },
                automaticFullScan = true,
            )
        }
    }
    if (cachedRule != null) preferenceRepository.clearAutomaticRule(context.feed.id)
    return WebsiteCandidateBatch(detectAutomaticCandidates(context, preference), automaticFullScan = true)
}

/** 自动检测保留来源级历史稳定性权重与现有动态候选质量规则。 */
private fun detectAutomaticCandidates(
    context: WebsiteCandidateContext, preference: WebsiteParsePreference?,
): List<WebsiteParseCandidate> = AutomaticWebsiteListDetector.detect(
    document = context.document,
    feed = context.feed,
    fetchedAt = context.fetchedAt,
    historyScoreProvider = { ruleId -> AutomaticRuleStabilityScorer.score(preference, ruleId) },
    includeRejected = context.allowLowConfidence,
)

/** 普通规则错误保留为明确的失败候选；取消必须停止整个解析链路。 */
internal fun parseRuleCandidate(
    rule: WebsiteRule, context: WebsiteCandidateContext, preference: WebsiteParsePreference?,
): WebsiteParseCandidate = try {
    val articles = ConfigurableWebsiteParser(rule).parse(context.document, context.feed, context.fetchedAt)
    val diagnostics = WebsiteCandidateScorer.score(articles, context.fetchedAt.time).copy(
        regionScore = rule.automaticRegionScore,
        historyScore = if (rule.id.startsWith(AutomaticWebsiteListDetector.RULE_ID_PREFIX)) {
            AutomaticRuleStabilityScorer.score(preference, rule.id)
        } else 0,
    )
    WebsiteParseCandidate(rule, articles, diagnostics)
} catch (cancelled: CancellationException) {
    // 取消属于调用方控制信号，不能转为普通规则失效。
    throw cancelled
} catch (error: Exception) {
    // 保留具体错误供评分和日志展示，自动选择才能判断所有人工规则是否失效。
    WebsiteParseCandidate(
        rule, emptyList(), WebsiteCandidateScorer.rejected(error.message ?: "Parsing failed"),
    )
}
