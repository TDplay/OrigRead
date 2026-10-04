package me.ash.reader.ui.page.home.feeds.drawer.feed

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.R
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.service.rebindJsonSource
import me.ash.reader.infrastructure.json.JsonRebindRequest
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.json.JsonSourceProbeResult
import me.ash.reader.ui.ext.formatUrl

/** 修复预览独立于已保存绑定，关闭预览不会修改来源配置。 */
data class JsonSourceRepairState(
    val sourcePageUrl: String = "", val isLoading: Boolean = false, val isSaving: Boolean = false,
    val error: String? = null, val candidates: List<JsonSourceProbeResult> = emptyList(),
    val selectedRuleId: String? = null,
)

/** 打开已有来源的原地修复入口，跨域 API 使用绑定保存的原始页面探测。 */
internal fun FeedOptionViewModel.performShowJsonParserDialog() {
    val feed = _feedOptionUiState.value.feed?.takeIf { it.sourceType == SourceType.JSON } ?: return
    invalidateJsonProbe()
    val page = jsonRuleRepository.subscriptionPageUrl(feed.id, feed.url)
    _feedOptionUiState.update { it.copy(jsonParserDialogVisible = true,
        jsonConfiguredRules = jsonRuleRepository.findConfiguredRules(page), jsonRepair = JsonSourceRepairState(page)) }
}

/** 取消预览使后台候选失效；确认后的配置提交由原地修复事务完成。 */
internal fun FeedOptionViewModel.performHideJsonParserDialog() {
    if (_feedOptionUiState.value.jsonRepair.isSaving) return
    invalidateJsonProbe()
    _feedOptionUiState.update { it.copy(jsonParserDialogVisible = false, jsonRepair = JsonSourceRepairState()) }
}

/** 输入改变就清除旧候选，不能把其他页面的成功探测作为确认结果。 */
internal fun FeedOptionViewModel.performInputJsonSourceUrl(url: String) {
    if (_feedOptionUiState.value.jsonRepair.isSaving) return
    invalidateJsonProbe()
    _feedOptionUiState.update { it.copy(jsonRepair = JsonSourceRepairState(url)) }
}

/** 启停仍作用于规则自身；原绑定的选择只通过探测后的确认入口替换。 */
internal fun FeedOptionViewModel.performSetJsonRuleEnabled(rule: JsonRule, enabled: Boolean) {
    if (_feedOptionUiState.value.jsonRepair.isSaving) return
    jsonRuleRepository.setEnabled(rule.id, enabled)
    val feed = _feedOptionUiState.value.feed ?: return
    val page = _feedOptionUiState.value.jsonRepair.sourcePageUrl
    performInputJsonSourceUrl(page)
    _feedOptionUiState.update { it.copy(jsonConfiguredRules = jsonRuleRepository.findConfiguredRules(page)) }
    applicationScope.launch(ioDispatcher) {
        rssService.forAccount(feed.accountId).doSyncOneTime(accountId = feed.accountId, feedId = feed.id)
    }
}

/** 网络探测只生成预览，不写绑定；失败和歧义由原设置面板明确展示。 */
internal fun FeedOptionViewModel.performProbeJsonSource() {
    val feed = _feedOptionUiState.value.feed ?: return
    if (_feedOptionUiState.value.jsonRepair.isLoading) return
    val page = _feedOptionUiState.value.jsonRepair.sourcePageUrl.trim().formatUrl()
    invalidateJsonProbe()
    val serial = jsonRepairSerial
    _feedOptionUiState.update { it.copy(jsonRepair = JsonSourceRepairState(page, isLoading = true)) }
    jsonRepairJob = viewModelScope.launch {
        try {
            val candidates = jsonSourceHelper.probeCandidates(page)
            if (!isCurrentJsonProbe(feed.id, serial)) return@launch
            val error = if (candidates.isEmpty()) androidStringsHelper.getString(R.string.json_repair_no_candidates) else null
            _feedOptionUiState.update { it.copy(jsonRepair = it.jsonRepair.copy(isLoading = false,
                error = error, candidates = candidates)) }
        } catch (error: CancellationException) {
            // 关闭、换来源或修改输入取消旧探测，不把取消展示成解析失败。
            throw error
        } catch (error: Exception) {
            // 网络和规则错误只发布到仍然有效的来源预览。
            publishJsonRepairError(feed.id, serial, error)
        }
    }
}

