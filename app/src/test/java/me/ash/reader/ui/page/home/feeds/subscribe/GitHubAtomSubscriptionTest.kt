package me.ash.reader.ui.page.home.feeds.subscribe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.service.SubscriptionRequest
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import me.ash.reader.infrastructure.discovery.FeedCatalogEntry
import me.ash.reader.infrastructure.discovery.FeedCatalogUrlMatch
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rsshub.RssHubInputParser
import me.ash.reader.infrastructure.source.SourceCandidateKind
import me.ash.reader.infrastructure.source.SourceInputHint
import me.ash.reader.infrastructure.source.sourceInputHint
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/** Issue #2：GitHub 原生 Atom 必须真实解析成功后直接进入 RSS 配置。 */
@OptIn(ExperimentalCoroutinesApi::class)
class GitHubAtomSubscriptionTest : SubscriptionPipelineFixture() {
    /** 走真实搜索预处理，确保旧目录建议不能穿过原生 Atom 成功后的配置发布。 */
    @Test fun nativeAtomClearsPreparedCatalogAndSubmitsTheParsedSource() = runBlocking<Unit> {
        // 真实 HTTP 使用实际时钟，避免虚拟时间在 IO 返回前提前耗尽发现阶段预算。
        Dispatchers.setMain(Dispatchers.Unconfined)
        val server = MockWebServer()
        server.start()
        try {
            rssHelper = actualHelper(server)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/atom+xml")
                .setHeader("ETag", "github-atom").setHeader("Last-Modified", HTTP_LAST_MODIFIED).setBody(GITHUB_ATOM))
            val suggestions = listOf("ReadYouApp/ReadYou", "oven-sh/bun", "biomejs/biome").map { repository ->
                FeedCatalogEntry(repository, repository, "https://github.com/$repository/commits/main.atom")
            }
            whenever(feedDiscoveryCatalog.matchUrl(REPORTED_URL)).thenReturn(FeedCatalogUrlMatch(suggestions = suggestions))
            val viewModel = createViewModel()
            viewModel.showDrawer()
            (viewModel.subscribeState.value as SubscribeState.Idle).linkState.edit { replace(0, length, REPORTED_URL) }
            viewModel.searchFeed()
            val terminal = withTimeout(TEST_TIMEOUT_MS) {
                viewModel.subscribeState.first {
                    it is SubscribeState.Configure || (it is SubscribeState.Idle && it.errorMessage != null)
                }
            }
            assertTrue("Atom discovery must succeed: $terminal", terminal is SubscribeState.Configure)
            val state = terminal as SubscribeState.Configure
            assertTrue(state.catalogMatches.isEmpty())
            assertEquals(0, state.catalogMatchCount)
            assertEquals(REPORTED_URL, state.feedLink)
            assertEquals(setOf(state.candidates.single().id), state.selectedCandidateIds)
            assertEquals(SourceCandidateKind.RSS_DIRECT, state.candidates.single().kind)
            verify(feedDiscoveryCatalog).matchUrl(REPORTED_URL)
            verifyNoInteractions(jsonSourceHelper, rssHubResolver, websiteHelper)
            assertOriginalSubmission(viewModel, state)
            assertEquals("/ReadYouApp/ReadYou/releases.atom", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        } finally {
            // 本机 HTTP 服务器只提供固定 Atom 响应，结束后释放端口和连接。
            server.shutdown()
        }
    }

    /** 点击确认必须提交实际选中来源和完整 validators，不能提交目录首项。 */
    private suspend fun assertOriginalSubmission(viewModel: SubscribeViewModel, state: SubscribeState.Configure) {
        viewModel.subscribe()
        testDispatcher.scheduler.advanceUntilIdle()
        val request = argumentCaptor<SubscriptionRequest>()
        verify(rssService).subscribe(request.capture())
        assertEquals(REPORTED_URL, request.firstValue.feedLink)
        assertSame(state.searchedFeed, request.firstValue.searchedFeed)
        assertEquals(SourceType.RSS, request.firstValue.sourceType)
        assertEquals("github-atom", request.firstValue.etag)
        assertEquals(HTTP_LAST_MODIFIED, request.firstValue.lastModified)
        assertNull(request.firstValue.rssHub)
    }

