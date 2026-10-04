package me.ash.reader.ui.page.home.feeds.drawer.feed

import kotlinx.coroutines.flow.update
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.website.WebsiteRule

/** 按来源加载现有解析偏好和过滤配置，换来源时清理旧探测预览。 */
internal suspend fun FeedOptionViewModel.performFetchFeed(feedId: String) {
    if (_feedOptionUiState.value.feed?.id != feedId) resetJsonRepairForFeedSwitch()
    val feed = rssService.get().findFeedById(feedId)
    val preference = feed?.takeIf { it.sourceType == SourceType.WEBSITE }
        ?.let { websiteHelper.getParsePreference(it.id) }
    val configuredWebsiteRules = feed
        ?.takeIf { it.sourceType == SourceType.WEBSITE }
        ?.let { websiteHelper.getConfiguredRules(it.url) }
        .orEmpty()
    val configuredJsonRules = feed
        ?.takeIf { it.sourceType == SourceType.JSON }
        ?.let { jsonRuleRepository.findConfiguredRules(it.url) }
        .orEmpty()
    val enabledRuleIds = configuredWebsiteRules.filter(WebsiteRule::enabled).mapTo(hashSetOf(), WebsiteRule::id)
    val effectivePreferredRuleId =
        preference?.preferredRuleId?.takeIf { it in enabledRuleIds }
            ?: preference?.lastSelectedRuleId?.takeIf { it in enabledRuleIds }
            ?: configuredWebsiteRules.singleOrNull(WebsiteRule::enabled)?.id
    val effectivePreferredRuleName = effectivePreferredRuleId?.let { ruleId ->
        configuredWebsiteRules.firstOrNull { it.id == ruleId }?.name
            ?: websiteHelper.getRuleName(ruleId)
    }
    _feedOptionUiState.update {
        it.copy(
            feed = feed,
            selectedGroupId = feed?.groupId ?: "",
            websiteConfiguredRules = configuredWebsiteRules,
            jsonConfiguredRules = configuredJsonRules,
            preferredWebsiteRuleId = effectivePreferredRuleId,
            preferredWebsiteRuleName =
                effectivePreferredRuleName
                    ?: preference?.preferredRuleName
                    ?: websiteHelper.getRuleName(effectivePreferredRuleId),
            lastSelectedWebsiteRuleId = preference?.lastSelectedRuleId,
            sourceFilterRules = feed?.let { articleFilterRepository.getByFeed(it.id) }.orEmpty(),
        )
    }
}