/** 多个候选必须由用户选择，普通重复订阅继续跳过已有来源。 */
internal fun FeedOptionViewModel.performSelectJsonProbe(ruleId: String) {
    val state = _feedOptionUiState.value.jsonRepair
    if (state.isLoading || state.candidates.none { it.rule.id == ruleId }) return
    _feedOptionUiState.update { it.copy(jsonRepair = it.jsonRepair.copy(selectedRuleId = ruleId)) }
}

/** 确认后沿用原 feedId，只替换规则和请求地址，并按固定账户刷新。 */
internal fun FeedOptionViewModel.performConfirmJsonRepair() {
    val feed = _feedOptionUiState.value.feed ?: return
    val state = _feedOptionUiState.value.jsonRepair
    if (state.isLoading) return
    val probe = state.candidates.firstOrNull { it.rule.id == state.selectedRuleId } ?: return
    val serial = jsonRepairSerial
    _feedOptionUiState.update { it.copy(jsonRepair = it.jsonRepair.copy(isLoading = true, isSaving = true, error = null)) }
    jsonRepairJob = viewModelScope.launch {
        try {
            val configured = withContext(ioDispatcher) {
                localRssService.rebindJsonSource(JsonRebindRequest(feed, state.sourcePageUrl, probe))
                jsonRuleRepository.findConfiguredRules(state.sourcePageUrl)
            }
            if (!isCurrentJsonProbe(feed.id, serial)) return@launch
            _feedOptionUiState.update { it.copy(feed = it.feed?.copy(url = probe.endpointUrl),
                jsonConfiguredRules = configured, jsonParserDialogVisible = false, jsonRepair = JsonSourceRepairState()) }
            rssService.forAccount(feed.accountId).doSyncOneTime(accountId = feed.accountId, feedId = feed.id)
        } catch (error: CancellationException) {
            // ViewModel 清理不发布结果；已确认的短提交区保证规则与地址同时完成。
            throw error
        } catch (error: Exception) {
            // 持久化错误保留候选和原绑定，用户可以看到失败原因。
            publishJsonRepairError(feed.id, serial, error)
        }
    }
}

/** UI 错误只能归属仍打开的来源和当前探测代次。 */
private fun FeedOptionViewModel.publishJsonRepairError(feedId: String, serial: Long, error: Exception) {
    if (!isCurrentJsonProbe(feedId, serial)) return
    _feedOptionUiState.update { it.copy(jsonRepair = it.jsonRepair.copy(isLoading = false, isSaving = false,
        error = error.message ?: error.toString())) }
}

/** 关闭面板或换来源后，任何旧候选和错误都无法覆盖新设置。 */
private fun FeedOptionViewModel.isCurrentJsonProbe(feedId: String, serial: Long): Boolean =
    jsonRepairSerial == serial && _feedOptionUiState.value.feed?.id == feedId &&
        _feedOptionUiState.value.jsonParserDialogVisible

/** 清理探测任务句柄并推进代次，实际保存绑定不会在预览阶段执行。 */
private fun FeedOptionViewModel.invalidateJsonProbe() {
    jsonRepairSerial++
    jsonRepairJob?.cancel()
    jsonRepairJob = null
}

/** 切换设置对象可以离开已确认的保存任务；结果不得写入另一个来源面板。 */
internal fun FeedOptionViewModel.resetJsonRepairForFeedSwitch() {
    invalidateJsonProbe()
    _feedOptionUiState.update { it.copy(jsonParserDialogVisible = false, jsonRepair = JsonSourceRepairState()) }
}
