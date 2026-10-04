package me.ash.reader.ui.page.home.feeds.drawer.feed

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.R
import me.ash.reader.infrastructure.website.WebsitePageTooComplexException

/** hideWebsiteParserDialog：处理当前来源设置。 */
internal fun FeedOptionViewModel.performHideWebsiteParserDialog() {
    _feedOptionUiState.update { it.copy(websiteParserDialogVisible = false) }
}

/** 在当前来源设置抽屉内展示加载状态并后台评估候选，避免创建独立 Dialog Window。 */
internal fun FeedOptionViewModel.performShowWebsiteParserDialog() {
    val feed = _feedOptionUiState.value.feed ?: return
    if (feed.sourceType != SourceType.WEBSITE) return
    _feedOptionUiState.update {
        it.copy(
            websiteParserDialogVisible = true,
            websiteParserLoading = true,
            websiteParserError = null,
            websiteCandidates = emptyList(),
            websiteConfiguredRules = websiteHelper.getConfiguredRules(feed.url),
        )
    }
    viewModelScope.launch(ioDispatcher) {
        val result = runCatching { websiteHelper.evaluateCandidates(feed) }
        withContext(mainDispatcher) {
            val error = result.exceptionOrNull()
            _feedOptionUiState.update {
                it.copy(
                    websiteParserLoading = false,
                    websiteCandidates = result.getOrDefault(emptyList()),
                    websiteParserError =
                        if (error is WebsitePageTooComplexException) {
                            androidStringsHelper.getString(R.string.website_parser_page_too_complex)
                        } else {
                            error?.message
                        },
                )
            }
        }
    }
}

/** 保存来源级规则偏好并触发同步；null 表示恢复自动选择。 */
internal fun FeedOptionViewModel.performSelectWebsiteRule(ruleId: String?) {
    val feed = _feedOptionUiState.value.feed ?: return
    val selectedRuleName =
        ruleId?.let { selectedId ->
            _feedOptionUiState.value.websiteCandidates
                .firstOrNull { it.rule.id == selectedId }
                ?.rule
                ?.name
                ?: _feedOptionUiState.value.websiteConfiguredRules
                    .firstOrNull { it.id == selectedId }
                    ?.name
                ?: websiteHelper.getRuleName(selectedId)
        }
    websiteHelper.setPreferredRule(feed.id, ruleId, selectedRuleName)
    _feedOptionUiState.update {
        it.copy(
            preferredWebsiteRuleId = ruleId,
            preferredWebsiteRuleName = selectedRuleName,
            websiteConfiguredRules = websiteHelper.getConfiguredRules(feed.url),
            websiteParserDialogVisible = false,
        )
    }
    applicationScope.launch(ioDispatcher) {
        rssService.get().doSyncOneTime()
    }
}

/** 更新已有网站文章的列表元数据，并清除正文缓存，让下次打开文章重新抓取。 */
internal fun FeedOptionViewModel.performReparseWebsiteArticles(
    callback: (Result<me.ash.reader.domain.service.WebsiteReparseResult>) -> Unit = {},
) {
    val feed = _feedOptionUiState.value.feed?.takeIf { it.sourceType == SourceType.WEBSITE } ?: return
    if (_feedOptionUiState.value.websiteReparseLoading) return
    _feedOptionUiState.update { it.copy(websiteReparseLoading = true) }
    viewModelScope.launch(ioDispatcher) {
        val result = runCatching {
            val existingArticles = articleDao.queryAllByFeedId(feed.accountId, feed.id)
            val reparseResult = localRssService.reparseWebsiteArticles(feed.id)
            existingArticles.forEach { article -> readerCacheHelper.deleteCacheFor(article.id, article.accountId) }
            reparseResult
        }
        withContext(mainDispatcher) {
            _feedOptionUiState.update { it.copy(websiteReparseLoading = false) }
            callback(result)
        }
    }
}
