package me.ash.reader.ui.page.settings.json

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.ai.AiGeneratedRulePreview
import me.ash.reader.infrastructure.ai.AiRuleGenerationProgress
import me.ash.reader.infrastructure.ai.AiRuleGenerationService
import me.ash.reader.infrastructure.ai.AiRuleGenerationStage
import me.ash.reader.infrastructure.ai.AiSettings
import me.ash.reader.infrastructure.ai.AiSettingsRepository
import me.ash.reader.infrastructure.ai.aiRuleGenerationUserMessage
import me.ash.reader.infrastructure.ai.resolvedDefaultModel
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.ui.page.settings.readRuleDocument
import me.ash.reader.ui.page.settings.writeRuleDocument

data class JsonRulesUiState(
    val rules: List<JsonRule> = emptyList(),
    val aiGenerating: Boolean = false,
    val aiProgress: AiRuleGenerationProgress? = null,
    val aiPreview: AiGeneratedRulePreview? = null,
    val aiError: String? = null,
    val aiNotice: String? = null,
    val aiSettings: AiSettings = AiSettings(),
    val selectedAiProviderId: String = aiSettings.defaultProviderId,
    val selectedAiModel: String = aiSettings.defaultProvider()?.resolvedDefaultModel().orEmpty(),
)

@HiltViewModel
class JsonRulesViewModel @Inject constructor(
    private val repository: JsonRuleRepository,
    private val aiRuleGenerationService: AiRuleGenerationService,
    private val aiSettingsRepository: AiSettingsRepository,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(JsonRulesUiState())
    val uiState: StateFlow<JsonRulesUiState> = _uiState.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            aiSettingsRepository.settings.collect { settings ->
                _uiState.update { state ->
                    val providerId =
                        state.selectedAiProviderId.takeIf { id -> settings.providers.any { it.id == id } }
                            ?: settings.defaultProviderId
                    val provider = settings.providers.firstOrNull { it.id == providerId }
                    state.copy(
                        aiSettings = settings,
                        selectedAiProviderId = providerId,
                        selectedAiModel =
                            when {
                                providerId != state.selectedAiProviderId -> provider?.resolvedDefaultModel().orEmpty()
                                state.selectedAiModel.isBlank() -> provider?.resolvedDefaultModel().orEmpty()
                                else -> state.selectedAiModel
                            },
                    )
                }
            }
        }
    }

    /** 发布规则列表；导入时传入 IO 线程已读取的列表，避免在主线程重新读文件。 */
    fun reload(rules: List<JsonRule> = repository.listRules()) {
        _uiState.update { it.copy(rules = rules) }
    }

    /** IO 线程负责打开、读取、解析、保存和重读规则，主线程只发布结果。 */
    fun importRules(openInput: () -> InputStream?, callback: (Result<Int>) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            val result = readRuleDocument(openInput) { content ->
                repository.importRules(content) to repository.listRules()
            }
            withContext(mainDispatcher) {
                result.onSuccess { reload(it.second) }
                callback(result.map { it.first })
            }
        }
    }

    /** 导出原规则格式；文档提供方调用、序列化和流关闭均在 IO 线程完成。 */
    fun exportRules(openOutput: () -> OutputStream?, callback: (Result<Unit>) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            val result = writeRuleDocument(openOutput, repository::exportRules)
            withContext(mainDispatcher) { callback(result) }
        }
    }

    /** 模板与普通导出使用相同的文件生命周期和错误反馈。 */
    fun exportTemplate(openOutput: () -> OutputStream?, callback: (Result<Unit>) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            val result = writeRuleDocument(openOutput, repository::exportTemplate)
            withContext(mainDispatcher) { callback(result) }
        }
    }

    fun setEnabled(rule: JsonRule, enabled: Boolean) {
        repository.setEnabled(rule.id, enabled)
        reload()
    }

    fun delete(rule: JsonRule) {
        repository.deleteRule(rule.id)
        reload()
    }

    /** 根据公开 JSON API 或 Next/Nuxt 页面生成候选规则，并在服务层用真实数据试跑。 */
    fun generateAiRule(url: String) {
        if (_uiState.value.aiGenerating) return
        val state = _uiState.value
        viewModelScope.launch(ioDispatcher) {
            _uiState.update {
                it.copy(
                    aiGenerating = true,
                    aiProgress = AiRuleGenerationProgress(AiRuleGenerationStage.PREPARING),
                    aiPreview = null,
                    aiError = null,
                    aiNotice = null,
                )
            }
            val result =
                runCatching {
                    aiRuleGenerationService.generateJsonRule(
                        url = url,
                        providerId = state.selectedAiProviderId,
                        modelOverride = state.selectedAiModel,
                        onProgress = { progress -> _uiState.update { it.copy(aiProgress = progress) } },
                    )
                }
            withContext(mainDispatcher) {
                val error = result.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        aiGenerating = false,
                        aiPreview = result.getOrNull(),
                        aiProgress =
                            if (error == null) {
                                AiRuleGenerationProgress(
                                    AiRuleGenerationStage.COMPLETED,
                                    result.getOrNull()?.attempts ?: 1,
                                )
                            } else {
                                AiRuleGenerationProgress(
                                    AiRuleGenerationStage.FAILED,
                                    detail = error?.let(::aiRuleGenerationUserMessage),
                                )
                            },
                        aiError = error?.let(::aiRuleGenerationUserMessage),
                    )
                }
            }
        }
    }

    /** 只有用户明确确认后才保存 AI 候选。 */
    fun saveAiPreview() {
        val preview = _uiState.value.aiPreview ?: return
        runCatching { aiRuleGenerationService.save(preview) }
            .onSuccess {
                _uiState.update {
                    it.copy(
                        aiPreview = null,
                        aiError = null,
                        aiNotice = "规则已保存：${preview.name}",
                    )
                }
                reload()
            }
            .onFailure { error ->
                _uiState.update { it.copy(aiError = error.message ?: "AI 规则保存失败") }
            }
    }

    fun dismissAiPreview() {
        _uiState.update { it.copy(aiPreview = null) }
    }

    fun clearAiError() {
        _uiState.update { it.copy(aiError = null) }
    }

    fun clearAiNotice() {
        _uiState.update { it.copy(aiNotice = null) }
    }

    fun selectAiProvider(providerId: String) {
        val provider = _uiState.value.aiSettings.providers.firstOrNull { it.id == providerId } ?: return
        _uiState.update {
            it.copy(
                selectedAiProviderId = providerId,
                selectedAiModel = provider.resolvedDefaultModel().orEmpty(),
            )
        }
    }

    fun setAiModel(model: String) {
        _uiState.update { it.copy(selectedAiModel = model) }
    }
}
