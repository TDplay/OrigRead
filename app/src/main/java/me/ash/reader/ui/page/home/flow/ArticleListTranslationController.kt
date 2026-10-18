package me.ash.reader.ui.page.home.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.translation.PersistedTranslation
import me.ash.reader.infrastructure.translation.TranslationErrorCode
import me.ash.reader.infrastructure.translation.TranslationException
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.infrastructure.translation.TranslationTarget
import me.ash.reader.infrastructure.translation.storageKey
import timber.log.Timber

/** 不携带 Article/rawDescription/fullContent。feedId 用于持久缓存归属和删除。 */
data class ListTranslationInput(val accountId: Int, val articleId: String, val title: String,
    val description: String, val feedId: String = "") {
    companion object {
        fun of(accountId: Int, articleId: String, title: String, description: String, feedId: String = "") =
            ListTranslationInput(accountId, articleId, excerpt(title, 1_024), excerpt(description, 512), feedId)
        private fun excerpt(value: String, limit: Int): String {
            val text = value.trim()
            return text.substring(0, text.offsetByCodePoints(0, text.codePointCount(0, text.length).coerceAtMost(limit)))
        }
    }
}

data class ListTranslationResult(val source: ListTranslationInput, val title: String, val description: String,
    val persisted: PersistedTranslation? = null, val showTranslation: Boolean = true, val cacheWriteFailed: Boolean = false) {
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean = persisted?.isFresh(now) != false
}

data class ListTranslationState(
    val scopeKey: Any? = null, val target: TranslationTarget? = null, val language: String = "",
    val translations: Map<String, ListTranslationResult> = emptyMap(), val isLoading: Boolean = false,
    val showTranslation: Boolean = false, val completed: Int = 0, val total: Int = 0,
    val stopped: Boolean = false, val errorMessage: String? = null,
    val restoreRevision: Long = 0L,
)

