package me.ash.reader.ui.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import me.ash.reader.ui.component.swipe.SwipeAction
import me.ash.reader.ui.component.swipe.SwipeableActionsBox
import me.ash.reader.ui.component.swipe.SwipeableActionsState
import me.ash.reader.ui.page.home.reading.PullToLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GestureMotionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelledSwipeDoesNotExecuteAnAction() {
        val state = SwipeableActionsState()
        var actions = 0
        compose.setContent {
            MaterialTheme {
                SwipeableActionsBox(state = state, swipeThreshold = 20.dp,
                    startActions = listOf(SwipeAction({ actions++ }, {}, androidx.compose.ui.graphics.Color.Red))) {
                    Box(Modifier.size(300.dp, 80.dp).testTag("swipe"))
                }
            }
        }
        compose.onNodeWithTag("swipe").performTouchInput {
            down(center)
            moveBy(Offset(100f, 0f))
            moveBy(Offset(100f, 0f))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe").performTouchInput { cancel() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, actions); assertEquals(0f, state.offset.value, 0.1f) }
    }

    @Test fun anotherSwipeCanInterruptTheReturnAndExecutesExactlyOnce() {
        val state = SwipeableActionsState()
        var actions = 0
        compose.setContent {
            MaterialTheme {
                SwipeableActionsBox(state = state, swipeThreshold = 20.dp,
                    startActions = listOf(SwipeAction({ actions++ }, {}, androidx.compose.ui.graphics.Color.Red))) {
                    Box(Modifier.size(300.dp, 80.dp).testTag("swipe"))
                }
            }
        }
        compose.mainClock.autoAdvance = false
        fun swipe() {
            compose.onNodeWithTag("swipe").performTouchInput {
                down(center)
                moveBy(Offset(140f, 0f), delayMillis = 32)
                moveBy(Offset(100f, 0f), delayMillis = 32)
                up()
            }
            compose.mainClock.advanceTimeByFrame()
        }
        swipe()
        compose.runOnIdle { assertEquals(1, actions); assertTrue(state.offset.value > 0f) }
        swipe()
        compose.runOnIdle { assertEquals(2, actions) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(0f, state.offset.value, 0.1f) }
    }

    @Test fun pullReleaseExecutesOnceAndTheNextInputOwnsTheOffset() {
        lateinit var scope: CoroutineScope
        compose.setContent { scope = rememberCoroutineScope() }
        var next = 0
        lateinit var state: PullToLoadState
        compose.runOnIdle {
            state = PullToLoadState(scope, mutableStateOf(null), mutableStateOf({ next++ }),
                OrigReadGestureMotion.settleSpec, 100f)
            state.onPull(-150f)
            state.onRelease(-300f)
            state.onRelease(-300f)
            assertEquals(1, next)
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.animateDistanceTo(0f, 200f) }
        compose.mainClock.advanceTimeByFrame()
        var pulled = 0f
        compose.runOnIdle { state.onPull(10f); pulled = state.progress }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(pulled, state.progress, 0.001f) }
    }

    @Test fun belowThresholdPullReturnsWithoutLoadingOrOvershooting() {
        lateinit var scope: CoroutineScope
        compose.setContent { scope = rememberCoroutineScope() }
        var loads = 0
        lateinit var state: PullToLoadState
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            state = PullToLoadState(scope, mutableStateOf({ loads++ }), mutableStateOf({ loads++ }),
                OrigReadGestureMotion.settleSpec, 100f)
            state.onPull(70f)
            state.onRelease(-200f)
        }
        repeat(40) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertTrue(state.progress >= -0.001f) }
        }
        compose.runOnIdle { assertEquals(0, loads); assertEquals(0f, state.progress, 0.001f) }
    }
}
