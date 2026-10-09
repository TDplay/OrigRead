package me.ash.reader.ui.page.home.reading

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal val MutableTransitionState<Boolean>.retainsContent: Boolean
    get() = currentState || targetState || !isIdle

/** Keeps the assistant mounted through exit; the owning reader reserves its settled space. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReaderSupportingPane(
    visibleState: MutableTransitionState<Boolean>,
    modifier: Modifier = Modifier,
    tabletop: Boolean = false,
    content: @Composable () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = (if (tabletop) slideInVertically(motion.defaultSpatialSpec()) { it }
            else slideInHorizontally(motion.defaultSpatialSpec()) { it }) + fadeIn(motion.fastEffectsSpec()),
        exit = (if (tabletop) slideOutVertically(motion.defaultSpatialSpec()) { it }
            else slideOutHorizontally(motion.defaultSpatialSpec()) { it }) + fadeOut(motion.fastEffectsSpec()),
    ) { content() }
}
