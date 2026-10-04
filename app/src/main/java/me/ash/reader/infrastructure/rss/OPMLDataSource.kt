package me.ash.reader.infrastructure.rss

import android.content.Context
import be.ceau.opml.OpmlParser
import be.ceau.opml.entity.Outline
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.source.SourceUrlNormalizer
import me.ash.reader.ui.ext.extractDomain
import me.ash.reader.ui.ext.spacerDollar

/** 保留输入数量供导入结果统计；分组中的来源已经按文件首次出现顺序去重。 */
data class ParsedOpmlDocument(val groups: List<GroupWithFeed>, val inputFeedCount: Int)

/** 递归读取 OPML，嵌套文件夹以完整路径映射到现有平面分组。 */
class OPMLDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private data class ParseContext(
        val defaultGroup: Group,
        val targetGroup: Group,
        val accountId: Int,
        val path: List<String> = emptyList(),
    )

    private data class ParsedOutline(val groups: List<Group>, val feeds: List<Feed>)

    /** 默认节点直接按分组 ID 归属，不能依赖最近插入的分组位置。 */
    @Throws(Exception::class)
    suspend fun parseFileInputStream(
        inputStream: InputStream,
        defaultGroup: Group,
        targetAccountId: Int,
    ): List<GroupWithFeed> = parseDocument(inputStream, defaultGroup, targetAccountId).groups

    /** 首次出现的地址决定最终分组，默认组的位置不改变文件内的去重优先级。 */
    suspend fun parseDocument(inputStream: InputStream, defaultGroup: Group, targetAccountId: Int): ParsedOpmlDocument {
        require(defaultGroup.accountId == targetAccountId) { "默认分组不属于导入账户" }
        val opml = OpmlParser().parse(inputStream)
        val options = ParseContext(defaultGroup, defaultGroup, targetAccountId)
        val parsed = opml.body.outlines.map { collectOutline(it, options) }
        val inputFeeds = parsed.flatMap { it.feeds }
        val feeds = inputFeeds.distinctBy { SourceUrlNormalizer.comparisonKey(it.url) }.groupBy(Feed::groupId)
        val groups = (listOf(defaultGroup) + parsed.flatMap { it.groups }).distinctBy(Group::id)
        return ParsedOpmlDocument(
            groups.map { GroupWithFeed(it, feeds[it.id].orEmpty().toMutableList()) }, inputFeeds.size,
        )
    }

    /** XML 节点可同时包含来源和子节点，所有层级均递归进入。 */
    private fun collectOutline(outline: Outline, options: ParseContext): ParsedOutline {
        val url = outline.extractUrl()
        if (url != null) {
            val feed = Feed(
                id = options.accountId.spacerDollar(UUID.randomUUID().toString()),
                name = outline.extractName(), url = url,
                groupId = options.targetGroup.id, accountId = options.accountId,
                isNotification = outline.attributes["isNotification"].toBoolean(),
                isFullContent = false, isBrowser = false,
            )
            val children = outline.subElements.map { collectOutline(it, options) }
            return ParsedOutline(children.flatMap { it.groups }, listOf(feed) + children.flatMap { it.feeds })
        }
        val isDefault = outline.attributes["isDefault"].toBoolean()
        val path = if (isDefault) emptyList() else options.path + outline.extractName()
        val group = if (isDefault) options.defaultGroup else Group(
            id = options.accountId.spacerDollar(UUID.randomUUID().toString()),
            name = path.joinToString(" / "), accountId = options.accountId,
        )
        val children = outline.subElements.map {
            collectOutline(it, options.copy(targetGroup = group, path = path))
        }
        return ParsedOutline(listOf(group) + children.flatMap { it.groups }, children.flatMap { it.feeds })
    }

    /** 标题缺省时沿用 OPML 文本和来源域名，保留已有命名语义。 */
    private fun Outline.extractName(): String = attributes["title"] ?: text
        ?: attributes["xmlUrl"].extractDomain() ?: attributes["htmlUrl"].extractDomain()
        ?: attributes["url"].extractDomain() ?: ""

    /** 标准 OPML 来源由 xmlUrl 声明，兼容已有文件中的 url 别名。 */
    private fun Outline.extractUrl(): String? = (attributes["xmlUrl"] ?: attributes["url"])
        ?.takeIf { it.isNotBlank() }
}
