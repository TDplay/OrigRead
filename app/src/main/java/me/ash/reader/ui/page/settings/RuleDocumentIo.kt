package me.ash.reader.ui.page.settings

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 在调用方的 IO 调度器上读完整规则文档；关闭流后才交给原规则仓库解析和保存。 */
internal suspend fun <T> readRuleDocument(
    openInput: () -> InputStream?,
    importContent: (String) -> T,
): Result<T> = ruleDocumentResult {
    val content = (openInput() ?: throw IOException("无法打开规则文件供读取"))
        .use { String(it.readBytes(), Charsets.UTF_8) }
    // 文档提供方的阻塞读取结束后，取消的页面任务不再执行规则导入。
    currentCoroutineContext().ensureActive()
    importContent(content)
}

/** 在调用方的 IO 调度器上生成原格式文档并完整写入，成功或失败都会关闭输出流。 */
internal suspend fun writeRuleDocument(
    openOutput: () -> OutputStream?,
    exportContent: () -> String,
): Result<Unit> = ruleDocumentResult {
    val content = exportContent().toByteArray(Charsets.UTF_8)
    currentCoroutineContext().ensureActive()
    (openOutput() ?: throw IOException("无法打开规则文件供写入"))
        .use { it.write(content) }
}

/** 文件和格式错误交给原页面反馈，协程取消仍沿原任务传播，不能显示导入/导出成功。 */
private suspend fun <T> ruleDocumentResult(block: suspend () -> T): Result<T> {
    return try {
        currentCoroutineContext().ensureActive()
        val value = block()
        currentCoroutineContext().ensureActive()
        Result.success(value)
    } catch (cancelled: CancellationException) {
        // 取消不属于文件导入失败，保留 ViewModel 生命周期的取消语义。
        throw cancelled
    } catch (error: Exception) {
        // 打开、读写、关闭或仓库解析失败统一回传，不把失败的文档操作当作成功。
        Result.failure(error)
    }
}
