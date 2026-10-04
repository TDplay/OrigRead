package me.ash.reader.ui.page.adaptive

import me.ash.reader.domain.data.FilterState
import me.ash.reader.domain.data.PagerData
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed

/** 沿用既有下一分组/来源选择规则，列表流只负责组合结果。 */
internal suspend fun ArticleListReaderViewModel.buildFlowUiState(pager: PagerData, groups: List<GroupWithFeed>): FlowUiState {
    val filter = pager.filterState
    val next = when {
        filter.group != null -> nextGroupFilter(filter, groups.map { it.group })
        filter.feed != null -> nextFeedFilter(filter, groups.flatMap { it.feeds })
        else -> null
    }
    return FlowUiState(pagerData = pager, nextFilterState = next)
}

/** 当前分组未出现在过滤列表时，仍按原完整顺序寻找下一可见分组。 */
private suspend fun ArticleListReaderViewModel.nextGroupFilter(filter: FilterState, groups: List<Group>): FilterState? {
    val current = filter.group ?: return null
    val index = groups.indexOfFirst { it.id == current.id }
    if (index != -1) return groups.getOrNull(index + 1)?.let { filter.copy(group = it) }
    val all = rssService.get().queryAllGroupWithFeeds().map { it.group }
    val originalIndex = all.indexOfFirst { it.id == current.id }
    if (originalIndex == -1) return null
    val visibleIds = groups.mapTo(hashSetOf()) { it.id }
    return all.drop(originalIndex).firstOrNull { it.id in visibleIds }?.let { filter.copy(group = it) }
}

/** 当前来源未出现在过滤列表时，仍按原完整顺序寻找下一可见来源。 */
private suspend fun ArticleListReaderViewModel.nextFeedFilter(filter: FilterState, feeds: List<Feed>): FilterState? {
    val current = filter.feed ?: return null
    val index = feeds.indexOfFirst { it.id == current.id }
    if (index != -1) return feeds.getOrNull(index + 1)?.let { filter.copy(feed = it) }
    val all = rssService.get().queryAllGroupWithFeeds().flatMap { it.feeds }
    val originalIndex = all.indexOfFirst { it.id == current.id }
    if (originalIndex == -1) return null
    val visibleIds = feeds.mapTo(hashSetOf()) { it.id }
    return all.drop(originalIndex).firstOrNull { it.id in visibleIds }?.let { filter.copy(feed = it) }
}
