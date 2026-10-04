package me.ash.reader.ui.page.settings.accounts

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Date
import me.ash.reader.R
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.ext.DateFormat
import me.ash.reader.ui.ext.MimeType
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.getCurrentVersion
import me.ash.reader.ui.ext.toString
import me.ash.reader.ui.theme.palette.onLight
import me.ash.reader.domain.model.account.Account

/** 账户设置页面保留原有状态与布局，OPML 范围说明由独立导出弹窗展示。 */
@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccountDetailsPage(viewModel: AccountViewModel, onBack: () -> Unit, navigateToFeeds: () -> Unit) {
    val uiState = viewModel.accountUiState.collectAsStateValue()
    val context = LocalContext.current
    val account = uiState.selectedAccount.collectAsStateValue(initial = null)
    val dialogs = remember { AccountDetailsDialogState(account) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MimeType.ANY)) { result ->
        viewModel.exportAsOPML(account!!.id!!) { string ->
            result?.let { uri ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(string.toByteArray()) }
            }
        }
    }
    val page = AccountDetailsContext(viewModel, account, dialogs, onBack, navigateToFeeds) {
        subscriptionOPMLFileLauncher(context, launcher)
    }
    AccountDetailsScaffold(page)
    AccountSettingsDialogs(page, uiState)
}

/** 页面回调与账户快照作为一个上下文传递，分区不自行读取其它账户。 */
internal data class AccountDetailsContext(
    val viewModel: AccountViewModel,
    val account: Account?,
    val dialogs: AccountDetailsDialogState,
    val onBack: () -> Unit,
    val navigateToFeeds: () -> Unit,
    val exportOpml: () -> Unit,
)

/** 账户显示、同步设置和高级操作保持原有列表顺序。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccountDetailsScaffold(page: AccountDetailsContext) {
    OrigReadScaffold(
        containerColor = MaterialTheme.colorScheme.surface onLight MaterialTheme.colorScheme.inverseOnSurface,
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface, onClick = page.onBack,
            )
        },
        actions = { AccountDetailsDoneButton(page.navigateToFeeds) },
        content = {
            LazyColumn {
                accountDisplayItems(page)
                accountSyncItems(page)
                accountAdvancedItems(page)
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
    )
}

/** 完成按钮沿用触感反馈与现有导航。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccountDetailsDoneButton(navigate: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Button(
        modifier = Modifier.padding(end = 8.dp),
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); navigate() },
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryFixedDim,
            contentColor = MaterialTheme.colorScheme.onPrimaryFixedVariant,
        ),
    ) { Text(stringResource(R.string.done)) }
}

/** OPML 文件名沿用现有版本和时间格式。 */
private fun subscriptionOPMLFileLauncher(context: Context, launcher: ManagedActivityResultLauncher<String, Uri?>) {
    launcher.launch(
        "OrigRead-${context.getCurrentVersion()}-subscription-" +
            "${Date().toString(DateFormat.YYYY_MM_DD_DASH_HH_MM_SS_DASH)}.opml"
    )
}
