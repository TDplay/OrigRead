package me.ash.reader.ui.page.settings.accounts

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.KeepArchivedPreference
import me.ash.reader.infrastructure.preference.SyncBlockListPreference
import me.ash.reader.infrastructure.preference.SyncIntervalPreference
import me.ash.reader.ui.component.base.OrigReadDialog
import me.ash.reader.ui.component.base.RadioDialog
import me.ash.reader.ui.component.base.RadioDialogOption
import me.ash.reader.ui.component.base.TextFieldDialog
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.ext.showToastLong
import me.ash.reader.ui.ext.toString

/** 所有短期弹窗由同一页面状态控制。 */
@Composable
internal fun AccountSettingsDialogs(page: AccountDetailsContext, uiState: AccountUiState) {
    AccountNameDialog(page)
    AccountSynchronizationDialogs(page)
    AccountBlockListDialog(page)
    AccountClearDialog(page, uiState.clearDialogVisible)
    AccountDeleteDialog(page, uiState.deleteDialogVisible)
    AccountOpmlExportDialog(page, uiState.exportOPMLMode)
}

/** 名称编辑保留非空确认条件。 */
@Composable
private fun AccountNameDialog(page: AccountDetailsContext) {
    if (!page.dialogs.nameVisible) return
    val state = rememberTextFieldState(page.dialogs.nameValue ?: "")
    TextFieldDialog(
        title = stringResource(R.string.name), textFieldState = state,
        onDismissRequest = { page.dialogs.nameVisible = false },
        onConfirm = {
            if (state.text.isNotBlank()) {
                page.account?.id?.let { page.viewModel.update(it) { copy(name = state.text.toString()) } }
                page.dialogs.nameVisible = false
            }
        },
    )
}

/** 同步间隔和文章保留枚举继续复用项目 RadioDialog。 */
@Composable
private fun AccountSynchronizationDialogs(page: AccountDetailsContext) {
    val context = LocalContext.current
    RadioDialog(
        visible = page.dialogs.syncIntervalVisible, title = stringResource(R.string.sync_interval),
        options = SyncIntervalPreference.values.map { preference ->
            RadioDialogOption(text = preference.toDesc(context), selected = preference == page.account?.syncInterval) {
                page.account?.id?.let { preference.put(it, page.viewModel) }
            }
        },
    ) { page.dialogs.syncIntervalVisible = false }
    RadioDialog(
        visible = page.dialogs.keepArchivedVisible, title = stringResource(R.string.keep_archived_articles),
        options = KeepArchivedPreference.values.map { preference ->
            RadioDialogOption(text = preference.toDesc(context), selected = preference == page.account?.keepArchived) {
                page.account?.id?.let { preference.put(it, page.viewModel) }
            }
        },
    ) { page.dialogs.keepArchivedVisible = false }
}

/** 保留既有屏蔽列表弹窗行为。 */
@Composable
private fun AccountBlockListDialog(page: AccountDetailsContext) {
    val context = LocalContext.current
    TextFieldDialog(
        visible = page.dialogs.blockListVisible, title = stringResource(R.string.block_list),
        value = page.dialogs.blockListValue, singleLine = false,
        onValueChange = { page.dialogs.blockListValue = it },
        onDismissRequest = { page.dialogs.blockListVisible = false },
        onConfirm = {
            page.account?.id?.let {
                SyncBlockListPreference.put(it, page.viewModel, page.account.syncBlockList)
                page.dialogs.blockListVisible = false
                context.showToast(page.account.syncBlockList.toString())
            }
        },
    )
}

/** 清理确认仍通过原有 ViewModel 操作，并保留完成消息。 */
@Composable
private fun AccountClearDialog(page: AccountDetailsContext, visible: Boolean) {
    val context = LocalContext.current
    OrigReadDialog(
        visible = visible, onDismissRequest = page.viewModel::hideClearDialog,
        icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.clear_all_articles)) },
        title = { Text(stringResource(R.string.clear_all_articles)) },
        text = { Text(stringResource(R.string.clear_all_articles_tips)) },
        confirmButton = {
            TextButton(onClick = {
                page.account?.let { page.viewModel.clear(it) {
                    page.viewModel.hideClearDialog()
                    context.showToastLong(context.getString(R.string.clear_all_articles_toast))
                } }
            }) { Text(stringResource(R.string.clear)) }
        },
        dismissButton = { TextButton(onClick = page.viewModel::hideClearDialog) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 删除确认与导航时机保持原有契约。 */
@Composable
private fun AccountDeleteDialog(page: AccountDetailsContext, visible: Boolean) {
    val context = LocalContext.current
    OrigReadDialog(
        visible = visible, onDismissRequest = page.viewModel::hideDeleteDialog,
        icon = { Icon(Icons.Outlined.PersonOff, contentDescription = stringResource(R.string.delete_account)) },
        title = { Text(stringResource(R.string.delete_account)) },
        text = { Text(stringResource(R.string.delete_account_tips)) },
        confirmButton = {
            TextButton(onClick = {
                page.account?.id?.let { page.viewModel.delete(it) {
                    page.onBack()
                    context.showToastLong(context.getString(R.string.delete_account_toast))
                } }
            }) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = { TextButton(onClick = page.viewModel::hideDeleteDialog) { Text(stringResource(R.string.cancel)) } },
    )
}
