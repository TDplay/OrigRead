package me.ash.reader.ui.page.home.flow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.infrastructure.translation.TranslationTarget
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleListTranslationControllerTest {
    private val cloud = TranslationTarget.Traditional(TranslationProviderType.MICROSOFT)
    private fun row(id: Int, accountId: Int = 1) = ListTranslationInput.of(accountId, "$id", "Title $id", "Preview $id")

    @Test fun requestIsBoundedAndCachedResultsToggleWithoutMoreCalls() = runTest {
        val sent = mutableListOf<List<String>>()
        val controller = ArticleListTranslationController(this, { texts, _, _ ->
            sent += texts
            texts.map { "译:$it" }
        }, { _, account -> account == 1 })
        val rows = (1..55).map { row(it) }
        controller.translateOrToggle("list", rows, cloud, "zh-CN")
        advanceUntilIdle()
        assertEquals(50, controller.state.value.completed)
        assertEquals(50, controller.state.value.translations.size)
        assertEquals(100, sent.flatten().size)
        assertFalse(sent.flatten().contains("Title 51"))
        assertTrue(sent.all { it.size <= 16 })
        val calls = sent.size
        controller.translateOrToggle("list", rows, cloud, "zh-CN")
        assertFalse(controller.state.value.showTranslation)
        controller.translateOrToggle("list", rows, cloud, "zh-CN")
        assertTrue(controller.state.value.showTranslation)
        assertEquals(calls, sent.size)
    }

    @Test fun stopRetainsCompletedBatchAndResumeOnlySendsMissingRows() = runTest {
        val late = CompletableDeferred<List<String>>()
        val sent = mutableListOf<List<String>>()
        val controller = ArticleListTranslationController(this, { texts, _, _ ->
            sent += texts
            if (sent.size == 2) late.await() else texts.map { "译:$it" }
        }, { _, _ -> true })
        val rows = (1..12).map { row(it) }
        controller.translateOrToggle("list", rows, cloud, "zh")
        runCurrent()
        assertEquals(8, controller.state.value.completed)
        controller.stop()
        late.complete(sent.last().map { "过期:$it" })
        runCurrent()
        assertEquals(8, controller.state.value.completed)
        assertTrue(controller.state.value.stopped)
        assertFalse(controller.state.value.isLoading)
        controller.translateOrToggle("list", rows, cloud, "zh")
        advanceUntilIdle()
        assertEquals(12, controller.state.value.completed)
        assertEquals(sent[1], sent[2])
        assertTrue(controller.state.value.translations.values.none { it.title.startsWith("过期:") })
    }

    @Test fun oldProviderResponseCannotOverwriteNewProviderOrBusyState() = runTest {
        val late = CompletableDeferred<Unit>()
        val next = TranslationTarget.Traditional(TranslationProviderType.DEEPL)
        val controller = ArticleListTranslationController(this, { texts, target, _ ->
            if (target == cloud) withContext(NonCancellable) { late.await() }
            texts.map { "$target:$it" }
        }, { _, _ -> true })
        controller.translateOrToggle("list", listOf(row(1)), cloud, "zh")
        runCurrent()
        controller.translateOrToggle("list", listOf(row(1)), next, "zh", forceShow = true)
        runCurrent()
        late.complete(Unit)
        advanceUntilIdle()
        assertEquals(next, controller.state.value.target)
        assertEquals("$next:Title 1", controller.state.value.translations["1"]?.title)
        assertFalse(controller.state.value.isLoading)
    }

    @Test fun changedScopeCannotPublishOldResults() = runTest {
        val late = CompletableDeferred<Unit>()
        var scope: Any = "old"
        val controller = ArticleListTranslationController(this, { texts, _, _ ->
            withContext(NonCancellable) { late.await() }
            texts.map { "译:$it" }
        }, { key, account -> key == scope && account == 1 })
        controller.translateOrToggle(scope, listOf(row(1)), cloud, "zh")
        runCurrent()
        scope = "new"
        controller.reset(scope)
        late.complete(Unit)
        advanceUntilIdle()
        assertEquals("new", controller.state.value.scopeKey)
        assertTrue(controller.state.value.translations.isEmpty())
        assertFalse(controller.state.value.isLoading)
    }

    @Test fun invalidResponseIsNotCachedAsSuccess() = runTest {
        val controller = ArticleListTranslationController(this, { _, _, _ -> listOf("only one field") }, { _, _ -> true })
        controller.translateOrToggle("list", listOf(row(1)), cloud, "zh")
        advanceUntilIdle()
        assertNotNull(controller.state.value.errorMessage)
        assertEquals(0, controller.state.value.completed)
        assertTrue(controller.state.value.translations.isEmpty())
        assertFalse(controller.state.value.isLoading)
    }

    @Test fun changedPreviewInvalidatesOnlyThatArticle() = runTest {
        val sent = mutableListOf<List<String>>()
        val controller = ArticleListTranslationController(this, { texts, _, _ -> sent += texts; texts.map { "译:$it" } }, { _, _ -> true })
        controller.translateOrToggle("list", listOf(row(1), row(2)), cloud, "zh")
        advanceUntilIdle()
        val changed = row(2).copy(description = "Updated preview")
        controller.translateOrToggle("list", listOf(row(1), changed), cloud, "zh")
        advanceUntilIdle()
        assertEquals(listOf("Title 2", "Updated preview"), sent.last())
        assertEquals(changed, controller.state.value.translations["2"]?.source)
    }

    @Test fun hiddenPreviewsAreNotSentAndMlKitDoesNotMixArticles() = runTest {
        val sent = mutableListOf<List<String>>()
        val controller = ArticleListTranslationController(this, { texts, _, _ -> sent += texts; texts.map { "译:$it" } }, { _, _ -> true })
        val local = TranslationTarget.Traditional(TranslationProviderType.ML_KIT)
        controller.translateOrToggle("list", listOf(row(1).copy(description = ""), row(2).copy(title = "日本語")), local, "zh")
        advanceUntilIdle()
        assertEquals(listOf("Title 1"), sent[0])
        assertEquals(listOf("日本語", "Preview 2"), sent[1])
        assertEquals("", controller.state.value.translations["1"]?.description)
    }

    @Test fun differentAccountOrStaleScopeDoesNotSendRequests() = runTest {
        var calls = 0
        val controller = ArticleListTranslationController(this, { texts, _, _ -> calls++; texts }, { key, account -> key == "new" && account == 1 })
        controller.translateOrToggle("old", listOf(row(1)), cloud, "zh")
        controller.translateOrToggle("new", listOf(row(1, 2)), cloud, "zh")
        advanceUntilIdle()
        assertEquals(0, calls)
    }

    @Test fun cacheEvictionDoesNotRemoveHitsFromTheCurrentRequest() = runTest {
        val controller = ArticleListTranslationController(this, { texts, _, _ -> texts.map { "译:$it" } }, { _, _ -> true })
        repeat(4) { page ->
            controller.translateOrToggle("list", (1..50).map { row(page * 50 + it) }, cloud, "zh")
            advanceUntilIdle()
        }
        val selected = (1..30).map { row(it) } + (201..220).map { row(it) }
        controller.translateOrToggle("list", selected, cloud, "zh")
        advanceUntilIdle()
        assertEquals(200, controller.state.value.translations.size)
        assertEquals(50, controller.state.value.completed)
        assertTrue(selected.all { controller.state.value.translations[it.articleId]?.source == it })
    }

    @Test fun excerptsDoNotSplitUnicodeSurrogatePairs() {
        val input = ListTranslationInput.of(1, "one", "😀".repeat(1_030), "😀".repeat(520))
        assertEquals(1_024, input.title.codePointCount(0, input.title.length))
        assertEquals(512, input.description.codePointCount(0, input.description.length))
        assertTrue(input.title.endsWith("😀"))
        assertTrue(input.description.endsWith("😀"))
    }
}
