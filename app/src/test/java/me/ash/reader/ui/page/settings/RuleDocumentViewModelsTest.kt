package me.ash.reader.ui.page.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.ai.AiSettings
import me.ash.reader.infrastructure.ai.AiSettingsRepository
import me.ash.reader.infrastructure.filter.ArticleFilterEngine
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.website.WebsiteRuleRepository
import me.ash.reader.ui.page.settings.filter.ArticleFilterSettingsViewModel
import me.ash.reader.ui.page.settings.json.JsonRulesViewModel
import me.ash.reader.ui.page.settings.website.WebsiteRulesViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** 复用现有仓库和两个执行线程验证三种规则文档的完整 IO、反馈和取消。 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuleDocumentViewModelsTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(TEST_TIMEOUT_SECONDS)
    private val main = Executors.newSingleThreadExecutor { Thread(it, MAIN_THREAD) }.asCoroutineDispatcher()
    private val io = Executors.newSingleThreadExecutor { Thread(it, IO_THREAD) }.asCoroutineDispatcher()
    private val directory = Files.createTempDirectory("origread-rule-document").toFile()
    private val viewModels = mutableListOf<ViewModel>()
    private lateinit var mainThread: Thread
    private lateinit var ioThread: Thread
    private lateinit var jsonRepository: JsonRuleRepository
    private lateinit var websiteRepository: WebsiteRuleRepository
    private lateinit var filterRepository: ArticleFilterRepository
    private lateinit var jsonViewModel: JsonRulesViewModel
    private lateinit var websiteViewModel: WebsiteRulesViewModel
    private lateinit var filterViewModel: ArticleFilterSettingsViewModel

    // 仅使用临时文件与内存 AI 配置，不访问用户配置或调用外部服务。
    @Before fun setUp() {
        Dispatchers.setMain(main)
        runBlocking {
            mainThread = withContext(main) { Thread.currentThread() }
            ioThread = withContext(io) { Thread.currentThread() }
        }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(directory)
        jsonRepository = spy(JsonRuleRepository(context))
        websiteRepository = WebsiteRuleRepository(context)
        filterRepository = ArticleFilterRepository(context)
        val settings = mock<AiSettingsRepository>()
        whenever(settings.settings).thenReturn(MutableStateFlow(AiSettings()))
        jsonViewModel = JsonRulesViewModel(
            repository = jsonRepository, aiRuleGenerationService = mock(),
            aiSettingsRepository = settings, ioDispatcher = io, mainDispatcher = main,
        )
        websiteViewModel = WebsiteRulesViewModel(
            repository = websiteRepository, websiteHelper = mock(), aiRuleGenerationService = mock(),
            aiSettingsRepository = settings, ioDispatcher = io, mainDispatcher = main,
        )
        filterViewModel = ArticleFilterSettingsViewModel(
            repository = filterRepository, articleFilterEngine = ArticleFilterEngine(filterRepository),
            articleDao = mock(), feedDao = mock(), accountService = mock(), ioDispatcher = io,
        )
        viewModels += listOf(jsonViewModel, websiteViewModel, filterViewModel)
    }

    @After fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        main.close()
        io.close()
        directory.deleteRecursively()
    }

    @Test fun `JSON export serialization and import reload run on IO and retain UTF8 format`() = runBlocking {
        val template = jsonRepository.exportTemplate().replace("Example JSON API", "中文示例")
        val expectedCount = jsonRepository.importRules(template)
        val expected = jsonRepository.exportRules()
        doAnswer {
            checkIo()
            it.callRealMethod()
        }.whenever(jsonRepository).exportRules()
        doAnswer {
            checkIo()
            it.callRealMethod()
        }.whenever(jsonRepository).listRules()

        assertEquals(expected, exportDocument(jsonViewModel::exportRules))
        assertEquals(jsonRepository.exportTemplate(), exportDocument(jsonViewModel::exportTemplate))
        assertEquals(expectedCount, importDocument(jsonViewModel::importRules, expected))
        assertTrue(jsonViewModel.uiState.value.rules.isNotEmpty())
    }

    @Test fun `website document and template keep format and import feedback`() = runBlocking {
        val template = websiteRepository.exportTemplate()
        assertEquals(template, exportDocument(websiteViewModel::exportTemplate))
        val count = importDocument(websiteViewModel::importRules, template)
        assertTrue(count > 0)
        assertEquals(websiteRepository.exportRules(), exportDocument(websiteViewModel::exportRules))
        assertEquals(count, websiteViewModel.uiState.value.rules.size)
    }

    @Test fun `filter rules keep UTF8 and existing statistics`() = runBlocking {
        filterRepository.add("广告")
        val expected = filterRepository.exportRules()
        val stats = filterRepository.getStats()
        assertEquals(expected, exportDocument(filterViewModel::exportRules))
        assertEquals(1, importDocument(filterViewModel::importRules, expected))
        assertEquals(stats, filterViewModel.uiState.value.stats)
        assertEquals("广告", filterViewModel.uiState.value.rules.single().keyword)
    }

    @Test fun `open and write failures are reported and failing streams are closed`() = runBlocking {
        val readError = IOException("document provider denied reading")
        val imports = listOf(jsonViewModel::importRules, websiteViewModel::importRules, filterViewModel::importRules)
        for (import in imports) {
            val result = awaitResult<Int> { callback -> import({ checkIo(); throw readError }, callback) }
            assertSame(readError, result.exceptionOrNull())
        }
        val exports = listOf(jsonViewModel::exportRules, websiteViewModel::exportRules, filterViewModel::exportRules)
        for (export in exports) {
            val writeError = IOException("document provider denied writing")
            val output = RecordingOutput(writeError)
            val result = awaitResult<Unit> { callback -> export({ checkIo(); output }, callback) }
            assertSame(writeError, result.exceptionOrNull())
            assertTrue(output.closed)
            val missingStream = awaitResult<Unit> { callback -> export({ null }, callback) }
            assertTrue(missingStream.exceptionOrNull() is IOException)
        }
    }

    @Test fun `cancellation after read closes the stream and skips persistence and feedback`() = runBlocking<Unit> {
        val closed = CompletableDeferred<Unit>()
        var callbacks = 0
        withContext(main) {
            jsonViewModel.importRules(openInput = {
                checkIo()
                jsonViewModel.viewModelScope.cancel()
                object : ByteArrayInputStream("{}".toByteArray(Charsets.UTF_8)) {
                    override fun close() { super.close(); closed.complete(Unit) }
                }
            }) { callbacks++ }
        }
        closed.await()
        withContext(io) { }
        withContext(main) { assertEquals(0, callbacks) }
        verify(jsonRepository, never()).importRules(any())
    }

    // 等待真实 ViewModel 的主线程回调，不用 sleep 或伪造成功结果。
    private suspend fun <T> awaitResult(operation: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val completion = CompletableDeferred<Pair<Thread, Result<T>>>()
        withContext(main) {
            operation { result ->
                completion.complete(Thread.currentThread() to result)
            }
        }
        // 先交付回调记录再断言，错误线程会直接使测试失败，不会丢失回调而一直等待。
        val (callbackThread, result) = completion.await()
        assertSame(mainThread, callbackThread)
        return result
    }

    // 流的打开、读取和关闭都必须由生产 ViewModel 的 IO 调度器执行。
    private suspend fun importDocument(
        operation: (() -> InputStream?, (Result<Int>) -> Unit) -> Unit,
        content: String,
    ): Int {
        val input = RecordingInput(content)
        val result = awaitResult<Int> { callback -> operation({ checkIo(); input }, callback) }
        assertTrue(input.closed)
        return result.getOrThrow()
    }

    // 比较原仓库的完整序列化内容，避免只验证“调用完成”而遗漏格式变化。
    private suspend fun exportDocument(operation: (() -> OutputStream?, (Result<Unit>) -> Unit) -> Unit): String {
        val output = RecordingOutput()
        awaitResult<Unit> { callback -> operation({ checkIo(); output }, callback) }.getOrThrow()
        assertTrue(output.closed)
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private inner class RecordingInput(content: String) : ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)) {
        var closed = false
            private set
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            checkIo()
            return super.read(bytes, offset, length)
        }
        override fun close() { checkIo(); closed = true; super.close() }
    }

    private inner class RecordingOutput(private val failure: IOException? = null) : ByteArrayOutputStream() {
        var closed = false
            private set
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            checkIo()
            failure?.let { throw it }
            super.write(bytes, offset, length)
        }
        override fun close() { checkIo(); closed = true; super.close() }
    }

    // 使用真实线程身份，协程调试追加线程名时仍能核对实际 IO 调度器。
    private fun checkIo() { check(Thread.currentThread() === ioThread) }

    private companion object {
        // 防止异步回调丢失导致测试永久等待，沿用一分钟测试边界。
        const val TEST_TIMEOUT_SECONDS = 60L
        // 分别标识页面回调线程与阻塞文件 IO 线程。
        const val MAIN_THREAD = "rule-document-test-main"
        const val IO_THREAD = "rule-document-test-io"
    }
}
