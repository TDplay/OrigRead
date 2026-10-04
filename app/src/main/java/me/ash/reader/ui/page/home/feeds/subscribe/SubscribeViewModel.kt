package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rometools.rome.feed.synd.SyndFeed
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.ash.reader.R
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.OpmlService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.discovery.FeedCatalogEntry
import me.ash.reader.infrastructure.discovery.FeedCatalogUrlMatch
import me.ash.reader.infrastructure.discovery.FeedDiscoveryCatalog
import me.ash.reader.infrastructure.json.JsonSourceHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rsshub.RssHubInputParser
import me.ash.reader.infrastructure.rsshub.RssHubResolver
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.infrastructure.source.SourceCandidateKind
import me.ash.reader.infrastructure.source.SourceInputHint
import me.ash.reader.infrastructure.source.sourceInputHint
import me.ash.reader.infrastructure.website.WebsiteHelper
import me.ash.reader.ui.ext.formatUrl

@HiltViewModel
class SubscribeViewModel
@Inject
constructor(
    internal val opmlService: OpmlService,
    val rssService: RssService,
    internal val rssHelper: RssHelper,
    internal val rssHubResolver: RssHubResolver,
    internal val rssHubSettingsRepository: RssHubSettingsRepository,
    internal val websiteHelper: WebsiteHelper,
    internal val jsonSourceHelper: JsonSourceHelper,
    internal val feedDiscoveryCatalog: FeedDiscoveryCatalog,
    internal val androidStringsHelper: AndroidStringsHelper,
    @ApplicationScope internal val applicationScope: CoroutineScope,
    internal val accountService: AccountService,
) : ViewModel() {

    /** 防止 Idle 状态到 Fetching 状态之间的极短窗口被连续点击启动两套发现链。 */
    internal var searchCoordinatorJob: Job? = null
    /** 所有订阅、分组和导入任务共享账户生命周期，切账户时完整取消在途操作。 */
    internal val accountOperationScope = CoroutineScope(
        applicationScope.coroutineContext + SupervisorJob(applicationScope.coroutineContext[Job]),
    )
    private var observedAccountId: Int? = null

    internal val _subscribeUiState = MutableStateFlow(SubscribeUiState())
    val subscribeUiState: StateFlow<SubscribeUiState> = _subscribeUiState.asStateFlow()

    internal val _subscribeState: MutableStateFlow<SubscribeState> =
        MutableStateFlow(SubscribeState.Hidden)
    val subscribeState = _subscribeState.asStateFlow()

    val groupsFlow = MutableStateFlow<List<Group>>(emptyList())

    init {
        viewModelScope.launch {
            accountService.currentAccountFlow.collectLatest { account ->
                val accountId = account?.id
                if (observedAccountId != null && observedAccountId != accountId) invalidateAccountDiscovery()
                observedAccountId = accountId
                rssService.get().pullGroups().collect { groupsFlow.value = it }
            }
        }
        viewModelScope.launch {
            groupsFlow.collect { groups ->
                _subscribeState.update {
                    when (it) {
                        is SubscribeState.Configure -> it.copy(
                            groups = groups.filter { group -> group.accountId == it.accountId },
                        )
                        else -> it
                    }
                }
            }
        }
    }

    fun reset() {
        cancelSearch()
    }

    /** 导入任务固定账户并向现有消息出口报告结果。 */
    fun importFromInputStream(inputStream: InputStream) = importSubscriptionFile { inputStream }

    /** 文件选择后的打开错误也进入同一个导入反馈出口。 */
    fun importFromProvider(openStream: () -> InputStream) = importSubscriptionFile(openStream)

    fun selectedGroup(groupId: String) {
        _subscribeState.update {
            when (it) {
                is SubscribeState.Configure -> it.copy(selectedGroupId = groupId)
                else -> it
            }
        }
    }

    /** 分组创建沿用候选账户，失败通过现有操作消息反馈。 */
    fun addNewGroup() = createSubscriptionGroup()

    fun toggleParseFullContentPreset() {
        _subscribeState.update { state ->
            when (state) {
                is SubscribeState.Configure ->
                    state.copy(fullContent = !state.fullContent, browser = false)

                else -> state
            }
        }
    }

    fun toggleOpenInBrowserPreset() {
        _subscribeState.update { state ->
            when (state) {
                is SubscribeState.Configure ->
                    state.copy(browser = !state.browser, fullContent = false)

                else -> state
            }
        }
    }

    fun toggleAllowNotificationPreset() {
        _subscribeState.update { state ->
            when (state) {
                is SubscribeState.Configure -> state.copy(notification = !state.notification)
                else -> state
            }
        }
    }

    /** 启动固定账户的发现链，具体网络阶段按职责拆分。 */
    fun searchFeed() = startSourceSearch()

    /** 更新统一来源探测的当前阶段。 */
    internal fun updateSearchStage(stage: SearchStage) {
        _subscribeState.update { state ->
            if (state is SubscribeState.Fetching) state.copy(stage = stage) else state
        }
    }

    fun cancelSearch() {
        searchCoordinatorJob?.takeIf(Job::isActive)?.cancel()
        _subscribeState.value.let {
            if (it is SubscribeState.Fetching && it.job.isActive) {
                it.job.cancel()
            }
        }
    }

    /** RSSHub 路由已命中但实例暂时不可用时，可从配置页重新执行完整来源发现。 */
    fun retrySourceDiscovery() {
        val state = _subscribeState.value as? SubscribeState.Configure ?: return
        _subscribeState.value = SubscribeState.Idle(linkState = TextFieldState(state.sourcePageUrl))
        searchFeed()
    }

    /** 逐项保存所选来源，失败项保留以便重试。 */
    fun subscribe() = submitSelectedSources()

    /** 已显示的操作消息可清除，以便下一次相同错误再次触发反馈。 */
    fun consumeOperationMessage() {
        _subscribeUiState.update { it.copy(operationMessage = null) }
    }

    fun inputNewGroup(content: String) {
        _subscribeUiState.update { it.copy(newGroupContent = content) }
    }

    fun handleSharedUrlFromIntent(url: String) {
        openFeedUrl(url)
    }

    /** 从内置来源目录进入时复用现有发现、重复校验与订阅配置流程。 */
    fun openFeedFromCatalog(url: String) {
        openFeedUrl(url)
    }

    private fun openFeedUrl(url: String) {
        viewModelScope
            .launch {
                _subscribeState.update { SubscribeState.Idle(linkState = TextFieldState(url)) }
                delay(50)
            }
            .invokeOnCompletion { searchFeed() }
    }

    fun showDrawer() {
        _subscribeState.value =
            SubscribeState.Idle(importFromOpmlEnabled = rssService.get().importSubscription)
    }

    fun hideDrawer() {
        cancelSearch()
        _subscribeState.value = SubscribeState.Hidden
    }

    fun showNewGroupDialog() {
        _subscribeUiState.update { it.copy(newGroupDialogVisible = true) }
    }

    fun hideNewGroupDialog() {
        _subscribeUiState.update { it.copy(newGroupDialogVisible = false) }
    }

    fun showRenameDialog() {
        _subscribeUiState.update { it.copy(renameDialogVisible = true) }
        _subscribeUiState.update { uiState ->
            (_subscribeState.value as? SubscribeState.Configure)?.searchedFeed?.title?.let { title
                ->
                uiState.copy(newName = title)
            } ?: uiState
        }
    }

    fun hideRenameDialog() {
        _subscribeUiState.update { it.copy(renameDialogVisible = false, newName = "") }
    }

    fun inputNewName(content: String) {
        _subscribeUiState.update { it.copy(newName = content) }
    }

    fun renameFeed() {
        _subscribeState.update { state ->
            when (state) {
                is SubscribeState.Configure ->
                    state.copy(
                        searchedFeed =
                            state.searchedFeed.apply { title = _subscribeUiState.value.newName }
                    )

                else -> state
            }
        }
    }
    /** 切换账户后废弃旧候选和分组，不能把 A 的预览提交到 B。 */
    private fun invalidateAccountDiscovery() {
        cancelSearch()
        accountOperationScope.coroutineContext.cancelChildren()
        _subscribeState.value = SubscribeState.Hidden
        groupsFlow.value = emptyList()
        showOperationMessage(androidStringsHelper.getString(R.string.subscription_account_changed))
    }

    /** ViewModel 销毁后不再观察账户变化，因此结束它拥有的全部来源操作。 */
    override fun onCleared() {
        accountOperationScope.cancel()
        super.onCleared()
    }
}
