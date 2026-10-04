package me.ash.reader.ui.page.adaptive

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.infrastructure.translation.TranslationTarget

/** 阅读页长按翻译选择器可以把传统服务或某个 AI 模型设为后续单击翻译的默认方式。 */
internal fun ArticleListReaderViewModel.performSetDefaultTranslationTarget(target: TranslationTarget) {
    translationSettingsRepository.setDefaultTarget(target)
}

/** 首次点击开始翻译；翻译中再次点击立即取消；已有译文时只在原文和译文之间切换。 */
internal fun ArticleListReaderViewModel.performTranslateOrToggle() {
    val state = _translationUiState.value
    if (state.isLoading) {
        stopTranslation()
        return
    }
    if (state.document != null) {
        _translationUiState.update { it.copy(showTranslation = !it.showTranslation) }
        return
    }
    translateWithTarget(resolveDefaultTranslationTarget())
}

/** 停止当前翻译并使未完成结果失效。 */
internal fun ArticleListReaderViewModel.performStopTranslation() {
    if (!_translationUiState.value.isLoading) return
    translationRequestSerial++
    translationJob?.cancel()
    translationJob = null
    _translationUiState.update { it.copy(isLoading = false, errorMessage = null) }
}

/**
 * Citation 永远核验原文。
 *
 * 如果翻译仍在生成，必须先失效并取消当前请求；否则旧请求可能在 Citation 已经定位成功后
 * 再次把 showTranslation 写回 true，把用户从原文证据位置切回译文。
 * 这里只改变当前阅读瞬时状态，不修改默认翻译目标或持久设置。
 */
internal fun ArticleListReaderViewModel.performShowOriginalContentForCitation() {
    if (_translationUiState.value.isLoading) {
        stopTranslation()
    }
    _translationUiState.update { state ->
        if (state.showTranslation) state.copy(showTranslation = false) else state
    }
}

/**
 * 默认翻译目标允许指向 AI 模型。若对应 AI 服务已被删除、停用或关闭 AI 阅读，
 * 则安全回退到传统默认 Provider，避免单击翻译落到失效配置。
 */
internal fun ArticleListReaderViewModel.performResolveDefaultTranslationTarget(): TranslationTarget {
    val translationSettings = translationSettingsRepository.current()
    return when (val requested = translationSettings.defaultTarget) {
        is TranslationTarget.Traditional -> requested
        is TranslationTarget.Ai -> {
            val aiSettings = aiSettingsRepository.current()
            val provider = aiSettings.providers.firstOrNull { it.id == requested.providerId }
            if (
                aiSettings.enabled &&
                    provider?.enabled == true &&
                    provider.endpoint.isNotBlank() &&
                    requested.model.isNotBlank()
            ) {
                requested.copy(providerName = provider.name)
            } else {
                TranslationTarget.Traditional(translationSettings.defaultProvider)
            }
        }
    }
}

/** 阅读页只展示真正具备运行条件的传统翻译服务。 */
internal fun ArticleListReaderViewModel.performIsTranslationProviderConfigured(type: TranslationProviderType): Boolean =
    translationSettingsRepository.current().provider(type).enabled &&
        translationSettingsRepository.isConfigured(type)

/** 翻译请求捕获文章、正文和目标，失败时恢复本次请求前的展示。 */
private data class ReaderTranslationRequest(
    val articleId: String, val title: String, val content: String,
    val target: TranslationTarget, val language: String, val serial: Long,
    val previous: ReaderTranslationUiState,
)

/** 统一翻译目标入口，保留相同目标与语言的已有译文。 */
internal fun ArticleListReaderViewModel.performTranslateWithTarget(target: TranslationTarget) {
    val state = _translationUiState.value
    val language = translationSettingsRepository.current().targetLanguage.trim().ifBlank {
        me.ash.reader.infrastructure.translation.TranslationSettings.defaultTargetLanguage()
    }
    if (state.document?.target == target && state.document.targetLanguage == language) {
        _translationUiState.update { it.copy(showTranslation = true) }
        return
    }
    val articleId = _readerState.value.articleId ?: return
    val content = _readerState.value.content.text.orEmpty()
    if (content.isBlank()) return
    translationJob?.cancel()
    val request = ReaderTranslationRequest(articleId, _readerState.value.title.orEmpty(), content,
        target, language, ++translationRequestSerial, state)
    // 先发布 loading，快速再次点击才能观察并取消正在进行的请求。
    _translationUiState.value = state.copy(isLoading = true, errorMessage = null)
    translationJob = viewModelScope.launch { executeTranslation(request) }
}

/** 成功与错误都按文章和代次发布，取消不转换成用户错误。 */
private suspend fun ArticleListReaderViewModel.executeTranslation(request: ReaderTranslationRequest) {
    try {
        val document = translationService.translateArticle(articleId = request.articleId,
            title = request.title, content = request.content, target = request.target,
            targetLanguageOverride = request.language)
        if (isCurrentTranslation(request) && document.target == request.target && document.targetLanguage == request.language) {
            _translationUiState.value = ReaderTranslationUiState(document = document, showTranslation = true)
        }
    } catch (error: CancellationException) {
        // 换文、切换正文模式或主动停止继续传播取消。
        throw error
    } catch (error: Exception) {
        // 普通翻译失败保留前次展示，仅当前请求允许写入错误。
        if (isCurrentTranslation(request)) _translationUiState.value = request.previous.copy(
            isLoading = false, errorMessage = error.message ?: "翻译失败")
    }
}

/** 目标改变或新请求开始后旧翻译不能重新切换页面。 */
private fun ArticleListReaderViewModel.isCurrentTranslation(request: ReaderTranslationRequest): Boolean =
    _readerState.value.articleId == request.articleId && translationRequestSerial == request.serial

/** 长按菜单选择传统 Provider 后立即翻译；保留该方法供旧调用点复用。 */
internal fun ArticleListReaderViewModel.performTranslateWithProvider(type: TranslationProviderType) {
    translateWithTarget(TranslationTarget.Traditional(type))
}

/** 清除翻译错误提示，保留已有译文。 */
internal fun ArticleListReaderViewModel.performClearTranslationError() {
    _translationUiState.update { it.copy(errorMessage = null) }
}

/** 正文或文章改变时取消翻译并清空旧展示。 */
internal fun ArticleListReaderViewModel.resetTranslation() {
    translationRequestSerial++
    translationJob?.cancel()
    translationJob = null
    _translationUiState.value = ReaderTranslationUiState()
}
