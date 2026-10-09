package me.ash.reader.ui.motion

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.ash.reader.R
import me.ash.reader.ui.page.home.feeds.FeedUnreadBadge
import me.ash.reader.ui.page.home.reading.ImageData
import me.ash.reader.ui.page.home.reading.ReaderImageViewer
import me.ash.reader.ui.page.home.reading.ReaderSupportingPane
import me.ash.reader.ui.page.home.reading.retainsContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LocalMotionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun interruptedFirstEntranceRetainsReaderSpaceUntilExitFinishes() {
        val state = MutableTransitionState(false)
        compose.setContent {
            MaterialTheme {
                ReaderSupportingPane(state, Modifier.size(240.dp, 300.dp)) { Text("Assistant") }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.targetState = true }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle {
            assertEquals(false, state.currentState)
            state.targetState = false
            assertTrue(state.retainsContent)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Assistant").assertExists()
        compose.runOnIdle { assertTrue(state.retainsContent) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(false, state.retainsContent) }
        compose.onNodeWithText("Assistant").assertDoesNotExist()
    }

    @Test fun assistantContentRemainsMountedUntilExitFinishes() {
        val state = MutableTransitionState(true)
        var disposed = 0
        compose.setContent {
            MaterialTheme {
                ReaderSupportingPane(state, Modifier.size(240.dp, 300.dp)) {
                    DisposableEffect(Unit) { onDispose { disposed++ } }
                    Text("Assistant")
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.targetState = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Assistant").assertExists()
        compose.runOnIdle { assertEquals(0, disposed) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Assistant").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, disposed) }
    }

    @Test fun assistantCanReopenDuringExitWithoutRecreatingItsContent() {
        val state = MutableTransitionState(true)
        var mounted = 0
        compose.setContent {
            MaterialTheme {
                ReaderSupportingPane(state, Modifier.size(240.dp, 300.dp)) {
                    DisposableEffect(Unit) { mounted++; onDispose {} }
                    Text("Assistant")
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.targetState = false }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { state.targetState = true }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Assistant").assertExists()
        compose.runOnIdle { assertEquals(1, mounted) }
    }

    @Test fun translatedResultsDoNotReplayModeAnimationOrRecreateContent() {
        val mode = mutableStateOf(false)
        val text = mutableStateOf("Original")
        lateinit var alpha: androidx.compose.runtime.State<Float>
        var mounted = 0
        compose.setContent {
            MaterialTheme {
                alpha = rememberOrigReadContentAlpha(mode.value)
                DisposableEffect(Unit) { mounted++; onDispose {} }
                Text(text.value)
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { text.value = "Translation arrived" }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1f, alpha.value, 0f) }
        compose.runOnIdle { mode.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertTrue(alpha.value < 1f) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(1f, alpha.value, 0.001f); assertEquals(1, mounted) }
    }

    @Test fun unreadCountCoalescesUpdatesAndKeepsItsEmptySlotWidth() {
        val count = mutableStateOf(2)
        compose.setContent { MaterialTheme { Box(Modifier.testTag("badge")) { FeedUnreadBadge(count.value) } } }
        val width = compose.onNodeWithTag("badge").fetchSemanticsNode().boundsInRoot.width
        compose.runOnIdle { count.value = 20 }
        compose.runOnIdle { count.value = 21 }
        compose.waitUntil(3000) { compose.onAllNodesWithText("21").fetchSemanticsNodes().size == 1 }
        compose.runOnIdle { count.value = 0 }
        compose.waitUntil(3000) { compose.onAllNodesWithText("21").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertEquals(width, compose.onNodeWithTag("badge").fetchSemanticsNode().boundsInRoot.width, 0.1f)
    }

    @Test fun imageDismissWaitsForExitAndCallsOwnerOnce() {
        val visible = mutableStateOf(true)
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                if (visible.value) ReaderImageViewer(ImageData(altText = "Image"), {}) {
                    dismissals++
                    visible.value = false
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val close = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.close)
        compose.onNodeWithContentDescription(close).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(0, dismissals) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(1, dismissals) }
        compose.onNodeWithContentDescription(close).assertDoesNotExist()
    }
}
