package me.ash.reader.ui.page.home.reading

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import me.ash.reader.R
import me.ash.reader.ui.ext.showToastLong
import me.ash.reader.ui.page.adaptive.ReaderState

/** 已获取的正文继续展示；当前文章的离线保存失败按一次状态变化明确提示。 */
@Composable
internal fun FullContentCacheNotice(articleId: String?, content: ReaderState.ContentState) {
    val context = LocalContext.current
    val cacheWriteFailed = (content as? ReaderState.FullContent)?.cacheWriteFailed == true
    LaunchedEffect(articleId, cacheWriteFailed) {
        if (cacheWriteFailed) context.showToastLong(context.getString(R.string.full_content_cache_not_saved))
    }
}
