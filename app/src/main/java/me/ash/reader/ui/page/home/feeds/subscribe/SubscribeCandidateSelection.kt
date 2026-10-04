package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rometools.rome.feed.synd.SyndFeed
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.ash.reader.R
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.OpmlService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.discovery.FeedCatalogEntry
import me.ash.reader.infrastructure.discovery.FeedCatalogUrlMatch
import me.ash.reader.infrastructure.discovery.FeedDiscoveryCatalog
import me.ash.reader.infrastructure.json.JsonSourceHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rsshub.RssHubInputParser
import me.ash.reader.infrastructure.rsshub.RssHubResolver
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.source.SourceCandidateKind
import me.ash.reader.infrastructure.source.SourceInputHint
import me.ash.reader.infrastructure.source.sourceInputHint
import me.ash.reader.infrastructure.website.WebsiteHelper
import me.ash.reader.ui.ext.formatUrl

/** 把探测结果发布为固定账户的配置，分组在发布时再次按账户筛选。 */
internal fun SubscribeViewModel.applyDiscoveryResult(options: SubscribeDiscoveryContext, result: SubscribeDiscoveryResult) {
    val ranked = SubscribeCandidateSelector.rank(result.candidates)
    val selected = ranked.firstOrNull()
    if (selected == null) {
        _subscribeState.value = options.idle.copy(
            errorMessage = result.notice ?: result.error?.message ?: androidStringsHelper.getString(R.string.website_request_failed),
            rssHubResults = result.rssHubResults,
        )
        return
    }
    val groups = groupsFlow.value.filter { it.accountId == options.accountId }
    val groupId = groups.firstOrNull { it.id == options.firstGroupId }?.id
        ?: groups.firstOrNull()?.id ?: error("探测账户没有可用分组")
    _subscribeState.value = SubscribeState.Configure(
        searchedFeed = selected.feed, feedLink = selected.feedLink,
        sourcePageUrl = options.idle.linkState.text.toString(), groups = groups,
        selectedGroupId = groupId, accountId = options.accountId, sourceType = selected.sourceType,
        sourceNotice = sourceNoticeFor(selected), fullContent = false,
        browser = if (selected.sourceType == SourceType.RSS) false else selected.browser,
        candidates = ranked, selectedCandidateId = selected.id, selectedCandidateIds = setOf(selected.id),
        dynamicRendering = selected.dynamicRendering, rssHubResults = result.rssHubResults,
        catalogMatches = options.idle.catalogMatches, catalogMatchCount = options.idle.catalogMatchCount,
    )
}

    /** RSSHub 网络失败仅作为非阻断提示，不影响其他候选参与评分。 */
internal fun SubscribeViewModel.rssHubFailureNotice(results: List<me.ash.reader.infrastructure.rsshub.RssHubProbeResult>): String? =
        rssHubFailureSummary(results)?.let { notice ->
            androidStringsHelper.getString(notice.resourceId, *notice.arguments.toTypedArray())
        }

    /** 显式 RSSHub 输入失败时不再回落网页解析，而是给出 route/instance 语义明确的错误。 */
internal fun SubscribeViewModel.explicitRssHubFailureNotice(
        results: List<me.ash.reader.infrastructure.rsshub.RssHubProbeResult>
    ): String =
        rssHubFailureNotice(results)
            ?: androidStringsHelper.getString(R.string.rsshub_explicit_unavailable_notice)

    /** 在配置页切换有效来源候选，同时保留分组和订阅偏好。 */
fun SubscribeViewModel.selectSourceCandidate(candidateId: String) {
        _subscribeState.update { state ->
            if (state !is SubscribeState.Configure) return@update state
            val selected = state.candidates.firstOrNull { it.id == candidateId } ?: return@update state

            state.copy(
                searchedFeed = selected.feed,
                feedLink = selected.feedLink,
                sourceType = selected.sourceType,
                sourceNotice = sourceNoticeFor(selected),
                fullContent = if (selected.sourceType == SourceType.RSS) false else state.fullContent,
                browser = if (selected.sourceType == SourceType.RSS) false else selected.browser,
                dynamicRendering = selected.dynamicRendering,
                selectedCandidateId = selected.id,
                selectedCandidateIds = setOf(selected.id),
            )
        }
    }

    /** RSSHub 路由代表可独立订阅的频道，可多选；其他来源候选仍保持互斥单选。 */
fun SubscribeViewModel.toggleRssHubCandidate(candidateId: String) {
        _subscribeState.update { state ->
            if (state !is SubscribeState.Configure) return@update state
            val candidate = state.candidates.firstOrNull { it.id == candidateId } ?: return@update state
            if (candidate.kind != SourceCandidateKind.RSSHUB) return@update state

            val currentRssHubOnly =
                state.selectedCandidateIds.isNotEmpty() &&
                    state.candidates
                        .filter { it.id in state.selectedCandidateIds }
                        .all { it.kind == SourceCandidateKind.RSSHUB }
            val baseSelection = if (currentRssHubOnly) state.selectedCandidateIds else emptySet()
            val nextSelection =
                when {
                    candidateId !in baseSelection -> baseSelection + candidateId
                    baseSelection.size > 1 -> baseSelection - candidateId
                    else -> baseSelection
                }
            val primaryId =
                when {
                    candidateId in nextSelection -> candidateId
                    state.selectedCandidateId in nextSelection -> state.selectedCandidateId
                    else -> nextSelection.firstOrNull()
                } ?: return@update state
            val primary = state.candidates.first { it.id == primaryId }

            state.copy(
                searchedFeed = primary.feed,
                feedLink = primary.feedLink,
                sourceType = primary.sourceType,
                sourceNotice = sourceNoticeFor(primary),
                fullContent = false,
                browser = false,
                dynamicRendering = primary.dynamicRendering,
                selectedCandidateId = primary.id,
                selectedCandidateIds = nextSelection,
            )
        }
    }

    /** WebView 低可信兜底不阻断添加，但必须明确告知解析风险。 */
internal fun SubscribeViewModel.sourceNoticeFor(candidate: SubscribeSourceCandidate): String? =
        if (candidate.kind == SourceCandidateKind.WEBSITE_DYNAMIC && !candidate.diagnostics.accepted) {
            androidStringsHelper.getString(R.string.dynamic_website_low_confidence_notice)
        } else {
            candidate.sourceNotice
        }
