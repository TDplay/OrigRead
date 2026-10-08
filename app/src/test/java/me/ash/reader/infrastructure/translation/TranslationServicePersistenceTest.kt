package me.ash.reader.infrastructure.translation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.repository.ArticleDao
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** 真实持久缓存与正文渲染器，仅替换渠道网络和 Android/DAO 边界。 */
class TranslationServicePersistenceTest {
    @get:Rule val directory = TemporaryFolder()
    private val owner = TranslationOwner(1, "feed", "article")
    private val target = TranslationTarget.Traditional(TranslationProviderType.MICROSOFT)
    private val settings: TranslationSettingsRepository = mock()
    private val provider: MicrosoftTranslationProvider = mock()
    private val dao: ArticleDao = mock()
    private lateinit var context: Context
    private var alive = true
    private var duringRequest: () -> Unit = {}
    private var current = TranslationSettings(defaultProvider = target.provider, defaultTarget = target,
        targetLanguage = "zh-CN", providers = TranslationSettings.defaultProviderSettings() +
            (target.provider to TranslationProviderSettings(enabled = true, endpoint = "https://translation.invalid")))

    @Before fun setUp() = runBlocking<Unit> {
        context = mock()
        whenever(context.noBackupFilesDir).thenReturn(directory.newFolder("private"))
        whenever(context.cacheDir).thenReturn(directory.newFolder("legacy"))
        whenever(dao.queryTranslationOwners(any())).thenAnswer { if (alive) listOf(owner) else emptyList<TranslationOwner>() }
        whenever(settings.current()).thenAnswer { current }
        whenever(settings.isConfigured(target.provider)).thenReturn(true)
        whenever(settings.runtimeConfig(target.provider)).thenReturn(TranslationRuntimeConfig("https://translation.invalid", "", "fixture"))
        whenever(provider.maxBatchItems).thenReturn(50)
        whenever(provider.maxBatchCharacters).thenReturn(20_000)
        whenever(provider.maxSegmentCharacters).thenReturn(4_000)
        whenever(provider.translate(any(), anyOrNull(), any(), any())).thenAnswer { invocation ->
            duringRequest()
            TranslationBatchResult(invocation.getArgument<List<String>>(0).map { "译文：$it" }, "en")
        }
    }

    private fun service() = TranslationService(settingsRepository = settings, mlKitProvider = mock(),
        microsoftProvider = provider, deepLProvider = mock(), googleCloudProvider = mock(), dlxProvider = mock(),
        aiSettingsRepository = mock(), aiProvider = mock(),
        cache = TranslationCache(context, dao, Dispatchers.IO), aiTaskPromptCustomizer = mock(), ioDispatcher = Dispatchers.IO)

    @Test fun completedBodySurvivesReopeningAndDisabledChannelWithoutAnotherRequest() = runBlocking<Unit> {
        val content = "<p>Original paragraph</p>"
        val first = service().translateArticle(owner, "Title", content, target)
        assertNotNull(first.persisted)
        assertFalse(first.cacheWriteFailed)
        current = current.copy(displayMode = TranslationDisplayMode.BILINGUAL,
            providers = current.providers + (target.provider to current.provider(target.provider).copy(enabled = false)))
        val reopened = service()
        val restored = requireNotNull(reopened.restoreArticle(owner, "Title", content, "zh-CN", current.displayMode))
        assertTrue(restored.second)
        assertTrue(restored.first.translatedContent.contains("Original paragraph"))
        assertTrue(restored.first.translatedContent.contains("译文"))
        val clicked = reopened.translateArticle(owner, "Title", content, target)
        assertEquals(first.expiresAt, clicked.expiresAt)
        verify(provider, times(1)).translate(any(), anyOrNull(), any(), any())
    }

    @Test fun restoringUntranslatedBodyNeverGenerates() = runBlocking<Unit> {
        assertNull(service().restoreArticle(owner, "Title", "<p>Body</p>", "zh-CN", TranslationDisplayMode.TRANSLATED))
        verify(provider, never()).translate(any(), anyOrNull(), any(), any())
    }

    @Test fun modeChangedDuringRequestIsRenderedLocallyAtCompletion() = runBlocking<Unit> {
        duringRequest = { current = current.copy(displayMode = TranslationDisplayMode.BILINGUAL) }
        val result = service().translateArticle(owner, "Title", "<p>Original paragraph</p>", target)
        assertEquals(TranslationDisplayMode.BILINGUAL, result.displayMode)
        assertTrue(result.translatedContent.contains("Original paragraph"))
        assertTrue(result.translatedContent.contains("origread-translation"))
        verify(provider, times(1)).translate(any(), anyOrNull(), any(), any())
    }

    @Test fun deletionDuringTranslationRejectsLateResult() = runBlocking<Unit> {
        duringRequest = { alive = false }
        try {
            service().translateArticle(owner, "Title", "<p>Body</p>", target)
            fail("Deleted article must not accept a late translation")
        } catch (_: TranslationException) { /* expected */ }
        assertFalse(context.noBackupFilesDir.walkTopDown().any { it.isFile })
    }
}
