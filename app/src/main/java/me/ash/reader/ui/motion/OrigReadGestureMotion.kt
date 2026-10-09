package me.ash.reader.ui.motion

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/** Direct manipulation settles without bounce and yields immediately to the next gesture. */
object OrigReadGestureMotion {
    val settleSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 700f,
    )
}
