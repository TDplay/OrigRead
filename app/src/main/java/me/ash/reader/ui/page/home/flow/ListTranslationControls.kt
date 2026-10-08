package me.ash.reader.ui.page.home.flow

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface

import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.ai.availableModels
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.infrastructure.translation.TranslationTarget
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.page.adaptive.ArticleListReaderViewModel
import me.ash.reader.ui.page.home.reading.TranslationTargetSheet

/** 复用正文的渠道选择器和默认目标，不创建另一份翻译设置。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ListTranslationButton(
    viewModel: ArticleListReaderViewModel,
    state: ListTranslationState,
    hasItems: Boolean,
    onTranslate: (TranslationTarget?) -> Unit,
) {
    val settings = viewModel.translationSettings.collectAsStateValue()
    val aiSettings = viewModel.aiSettings.collectAsStateValue()
    var showTargets by remember { mutableStateOf(false) }
    val view = LocalView.current
    val defaultTarget = remember(settings, aiSettings) { viewModel.resolveDefaultTranslationTarget() }
    val targets: List<TranslationTarget> = TranslationProviderType.entries.filter(viewModel::isTranslationProviderConfigured)
        .map(TranslationTarget::Traditional) +
        if (!aiSettings.enabled) emptyList() else aiSettings.providers
            .filter { it.enabled && it.endpoint.isNotBlank() }
            .flatMap { provider -> provider.availableModels().map { model ->
                TranslationTarget.Ai(provider.id, provider.name, model)
            } }
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape)
            .background(if (state.showTranslation) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(
                enabled = hasItems || state.isLoading,
                role = Role.Button,
                onLongClickLabel = stringResource(R.string.translation_choose_method),
                onClick = { onTranslate(null) },
                onLongClick = {
                    viewModel.listTranslation.stop()
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showTargets = true
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (state.isLoading) Icons.Rounded.Stop else Icons.Rounded.Translate,
            contentDescription = stringResource(if (state.isLoading) R.string.list_translation_stop else R.string.list_translation_action),
            tint = if (hasItems || state.isLoading) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
    if (showTargets) TranslationTargetSheet(
        targets = targets,
        defaultTarget = defaultTarget,
        activeTarget = state.target,
        onDismiss = { showTargets = false },
        onTargetSelected = { onTranslate(it) },
        onSetDefaultTarget = viewModel::setDefaultTranslationTarget,
    )
}
/** 只显示当前任务或可关闭的失败；空闲、完成、停止不常驻操作说明。 */
@Composable
internal fun ListTranslationStatus(state: ListTranslationState) {
    var errorDismissed by remember(state.isLoading, state.errorMessage) { mutableStateOf(false) }
    if (!state.isLoading && (state.errorMessage == null || errorDismissed)) return
    val message = if (state.isLoading) {
        stringResource(R.string.list_translation_progress, state.completed, state.total)
    } else {
        stringResource(R.string.list_translation_error, state.completed, state.total, state.errorMessage.orEmpty())
    }
    // 背景只包住图标和文字；短提示不铺满底栏，长错误在屏幕约束与最大宽度内换行。
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            .widthIn(max = 320.dp).wrapContentWidth(Alignment.Start)
            .testTag("list_translation_status")
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = message, modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelMedium,
                maxLines = if (state.isLoading) 2 else 3, overflow = TextOverflow.Ellipsis,
                color = if (state.isLoading) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            if (!state.isLoading) {
                IconButton(onClick = { errorDismissed = true }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel))
                }
            }
        }
    }
}
