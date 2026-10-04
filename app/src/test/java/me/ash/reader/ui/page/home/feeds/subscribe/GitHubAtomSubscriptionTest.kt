package me.ash.reader.ui.page.home.feeds.subscribe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions

/** Issue #2：GitHub 原生 Atom 必须真实解析成功后直接进入 RSS 配置。 */
class GitHubAtomSubscriptionTest : SubscriptionPipelineFixture() {
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
