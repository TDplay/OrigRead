package me.ash.reader.ui.page.home.feeds.drawer.feed

import androidx.compose.material.ExperimentalMaterialApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.service.LocalRssService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.filter.ArticleFilterRule
import me.ash.reader.infrastructure.filter.ArticleFilterRuleType
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.website.WebsiteHelper
import me.ash.reader.infrastructure.website.WebsiteRule

@OptIn(ExperimentalMaterialApi::class)
@HiltViewModel
class FeedOptionViewModel
@Inject
constructor(
    val rssService: RssService,
    internal val localRssService: LocalRssService,
    @MainDispatcher internal val mainDispatcher: CoroutineDispatcher,
    @IODispatcher internal val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope internal val applicationScope: CoroutineScope,
    internal val rssHelper: RssHelper,
    internal val articleDao: ArticleDao,
    internal val readerCacheHelper: ReaderCacheHelper,
    internal val feedDao: FeedDao,
    internal val websiteHelper: WebsiteHelper,
    internal val articleFilterRepository: ArticleFilterRepository,
    internal val jsonRuleRepository: JsonRuleRepository,
    internal val jsonSourceHelper: me.ash.reader.infrastructure.json.JsonSourceHelper,
    internal val androidStringsHelper: AndroidStringsHelper,
) : ViewModel() {

    internal val _feedOptionUiState = MutableStateFlow(FeedOptionUiState())
    val feedOptionUiState: StateFlow<FeedOptionUiState> = _feedOptionUiState.asStateFlow()

    internal var jsonRepairJob: kotlinx.coroutines.Job? = null
    internal var jsonRepairSerial = 0L

    init {
        viewModelScope.launch(ioDispatcher) {
            rssService.flow().collectLatest {
                it.pullGroups().collectLatest { groups ->
                    _feedOptionUiState.update { it.copy(groups = groups) }
                }
            }
        }
    }

    fun showSourceFilterDialog() = performShowSourceFilterDialog()

    fun hideSourceFilterDialog() = performHideSourceFilterDialog()

    fun addSourceFilter(pattern: String, type: ArticleFilterRuleType): Boolean = performAddSourceFilter(pattern, type)

    fun setSourceFilterEnabled(rule: ArticleFilterRule, enabled: Boolean) = performSetSourceFilterEnabled(rule, enabled)

    fun deleteSourceFilter(rule: ArticleFilterRule) = performDeleteSourceFilter(rule)

    fun hideWebsiteParserDialog() = performHideWebsiteParserDialog()

    fun hideJsonParserDialog() = performHideJsonParserDialog()

    suspend fun fetchFeed(feedId: String) = performFetchFeed(feedId)

    fun showWebsiteParserDialog() = performShowWebsiteParserDialog()

    fun selectWebsiteRule(ruleId: String?) = performSelectWebsiteRule(ruleId)

    fun showJsonParserDialog() = performShowJsonParserDialog()

    fun setJsonRuleEnabled(rule: JsonRule, enabled: Boolean) = performSetJsonRuleEnabled(rule, enabled)

    fun reparseWebsiteArticles(
        callback: (Result<me.ash.reader.domain.service.WebsiteReparseResult>) -> Unit = {},
    ) = performReparseWebsiteArticles(callback)

    /** 显式探测和确认已有 JSON 来源，普通重复订阅不触发绑定替换。 */
    fun probeJsonSource() = performProbeJsonSource()
    fun inputJsonSourceUrl(url: String) = performInputJsonSourceUrl(url)
    fun selectJsonProbe(ruleId: String) = performSelectJsonProbe(ruleId)
    fun confirmJsonRepair() = performConfirmJsonRepair()

    fun showNewGroupDialog() {
        _feedOptionUiState.update { it.copy(newGroupDialogVisible = true, newGroupContent = "") }
    }

    fun hideNewGroupDialog() {
        _feedOptionUiState.update { it.copy(newGroupDialogVisible = false, newGroupContent = "") }
    }

    fun inputNewGroup(content: String) {
        _feedOptionUiState.update { it.copy(newGroupContent = content) }
    }

    fun addNewGroup() {
        if (_feedOptionUiState.value.newGroupContent.isNotBlank()) {
            applicationScope.launch {
                selectedGroup(
                    rssService
                        .get()
                        .addGroup(
                            destFeed = _feedOptionUiState.value.feed,
                            newGroupName = _feedOptionUiState.value.newGroupContent,
                        )
                )
                hideNewGroupDialog()
            }
        }
    }

    fun selectedGroup(groupId: String) {
        applicationScope.launch(ioDispatcher) {
            _feedOptionUiState.value.feed?.let {
                rssService
                    .get()
                    .moveFeed(originGroupId = it.groupId, feed = it.copy(groupId = groupId))
                fetchFeed(it.id)
            }
        }
    }

    fun changeParseFullContentPreset() {
        viewModelScope.launch(ioDispatcher) {
            _feedOptionUiState.value.feed?.let {
                val isFullContent = !it.isFullContent
                val isBrowser = if (isFullContent) false else it.isBrowser
                rssService
                    .get()
                    .updateFeed(it.copy(isFullContent = isFullContent, isBrowser = isBrowser))
                fetchFeed(it.id)
            }
        }
    }

    fun changeOpenInBrowserPreset() {
        viewModelScope.launch(ioDispatcher) {
            _feedOptionUiState.value.feed?.let {
                val isBrowser = !it.isBrowser
                val isFullContent = if (isBrowser) false else it.isFullContent
                rssService
                    .get()
                    .updateFeed(it.copy(isBrowser = isBrowser, isFullContent = isFullContent))
                fetchFeed(it.id)
            }
        }
    }

    fun changeAllowNotificationPreset() {
        viewModelScope.launch(ioDispatcher) {
            _feedOptionUiState.value.feed?.let {
                rssService.get().updateFeed(it.copy(isNotification = !it.isNotification))
                fetchFeed(it.id)
            }
        }
    }

    fun delete(callback: () -> Unit = {}) {
        _feedOptionUiState.value.feed?.let {
            applicationScope.launch(ioDispatcher) {
                articleFilterRepository.deleteByFeed(it.id)
                rssService.get().deleteFeed(it)
                withContext(mainDispatcher) { callback() }
            }
        }
    }

    fun hideDeleteDialog() {
        _feedOptionUiState.update { it.copy(deleteDialogVisible = false) }
    }

    fun showDeleteDialog() {
        _feedOptionUiState.update { it.copy(deleteDialogVisible = true) }
    }

    fun showClearDialog() {
        _feedOptionUiState.update { it.copy(clearDialogVisible = true) }
    }

    fun hideClearDialog() {
        _feedOptionUiState.update { it.copy(clearDialogVisible = false) }
    }

    fun clearFeed(callback: () -> Unit = {}) {
        _feedOptionUiState.value.feed?.let {
            viewModelScope.launch(ioDispatcher) {
                rssService.get().deleteArticles(feed = it)
                withContext(mainDispatcher) { callback() }
            }
        }
    }

    fun renameFeed() {
        _feedOptionUiState.value.feed?.let {
            applicationScope.launch {
                rssService.get().renameFeed(it.copy(name = _feedOptionUiState.value.newName))
                _feedOptionUiState.update { it.copy(renameDialogVisible = false) }
            }
        }
    }

    fun showRenameDialog() {
        _feedOptionUiState.update {
            it.copy(renameDialogVisible = true, newName = _feedOptionUiState.value.feed?.name ?: "")
        }
    }

    fun hideRenameDialog() {
        _feedOptionUiState.update { it.copy(renameDialogVisible = false, newName = "") }
    }

    fun inputNewName(content: String) {
        _feedOptionUiState.update { it.copy(newName = content) }
    }

    fun showFeedUrlDialog() {
        _feedOptionUiState.update {
            it.copy(
                changeUrlDialogVisible = true,
                newUrl = _feedOptionUiState.value.feed?.url ?: "",
            )
        }
    }

    fun hideFeedUrlDialog() {
        _feedOptionUiState.update { it.copy(changeUrlDialogVisible = false, newUrl = "") }
    }

    fun inputNewUrl(content: String) {
        _feedOptionUiState.update { it.copy(newUrl = content) }
    }

    fun changeFeedUrl() {
        _feedOptionUiState.value.feed?.let {
            applicationScope.launch {
                rssService.get().changeFeedUrl(it.copy(url = _feedOptionUiState.value.newUrl))
                _feedOptionUiState.update { it.copy(changeUrlDialogVisible = false) }
            }
        }
    }

    fun reloadIcon() {
        _feedOptionUiState.value.feed?.let { feed ->
            viewModelScope.launch(ioDispatcher) {
                val icon = rssHelper.queryRssIconLink(feed.url) ?: return@launch
                feedDao.update(feed.copy(icon = icon))
                fetchFeed(feed.id)
            }
        }
    }
}
