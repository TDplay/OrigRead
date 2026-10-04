package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.ui.component.RenameDialog
import me.ash.reader.ui.component.base.TextFieldDialog
import me.ash.reader.ui.ext.MimeType
import me.ash.reader.ui.ext.collectAsStateValue

/** 订阅底部面板保留现有布局，操作结果独立于面板可见状态显示。 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SubscribeDialog(subscribeViewModel: SubscribeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val uiState = subscribeViewModel.subscribeUiState.collectAsStateValue()
    val state = subscribeViewModel.subscribeState.collectAsStateValue()
    SubscriptionOperationFeedback(uiState.operationMessage, subscribeViewModel::consumeOperationMessage)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected ->
            subscribeViewModel.importFromProvider {
                requireNotNull(context.contentResolver.openInputStream(selected)) { "无法打开 OPML 文件" }
            }
        }
    }
    if (state !is SubscribeState.Visible) return
    DisposableEffect(Unit) { onDispose { subscribeViewModel.cancelSearch() } }
    val actions = subscriptionSheetActions(subscribeViewModel, focus) {
        launcher.launch(arrayOf(MimeType.ANY))
    }
    SubscribeSheet(state, uiState.isSubscribing, actions)
    SubscriptionEditDialogs(subscribeViewModel, uiState)
}

/** 界面动作在同一入口绑定，候选选择和提交仍由 ViewModel 保持账户身份。 */
private fun subscriptionSheetActions(
    viewModel: SubscribeViewModel, focus: androidx.compose.ui.focus.FocusManager, importOpml: () -> Unit,
) = SubscribeSheetCallbacks(
    onRename = viewModel::showRenameDialog,
    onClose = { focus.clearFocus(); viewModel.hideDrawer() },
    onSelectCatalog = { viewModel.openFeedFromCatalog(it.feedUrl) },
    onSearch = { focus.clearFocus(); viewModel.searchFeed() },
    onSelectCandidate = viewModel::selectSourceCandidate,
    onToggleRssHub = viewModel::toggleRssHubCandidate,
    onRetryRssHub = viewModel::retrySourceDiscovery,
    onToggleNotification = viewModel::toggleAllowNotificationPreset,
    onToggleFullContent = viewModel::toggleParseFullContentPreset,
    onToggleBrowser = viewModel::toggleOpenInBrowserPreset,
    onGroupClick = viewModel::selectedGroup,
    onAddGroup = viewModel::showNewGroupDialog,
    onImportOpml = { focus.clearFocus(); importOpml(); viewModel.hideDrawer() },
    onSubscribe = { focus.clearFocus(); viewModel.subscribe() },
)

/** 名称和分组弹窗继续使用项目既有组件。 */
@Composable
private fun SubscriptionEditDialogs(viewModel: SubscribeViewModel, state: SubscribeUiState) {
    RenameDialog(
        visible = state.renameDialogVisible, value = state.newName,
        onValueChange = viewModel::inputNewName, onDismissRequest = viewModel::hideRenameDialog,
        onConfirm = { viewModel.renameFeed(); viewModel.hideRenameDialog() },
    )
    TextFieldDialog(
        visible = state.newGroupDialogVisible, title = stringResource(R.string.create_new_group),
        icon = Icons.Outlined.CreateNewFolder, value = state.newGroupContent,
        placeholder = stringResource(R.string.name), onValueChange = viewModel::inputNewGroup,
        onDismissRequest = viewModel::hideNewGroupDialog, onConfirm = { viewModel.addNewGroup() },
    )
}
