package me.ash.reader.ui.page.settings.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.ui.component.base.OrigReadDialog
import me.ash.reader.ui.component.base.RadioDialogOption

/** 导出弹窗明确说明 RSS 与完整配置备份的范围，避免网站和 JSON 被回导为 RSS。 */
@Composable
internal fun AccountOpmlExportDialog(page: AccountDetailsContext, mode: ExportOPMLMode) {
    OrigReadDialog(
        visible = page.dialogs.exportOpmlVisible,
        title = {
            Text(
                stringResource(R.string.export_as_opml), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = { AccountOpmlExportOptions(page.viewModel, mode) },
        confirmButton = {
            TextButton(onClick = { page.dialogs.exportOpmlVisible = false; page.exportOpml() }) {
                Text(stringResource(R.string.export))
            }
        },
        dismissButton = {
            TextButton(onClick = { page.dialogs.exportOpmlVisible = false }) { Text(stringResource(R.string.cancel)) }
        },
        onDismissRequest = { page.dialogs.exportOpmlVisible = false },
    )
}

/** 附加信息选项与文件格式保持不变，来源范围说明放在选项之前。 */
@Composable
private fun AccountOpmlExportOptions(viewModel: AccountViewModel, mode: ExportOPMLMode) {
    val context = LocalContext.current
    LazyColumn {
        item {
            Text(stringResource(R.string.opml_export_rss_only_notice))
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.additional_info_desc))
            Spacer(modifier = Modifier.height(16.dp))
        }
        items(listOf(
            RadioDialogOption(
                text = context.getString(R.string.include_additional_info), selected = mode == ExportOPMLMode.ATTACH_INFO,
            ) { viewModel.changeExportOPMLMode(ExportOPMLMode.ATTACH_INFO) },
            RadioDialogOption(
                text = context.getString(R.string.exclude), selected = mode == ExportOPMLMode.NO_ATTACH,
            ) { viewModel.changeExportOPMLMode(ExportOPMLMode.NO_ATTACH) },
        )) { AccountOpmlModeOption(it) }
    }
}

/** 导出格式选项行沿用原有单选布局。 */
@Composable
private fun AccountOpmlModeOption(option: RadioDialogOption) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(CircleShape).clickable { option.onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = option.selected, onClick = option.onClick)
        Text(
            modifier = Modifier.padding(start = 6.dp), text = option.text,
            style = MaterialTheme.typography.bodyLarge.copy(baselineShift = BaselineShift.None).merge(other = option.style),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
