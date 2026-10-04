package me.ash.reader.ui.page.settings.accounts

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import me.ash.reader.infrastructure.preference.SyncBlockListPreference
import me.ash.reader.ui.ext.toString
import me.ash.reader.domain.model.account.Account

/** 弹窗的短期输入状态沿用页面 remember 生命周期。 */
internal class AccountDetailsDialogState(account: Account?) {
    var nameValue by mutableStateOf(account?.name)
    var nameVisible by mutableStateOf(false)
    var blockListValue by mutableStateOf(
        SyncBlockListPreference.toString(account?.syncBlockList ?: SyncBlockListPreference.default)
    )
    var blockListVisible by mutableStateOf(false)
    var syncIntervalVisible by mutableStateOf(false)
    var keepArchivedVisible by mutableStateOf(false)
    var exportOpmlVisible by mutableStateOf(false)
}
