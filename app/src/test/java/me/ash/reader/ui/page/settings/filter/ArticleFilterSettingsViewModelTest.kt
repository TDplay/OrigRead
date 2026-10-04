package me.ash.reader.ui.page.settings.filter

import android.content.Context
import androidx.lifecycle.viewModelScope
import java.nio.file.Files
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFilterMetadata
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.filter.ArticleFilterEngine
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.filter.FilteredArticleRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** 固定原页面的完整结果，保护先匹配、按来源与文章去重、排序后截取的语义。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArticleFilterSettingsViewModelTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(TEST_TIMEOUT_SECONDS)
    private val dispatcher = StandardTestDispatcher()
    private val directory = Files.createTempDirectory("origread-filter-metadata").toFile()
    private val articleDao = mock<ArticleDao>()
    private val feedDao = mock<FeedDao>()
    private val accountService = mock<AccountService>()
    private val feed = Feed(
        id = "feed", name = "当前来源", url = "https://example.com/feed",
        groupId = "group", accountId = ACCOUNT_ID,
    )
    private lateinit var viewModel: ArticleFilterSettingsViewModel

    // 页面协程与 IO 查询共用测试调度器，结果由 advanceUntilIdle 确定性收集。
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    // 仅取消本用例的页面任务并清理它自己的临时规则文件。
    @After fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        directory.deleteRecursively()
    }

    @Test fun filteringPreservesSavedRecordsAndAppliesLimitAfterMatching() = runTest(dispatcher) {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(directory)
        val repository = ArticleFilterRepository(context)
        repository.add(keyword = "blocked")
        val articles = (0 until UNMATCHED_COUNT + MATCHED_COUNT).map(::article)
        val savedDuplicate = record(articles[UNMATCHED_COUNT]).copy(
            sourceName = "抓取时来源", filteredAt = SAVED_RECORD_TIME,
        )
        val savedOtherFeed = record(articles.last()).copy(
            feedId = "saved-feed", filteredAt = SAVED_RECORD_TIME + 1,
        )
        repository.recordFilteredArticles(listOf(savedDuplicate, savedOtherFeed))
        whenever(accountService.getCurrentAccountId()).thenReturn(ACCOUNT_ID)
        whenever(feedDao.queryAll(ACCOUNT_ID)).thenReturn(listOf(feed))
        whenever(articleDao.queryFilterMetadataByAccountId(ACCOUNT_ID)).thenReturn(
            articles.map { ArticleFilterMetadata(id = it.id, feedId = it.feedId, title = it.title, date = it.date) }
        )
        viewModel = ArticleFilterSettingsViewModel(
            repository = repository, articleFilterEngine = ArticleFilterEngine(repository),
            articleDao = articleDao, feedDao = feedDao, accountService = accountService,
            ioDispatcher = dispatcher,
        )

        viewModel.loadFilteredArticles()
        advanceUntilIdle()

        val expected = listOf(savedOtherFeed, savedDuplicate) +
            articles.takeLast(DISPLAY_LIMIT - 2).reversed().map(::record)
        assertEquals(expected, viewModel.uiState.value.filteredArticles)
        assertEquals(DISPLAY_LIMIT, viewModel.uiState.value.filteredArticles.size)
        assertFalse(viewModel.uiState.value.filteredArticlesLoading)
        verify(articleDao).queryFilterMetadataByAccountId(ACCOUNT_ID)
        verify(articleDao, never()).queryAllByAccountId(ACCOUNT_ID)
        verify(feedDao).queryAll(ACCOUNT_ID)
    }

    // 不命中的较早文章占满展示上限，以暴露把 LIMIT 提前到查询阶段的回归。
    private fun article(index: Int) = Article(
        id = "article-$index", date = Date(index.toLong()),
        title = if (index < UNMATCHED_COUNT) "normal $index" else "blocked $index",
        rawDescription = "完整正文 $index", shortDescription = "摘要 $index",
        link = "https://example.com/$index", feedId = feed.id, accountId = ACCOUNT_ID,
    )

    // 预期来源来自当前订阅，命中规则与日期沿用完整实体的原字段。
    private fun record(article: Article) = FilteredArticleRecord(
        articleId = article.id, feedId = article.feedId, sourceName = feed.name,
        title = article.title, matchedRule = "blocked", filteredAt = article.date.time,
    )

    private companion object {
        // 单个回归用例的执行上限，避免协程等待失控。
        const val TEST_TIMEOUT_SECONDS = 60L
        // 固定当前账户与页面原有的最终展示条数。
        const val ACCOUNT_ID = 1
        const val DISPLAY_LIMIT = 200
        // 覆盖先过滤再截取，以及命中条数超过展示上限。
        const val UNMATCHED_COUNT = 200
        const val MATCHED_COUNT = 240
        // 抓取记录晚于数据库文章，且应在去重时保留。
        const val SAVED_RECORD_TIME = 1000L
    }
}
