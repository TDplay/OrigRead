package me.ash.reader.ui.page.home.flow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ash.reader.infrastructure.translation.PersistedTranslation
import me.ash.reader.infrastructure.translation.TranslationContentKind
import me.ash.reader.infrastructure.translation.TranslationOwner
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.infrastructure.translation.TranslationTarget
import me.ash.reader.infrastructure.translation.translationSourceHash
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListTranslationPersistenceTest {
    private val target = TranslationTarget.Traditional(TranslationProviderType.MICROSOFT)
    private val input = ListTranslationInput.of(1, "article", "Title", "Preview", "feed")
    private fun result(show: Boolean = true) = ListTranslationResult(input, "标题", "摘要", PersistedTranslation(
        TranslationOwner(1, "feed", "article"), TranslationContentKind.LIST,
        translationSourceHash(input.title, input.description), target, "zh-CN", "default", listOf("标题", "摘要"),
        System.currentTimeMillis()), show)

    @Test fun reopeningRestoresStoredTextWithoutGeneratingOrSaving() = runTest {
        var requests = 0
        var writes = 0
        val controller = ArticleListTranslationController(this, { texts, _, _ -> requests++; texts }, { _, _ -> true },
            readCached = { _, _, _ -> result() }, saveCached = { r, _, _ -> writes++; r })
        controller.restore("page", listOf(input), "zh-CN")
        assertTrue(controller.state.value.showTranslation)
        assertEquals("标题", controller.state.value.translations["article"]?.title)
        controller.translateOrToggle("page", listOf(input), target, "zh-CN")
        advanceUntilIdle()
        assertFalse(controller.state.value.showTranslation)
        assertEquals(0, requests)
        assertEquals(0, writes)
    }

    @Test fun emptyDiskDoesNotAuthorizePretranslation() = runTest {
        var requests = 0
        val controller = ArticleListTranslationController(this, { texts, _, _ -> requests++; texts }, { _, _ -> true })
        controller.restore("page", listOf(input), "zh-CN")
        assertEquals(0, requests)
        assertTrue(controller.state.value.translations.isEmpty())
        assertFalse(controller.state.value.isLoading)
    }

    @Test fun samePagerResetRestoresTheUnchangedWindowWithoutGenerating() = runTest {
        var requests = 0
        var reads = 0
        val controller = ArticleListTranslationController(this, { texts, _, _ -> requests++; texts }, { _, _ -> true },
            readCached = { _, _, _ -> reads++; result() })
        backgroundScope.launch {
            controller.state.map { it.restoreRevision }.distinctUntilChanged().collectLatest {
                controller.restore("page", listOf(input), "zh-CN")
            }
        }
        runCurrent()
        assertEquals("标题", controller.state.value.translations[input.articleId]?.title)
        val initialReads = reads

        // Changing the default provider resets this pager even when neither rows nor language change.
        controller.reset("page")
        assertTrue(controller.state.value.translations.isEmpty())
        runCurrent()

        assertEquals(initialReads + 1, reads)
        assertEquals("标题", controller.state.value.translations[input.articleId]?.title)
        assertTrue(controller.state.value.showTranslation)
        assertEquals(0, requests)
    }

    @Test fun failedLocalLookupKeepsOtherRestoredRowsAndDoesNotGenerate() = runTest {
        var requests = 0
        val failingInput = input.copy(articleId = "unreadable")
        val controller = ArticleListTranslationController(this, { texts, _, _ -> requests++; texts }, { _, _ -> true },
            readCached = { row, _, _ ->
                if (row == failingInput) throw IllegalStateException("Local translation lookup failed")
                result()
            })
        controller.restore("page", listOf(input), "zh-CN")
        controller.restore("page", listOf(failingInput, input), "zh-CN")

        assertEquals(setOf(input.articleId), controller.state.value.translations.keys)
        assertEquals("标题", controller.state.value.translations[input.articleId]?.title)
        assertFalse(controller.state.value.isLoading)
        assertNull(controller.state.value.errorMessage)
        assertEquals(0, requests)
    }

    @Test fun cancelledLocalLookupStillPropagatesCancellation() = runTest {
        val cancelled = CancellationException("The list has left composition")
        val controller = ArticleListTranslationController(this, { _, _, _ -> error("No network") }, { _, _ -> true },
            readCached = { _, _, _ -> throw cancelled })

        try {
            controller.restore("page", listOf(input), "zh-CN")
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertTrue(controller.state.value.translations.isEmpty())
    }

    @Test fun rememberedOriginalViewIsNotSwitchedBackByRestore() = runTest {
        val controller = ArticleListTranslationController(this, { _, _, _ -> error("No requests allowed") }, { _, _ -> true },
            readCached = { _, _, _ -> result(show = false) })
        controller.restore("page", listOf(input), "zh-CN")
        assertFalse(controller.state.value.showTranslation)
        assertNotNull(controller.state.value.translations[input.articleId])
    }

    @Test fun delayedDiskReadCannotPublishAfterScopeSwitch() = runTest {
        val gate = CompletableDeferred<Unit>()
        var page = "old"
        val controller = ArticleListTranslationController(this, { _, _, _ -> error("No network") }, { key, _ -> key == page },
            readCached = { _, _, _ -> gate.await(); result() })
        val restore = launch { controller.restore("old", listOf(input), "zh-CN") }
        runCurrent()
        page = "new"
        controller.reset("new")
        gate.complete(Unit)
        restore.join()
        assertTrue(controller.state.value.translations.isEmpty())
    }
}
