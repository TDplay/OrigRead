package me.ash.reader.ui.page.home.feeds.subscribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 分组名和账户在启动前捕获，账户变化取消任务并禁止旧分组回写新候选。 */
internal fun SubscribeViewModel.createSubscriptionGroup() {
    val state = _subscribeState.value as? SubscribeState.Configure ?: return
    val name = _subscribeUiState.value.newGroupContent
    if (name.isBlank()) return
    val accountId = requireNotNull(state.accountId)
    accountOperationScope.launch {
        try {
            require(accountService.getCurrentAccount().id == accountId) { "账户已切换，请重新探测订阅来源" }
            val groupId = rssService.forAccount(accountId).addGroup(null, name, accountId)
            currentCoroutineContext().ensureActive()
            val current = _subscribeState.value as? SubscribeState.Configure ?: return@launch
            if (current.accountId != accountId) return@launch
            selectedGroup(groupId)
            hideNewGroupDialog()
            _subscribeUiState.update { it.copy(newGroupContent = "") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 远端分类或本地分组保存失败保留输入，避免误报创建成功。
            showOperationMessage(error.message ?: error.toString())
        }
    }
}
