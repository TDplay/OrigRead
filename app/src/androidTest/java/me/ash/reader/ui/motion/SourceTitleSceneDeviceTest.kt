package me.ash.reader.ui.motion

import android.graphics.Bitmap
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import me.ash.reader.ui.page.nav3.key.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pixel checks during navigation, not only after animation settles. No account or network needed. */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
class SourceTitleSceneDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val stack = mutableStateListOf<NavKey>(Route.Feeds)
    private val selected = mutableStateOf("a")
    private val names = mapOf("a" to "51CTO技术栈", "b" to "Quanta Magazine")

    private fun mount() {
        compose.setContent {
            MaterialTheme(motionScheme = MotionScheme.expressive()) {
                SharedTransitionLayout {
                    val motion = MaterialTheme.motionScheme
                    NavDisplay(
                        modifier = Modifier.fillMaxSize(),
                        backStack = stack,
                        transitionSpec = { origReadNavigationTransform(OrigReadMotionDirection.Forward, motion) },
                        popTransitionSpec = { origReadNavigationTransform(OrigReadMotionDirection.Backward, motion) },
                        onBack = { stack.removeLastOrNull() },
                        entryProvider = { route ->
                            NavEntry(route) {
                                if (route == Route.Feeds) {
                                    Box(Modifier.fillMaxSize().background(Color.White).testTag("sources")) {
                                        Text("OrigRead", fontSize = 48.sp, color = Color.Black,
                                            modifier = Modifier.offset(24.dp, 100.dp))
                                        names.entries.forEachIndexed { index, (id, name) ->
                                            Box(Modifier.offset(56.dp, (350 + index * 70).dp).testTag("source-row-$id")) {
                                                Text(name, fontSize = 18.sp, color = Color.Magenta,
                                                    modifier = Modifier.origReadSourceTitle())
                                            }
                                        }
                                    }
                                } else {
                                    Box(Modifier.fillMaxSize().background(Color.White).testTag("flow-page")) {
                                        Text(names.getValue(selected.value), fontSize = 48.sp, color = Color.Magenta,
                                            modifier = Modifier.offset(24.dp, 100.dp)
                                                .origReadSourceTitle())
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun open(id: String, settle: Boolean = false) {
        compose.runOnIdle { selected.value = id; stack.add(Route.Reading(null)) }
        compose.mainClock.advanceTimeBy(if (settle) 1200L else 32L)
    }

    private fun back() { compose.runOnIdle { stack.removeLastOrNull() } }

    private fun assertNoFloatingTitle(frame: String): Int {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val flows = compose.onAllNodesWithTag("flow-page").fetchSemanticsNodes()
        // Only inspect the part of the homepage revealed to the left of the outgoing page.
        val exposedWidth = (flows.minOfOrNull { it.boundsInRoot.left } ?: root.right)
            .minus(root.left).toInt().coerceIn(0, image.width)
        val density = image.width / (root.width / InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density)
        val top = (80 * density).toInt().coerceIn(0, image.height)
        val bottom = (240 * density).toInt().coerceIn(top, image.height)
        var escaped = 0
        for (y in top until bottom) for (x in 0 until exposedWidth) {
            val pixel = image.getPixel(x, y)
            if (android.graphics.Color.red(pixel) > 120 && android.graphics.Color.blue(pixel) > 120 &&
                android.graphics.Color.green(pixel) < 100) escaped++
        }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null), "motion-title-qa").apply { mkdirs() }
        File(directory, "$frame.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals("Shared title escaped its page into the homepage header: $frame, exposedWidth=$exposedWidth", 0, escaped)
        return exposedWidth
    }

    @Test fun returnKeepsTitleInsideItsOwnSceneDuringTheAnimation() {
        mount()
        open("a", settle = true)
        back()
        var exposed = 0
        repeat(5) { frame ->
            compose.mainClock.advanceTimeBy(32)
            exposed = maxOf(exposed, assertNoFloatingTitle("return-$frame"))
        }
        assertTrue("The regression must sample visible homepage pixels", exposed > 30)
        compose.mainClock.advanceTimeBy(1200)
        assertNoFloatingTitle("return-settled")
    }

    @Test fun returningSourceRowKeepsItsOwnLabelDuringTheAnimation() {
        mount()
        open("a", settle = true)
        back()
        compose.mainClock.advanceTimeBy(192)
        val row = compose.onAllNodesWithTag("source-row-a").fetchSemanticsNodes().single().boundsInRoot
        val flow = compose.onAllNodesWithTag("flow-page").fetchSemanticsNodes().single().boundsInRoot
        assertTrue("The row must be revealed while the outgoing page is still moving", row.right < flow.left)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        var ink = 0
        for (y in row.top.toInt().coerceAtLeast(0) until row.bottom.toInt().coerceAtMost(bitmap.height)) {
            for (x in row.left.toInt().coerceAtLeast(0) until row.right.toInt().coerceAtMost(bitmap.width)) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(pixel) > 120 && android.graphics.Color.blue(pixel) > 120 &&
                    android.graphics.Color.green(pixel) < 100) ink++
            }
        }
        assertTrue("Restored source label must not be evacuated into the global overlay: ink=$ink", ink > 20)
    }

    @Test fun rapidSourceChangesAndReturnsKeepTitlesInsideTheirScenes() {
        mount()
        open("a", settle = true)
        repeat(3) { turn ->
            back()
            compose.mainClock.advanceTimeBy(64)
            assertNoFloatingTitle("switch-$turn-back")
            open(if (turn % 2 == 0) "b" else "a")
            compose.mainClock.advanceTimeBy(32)
            assertNoFloatingTitle("switch-$turn-open")
        }
        back()
        repeat(5) { frame ->
            compose.mainClock.advanceTimeBy(32)
            assertNoFloatingTitle("switch-final-$frame")
        }
        compose.mainClock.advanceTimeBy(1200)
        assertNoFloatingTitle("switch-settled")
    }
}
