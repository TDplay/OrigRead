package me.ash.reader.ui.page.home.feeds.drawer.feed

import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.filter.ArticleFilterRule
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.website.WebsiteParseCandidate
import me.ash.reader.infrastructure.website.WebsiteRule

data class FeedOptionUiState(
    val feed: Feed? = null,
    val selectedGroupId: String = "",
    val newGroupContent: String = "",
    val newGroupDialogVisible: Boolean = false,
    val groups: List<Group> = emptyList(),
    val deleteDialogVisible: Boolean = false,
    val clearDialogVisible: Boolean = false,
    val newName: String = "",
    val renameDialogVisible: Boolean = false,
    val newUrl: String = "",
    val changeUrlDialogVisible: Boolean = false,
    val websiteParserDialogVisible: Boolean = false,
    val jsonParserDialogVisible: Boolean = false,
    val websiteParserLoading: Boolean = false,
    val websiteParserError: String? = null,
    val websiteCandidates: List<WebsiteParseCandidate> = emptyList(),
    val websiteConfiguredRules: List<WebsiteRule> = emptyList(),
    val preferredWebsiteRuleId: String? = null,
    val preferredWebsiteRuleName: String? = null,
    val lastSelectedWebsiteRuleId: String? = null,
    val websiteReparseLoading: Boolean = false,
    val jsonRepair: JsonSourceRepairState = JsonSourceRepairState(),
    val jsonConfiguredRules: List<JsonRule> = emptyList(),
    val sourceFilterDialogVisible: Boolean = false,
    val sourceFilterRules: List<ArticleFilterRule> = emptyList(),
    val sourceFilterError: String? = null,
)
