package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.discovery.FeedCatalogEntry
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.ext.roundClick
import me.ash.reader.infrastructure.source.SourceCandidateKind

/** 目录匹配计数保留原有截断提示。 */
@Composable
internal fun CatalogMatchSection(matches: List<FeedCatalogEntry>, totalMatches: Int, onSelect: (FeedCatalogEntry) -> Unit) {
    Text(stringResource(R.string.source_catalog_matches_title), style = MaterialTheme.typography.titleSmall)
    Text(
        text = if (totalMatches > matches.size) {
            stringResource(R.string.source_catalog_matches_desc_truncated, totalMatches, matches.size)
        } else stringResource(R.string.source_catalog_matches_desc, totalMatches),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { matches.forEach { CatalogMatchCard(it, onSelect) } }
}

/** 目录卡片点击仍进入真实来源探测。 */
@Composable
private fun CatalogMatchCard(feed: FeedCatalogEntry, onSelect: (FeedCatalogEntry) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().roundClick { onSelect(feed) },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            FeedIcon(feedName = feed.name, iconUrl = null, size = 34.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(feed.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    feed.siteUrl?.takeIf(String::isNotBlank) ?: feed.feedUrl, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.source_catalog_match_validate), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

internal data class CandidateCardSelection(val selected: Boolean, val recommended: Boolean, val onClick: () -> Unit)

/** 单来源候选使用原有单选卡片。 */
@Composable
internal fun SourceCandidateCard(candidate: SubscribeSourceCandidate, selection: CandidateCardSelection) {
    Surface(
        modifier = Modifier.fillMaxWidth().roundClick(onClick = selection.onClick),
        color = if (selection.selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selection.selected, onClick = selection.onClick)
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) { SourceCandidateText(candidate, selection.recommended) }
        }
    }
}

/** 候选内容显示实际 Feed 名称、来源类型和预览文章数量。 */
@Composable
private fun SourceCandidateText(candidate: SubscribeSourceCandidate, recommended: Boolean) {
    val sourceName = candidate.kind.displayName()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            candidate.feed.title?.trim().orEmpty().ifBlank { sourceName }, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (recommended) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.source_candidate_recommended), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    Text(
        "$sourceName · " + stringResource(R.string.source_candidate_article_count, candidate.diagnostics.articleCount),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 来源类型沿用已有语言资源。 */
@Composable
private fun SourceCandidateKind.displayName(): String = when (this) {
    SourceCandidateKind.RSS_DIRECT -> stringResource(R.string.source_kind_rss_direct)
    SourceCandidateKind.RSS_DISCOVERED -> stringResource(R.string.source_kind_rss_discovered)
    SourceCandidateKind.RSSHUB -> stringResource(R.string.source_kind_rsshub)
    SourceCandidateKind.JSON -> stringResource(R.string.source_kind_json)
    SourceCandidateKind.WEBSITE -> stringResource(R.string.source_kind_website)
    SourceCandidateKind.WEBSITE_DYNAMIC -> stringResource(R.string.source_kind_website_dynamic)
}
