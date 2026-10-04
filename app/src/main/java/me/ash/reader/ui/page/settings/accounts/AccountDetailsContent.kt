package me.ash.reader.ui.page.settings.accounts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.not
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.OrigReadSwitch
import me.ash.reader.ui.component.base.Subtitle
import me.ash.reader.ui.component.base.Tips
import me.ash.reader.ui.ext.toString
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.page.settings.accounts.connection.AccountConnection
import androidx.compose.foundation.lazy.LazyListScope

/** 显示名称与账户连接配置沿用原有入口。 */
internal fun LazyListScope.accountDisplayItems(page: AccountDetailsContext) {
    item {
        val context = LocalContext.current
        DisplayText(text = page.account?.type?.toDesc(context) ?: "", desc = "")
        Spacer(modifier = Modifier.height(16.dp))
    }
    item {
        Subtitle(modifier = Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.display))
        SettingItem(
            title = stringResource(R.string.name), desc = page.account?.name ?: "",
            onClick = { page.dialogs.nameValue = page.account?.name; page.dialogs.nameVisible = true },
        ) {}
        Spacer(modifier = Modifier.height(24.dp))
    }
    page.account?.let { account -> item { AccountConnection(account = account) } }
}

/** 同步设置仍按账户编辑，保留现有开关与文章保留策略。 */
internal fun LazyListScope.accountSyncItems(page: AccountDetailsContext) {
    item {
        val context = LocalContext.current
        Subtitle(modifier = Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.synchronous))
        page.account?.let {
            SettingItem(
                title = stringResource(R.string.last_updated), desc = it.updateAt.toString(),
                onClick = {}, enabled = false,
            ) {}
        }
        SettingItem(
            title = stringResource(R.string.sync_interval), desc = page.account?.syncInterval?.toDesc(context),
            onClick = { page.dialogs.syncIntervalVisible = true },
        ) {}
        AccountSynchronizationToggles(page)
        SettingItem(
            title = stringResource(R.string.keep_archived_articles), desc = page.account?.keepArchived?.toDesc(context),
            onClick = { page.dialogs.keepArchivedVisible = true },
        ) {}
        Tips(text = stringResource(R.string.synchronous_tips) + "\n\n" + stringResource(R.string.keep_archived_tips))
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 同步偏好开关保持原来的布尔翻转和账户写入。 */
@Composable
private fun AccountSynchronizationToggles(page: AccountDetailsContext) {
    val account = page.account
    AccountSyncSwitch(stringResource(R.string.sync_once_on_start), account?.syncOnStart?.value == true) {
        account?.id?.let { (!account.syncOnStart).put(it, page.viewModel) }
    }
    AccountSyncSwitch(stringResource(R.string.only_on_wifi), account?.syncOnlyOnWiFi?.value == true) {
        account?.id?.let { (!account.syncOnlyOnWiFi).put(it, page.viewModel) }
    }
    AccountSyncSwitch(stringResource(R.string.only_when_charging), account?.syncOnlyWhenCharging?.value == true) {
        account?.id?.let { (!account.syncOnlyWhenCharging).put(it, page.viewModel) }
    }
}

/** 行点击和开关点击使用同一个偏好操作。 */
@Composable
private fun AccountSyncSwitch(title: String, enabled: Boolean, toggle: () -> Unit) {
    SettingItem(title = title, onClick = toggle) { OrigReadSwitch(activated = enabled) { toggle() } }
}

/** 高级操作仍使用已有清理与删除确认，导出增加明确的 RSS 范围说明。 */
internal fun LazyListScope.accountAdvancedItems(page: AccountDetailsContext) {
    item {
        Subtitle(modifier = Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.advanced))
        SettingItem(
            title = stringResource(R.string.export_as_opml), onClick = { page.dialogs.exportOpmlVisible = true },
        ) {}
        SettingItem(title = stringResource(R.string.clear_all_articles), onClick = page.viewModel::showClearDialog) {}
        SettingItem(title = stringResource(R.string.delete_account), onClick = page.viewModel::showDeleteDialog) {}
        Spacer(modifier = Modifier.height(24.dp))
    }
}
