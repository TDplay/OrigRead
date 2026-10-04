package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import me.ash.reader.R
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.infrastructure.discovery.FeedCatalogUrlMatch
import me.ash.reader.infrastructure.rsshub.RssHubExplicitRoute
import me.ash.reader.infrastructure.rsshub.RssHubInputParser
import me.ash.reader.infrastructure.source.SourceInputHint
import me.ash.reader.infrastructure.source.sourceInputHint
import me.ash.reader.ui.ext.formatUrl

/** 一次探测的固定上下文；所有网络阶段都使用开始时的账户和输入。 */
internal data class SubscribeDiscoveryContext(
    val account: Account,
    val idle: SubscribeState.Idle,
    val feedLink: String,
    val firstGroupId: String,
    val catalog: FeedCatalogUrlMatch,
    val explicitRoute: RssHubExplicitRoute?,
) {
    val accountId: Int get() = requireNotNull(account.id)
    val isLocal: Boolean get() = account.type.id == AccountType.Local.id
    val hint: SourceInputHint get() = sourceInputHint(feedLink)
}

/** 入口仍只允许启动一条发现链；账户切换会取消整个父任务。 */
internal fun SubscribeViewModel.startSourceSearch() {
    if (searchCoordinatorJob?.isActive == true) return
    val idle = _subscribeState.value as? SubscribeState.Idle ?: return
    searchCoordinatorJob = viewModelScope.launch {
        try {
            val options = prepareDiscovery(idle) ?: return@launch
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) { executeDiscovery(options) }
            _subscribeState.value = SubscribeState.Fetching(options.idle.linkState, job, initialSearchStage(options))
            job.start()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 输入、账户或目录读取失败保留原输入并明确展示错误。
            _subscribeState.value = idle.copy(errorMessage = error.message ?: error.toString())
        }
    }
}

/** 预处理只查用户实际输入地址，目录建议不替代订阅查重或真实解析。 */
private suspend fun SubscribeViewModel.prepareDiscovery(idle: SubscribeState.Idle): SubscribeDiscoveryContext? {
    val account = accountService.getCurrentAccount()
    val rawInput = idle.linkState.text.trim().toString()
    val instances = rssHubSettingsRepository.current().instances.map { it.url }
    val explicit = RssHubInputParser.parseExplicit(rawInput, instances)
    if (rawInput.startsWith("rsshub:", ignoreCase = true) && explicit == null) {
        _subscribeState.value = idle.copy(errorMessage = androidStringsHelper.getString(R.string.rsshub_invalid_input_notice))
        return null
    }
    val link = explicit?.preferredInstance?.let { RssHubInputParser.buildFeedUrl(it, explicit.routePath) }
        ?: if (explicit != null) rawInput else rawInput.formatUrl()
    idle.linkState.edit { replace(0, length, link) }
    val catalog = if (explicit == null) optionalCatalogMatch(link) else FeedCatalogUrlMatch()
    val preparedIdle = idle.copy(catalogMatches = catalog.suggestions, catalogMatchCount = catalog.totalSuggestions)
    if (rssService.get().isFeedExist(link)) {
        _subscribeState.value = preparedIdle.copy(errorMessage = androidStringsHelper.getString(R.string.already_subscribed))
        return null
    }
    val firstGroup = groupsFlow.value.firstOrNull { it.accountId == account.id }
        ?: error("当前账户没有可用订阅分组")
    return SubscribeDiscoveryContext(account, preparedIdle, link, firstGroup.id, catalog, explicit)
}

/** 目录本来是可选辅助知识；错误写日志后沿用现有来源发现链。 */
private fun SubscribeViewModel.optionalCatalogMatch(link: String): FeedCatalogUrlMatch =
    try {
        feedDiscoveryCatalog.matchUrl(link)
    } catch (error: Exception) {
        timber.log.Timber.w(error, "来源目录匹配失败")
        FeedCatalogUrlMatch()
    }

/** UI 显示与实际首阶段一致，URL Hint 只影响尝试顺序。 */
private fun initialSearchStage(options: SubscribeDiscoveryContext): SearchStage = when {
    options.explicitRoute != null -> SearchStage.CHECKING_RSSHUB
    options.isLocal && options.hint == SourceInputHint.JSON_LIKELY -> SearchStage.CHECKING_JSON
    else -> SearchStage.CHECKING_RSS
}

/** 发布候选前再次核对账户；取消后的旧候选不能覆盖新账户页面。 */
private suspend fun SubscribeViewModel.executeDiscovery(options: SubscribeDiscoveryContext) {
    try {
        val result = discoverSources(options)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (accountService.getCurrentAccount().id != options.accountId) throw CancellationException("探测账户已切换")
        applyDiscoveryResult(options, result)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // 一次发现链失败留在输入页，原始错误不转成成功候选。
        _subscribeState.value = options.idle.copy(errorMessage = error.message ?: error.toString())
    }
}
