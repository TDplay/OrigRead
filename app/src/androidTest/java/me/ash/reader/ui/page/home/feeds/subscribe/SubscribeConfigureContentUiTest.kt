package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.util.concurrent.atomic.AtomicReference
import me.ash.reader.R
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.discovery.FeedCatalogEntry
import me.ash.reader.infrastructure.rsshub.RssHubProbeResult
import me.ash.reader.infrastructure.rsshub.RssHubRouteDefinition
import me.ash.reader.infrastructure.rsshub.RssHubRouteMatch
import me.ash.reader.infrastructure.source.SourceCandidateKind
import me.ash.reader.infrastructure.website.CandidateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 配置页展示真实来源，目录建议的位置与收起状态不能再挤掉订阅配置。 */
@RunWith(AndroidJUnit4::class)
class SubscribeConfigureContentUiTest {
    @get:Rule val composeRule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 单个 RSS 候选必须明确显示成功识别、实际 Feed 地址和主要配置项。 */
    @Test fun singleRssShowsItsActualAddressAndConfiguration() {
        show(state(SourceCandidateKind.RSS_DIRECT))
        composeRule.onNodeWithText(context.getString(R.string.subscription_native_rss_confirmed)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.subscription_feed_address, FEED_URL)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.allow_notification)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.add_to_group)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.subscription_other_catalog_unverified)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.source_catalog_matches_title)).assertDoesNotExist()
    }

    /** 网页风险提示保留，目录建议默认收起且排在主要配置之后；展开仍可选择真实目录项。 */
    @Test fun unverifiedCatalogIsCollapsedAfterOptionsAndStillSelectable() {
        val suggestion = FeedCatalogEntry("other", "Other source", "https://example.com/other.xml")
        val selected = AtomicReference<FeedCatalogEntry>()
        val configure = state(SourceCandidateKind.WEBSITE).copy(
            sourceNotice = "Website source risk", catalogMatches = listOf(suggestion), catalogMatchCount = 1,
        )
        show(configure, actions(onCatalog = selected::set))
        composeRule.onNodeWithText("Website source risk").assertIsDisplayed()
        val header = composeRule.onNodeWithText(context.getString(R.string.subscription_other_catalog_unverified))
        // 滚动区域之外的节点会被裁成空矩形，位置比较必须使用完整布局边界。
        val groupTop = composeRule.onNodeWithText(context.getString(R.string.add_to_group)).getUnclippedBoundsInRoot().top
        assertTrue(header.getUnclippedBoundsInRoot().top > groupTop)
        composeRule.onNodeWithText(suggestion.name).assertDoesNotExist()
        header.performScrollTo().performClick()
        composeRule.onNodeWithText(suggestion.name).performScrollTo().performClick()
        assertEquals(suggestion, selected.get())
        header.performScrollTo().performClick()
        composeRule.onNodeWithText(suggestion.name).assertDoesNotExist()
    }

    /** RSSHub 的两个可用路由继续提供多选，不能被原生 RSS 的展示规则误伤。 */
    @Test fun rssHubRoutesRemainSelectable() {
        val results = listOf("hot", "latest").map { route ->
            val path = "/news/$route"
            RssHubProbeResult(
                match = RssHubRouteMatch(RssHubRouteDefinition(route, route, "example.com", "/news", path),
                    feedUrl = "https://hub.example.com$path"),
                state = CandidateState.AVAILABLE, feed = feed(route), routePath = path,
                instanceBaseUrl = "https://hub.example.com",
            )
        }
        val candidates = SubscribeCandidateSelector.rank(results.map {
            SubscribeCandidateProbe(requireNotNull(it.feed), requireNotNull(it.match.feedUrl), SourceType.RSS,
                SourceCandidateKind.RSSHUB, rssHubRoutePath = it.routePath)
        })
        val selected = AtomicReference<String>()
        show(state(SourceCandidateKind.RSSHUB).copy(
            candidates = candidates, selectedCandidateId = candidates.first().id,
            selectedCandidateIds = setOf(candidates.first().id), rssHubResults = results,
            feedLink = candidates.first().feedLink,
        ), actions(onToggle = selected::set))
        composeRule.onNodeWithText(context.getString(R.string.subscription_native_rss_confirmed)).assertDoesNotExist()
        val routes = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and isToggleable()
        composeRule.onAllNodes(routes).assertCountEquals(2)
        composeRule.onNodeWithText("latest").performScrollTo().performClick()
        assertEquals(candidates.last().id, selected.get())
    }

    /** 每个场景仅渲染真实配置组件，账户数据和公网依赖留给完整链路设备测试。 */
    private fun show(configure: SubscribeState.Configure, callbacks: SubscribeSheetCallbacks = actions()) {
        composeRule.setContent { MaterialTheme { SubscribeConfigureContent(configure, callbacks) } }
    }

    /** 配置页的输入站点与保存地址故意不同，防止又展示原始网页地址。 */
    private fun state(kind: SourceCandidateKind): SubscribeState.Configure {
        val sourceType = if (kind == SourceCandidateKind.WEBSITE) SourceType.WEBSITE else SourceType.RSS
        val candidate = SubscribeCandidateSelector.rank(listOf(
            SubscribeCandidateProbe(feed("ReadYou"), FEED_URL, sourceType, kind),
        )).single()
        return SubscribeState.Configure(
            searchedFeed = candidate.feed, feedLink = candidate.feedLink, sourcePageUrl = "https://example.com/news",
            groups = listOf(Group("group_1", "Default", 1)), selectedGroupId = "group_1", accountId = 1,
            sourceType = sourceType, candidates = listOf(candidate), selectedCandidateId = candidate.id,
        )
    }

    /** 真实 ROME 模型提供健康文章，使测试候选经过与生产相同的评分规则。 */
    private fun feed(name: String) = SyndFeedImpl().apply {
        title = name
        entries = listOf(SyndEntryImpl().apply { title = "Release"; link = "https://example.com/release" })
    }

    /** 回调直接记录目录选择和路由切换，其余动作不参与这三个展示场景。 */
    private fun actions(onCatalog: (FeedCatalogEntry) -> Unit = {}, onToggle: (String) -> Unit = {}) = SubscribeSheetCallbacks(
        onRename = {}, onClose = {}, onSelectCatalog = onCatalog, onSearch = {}, onSelectCandidate = {},
        onToggleRssHub = onToggle, onRetryRssHub = {}, onToggleNotification = {}, onToggleFullContent = {},
        onToggleBrowser = {}, onGroupClick = {}, onAddGroup = {}, onImportOpml = {}, onSubscribe = {},
    )

    private companion object {
        /** 来源摘要应展示的实际订阅地址。 */
        const val FEED_URL = "https://github.com/ReadYouApp/ReadYou/releases.atom"
    }
}
