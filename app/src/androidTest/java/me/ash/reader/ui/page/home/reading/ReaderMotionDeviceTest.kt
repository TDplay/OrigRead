package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import me.ash.reader.ui.component.reader.ReaderEvidenceAnchorTarget
import me.ash.reader.ui.component.webview.OrigReadWebView
import me.ash.reader.ui.component.webview.WebViewReaderAnchorState
import me.ash.reader.ui.component.webview.WebViewReaderAnchorNavigationResult
import me.ash.reader.ui.component.webview.WebViewReaderScrollState
import me.ash.reader.ui.component.webview.prepareWebViewReaderContent
import me.ash.reader.ui.page.adaptive.ReaderState
import me.ash.reader.infrastructure.preference.LocalReadingRenderer
import me.ash.reader.infrastructure.preference.ReadingRendererPreference
import me.ash.reader.ui.component.reader.NativeReaderAnchorState
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReaderMotionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingAndMetadataUpdatesKeepOneArticleSceneAndRenderLatestText() {
        val state = mutableStateOf(ReaderState(articleId = "a"))
        var mounted = 0
        var disposed = 0
        compose.setContent {
            MaterialTheme {
                ReaderAnimatedContent(state.value) { scene, modifier ->
                    DisposableEffect(Unit) { mounted++; onDispose { disposed++ } }
                    Box(modifier.fillMaxSize()) { Text(scene.title ?: "Loading") }
                }
            }
        }
        compose.onNodeWithText("Loading").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(title = "Loaded", content = ReaderState.Description("text")) }
        compose.onNodeWithText("Loaded").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(nextArticle = ReaderState.PrefetchResult("b", 1)) }
        compose.onNodeWithText("Loaded").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, mounted); assertEquals(0, disposed) }
    }

    @Test fun refreshingAnArticleKeepsItsTextAndDoesNotRecreateTheScene() {
        val state = mutableStateOf(ReaderState(articleId = "a", content = ReaderState.Description("Readable")))
        var mounted = 0
        compose.setContent {
            MaterialTheme {
                ReaderAnimatedContent(state.value) { scene, modifier ->
                    DisposableEffect(Unit) { mounted++; onDispose {} }
                    Text(scene.content.text ?: "Loading", modifier)
                }
            }
        }
        compose.runOnIdle { state.value = state.value.copy(content = ReaderState.Loading) }
        compose.onNodeWithText("Readable").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(content = ReaderState.FullContent("Full text")) }
        compose.onNodeWithText("Full text").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, mounted) }
    }

    @Test fun outgoingArticleRetainsItsOwnTextWhileIncomingArticleUpdates() {
        val state = mutableStateOf(ReaderState(articleId = "a", title = "Article A"))
        compose.setContent {
            MaterialTheme {
                ReaderAnimatedContent(state.value) { scene, modifier -> Text(scene.title ?: "Loading B", modifier) }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = ReaderState(articleId = "b") }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { state.value = state.value.copy(title = "Article B", content = ReaderState.Description("body")) }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Article A").assertExists()
        compose.onNodeWithText("Article B").assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Article A").assertDoesNotExist()
        compose.onNodeWithText("Article B").assertIsDisplayed()
    }

    @Test fun rapidArticleChangesFinishAtTheLatestArticle() {
        val state = mutableStateOf(ReaderState(articleId = "a", title = "A"))
        compose.setContent {
            MaterialTheme {
                ReaderAnimatedContent(state.value) { scene, modifier -> Text(scene.title.orEmpty(), modifier) }
            }
        }
        compose.mainClock.autoAdvance = false
        for (id in listOf("b", "c", "a")) {
            compose.runOnIdle { state.value = ReaderState(articleId = id, title = id.uppercase()) }
            compose.mainClock.advanceTimeBy(32)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("A").assertIsDisplayed()
        compose.onNodeWithText("B").assertDoesNotExist()
        compose.onNodeWithText("C").assertDoesNotExist()
    }

    @Test fun outgoingNativeArticleDoesNotUnbindTheIncomingReader() {
        val state = mutableStateOf(ReaderState(articleId = "a", title = "Native A",
            content = ReaderState.Description("<p>First article body.</p>")))
        val anchors = NativeReaderAnchorState()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReadingRenderer provides ReadingRendererPreference.NativeComponent) {
                    ReaderAnimatedContent(state.value) { scene, modifier ->
                        Content(
                            modifier = modifier,
                            articleId = scene.articleId,
                            content = scene.content.text.orEmpty(),
                            feedName = "Test feed",
                            title = scene.title.orEmpty(),
                            publishedDate = Date(0),
                            scrollState = rememberScrollState(),
                            listState = rememberLazyListState(),
                            isLoading = scene.content == ReaderState.Loading,
                            nativeReaderAnchorState = anchors.takeIf { scene.articleId == state.value.articleId },
                        )
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals("a", anchors.currentArticleId()) }
        compose.runOnIdle {
            state.value = ReaderState(articleId = "b", title = "Native B",
                content = ReaderState.Description("<p>Second article body.</p>"))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Native A").assertDoesNotExist()
        compose.onNodeWithText("Native B").assertIsDisplayed()
        compose.runOnIdle { assertEquals("b", anchors.currentArticleId()) }
        saveScreenshot("native-reader")
    }

    @Test fun returningToRetainedWebViewRestoresItsReadyCitationBinding() {
        val first = ReaderState(articleId = "a", title = "A", content = ReaderState.Description("<p>Evidence for article A.</p>"))
        val second = ReaderState(articleId = "b", title = "B", content = ReaderState.Description("<p>Evidence for article B.</p>"))
        val state = mutableStateOf(first)
        val anchors = WebViewReaderAnchorState()
        compose.setContent {
            MaterialTheme {
                ReaderAnimatedContent(state.value) { scene, modifier ->
                    OrigReadWebView(
                        modifier = modifier.fillMaxSize(),
                        articleId = scene.articleId,
                        content = scene.content.text.orEmpty(),
                        sourceUrl = "https://example.test/${scene.articleId}",
                        readerAnchorState = anchors.takeIf { scene.articleId == state.value.articleId },
                        viewportScrollState = remember { WebViewReaderScrollState() },
                    )
                }
            }
        }
        compose.waitUntil(10_000) { anchors.readyArticleId == "a" }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = second }
        compose.mainClock.advanceTimeBy(32)
        compose.waitUntil(10_000) { anchors.readyArticleId == "b" }
        compose.runOnIdle { state.value = first }
        compose.mainClock.advanceTimeBy(32)
        compose.waitUntil(10_000) { anchors.readyArticleId == "a" }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("a", anchors.readyArticleId) }
        val evidence = prepareWebViewReaderContent(first.content.text.orEmpty(), "https://example.test/a", true)
        val block = evidence.evidenceDocument.blocks.first()
        val result = AtomicReference<WebViewReaderAnchorNavigationResult?>(null)
        compose.runOnIdle {
            anchors.navigateTo(ReaderEvidenceAnchorTarget(articleId = "a", stableLocatorKey = block.stableLocatorKey)) {
                result.set(it)
            }
        }
        compose.waitUntil(10_000) { result.get() != null }
        org.junit.Assert.assertTrue(result.get().toString(), result.get() is WebViewReaderAnchorNavigationResult.Located)
        saveScreenshot("webview-reader")
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "motion-qa").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
