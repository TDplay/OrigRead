package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.discovery.FeedCatalogEntry
import me.ash.reader.ui.ext.roundClick

/** 单候选也展示确认状态和完整地址，多选 RSSHub 时同时列出其余实际选中的 Feed。 */
@Composable
internal fun SubscriptionCurrentSource(state: SubscribeState.Configure) {
    val current = state.candidates.firstOrNull { it.id == state.selectedCandidateId }
    val title = if (current?.isConfirmedNativeRss == true) {
        R.string.subscription_native_rss_confirmed
    } else R.string.subscription_current_source
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        SubscriptionFeedAddress(state.feedLink)
        state.candidates.filter { it.id in state.selectedCandidateIds && it.id != state.selectedCandidateId }
            .forEach { SubscriptionFeedAddress(it.feedLink) }
    }
    Spacer(modifier = Modifier.height(16.dp))
}

/** 完整展示将提交的地址，避免截断后无法区分原生 Atom、目录建议和 RSSHub 路由。 */
@Composable
private fun SubscriptionFeedAddress(feedLink: String) {
    Text(
        stringResource(R.string.subscription_feed_address, feedLink),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 未验证目录建议只作可展开的辅助入口；新的订阅输入恢复默认收起状态。 */
@Composable
internal fun SubscriptionCatalogSuggestions(state: SubscribeState.Configure, onSelect: (FeedCatalogEntry) -> Unit) {
    if (state.catalogMatches.isEmpty()) return
    var expanded by rememberSaveable(state.sourcePageUrl) { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth().roundClick { expanded = !expanded }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.subscription_other_catalog_unverified),
            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
        )
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.subscription_catalog_collapse else R.string.subscription_catalog_expand,
            ),
        )
    }
    AnimatedVisibility(visible = expanded) {
        Column { CatalogMatchSection(state.catalogMatches, state.catalogMatchCount, onSelect) }
    }
}
