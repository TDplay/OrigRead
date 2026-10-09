package me.ash.reader.ui.component.base

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription

/** One semantic icon and one layout slot, including while its two visual states overlap. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OrigReadAnimatedIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val animatedTint by animateColorAsState(tint,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "state_icon_tint")
    Box(modifier.clearAndSetSemantics {
        contentDescription?.let { this.contentDescription = it }
    }) {
        Crossfade(targetState = imageVector,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "state_icon") { icon ->
            Icon(imageVector = icon, contentDescription = null, tint = animatedTint)
        }
    }
}
