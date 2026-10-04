package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import me.ash.reader.ui.motion.origReadFadeThroughTransform

/** 面板回调按职责集中传递，避免候选区使用长位置参数列表。 */
internal data class SubscribeSheetCallbacks(
    val onRename: () -> Unit,
    val onClose: () -> Unit,
    val onSelectCatalog: (FeedCatalogEntry) -> Unit,
    val onSearch: () -> Unit,
    val onSelectCandidate: (String) -> Unit,
    val onToggleRssHub: (String) -> Unit,
    val onRetryRssHub: () -> Unit,
    val onToggleNotification: () -> Unit,
    val onToggleFullContent: () -> Unit,
    val onToggleBrowser: () -> Unit,
    val onGroupClick: (String) -> Unit,
    val onAddGroup: () -> Unit,
    val onImportOpml: () -> Unit,
    val onSubscribe: () -> Unit,
)

/** 底部面板的头、候选内容与操作区保持原有顺序。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubscribeSheet(state: SubscribeState, loading: Boolean, actions: SubscribeSheetCallbacks) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = actions.onClose, sheetState = sheetState, dragHandle = null) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            SubscribeSheetHeader(state, actions)
            HorizontalDivider()
            SubscriptionAnimatedContent(state, actions)
            HorizontalDivider()
            SubscriptionAnimatedActions(state, loading, actions)
        }
    }
}

/** 内容切换使用既有动画和稳定的状态键。 */
@Composable
private fun SubscriptionAnimatedContent(state: SubscribeState, actions: SubscribeSheetCallbacks) {
    val transition = origReadFadeThroughTransform()
    AnimatedContent(
        targetState = state,
        contentKey = { if (it is SubscribeState.Configure) "configure" else if (it is SubscribeState.Input) "input" else "hidden" },
        transitionSpec = { transition }, label = "subscribe-content",
    ) {
        when (it) {
            is SubscribeState.Input -> SubscribeInputContent(it, actions)
            is SubscribeState.Configure -> SubscribeConfigureContent(it, actions)
            SubscribeState.Hidden -> Unit
        }
    }
}

/** 按状态显示导入、搜索或确认；提交期间禁用原有确认按钮。 */
@Composable
private fun SubscriptionAnimatedActions(state: SubscribeState, loading: Boolean, actions: SubscribeSheetCallbacks) {
    val transition = origReadFadeThroughTransform()
    AnimatedContent(
        targetState = state, contentKey = { if (it is SubscribeState.Configure) "configure" else "input" },
        transitionSpec = { transition }, label = "subscribe-actions",
    ) { SubscribeSheetActions(it, loading, actions) }
}

/** 来源名称和原始页面地址仍显示在固定头部。 */
@Composable
private fun SubscribeSheetHeader(state: SubscribeState, actions: SubscribeSheetCallbacks) {
    val configure = state as? SubscribeState.Configure
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeedIcon(
            feedName = configure?.searchedFeed?.title, iconUrl = configure?.searchedFeed?.icon?.url,
            size = 42.dp, placeholderIcon = Icons.Rounded.RssFeed,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) { SubscriptionHeaderText(state, actions.onRename) }
        IconButton(onClick = actions.onClose) {
            Icon(imageVector = Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
        }
    }
}

/** 标题重命名仅在已探测配置状态开放。 */
@Composable
private fun SubscriptionHeaderText(state: SubscribeState, rename: () -> Unit) {
    val configure = state as? SubscribeState.Configure
    val title = when (state) {
        is SubscribeState.Configure -> state.searchedFeed.title
        is SubscribeState.Fetching -> stringResource(R.string.searching)
        is SubscribeState.Idle -> stringResource(R.string.subscribe)
        SubscribeState.Hidden -> ""
    }
    val url = configure?.sourcePageUrl ?: (state as? SubscribeState.Input)?.linkState?.text?.toString().orEmpty()
    Text(
        modifier = if (configure != null) Modifier.roundClick(onClick = rename) else Modifier,
        text = title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    if (url.isNotBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = url, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 面板操作区复用原有大小和文案，不把技术实现细节带入订阅流程。 */
@Composable
private fun SubscribeSheetActions(state: SubscribeState, loading: Boolean, actions: SubscribeSheetCallbacks) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (state is SubscribeState.Idle && state.importFromOpmlEnabled) {
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = actions.onImportOpml) {
                Text(stringResource(R.string.import_from_opml))
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        when (state) {
            is SubscribeState.Configure -> Button(
                modifier = Modifier.fillMaxWidth().height(50.dp), enabled = !loading, onClick = actions.onSubscribe,
            ) { Text(stringResource(R.string.confirm_subscribe)) }
            is SubscribeState.Input -> Button(
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = state is SubscribeState.Idle && state.linkState.text.isNotBlank(), onClick = actions.onSearch,
            ) {
                Text(stringResource(if (state is SubscribeState.Fetching) R.string.searching else R.string.search))
            }
            SubscribeState.Hidden -> Unit
        }
    }
}
