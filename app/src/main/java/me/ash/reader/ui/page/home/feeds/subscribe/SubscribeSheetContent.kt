package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.discovery.FeedCatalogEntry
import me.ash.reader.ui.component.base.ClipboardTextField
import me.ash.reader.ui.motion.origReadVisibilityEnter
import me.ash.reader.ui.motion.origReadVisibilityExit
import me.ash.reader.ui.page.home.feeds.FeedOptionView
import me.ash.reader.infrastructure.source.SourceCandidateKind

/** 输入与探测反馈保留同一个滚动区域。 */
@Composable
internal fun SubscribeInputContent(state: SubscribeState.Input, actions: SubscribeSheetCallbacks) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        ClipboardTextField(
            state = state.linkState, modifier = Modifier.fillMaxWidth(), readOnly = state is SubscribeState.Fetching,
            placeholder = stringResource(R.string.feed_or_site_url),
            errorText = (state as? SubscribeState.Idle)?.errorMessage.orEmpty(),
            imeAction = ImeAction.Search, onConfirm = { actions.onSearch() },
        )
        SubscriptionSearchProgress(state as? SubscribeState.Fetching)
        SubscriptionIdleSuggestions(state as? SubscribeState.Idle, actions.onSelectCatalog)
    }
}

/** 取消后 Fetching 状态消失，界面不会继续显示上一轮探测阶段。 */
@Composable
private fun SubscriptionSearchProgress(state: SubscribeState.Fetching?) {
    AnimatedVisibility(visible = state != null, enter = origReadVisibilityEnter(), exit = origReadVisibilityExit()) {
        state?.let {
            Column {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = searchStageLabel(it.stage), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 目录匹配和 RSSHub 失败提示继续显示已有真实探测结果。 */
@Composable
private fun SubscriptionIdleSuggestions(state: SubscribeState.Idle?, onCatalog: (FeedCatalogEntry) -> Unit) {
    AnimatedVisibility(
        visible = !state?.catalogMatches.isNullOrEmpty(), enter = origReadVisibilityEnter(), exit = origReadVisibilityExit(),
    ) {
        state?.let {
            Column {
                Spacer(modifier = Modifier.height(20.dp))
                CatalogMatchSection(it.catalogMatches, it.catalogMatchCount, onCatalog)
            }
        }
    }
    AnimatedVisibility(
        visible = !state?.rssHubResults.isNullOrEmpty(), enter = origReadVisibilityEnter(), exit = origReadVisibilityExit(),
    ) {
        state?.let {
            Column {
                Spacer(modifier = Modifier.height(20.dp))
                RssHubRouteSection(it.rssHubResults, emptyList(), emptySet(), {}, null)
            }
        }
    }
}

/** 失败消息保留在候选区，部分成功后只选中失败来源供重试。 */
@Composable
internal fun SubscribeConfigureContent(state: SubscribeState.Configure, actions: SubscribeSheetCallbacks) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 570.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        SubscriptionConfigureCandidates(state, actions)
        SubscriptionSourceNotice(state)
        SubscriptionFailureMessage(state.submissionMessage)
        FeedOptionView(
            link = state.feedLink, groups = state.groups, selectedAllowNotificationPreset = state.notification,
            selectedParseFullContentPreset = state.fullContent, selectedOpenInBrowserPreset = state.browser,
            selectedGroupId = state.selectedGroupId, allowNotificationPresetOnClick = actions.onToggleNotification,
            parseFullContentPresetOnClick = actions.onToggleFullContent, openInBrowserPresetOnClick = actions.onToggleBrowser,
            onGroupClick = actions.onGroupClick, onAddNewGroup = actions.onAddGroup,
            sourceType = state.sourceType, showUrl = false, showArticleFilter = false, scrollable = false,
        )
    }
}

/** 来源目录、RSSHub 路由和其它候选继续按原有顺序展示。 */
@Composable
private fun SubscriptionConfigureCandidates(state: SubscribeState.Configure, actions: SubscribeSheetCallbacks) {
    if (state.catalogMatches.isNotEmpty()) {
        CatalogMatchSection(state.catalogMatches, state.catalogMatchCount, actions.onSelectCatalog)
        Spacer(modifier = Modifier.height(20.dp))
    }
    if (state.rssHubResults.isNotEmpty()) {
        RssHubRouteSection(
            state.rssHubResults, state.candidates, state.selectedCandidateIds, actions.onToggleRssHub,
            if (state.rssHubResults.any { !it.available }) actions.onRetryRssHub else null,
        )
        Spacer(modifier = Modifier.height(20.dp))
    }
    val others = state.candidates.filter { it.kind != SourceCandidateKind.RSSHUB }
    if (others.size > 1 || (others.isNotEmpty() && state.rssHubResults.isNotEmpty())) {
        Text(stringResource(R.string.other_available_sources), style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            others.forEach { candidate ->
                SourceCandidateCard(candidate, CandidateCardSelection(
                    candidate.id in state.selectedCandidateIds,
                    candidate.diagnostics.accepted && candidate.id == state.candidates.firstOrNull()?.id,
                    { actions.onSelectCandidate(candidate.id) },
                ))
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

/** 非 RSSHub 来源继续展示既有风险文案。 */
@Composable
private fun SubscriptionSourceNotice(state: SubscribeState.Configure) {
    val kind = state.candidates.firstOrNull { it.id == state.selectedCandidateId }?.kind
    if (kind == SourceCandidateKind.RSSHUB) return
    state.sourceNotice?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(14.dp))
    }
}

/** 探测阶段名称使用已有本地化资源。 */
@Composable
private fun searchStageLabel(stage: SearchStage): String = when (stage) {
    SearchStage.CHECKING_RSS -> stringResource(R.string.search_stage_rss)
    SearchStage.CHECKING_RSSHUB -> stringResource(R.string.search_stage_rsshub)
    SearchStage.CHECKING_JSON -> stringResource(R.string.search_stage_json)
    SearchStage.CHECKING_WEBSITE -> stringResource(R.string.search_stage_website)
    SearchStage.CHECKING_DYNAMIC_WEBSITE -> stringResource(R.string.search_stage_dynamic_website)
}
