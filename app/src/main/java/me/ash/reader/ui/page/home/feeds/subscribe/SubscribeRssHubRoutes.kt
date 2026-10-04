package me.ash.reader.ui.page.home.feeds.subscribe

import me.ash.reader.domain.model.feed.SourceType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.rsshub.RssHubProbeResult
import me.ash.reader.ui.ext.roundClick
import me.ash.reader.infrastructure.source.SourceCandidateKind

/** 已有组件调用契约保持不变，内部通过配置对象传递多选状态。 */
@Composable
internal fun RssHubRouteSection(
    results: List<RssHubProbeResult>, candidates: List<SubscribeSourceCandidate>,
    selectedIds: Set<String>, onToggle: (String) -> Unit, onRetry: (() -> Unit)?,
) {
    val options = RssHubRouteOptions(
        results, candidates.filter { it.sourceType == SourceType.RSS }.associateBy(SubscribeSourceCandidate::feedLink),
        selectedIds, onToggle, onRetry,
    )
    RssHubRoutesHeader(options)
    Spacer(modifier = Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        results.forEach { RssHubRouteCard(it, options) }
    }
}

private data class RssHubRouteOptions(
    val results: List<RssHubProbeResult>,
    val candidatesByUrl: Map<String, SubscribeSourceCandidate>,
    val selectedIds: Set<String>,
    val onToggle: (String) -> Unit,
    val onRetry: (() -> Unit)?,
)

/** 可选择的路由计数仅包含实际通过预览质量检查的 RSSHub 候选。 */
@Composable
private fun RssHubRoutesHeader(options: RssHubRouteOptions) {
    val selectable = options.results.filter {
        it.available && it.match.feedUrl?.let { url -> options.candidatesByUrl[url]?.kind } == SourceCandidateKind.RSSHUB
    }
    val confirmed = options.results.any {
        it.available && it.match.feedUrl?.let(options.candidatesByUrl::containsKey) == true
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(if (selectable.isEmpty()) R.string.rsshub_probe_results else R.string.rsshub_matched_channels),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = if (selectable.isEmpty()) {
                    stringResource(if (confirmed) R.string.rsshub_probe_rss_available_desc else R.string.rsshub_probe_results_desc)
                } else stringResource(
                    R.string.rsshub_matched_channels_desc,
                    selectable.map { it.match.route.id to it.match.parameters }.distinct().size,
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        options.onRetry?.let { retry -> TextButton(onClick = retry) { Text(stringResource(R.string.rsshub_retry_detection)) } }
    }
}

/** 失败和质量不合格项只展示诊断；真实通过预览的路由才能进入多选提交。 */
@Composable
private fun RssHubRouteCard(result: RssHubProbeResult, options: RssHubRouteOptions) {
    val candidate = if (result.available) result.match.feedUrl?.let(options.candidatesByUrl::get) else null
    val selectable = candidate?.kind == SourceCandidateKind.RSSHUB
    val selected = selectable && candidate?.id in options.selectedIds
    val toggle = { options.onToggle(requireNotNull(candidate).id) }
    Surface(
        modifier = if (selectable) Modifier.fillMaxWidth().roundClick(onClick = toggle) else Modifier.fillMaxWidth(),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, enabled = selectable, onCheckedChange = if (selectable) ({ _: Boolean -> toggle() }) else null)
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) { RssHubRouteText(result, candidate) }
        }
    }
}

/** 路由卡片保留来源、失败原因和所用实例，便于部分失败后定位和重试。 */
@Composable
private fun RssHubRouteText(result: RssHubProbeResult, candidate: SubscribeSourceCandidate?) {
    Text(result.match.route.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        rssHubRouteStatus(result, candidate), style = MaterialTheme.typography.bodySmall,
        color = if (candidate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    result.instanceBaseUrl?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 成功、质量拒绝与网络失败分别使用原有诊断文案。 */
@Composable
private fun rssHubRouteStatus(result: RssHubProbeResult, candidate: SubscribeSourceCandidate?): String = when {
    result.available && candidate != null -> stringResource(R.string.rsshub_route_available, candidate.diagnostics.articleCount)
    result.available -> stringResource(R.string.rsshub_route_quality_rejected)
    else -> rssHubFailureText(result).let { stringResource(it.resourceId, *it.arguments.toTypedArray()) }
}