    /** 仅替换公网传输地址，保留输入分类、XML 解析、发现链及配置发布。 */
    @Test fun githubAtomFeedsShortCircuitBeforeRssHub() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            rssHelper = actualHelper(server)
            listOf("releases.atom", "commits/main.atom").forEach { path ->
                server.enqueue(MockResponse().setHeader("Content-Type", "application/atom+xml; charset=utf-8")
                    .setHeader("ETag", "github-atom").setBody(GITHUB_ATOM))
                assertDirectConfiguration("https://github.com/ZGMFX01A/OrigRead/$path")
                assertEquals("/ZGMFX01A/OrigRead/$path", server.takeRequest().path)
            }
            assertEquals(2, server.requestCount)
            verifyNoInteractions(jsonSourceHelper, rssHubResolver, websiteHelper)
        } finally {
            // 每个用例仅占用本机服务器，关闭后不留下连接或线上订阅。
            server.shutdown()
        }
    }

    /** 检查原始地址与已解析 Atom 同时保留，不能用目录或 RSSHub 地址替代。 */
    private suspend fun assertDirectConfiguration(url: String) {
        assertEquals(SourceInputHint.RSS_LIKELY, sourceInputHint(url))
        val explicit = RssHubInputParser.parseExplicit(url)
        assertNull(explicit)
        val viewModel = createViewModel()
        viewModel.showDrawer()
        val idle = viewModel.subscribeState.value as SubscribeState.Idle
        idle.linkState.edit { replace(0, length, url) }
        val options = SubscribeDiscoveryContext(requireNotNull(accountFlow.value), idle, url,
            "group_1", FeedCatalogUrlMatch(), explicit)
        val result = viewModel.discoverSources(options)
        assertNull(result.error)
        assertEquals("github-atom", result.candidates.single().etag)
        viewModel.applyDiscoveryResult(options, result)
        val state = viewModel.subscribeState.value as SubscribeState.Configure
        assertEquals(url, state.feedLink)
        assertEquals(SourceType.RSS, state.sourceType)
        assertEquals(SourceCandidateKind.RSS_DIRECT, state.candidates.single().kind)
        assertEquals("GitHub releases", state.searchedFeed.title)
        assertEquals("tag:github.com,2008:Repository/1315917939/v1.6.1", state.searchedFeed.entries.single().uri)
    }

    /** HTTP 响应来自本机，真实 ROME 解析器和来源发现实现保持原样。 */
    private fun actualHelper(server: MockWebServer) = RssHelper(
        context = mock<Context>(), ioDispatcher = Dispatchers.IO,
        okHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
        }.build(),
        contentExtractionService = mock(), dynamicArticleContentService = mock(),
        articleWebSessionManager = mock<ArticleWebSessionManager> {
            on { desktopHttpUserAgent } doReturn "Mozilla/5.0 OrigRead-Test"
        },
    )

    private companion object {
        /** 真实本机 HTTP 回归的等待上限，避免测试因失败状态漏发而挂住。 */
        const val TEST_TIMEOUT_MS = 60_000L
        /** Issue 作者给出的原生 Atom 地址。 */
        const val REPORTED_URL = "https://github.com/ReadYouApp/ReadYou/releases.atom"
        /** 订阅后用于条件请求的服务器时间，必须与解析结果一起保留。 */
        const val HTTP_LAST_MODIFIED = "Mon, 05 Oct 2026 00:00:00 GMT"
        /** GitHub 使用 Atom 命名空间、tag 身份与 HTML 内容，不要求 RSS version 属性。 */
        const val GITHUB_ATOM = """<?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/" xml:lang="en-US">
            <id>tag:github.com,2008:https://github.com/ZGMFX01A/OrigRead/releases</id>
            <title>GitHub releases</title><updated>2026-09-30T12:52:39Z</updated>
            <entry><id>tag:github.com,2008:Repository/1315917939/v1.6.1</id>
            <updated>2026-09-30T13:07:42Z</updated><title>OrigRead v1.6.1</title>
            <link rel="alternate" type="text/html" href="https://github.com/ZGMFX01A/OrigRead/releases/tag/v1.6.1"/>
            <content type="html">&lt;p&gt;Release notes&lt;/p&gt;</content></entry></feed>"""
    }
}
