package me.ash.reader.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Reveals a user-selected mode without creating a second renderer or animating its height. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberOrigReadContentAlpha(mode: Any): State<Float> {
    val alpha = remember { Animatable(1f) }
    val previousMode = remember { arrayOf(mode) }
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(mode) {
        if (previousMode[0] != mode) {
            previousMode[0] = mode
            alpha.snapTo(0.7f)
            alpha.animateTo(1f, effects)
        }
    }
    return alpha.asState()
}

/** Historic rows and recycled rows must never replay their entrance. */
internal class OrigReadMessageEntranceRegistry(private val openedAtMillis: Long) {
    private val displayedIds = mutableSetOf<String>()
    fun shouldAnimate(id: String, createdAtMillis: Long): Boolean =
        displayedIds.add(id) && createdAtMillis >= openedAtMillis
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Modifier.origReadMessageEntrance(animate: Boolean): Modifier {
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val distance = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(Unit) { if (animate) progress.animateTo(1f, effects) }
    return graphicsLayer {
        alpha = progress.value
        translationY = distance * (1f - progress.value)
    }
}
