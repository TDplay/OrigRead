package me.ash.reader.ui.page.home.feeds.subscribe

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import me.ash.reader.ui.ext.showToastLong

/** 订阅/导入消息独立于底部面板显示，完成后关闭面板也不会丢失结果。 */
@Composable
internal fun SubscriptionOperationFeedback(message: String?, consume: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(message) {
        if (message != null) {
            context.showToastLong(message)
            consume()
        }
    }
}

/** 部分失败在配置面板持续可见，用户重试时仍能核对失败来源。 */
@Composable
internal fun SubscriptionFailureMessage(message: String?) {
    if (message == null) return
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