/** 显式点击才能生成；restore 仅查本地。没有持久化作业、续翻游标或后台预翻译。 */
class ArticleListTranslationController(
    private val coroutineScope: CoroutineScope,
    private val translate: suspend (List<String>, TranslationTarget, String) -> List<String>,
    private val isScopeCurrent: (Any, Int) -> Boolean,
    private val readCached: suspend (ListTranslationInput, TranslationTarget?, String) -> ListTranslationResult? = { _, _, _ -> null },
    private val saveCached: suspend (ListTranslationResult, TranslationTarget, String) -> ListTranslationResult = { value, _, _ -> value },
    private val selectCached: suspend (ListTranslationResult, Boolean) -> Unit = { _, _ -> },
) {
    private val mutableState = MutableStateFlow(ListTranslationState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var serial = 0L
    private var manualVisibility: Boolean? = null
    private var restoredRevision = -1L

    fun reset(scopeKey: Any? = null) {
        stop()
        manualVisibility = null
        // Settings can reset the same pager without changing the loaded rows.
        mutableState.value = ListTranslationState(scopeKey = scopeKey,
            restoreRevision = mutableState.value.restoreRevision + 1L)
    }

    fun stop() {
        serial++
        job?.cancel()
        job = null
        val previous = mutableState.value
        if (previous.isLoading) mutableState.value = previous.copy(isLoading = false, stopped = true, errorMessage = null)
    }

    /** 当前已加载窗口恢复展示。换页/账户/新请求后晚到本地结果同样不得回写。 */
    suspend fun restore(scopeKey: Any, inputs: List<ListTranslationInput>, language: String, revision: Long = 0L) {
        if (mutableState.value.isLoading) return
        val rows = boundedRows(inputs)
        val accountId = rows.firstOrNull()?.accountId ?: return
        if (!isScopeCurrent(scopeKey, accountId)) return
        if (mutableState.value.scopeKey != scopeKey || (mutableState.value.language.isNotEmpty() && mutableState.value.language != language)) reset(scopeKey)
        val generation = serial
        val restored = rows.mapNotNull { row ->
            mutableState.value.translations[row.articleId]?.takeIf {
                restoredRevision == revision && it.source == row && it.isFresh()
            } ?: try {
                readCached(row, null, language)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A failed local lookup must not crash the list or authorize a network fallback.
                Timber.w(error, "Stored list translation could not be restored")
                null
            }
        }
        currentCoroutineContext().ensureActive()
        if (serial != generation || !isScopeCurrent(scopeKey, accountId) || mutableState.value.isLoading) return
        restoredRevision = revision
        val results = LinkedHashMap(mutableState.value.translations.filterValues { it.isFresh() })
        rows.forEach { results.remove(it.articleId) }
        restored.forEach { results[it.source.articleId] = it }
        trim(results, rows.mapTo(hashSetOf()) { it.articleId })
        mutableState.value = mutableState.value.copy(scopeKey = scopeKey, language = language,
            translations = results, target = mutableState.value.target ?: restored.firstOrNull()?.persisted?.target,
            showTranslation = manualVisibility ?: results.values.any { it.showTranslation })
    }

    fun discardExpired() {
        mutableState.value = mutableState.value.copy(translations = mutableState.value.translations.filterValues { it.isFresh() })
    }

    fun translateOrToggle(scopeKey: Any, inputs: List<ListTranslationInput>, target: TranslationTarget,
        language: String, forceShow: Boolean = false) {
        if (mutableState.value.isLoading && !forceShow) { stop(); return }
        val rows = boundedRows(inputs)
        val accountId = rows.firstOrNull()?.accountId ?: return
        if (!isScopeCurrent(scopeKey, accountId)) return
        val previous = mutableState.value
        val wasShowing = previous.showTranslation
        if (previous.scopeKey != scopeKey || previous.target?.storageKey() != target.storageKey() || previous.language != language) {
            reset(scopeKey)
            mutableState.value = mutableState.value.copy(target = target, language = language)
        }
        // 当前窗口的纯显示切换即时完成；磁盘只异步记录偏好，不显示生成进度。
        if (rows.all { row -> mutableState.value.translations[row.articleId]?.let {
                it.source == row && it.isFresh() && (it.persisted == null || it.persisted.target.storageKey() == target.storageKey())
            } == true }) {
            stop()
            val visible = forceShow || !wasShowing
            manualVisibility = visible
            val selected = rows.mapNotNull { mutableState.value.translations[it.articleId] }
            val results = mutableState.value.translations.toMutableMap()
            selected.forEach { results[it.source.articleId] = it.copy(showTranslation = visible) }
            mutableState.value = mutableState.value.copy(translations = results, showTranslation = visible,
                completed = rows.size, total = rows.size, stopped = false, errorMessage = null)
            job = coroutineScope.launch { selected.forEach { selectCached(it, visible) } }
            return
        }
        stop()
        val generation = ++serial
        mutableState.value = mutableState.value.copy(isLoading = true, stopped = false, errorMessage = null, completed = 0, total = rows.size)
        job = coroutineScope.launch {
            try {
                val results = LinkedHashMap(mutableState.value.translations.filterValues { it.isFresh() })
                // 正常点击也优先复用磁盘结果；缓存未命中才加入本次用户请求。
                for (row in rows) {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(generation, scopeKey, accountId)) return@launch
                    val memory = results[row.articleId]?.takeIf { it.source == row &&
                        (it.persisted == null || it.persisted.target.storageKey() == target.storageKey()) }
                    val cached = memory ?: readCached(row, target, language)
                    if (cached == null) results.remove(row.articleId) else results[row.articleId] = cached
                }
                if (!isCurrent(generation, scopeKey, accountId)) return@launch
                val pending = rows.filter { results[it.articleId]?.source != it }
                val visible = pending.isNotEmpty() || forceShow || !wasShowing
                manualVisibility = visible
                rows.mapNotNull { results[it.articleId] }.forEach { cached ->
                    selectCached(cached, visible)
                    results[cached.source.articleId] = cached.copy(showTranslation = visible)
                }
                if (!isCurrent(generation, scopeKey, accountId)) return@launch
                val protected = rows.mapTo(hashSetOf()) { it.articleId }
                trim(results, protected)
                mutableState.value = mutableState.value.copy(translations = results.toMap(), showTranslation = visible,
                    completed = rows.size - pending.size)
                val size = if (target == TranslationTarget.Traditional(TranslationProviderType.ML_KIT)) 1 else 8
                for (batch in pending.chunked(size)) {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(generation, scopeKey, accountId)) return@launch
                    val texts = batch.flatMap { listOf(it.title, it.description).filter(String::isNotBlank) }
                    val translated = translate(texts, target, language)
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(generation, scopeKey, accountId)) return@launch
                    if (translated.size != texts.size || translated.any(String::isBlank)) throw TranslationException(
                        TranslationErrorCode.INVALID_RESPONSE, "翻译结果不完整，请重试")
                    var index = 0
                    for (row in batch) {
                        val result = ListTranslationResult(row, if (row.title.isNotBlank()) translated[index++] else "",
                            if (row.description.isNotBlank()) translated[index++] else "")
                        val saved = saveCached(result, target, language)
                        currentCoroutineContext().ensureActive()
                        if (!isCurrent(generation, scopeKey, accountId)) return@launch
                        results.remove(row.articleId)
                        results[row.articleId] = saved
                        trim(results, protected)
                        mutableState.value = mutableState.value.copy(translations = results.toMap(),
                            completed = mutableState.value.completed + 1,
                            errorMessage = if (saved.cacheWriteFailed) "译文可阅读，但未能保存到本机" else mutableState.value.errorMessage)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (isCurrent(generation, scopeKey, accountId)) mutableState.value = mutableState.value.copy(errorMessage = error.message ?: "翻译失败")
            } finally {
                if (serial == generation) { mutableState.value = mutableState.value.copy(isLoading = false); job = null }
            }
        }
    }

    private fun boundedRows(inputs: List<ListTranslationInput>): List<ListTranslationInput> {
        val account = inputs.firstOrNull()?.accountId ?: return emptyList()
        return inputs.asSequence().filter { it.accountId == account && (it.title.isNotBlank() || it.description.isNotBlank()) }
            .distinctBy { it.articleId }.take(MAX_ITEMS_PER_REQUEST).toList()
    }
    private fun trim(results: LinkedHashMap<String, ListTranslationResult>, protected: Set<String>) {
        while (results.size > MAX_CACHED_ITEMS) results.remove(results.keys.first { it !in protected })
    }
    private fun isCurrent(generation: Long, scopeKey: Any, accountId: Int): Boolean =
        serial == generation && mutableState.value.scopeKey == scopeKey && isScopeCurrent(scopeKey, accountId)
    companion object { const val MAX_ITEMS_PER_REQUEST = 50; private const val MAX_CACHED_ITEMS = 200 }
}
