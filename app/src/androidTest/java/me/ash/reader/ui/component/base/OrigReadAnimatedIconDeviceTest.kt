package me.ash.reader.ui.component.base

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OrigReadAnimatedIconDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingIconKeepsOneSemanticNodeAndFixedBounds() {
        val starred = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                OrigReadAnimatedIcon(
                    imageVector = if (starred.value) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    contentDescription = "Star",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        val initialBounds = compose.onNodeWithContentDescription("Star").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { starred.value = true }
        compose.mainClock.advanceTimeBy(32)
        compose.onAllNodesWithContentDescription("Star").assertCountEquals(1)
        assertEquals(initialBounds, compose.onNodeWithContentDescription("Star").fetchSemanticsNode().boundsInRoot)
        compose.mainClock.autoAdvance = true
    }
}
