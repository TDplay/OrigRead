package me.ash.reader.ui.page.adaptive

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.translation.TranslationDisplayMode
import me.ash.reader.infrastructure.translation.TranslationDocument
import me.ash.reader.infrastructure.translation.TranslationOwner
import me.ash.reader.infrastructure.translation.TranslationSettings
import timber.log.Timber

private data class ReaderCacheInput(
    val owner: TranslationOwner?, val articleId: String?, val title: String, val content: String,
    val language: String, val mode: TranslationDisplayMode, val currentAccount: Int?, val cacheRevision: Long,
)

/** 标题/正文准备好后恢复本地结果；这个入口没有生成失败后的网络 fallback。 */
internal fun ArticleListReaderViewModel.observeStoredReaderTranslation() {
    viewModelScope.launch {
        combine(_readerState, _readingUiState, translationSettingsRepository.settings,
            accountService.currentAccountIdFlow, translationService.cacheInvalidations) { reader, reading, settings, account, revision ->
            val article = reading.articleWithFeed?.article
            ReaderCacheInput(article?.let { TranslationOwner(it.accountId, it.feedId, it.id) },
                reader.articleId, reader.title.orEmpty(), reader.content.text.orEmpty(),
                settings.targetLanguage.trim().ifBlank { TranslationSettings.defaultTargetLanguage() },
                settings.displayMode, account, revision)
        }.distinctUntilChanged().collect { input ->
            val owner = input.owner
            if (owner != null && input.currentAccount != owner.accountId) {
                resetTranslation()
                return@collect
            }
            if (owner == null || owner.articleId != input.articleId || input.content.isBlank() || _translationUiState.value.isLoading) return@collect
            translationRestoreJob?.cancel()
            val generation = translationRequestSerial
            translationRestoreJob = viewModelScope.launch {
                try {
                    val stored = translationService.restoreArticle(owner, input.title, input.content, input.language, input.mode)
                    currentCoroutineContext().ensureActive()
                    if (translationRequestSerial != generation || _translationUiState.value.isLoading ||
                        currentArticle?.id != owner.articleId || currentArticle?.accountId != owner.accountId ||
                        _readerState.value.content.text != input.content || accountService.getCurrentAccountId() != owner.accountId) return@launch
                    _translationUiState.value = if (stored == null) ReaderTranslationUiState()
                        else ReaderTranslationUiState(document = stored.first, showTranslation = stored.second)
                    stored?.first?.let { scheduleStoredTranslationExpiry(it) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    // 自动恢复失败保持原文，不自动重译、不弹出长驻错误。
                    Timber.w(error, "Stored translation could not be restored")
                }
            }
        }
    }
}

/** 正在显示的结果也受到期限制，不因页面一直开着就无限保留。 */
internal fun ArticleListReaderViewModel.scheduleStoredTranslationExpiry(document: TranslationDocument) {
    translationExpiryJob?.cancel()
    if (document.expiresAt == Long.MAX_VALUE) return
    translationExpiryJob = viewModelScope.launch {
        delay((document.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
        if (_translationUiState.value.document == document) _translationUiState.value = ReaderTranslationUiState()
    }
}

/** 只记录当前文章的显示选择，离开页面不代表删除已完成译文。 */
internal fun ArticleListReaderViewModel.rememberTranslationVisibility(document: TranslationDocument, visible: Boolean) {
    val entry = document.persisted ?: return
    applicationScope.launch(ioDispatcher) { translationService.setTranslationVisible(entry, visible) }
}
