package me.ash.reader.ui.page.home.feeds.subscribe

import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.domain.service.SubscriptionRequest
import me.ash.reader.domain.service.SubscriptionResult
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionDescriptor
import me.ash.reader.infrastructure.source.SourceCandidateKind

/** 每个来源保留真实提交结果，失败的候选仍可独立重试。 */
private data class SubscriptionItemResult(
    val candidate: SubscribeSourceCandidate,
    val result: SubscriptionResult? = null,
    val error: Exception? = null,
)

/** 固定配置快照提交；同一按钮重复点击仍由现有加载状态拦截。 */
internal fun SubscribeViewModel.submitSelectedSources() {
    val state = _subscribeState.value as? SubscribeState.Configure ?: return
    if (_subscribeUiState.value.isSubscribing) return
    _subscribeUiState.update { it.copy(isSubscribing = true, operationMessage = null) }
    accountOperationScope.launch {
        try {
            validateSubmissionAccount(state)
            val selected = state.candidates.filter { it.id in state.selectedCandidateIds }
            require(selected.isNotEmpty()) { "未选择订阅来源" }
            require(selected.size == 1 || selected.all { it.kind == SourceCandidateKind.RSSHUB }) { "只有 RSSHub 路由支持多选" }
            val results = selected.map { submitCandidate(state, it) }
            currentCoroutineContext().ensureActive()
            finishSubmission(state, results)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 账户或整体配置错误保留当前候选，用户可以核对后重新提交。
            showOperationMessage(error.message ?: error.toString())
        } finally {
            _subscribeUiState.update { it.copy(isSubscribing = false) }
        }
    }
}

/** 每一项开始前复核探测账户和选中分组，实际数据库提交还会校验所属账户。 */
private fun SubscribeViewModel.validateSubmissionAccount(state: SubscribeState.Configure) {
    require(state.accountId == accountService.getCurrentAccount().id) { "账户已切换，请重新探测订阅来源" }
    require(state.groups.any { it.id == state.selectedGroupId && it.accountId == state.accountId }) { "订阅分组已失效" }
}

/** 普通业务失败汇总到这一项；取消必须终止后续请求和旧状态发布。 */
private suspend fun SubscribeViewModel.submitCandidate(
    state: SubscribeState.Configure, candidate: SubscribeSourceCandidate,
): SubscriptionItemResult {
    return try {
        currentCoroutineContext().ensureActive()
        validateSubmissionAccount(state)
        val result = rssService.subscribe(candidate.subscriptionRequest(state))
        SubscriptionItemResult(candidate, result)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // 请求、远端部分提交和写盘失败都保留具体来源及原始异常，不能转成跳过或成功。
        currentCoroutineContext().ensureActive()
        SubscriptionItemResult(candidate, error = error)
    }
}

/** 成功规则、HTTP 缓存与 RSSHub 实例约束作为同一提交请求传入业务层。 */
private fun SubscribeSourceCandidate.subscriptionRequest(state: SubscribeState.Configure) = SubscriptionRequest(
    accountId = requireNotNull(state.accountId), groupId = state.selectedGroupId,
    feedLink = feedLink, searchedFeed = feed, sourceType = sourceType,
    notification = state.notification, fullContent = state.fullContent, browser = state.browser,
    dynamicRendering = dynamicRendering, sourcePageUrl = state.sourcePageUrl,
    etag = etag, lastModified = lastModified, jsonProbe = jsonProbe,
    rssHub = if (kind == SourceCandidateKind.RSSHUB) RssHubSubscriptionDescriptor(
        originalInput = state.sourcePageUrl, routePath = rssHubRoutePath,
        preferredInstance = rssHubPreferredInstanceBaseUrl,
        lastResolvedInstance = rssHubInstanceBaseUrl, lastResolvedUrl = feedLink,
    ) else null,
)

/** 汇总新增、已存在和失败；部分成功时仅保留失败项的选择，重复点击不会再提交成功项。 */
private fun SubscribeViewModel.finishSubmission(state: SubscribeState.Configure, results: List<SubscriptionItemResult>) {
    val failed = results.filter { it.error != null }
    val summary = androidStringsHelper.getString(
        R.string.subscription_batch_result, results.count { it.result?.created == true },
        results.count { it.result?.created == false }, failed.size,
    )
    val details = failed.joinToString("\n") { "${it.candidate.feed.title}: ${it.error?.message}" }
    val message = listOf(summary, details).filter(String::isNotBlank).joinToString("\n")
    showOperationMessage(message)
    val current = _subscribeState.value as? SubscribeState.Configure ?: return
    if (current.accountId != state.accountId || current.sourcePageUrl != state.sourcePageUrl) return
    if (failed.isEmpty()) {
        hideDrawer()
        return
    }
    val primary = failed.first().candidate
    _subscribeState.value = current.copy(
        selectedCandidateIds = failed.mapTo(linkedSetOf()) { it.candidate.id },
        selectedCandidateId = primary.id, searchedFeed = primary.feed, feedLink = primary.feedLink,
        submissionMessage = message,
    )
}

/** 导入也固定入口账户，文件流在任务结束或取消时关闭，异常展示在现有消息出口。 */
internal fun SubscribeViewModel.importSubscriptionFile(openStream: () -> InputStream) {
    val accountId = requireNotNull(accountService.getCurrentAccount().id)
    accountOperationScope.launch {
        try {
            val result = openStream().use { opmlService.saveToDatabase(it, accountId) }
            rssService.forAccount(accountId).doSyncOneTime(accountId = accountId)
            showOperationMessage(androidStringsHelper.getString(
                R.string.opml_import_result, result.importedCount, result.duplicateCount,
            ))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 文件打开、OPML 格式和数据库错误统一显示，不能把半批次当作导入成功。
            showOperationMessage(androidStringsHelper.getString(R.string.opml_import_failed, error.message ?: error.toString()))
        }
    }
}

/** 操作反馈持续保存在 UI 状态中，关闭底部面板后仍可显示完成或失败消息。 */
internal fun SubscribeViewModel.showOperationMessage(message: String) {
    _subscribeUiState.update { it.copy(operationMessage = message) }
}
