package me.ash.reader.ui.page.home.feeds.drawer.feed

import kotlinx.coroutines.flow.update
import me.ash.reader.infrastructure.filter.ArticleFilterRule
import me.ash.reader.infrastructure.filter.ArticleFilterRuleType

/** showSourceFilterDialog：处理当前来源设置。 */
internal fun FeedOptionViewModel.performShowSourceFilterDialog() {
    val feed = _feedOptionUiState.value.feed ?: return
    _feedOptionUiState.update {
        it.copy(
            sourceFilterDialogVisible = true,
            sourceFilterRules = articleFilterRepository.getByFeed(feed.id),
            sourceFilterError = null,
        )
    }
}

/** hideSourceFilterDialog：处理当前来源设置。 */
internal fun FeedOptionViewModel.performHideSourceFilterDialog() {
    _feedOptionUiState.update { it.copy(sourceFilterDialogVisible = false, sourceFilterError = null) }
}

/** addSourceFilter：处理当前来源设置。 */
internal fun FeedOptionViewModel.performAddSourceFilter(pattern: String, type: ArticleFilterRuleType): Boolean {
    val feed = _feedOptionUiState.value.feed ?: return false
    return runCatching {
        articleFilterRepository.add(pattern, feed.id, feed.name, type)
        _feedOptionUiState.update {
            it.copy(
                sourceFilterRules = articleFilterRepository.getByFeed(feed.id),
                sourceFilterError = null,
            )
        }
    }.onFailure { error ->
        _feedOptionUiState.update { it.copy(sourceFilterError = error.message) }
    }.isSuccess
}

/** setSourceFilterEnabled：处理当前来源设置。 */
internal fun FeedOptionViewModel.performSetSourceFilterEnabled(rule: ArticleFilterRule, enabled: Boolean) {
    articleFilterRepository.setEnabled(rule, enabled)
    showSourceFilterDialog()
}

/** deleteSourceFilter：处理当前来源设置。 */
internal fun FeedOptionViewModel.performDeleteSourceFilter(rule: ArticleFilterRule) {
    articleFilterRepository.delete(rule)
    showSourceFilterDialog()
}
