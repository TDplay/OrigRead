package me.ash.reader.ui.page.settings.backup

import android.content.Intent
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.backup.ConfigurationBackupService
import me.ash.reader.infrastructure.backup.ConfigurationBackupSummary
import me.ash.reader.infrastructure.backup.ConfigurationRestoreResult
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.editionsync.EditionSyncTransferManager
import me.ash.reader.R

/** 一次导出的目标与加密选择快照；密码只在调用期间使用。 */
data class BackupExportRequest(val uri: Uri, val includeSecrets: Boolean, val password: String)

data class ConfigurationBackupUiState(
    val isWorking: Boolean = false,
    val pendingBackup: String? = null,
    val pendingSummary: ConfigurationBackupSummary? = null,
    val peerEditionInstalled: Boolean = false,
)

@HiltViewModel
class ConfigurationBackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupService: ConfigurationBackupService,
    private val editionSyncTransferManager: EditionSyncTransferManager,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            ConfigurationBackupUiState(
                peerEditionInstalled = editionSyncTransferManager.isPeerInstalled(),
            )
        )
    val uiState: StateFlow<ConfigurationBackupUiState> = _uiState.asStateFlow()

    fun refreshPeerEditionInstalled() {
        _uiState.update {
            it.copy(peerEditionInstalled = editionSyncTransferManager.isPeerInstalled())
        }
    }

    /**
     * 创建发送给另一 Edition 的一次性加密同步 Intent。
     * 公共 AI/翻译凭据固定包含在 Edition Sync 中；普通配置备份的 Secret 开关不影响同机直传。
     * 快照、配置与凭据收集都在 IO dispatcher 执行，Intent 仅在全部数据准备成功后交回 UI 启动。
     */
    fun createEditionSyncIntent(callback: (Result<Intent>) -> Unit) {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true) }
        viewModelScope.launch(ioDispatcher) {
            val result =
                runCatching {
                    editionSyncTransferManager.createPeerTransferIntent()
                }
            withContext(mainDispatcher) {
                _uiState.update { it.copy(isWorking = false) }
                callback(result)
            }
        }
    }

    /** 生成、开流、写入和关闭全部在 IO 执行；成功回调代表文件已经写完。 */
    fun exportBackup(request: BackupExportRequest, callback: (Result<Unit>) -> Unit) =
        executeDocumentOperation(operation = {
            val bytes = backupService.exportBackup(request.includeSecrets, request.password).toByteArray(Charsets.UTF_8)
            val stream = context.contentResolver.openOutputStream(request.uri, "wt")
                ?: error(context.getString(R.string.configuration_backup_write_failed))
            stream.use { it.write(bytes) }
        }, complete = callback)

    /** 读取并校验文件头，成功后显示恢复确认信息。 */
    fun inspectBackup(uri: Uri, callback: (Result<ConfigurationBackupSummary>) -> Unit) =
        executeDocumentOperation(operation = {
            val stream = context.contentResolver.openInputStream(uri)
                ?: error(context.getString(R.string.configuration_backup_read_failed))
            val raw = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            raw to backupService.inspectBackup(raw)
        }, complete = { result ->
            result.onSuccess { (raw, summary) ->
                _uiState.update { it.copy(pendingBackup = raw, pendingSummary = summary) }
            }
            callback(result.map { it.second })
        })

    /** 文档提供器可能等待云端下载；UI 只管理状态和结果，取消保留协程语义。 */
    private fun <T> executeDocumentOperation(operation: suspend () -> T, complete: (Result<T>) -> Unit) {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true) }
        viewModelScope.launch(mainDispatcher) {
            try {
                val result = withContext(ioDispatcher) {
                    try {
                        Result.success(operation())
                    } catch (error: CancellationException) {
                        // 离开页面或清理 ViewModel 时停止发布结果，不显示成功或用户错误。
                        throw error
                    } catch (error: Exception) {
                        // 开流、读写、关闭或格式校验失败向页面报告具体异常。
                        Result.failure(error)
                    }
                }
                _uiState.update { it.copy(isWorking = false) }
                complete(result)
            } finally {
                _uiState.update { it.copy(isWorking = false) }
            }
        }
    }

    fun dismissRestore() {
        _uiState.update { it.copy(pendingBackup = null, pendingSummary = null) }
    }

    /** 执行已经预览过的备份恢复。 */
    fun restoreBackup(password: String, callback: (Result<ConfigurationRestoreResult>) -> Unit) {
        val content = _uiState.value.pendingBackup ?: return
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true) }
        viewModelScope.launch(ioDispatcher) {
            val result = runCatching { backupService.restoreBackup(content, password) }
            withContext(mainDispatcher) {
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        pendingBackup = if (result.isSuccess) null else it.pendingBackup,
                        pendingSummary = if (result.isSuccess) null else it.pendingSummary,
                    )
                }
                callback(result)
            }
        }
    }
}
