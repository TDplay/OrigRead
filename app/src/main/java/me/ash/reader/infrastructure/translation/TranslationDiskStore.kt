package me.ash.reader.infrastructure.translation

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 本机译文文件存储。调用者在 IO dispatcher 使用；不发请求、不存任务游标、不修改文章。
 * 参照 DiskLruCache 的未提交文件不可读策略；使用会抛错的原子 move，避免 issue #70 的静默 rename 失败。
 * 同一实例串行文件发布/清理；数据库删除先使读取失效，再由 invalidation/每日清理移除文件。
 */
internal class TranslationDiskStore(
    private val root: File,
    private val existingOwners: suspend (List<TranslationOwner>) -> Set<TranslationOwner>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 128L * 1024 * 1024,
    private val publish: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) {
    private val mutex = Mutex()
    private data class Metadata(val owner: TranslationOwner, val createdAt: Long, val bytes: Long)
    private val index = LinkedHashMap<File, Metadata>()
    private var indexed = false
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    suspend fun read(
        owner: TranslationOwner, kind: TranslationContentKind, hash: String,
        target: TranslationTarget, language: String, variant: String,
    ): PersistedTranslation? = mutex.withLock {
        if (owner !in existingOwners(listOf(owner))) { removeOwner(owner); return@withLock null }
        val probe = PersistedTranslation(owner, kind, hash, target, language, variant, emptyList(), 1L)
        readEntry(entryFile(probe))?.takeIf {
            it.owner == owner && it.kind == kind && it.sourceHash == hash &&
                it.target.storageKey() == target.storageKey() && it.language == language && it.promptVariant == variant
        }?.copy(target = target)
    }

    suspend fun selected(
        owner: TranslationOwner, kind: TranslationContentKind, hash: String, language: String,
    ): SavedTranslationSelection? = mutex.withLock {
        if (owner !in existingOwners(listOf(owner))) { removeOwner(owner); return@withLock null }
        val selectionFile = File(kindDirectory(owner, kind), "selected.json")
        val selection = readJson(selectionFile) ?: return@withLock null
        val key = selection.optString("key")
        if (!key.matches(Regex("[a-f0-9]{64}"))) return@withLock null
        val entry = readEntry(File(selectionFile.parentFile, "$key.json")) ?: return@withLock null
        if (entry.owner != owner || entry.kind != kind || entry.sourceHash != hash || entry.language != language) return@withLock null
        SavedTranslationSelection(entry, selection.optBoolean("show", false))
    }

    /** 网络已结束才进入存储；失败不覆盖原文件，删除后的晚结果不能留下可复用译文。 */
    suspend fun write(entry: PersistedTranslation): Boolean = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!entry.isFresh(clock()) || entry.owner !in existingOwners(listOf(entry.owner))) return@withLock false
        val file = entryFile(entry)
        atomicWrite(file, entry.encode())
        if (entry.owner !in existingOwners(listOf(entry.owner))) {
            removeOwner(entry.owner)
            return@withLock false
        }
        index[file] = Metadata(entry.owner, entry.createdAt, file.length())
        writeSelection(entry, true)
        trimToSize()
        file.exists()
    }

    /** 切换原文/译文只写选择指针，不触碰内容文件或生成日期。 */
    suspend fun select(entry: PersistedTranslation, show: Boolean) = mutex.withLock {
        if (entry.owner in existingOwners(listOf(entry.owner)) && readEntry(entryFile(entry)) != null) {
            writeSelection(entry, show)
        }
    }

    /** 启动/每天执行完整文件巡检；平常数据库变化只查内存索引中的 ID，不重新读所有译文。 */
    suspend fun sweep() = mutex.withLock {
        index.clear()
        if (root.exists()) {
            root.walkTopDown().filter(File::isFile).forEach { file ->
                currentCoroutineContext().ensureActive()
                when {
                    file.name.endsWith(".pending") -> file.delete()
                    file.name != "selected.json" && file.extension == "json" -> readEntry(file)
                }
            }
        }
        indexed = true
        removeMissingOwnersLocked()
        trimToSize()
        // 清掉没有正文文件的指针和空目录，不触及其他应用缓存。
        if (root.exists()) root.walkBottomUp().filter(File::isDirectory).forEach { directory ->
            val children = directory.listFiles().orEmpty()
            if (children.all { it.name == "selected.json" }) {
                children.forEach(File::delete)
                directory.delete()
            }
        }
    }

    suspend fun removeMissingOwners() = mutex.withLock {
        // 启动巡检负责建立索引；在它之前也会检查本进程已经读/写的条目。
        removeMissingOwnersLocked()
    }

    suspend fun ensureIndexed() { if (!indexed) sweep() }

    private suspend fun removeMissingOwnersLocked() {
        val owners = index.values.map { it.owner }.distinct()
        for (batch in owners.chunked(400)) {
            currentCoroutineContext().ensureActive()
            val valid = existingOwners(batch)
            batch.filterNot(valid::contains).forEach(::removeOwner)
        }
    }

    private fun ownerDirectory(owner: TranslationOwner) = root.resolve(owner.accountId.toString())
        .resolve(translationHash(owner.feedId)).resolve(translationHash(owner.articleId))
    private fun kindDirectory(owner: TranslationOwner, kind: TranslationContentKind) = ownerDirectory(owner).resolve(kind.name)
    private fun entryFile(entry: PersistedTranslation) = kindDirectory(entry.owner, entry.kind).resolve("${entry.key}.json")

    private fun readEntry(file: File): PersistedTranslation? {
        if (!file.isFile) { index.remove(file); return null }
        val entry = try {
            if (file.length() > MAX_ENTRY_BYTES) null else decodePersistedTranslation(file.readText(Charsets.UTF_8))
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null } catch (_: IllegalStateException) { null } catch (_: org.json.JSONException) { null }
        if (entry == null || !entry.isFresh(clock()) || entryFile(entry) != file) {
            if (file.delete()) mutableRevision.value++
            index.remove(file)
            return null
        }
        index[file] = Metadata(entry.owner, entry.createdAt, file.length())
        return entry
    }

    private fun readJson(file: File): JSONObject? = try {
        if (file.isFile && file.length() < 4_096) JSONObject(file.readText(Charsets.UTF_8)) else null
    } catch (_: IOException) { null } catch (_: org.json.JSONException) { null }

    private suspend fun writeSelection(entry: PersistedTranslation, show: Boolean) = atomicWrite(
        File(kindDirectory(entry.owner, entry.kind), "selected.json"),
        JSONObject().put("key", entry.key).put("show", show).toString(),
    )

    private suspend fun atomicWrite(file: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_ENTRY_BYTES) throw IOException("Translation cache entry is too large")
        val directory = requireNotNull(file.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create translation cache directory")
        val pending = File.createTempFile("translation-", ".pending", directory)
        try {
            FileOutputStream(pending).use { output -> output.write(bytes); output.fd.sync() }
            currentCoroutineContext().ensureActive()
            publish(pending, file)
        } finally { pending.delete() }
    }

    private fun removeOwner(owner: TranslationOwner) {
        val files = index.filterValues { it.owner == owner }.keys.toList()
        val directory = ownerDirectory(owner)
        val existed = directory.exists()
        val deleted = directory.deleteRecursively()
        if (existed || files.isNotEmpty()) mutableRevision.value++
        if (deleted) files.forEach(index::remove)
    }

    private fun trimToSize() {
        var bytes = index.values.sumOf { it.bytes }
        if (bytes <= maxBytes) return
        for ((file, meta) in index.entries.sortedBy { it.value.createdAt }) {
            if (bytes <= maxBytes) break
            if (!file.exists() || file.delete()) {
                index.remove(file)
                bytes -= meta.bytes
                mutableRevision.value++
            }
        }
    }

    private companion object { const val MAX_ENTRY_BYTES = 8L * 1024 * 1024 }
}
