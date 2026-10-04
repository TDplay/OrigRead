package me.ash.reader.ui.page.adaptive

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.ai.AiException
import me.ash.reader.infrastructure.ai.AiPerfTracer
import me.ash.reader.infrastructure.ai.AiSummaryLength
import me.ash.reader.infrastructure.ai.AiSummaryProgressStage
import me.ash.reader.infrastructure.ai.AiSummaryStreamUpdate
import me.ash.reader.infrastructure.ai.resolvedDefaultModel
import timber.log.Timber

/** 摘要进度按既有一秒周期更新，避免虚构百分比。 */
private const val SUMMARY_PROGRESS_INTERVAL_MILLIS = 1_000L

/** 非流式接口没有可靠百分比，因此只显示真实可观察的阶段和已等待时间。 */
internal fun ArticleListReaderViewModel.startAiSummaryProgressTicker(articleId: String) {
    val startedAt = SystemClock.elapsedRealtime()
    aiSummaryProgressJob =
        viewModelScope.launch {
            while (true) {
                delay(SUMMARY_PROGRESS_INTERVAL_MILLIS)
                if (_readerState.value.articleId != articleId) return@launch
                val elapsed =
                    ((SystemClock.elapsedRealtime() - startedAt) / SUMMARY_PROGRESS_INTERVAL_MILLIS).toInt().coerceAtLeast(0)
                _aiSummaryUiState.update { state ->
                    if (state.isLoading) state.copy(elapsedSeconds = elapsed) else state
                }
            }
        }
}

/** 摘要设置仅作用于本次请求，不改变默认服务配置。 */
internal data class ReaderSummaryOptions(
    val forceRefresh: Boolean = false,
    val providerId: String? = null,
    val modelOverride: String? = null,
    val lengthOverride: AiSummaryLength? = null,
)

/** 生成或重新展示摘要，保存正文与服务快照后交给受控请求执行。 */
internal fun ArticleListReaderViewModel.performSummarizeArticle(options: ReaderSummaryOptions) {
    val state = _aiSummaryUiState.value
    if (state.document != null && options == ReaderSummaryOptions()) {
        _aiSummaryUiState.update { it.copy(showPanel = true, errorMessage = null) }
        return
    }
    if (state.isLoading) return
    val articleId = _readerState.value.articleId ?: return
    val content = _readerState.value.content.text.orEmpty()
    if (content.isBlank()) return
    val settings = aiSettingsRepository.current()
    val provider = options.providerId?.let { id -> settings.providers.firstOrNull { it.id == id } }
        ?: settings.defaultProvider()
    if (provider == null) {
        _aiSummaryUiState.update { it.copy(errorMessage = "没有可用的 AI 服务") }
        return
    }
    val model = options.modelOverride?.trim().takeUnless { it.isNullOrBlank() }
        ?: provider.resolvedDefaultModel().orEmpty()
    val trace = AiPerfTracer.start("summary")
    AiPerfTracer.mark(trace, "ui_request_ready", "articleChars" to content.length, "providerId" to provider.id, "model" to model)
    aiSummaryJob?.cancel()
    aiSummaryProgressJob?.cancel()
    val request = ReaderSummaryRequest(articleId, _readerState.value.title.orEmpty(), content,
        provider, model, ++aiSummaryRequestSerial, options, trace)
    aiSummaryJob = viewModelScope.launch { executeSummaryRequest(request) }
}

/** 立即终止当前摘要任务，并让阅读页恢复到可再次生成的状态。 */
internal fun ArticleListReaderViewModel.performStopAiSummary() {
    aiSummaryRequestSerial++
    aiSummaryJob?.cancel()
    aiSummaryJob = null
    aiSummaryProgressJob?.cancel()
    aiSummaryProgressJob = null
    _aiSummaryUiState.update { state ->
        state.copy(
            isLoading = false,
            showPanel = state.document != null && state.showPanel,
            progressStage = null,
            elapsedSeconds = 0,
            errorMessage = null,
            streamingSummaryPreview = "",
            streamingReasoningPreview = "",
        )
    }
}

/** 关闭摘要面板，保留已生成文档。 */
internal fun ArticleListReaderViewModel.performDismissAiSummary() {
    _aiSummaryUiState.update { it.copy(showPanel = false) }
}

/** 清除摘要错误提示，保留已有结果。 */
internal fun ArticleListReaderViewModel.performClearAiSummaryError() {
    _aiSummaryUiState.update { it.copy(errorMessage = null) }
}

/** 正文或文章改变时取消摘要和计时器并清空旧展示。 */
internal fun ArticleListReaderViewModel.resetAiSummary() {
    aiSummaryRequestSerial++
    aiSummaryJob?.cancel()
    aiSummaryJob = null
    aiSummaryProgressJob?.cancel()
    aiSummaryProgressJob = null
    _aiSummaryUiState.value = ReaderAiSummaryUiState()
}
