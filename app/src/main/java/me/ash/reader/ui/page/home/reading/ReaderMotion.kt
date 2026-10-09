package me.ash.reader.ui.page.home.reading

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.ash.reader.ui.page.adaptive.ReaderState

internal enum class ReaderContentMode { Loading, Description, FullContent, Error }

internal fun ReaderState.contentMode(): ReaderContentMode = when (content) {
    ReaderState.Loading -> ReaderContentMode.Loading
    is ReaderState.Description -> ReaderContentMode.Description
    is ReaderState.FullContent -> ReaderContentMode.FullContent
    is ReaderState.Error -> ReaderContentMode.Error
}

/** Each article owns its snapshot; an outgoing scene must never render the next article. */
internal class ReaderSceneSnapshot(initialState: ReaderState) {
    val articleId = initialState.articleId
    var state by mutableStateOf(initialState)
        private set

    fun resolve(liveState: ReaderState): ReaderState {
        if (liveState.articleId == state.articleId) {
            // Keep readable text while a same-article full-content request is loading.
            state = if (liveState.content == ReaderState.Loading && state.content.text != null) {
                liveState.copy(content = state.content)
            } else liveState
        }
        return state
    }
}

internal fun readerArticleDirection(initial: ReaderState, target: ReaderState): Int =
    if (initial.previousArticle?.articleId == target.articleId) -1 else 1

/** Article changes move; same-article updates keep the renderer and its scroll state alive. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReaderAnimatedContent(
    state: ReaderState,
    content: @Composable (ReaderState, Modifier) -> Unit,
) {
    val motionScheme = MaterialTheme.motionScheme
    val distance = with(LocalDensity.current) { 40.dp.roundToPx() }
    val currentScene = remember(state.articleId) { ReaderSceneSnapshot(state) }
    currentScene.resolve(state)
    AnimatedContent(
        targetState = currentScene,
        contentKey = { it.articleId },
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val direction = readerArticleDirection(initialState.state, targetState.state)
            ((slideInVertically(motionScheme.defaultSpatialSpec()) { distance * direction } +
                fadeIn(motionScheme.defaultEffectsSpec())) togetherWith
                (slideOutVertically(motionScheme.defaultSpatialSpec()) { -distance * direction } +
                    fadeOut(motionScheme.fastEffectsSpec()))).using(null)
        },
        label = "reader_article",
    ) { scene ->
        val visibleState = scene.state
        val mode = visibleState.contentMode()
        var previousMode by remember { mutableStateOf(mode) }
        val alpha = remember { Animatable(1f) }
        val revealSpec = motionScheme.fastEffectsSpec<Float>()
        LaunchedEffect(mode) {
            if (previousMode != mode) {
                previousMode = mode
                alpha.snapTo(0f)
                alpha.animateTo(1f, revealSpec)
            }
        }
        content(visibleState, Modifier.graphicsLayer { this.alpha = alpha.value })
    }
}
