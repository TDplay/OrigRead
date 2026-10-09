package me.ash.reader.ui.page.home.feeds

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Coalesce sync bursts before changing the displayed count, rather than animating every DB emission.
private const val UnreadCountCoalescingMillis = 160L

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FeedUnreadBadge(count: Int) {
    var displayedCount by remember { mutableIntStateOf(count) }
    LaunchedEffect(count) {
        if (count != displayedCount) {
            delay(UnreadCountCoalescingMillis)
            displayedCount = count
        }
    }
    Box(Modifier.widthIn(min = 32.dp).heightIn(min = 16.dp).clearAndSetSemantics {
        if (displayedCount != 0) text = AnnotatedString(displayedCount.toString())
    }, contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            visible = displayedCount != 0,
            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            Badge(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.outline,
            ) {
                Crossfade(displayedCount, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "feed-unread-count") {
                    Text(it.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
