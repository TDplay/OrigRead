package me.ash.reader.ui.page.adaptive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import me.ash.reader.infrastructure.ai.AiException
import me.ash.reader.infrastructure.ai.AiPerfTrace
import me.ash.reader.infrastructure.ai.AiPerfTracer
import me.ash.reader.infrastructure.ai.AiProviderProfile
import me.ash.reader.infrastructure.ai.AiSummaryDocument
import me.ash.reader.infrastructure.ai.AiSummaryProgressStage
import me.ash.reader.infrastructure.ai.AiSummaryStreamUpdate
import timber.log.Timber

/** 摘要请求持有开始时的正文、目标和代次，避免异步回调访问新文章内容。 */
internal data class ReaderSummaryRequest(
    val articleId: String, val title: String, val content: String,
    val provider: AiProviderProfile, val model: String, val serial: Long,
    val options: ReaderSummaryOptions, val trace: AiPerfTrace,
)

/** 拆分既有摘要执行流程，保留进度、流式预览和结果发布规则。 */
internal suspend fun ArticleListReaderViewModel.executeSummaryRequest(request: ReaderSummaryRequest) {
    AiPerfTracer.mark(request.trace, "summary_job_started")
    _aiSummaryUiState.update { it.copy(isLoading = true, showPanel = true, errorMessage = null,
        progressStage = AiSummaryProgressStage.PREPARING, elapsedSeconds = 0,
        activeProviderId = request.provider.id, activeProviderName = request.provider.name, activeModel = request.model,
        streamingSummaryPreview = "", streamingReasoningPreview = "") }
    startAiSummaryProgressTicker(request.articleId)
    try {
        publishSummary(request, fetchSummary(request))
    } catch (error: CancellationException) {
        // 换文、停止或 ViewModel 清理保留取消传播，UI 不显示失败。
        throw error
    } catch (error: Exception) {
        // 普通服务异常保留日志，并仅向本次有效请求发布错误。
        Timber.e(error, "AI summary generation failed")
        if (isCurrentSummary(request)) _aiSummaryUiState.update {
            it.copy(errorMessage = if (error is AiException) error.message?.takeIf(String::isNotBlank)
                ?: "AI 摘要生成失败，请重试" else "AI 摘要生成失败，请重试")
        }
    } finally {
        finishSummary(request)
    }
}

/** 服务调用和阶段回调仍使用同一请求，首次可见流输出继续记录性能阶段。 */
private suspend fun ArticleListReaderViewModel.fetchSummary(request: ReaderSummaryRequest): AiSummaryDocument {
    var firstFeedbackVisible = false
    return aiSummaryService.summarizeArticle(
        articleId = request.articleId, title = request.title, content = request.content,
        forceRefresh = request.options.forceRefresh, providerId = request.provider.id,
        modelOverride = request.model, lengthOverride = request.options.lengthOverride,
        onProgress = { stage ->
            if (isCurrentSummary(request)) _aiSummaryUiState.update { it.copy(progressStage = stage) }
        },
        onStreamUpdate = { update ->
            if (publishSummaryStream(request, update) && !firstFeedbackVisible && update.hasVisibleContent) {
                firstFeedbackVisible = true
                AiPerfTracer.mark(request.trace, "ui_first_stream_visible",
                    "hasReasoning" to update.reasoningPreview.isNotBlank(), "hasContent" to update.summaryPreview.isNotBlank())
            }
        }, perfTrace = request.trace,
    )
}

/** 只有当前文章和摘要代次匹配时才展示流式预览。 */
private fun ArticleListReaderViewModel.publishSummaryStream(request: ReaderSummaryRequest, update: AiSummaryStreamUpdate): Boolean {
    if (!isCurrentSummary(request)) return false
    _aiSummaryUiState.update { it.copy(streamingSummaryPreview = update.summaryPreview,
        streamingReasoningPreview = update.reasoningPreview) }
    return true
}

/** 最终摘要替换预览，继续保留对应服务与模型信息。 */
private fun ArticleListReaderViewModel.publishSummary(request: ReaderSummaryRequest, document: AiSummaryDocument) {
    if (!isCurrentSummary(request)) return
    _aiSummaryUiState.update { it.copy(document = document, activeProviderId = document.providerId,
        activeProviderName = document.providerName, activeModel = document.model, errorMessage = null,
        streamingSummaryPreview = "", streamingReasoningPreview = "") }
    AiPerfTracer.mark(request.trace, "ui_result_visible", "summaryChars" to document.summary.length)
}

/** 旧摘要完成不能停止新摘要的计时器或清除新请求句柄。 */
private fun ArticleListReaderViewModel.finishSummary(request: ReaderSummaryRequest) {
    if (request.serial != aiSummaryRequestSerial) return
    aiSummaryProgressJob?.cancel()
    aiSummaryProgressJob = null
    if (isCurrentSummary(request)) _aiSummaryUiState.update { it.copy(isLoading = false,
        progressStage = null, streamingSummaryPreview = "", streamingReasoningPreview = "") }
    aiSummaryJob = null
}

/** 摘要 UI 代次随换文与全文替换失效。 */
private fun ArticleListReaderViewModel.isCurrentSummary(request: ReaderSummaryRequest): Boolean =
    request.serial == aiSummaryRequestSerial && _readerState.value.articleId == request.articleId
