package me.ash.reader.infrastructure.translation

import java.io.File
import java.io.IOException
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.infrastructure.ai.AiTaskPromptCustomization
import org.json.JSONObject

/** v2 只读兼容：自然命中才读取；不全量重译，不用迁移时钟把旧文件重新续期。 */
internal class LegacyTranslationCache(private val root: File, private val articleDao: ArticleDao) {
    fun read(owner: TranslationOwner, title: String, content: String, target: TranslationTarget,
        language: String, mode: TranslationDisplayMode, variant: String): TranslationDocument? {
        val parts = mutableListOf("2", owner.articleId, translationHash(title), translationHash(content),
            target.storageKey(), language, mode.name)
        if (variant != AiTaskPromptCustomization.DEFAULT_CACHE_VARIANT) parts += variant
        val file = root.resolve(owner.accountId.toString()).resolve(translationHash(parts.joinToString(":")) + ".json")
        return try {
            val created = file.lastModified()
            val now = System.currentTimeMillis()
            if (!file.isFile || created <= 0 || created > now || now - created >= TRANSLATION_RETENTION_MS || file.length() > 8L * 1024 * 1024) {
                if (file.isFile) file.delete()
                return null
            }
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.getString("articleId") != owner.articleId || json.getString("targetLanguage") != language ||
                decodeTranslationTarget(json.getJSONObject("target")).storageKey() != target.storageKey() ||
                json.getString("displayMode") != mode.name) return null
            TranslationDocument(owner.articleId, target, language,
                json.optString("sourceLanguage").takeUnless { it.isBlank() || it == "null" }, mode,
                json.getString("translatedTitle"), json.getString("translatedContent"),
                expiresAt = created + TRANSLATION_RETENTION_MS)
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null } catch (_: org.json.JSONException) { null }
    }

    suspend fun cleanup() {
        if (!root.isDirectory) return
        // 老文件没有 feedId；分批查询身份，不读取全库文章正文。
        root.walkTopDown().filter { it.isFile && it.extension == "json" }.chunked(400).forEach { files ->
            val now = System.currentTimeMillis()
            val candidates = files.mapNotNull { file ->
                try {
                    if (file.lastModified() <= 0 || file.lastModified() > now ||
                        now - file.lastModified() >= TRANSLATION_RETENTION_MS || file.length() > 8L * 1024 * 1024) {
                        file.delete(); null
                    } else file to JSONObject(file.readText(Charsets.UTF_8)).getString("articleId")
                } catch (_: IOException) { null } catch (_: org.json.JSONException) { file.delete(); null }
            }
            val owners = if (candidates.isEmpty()) emptyList() else articleDao.queryTranslationOwners(candidates.map { it.second }.distinct())
            val identities = owners.mapTo(hashSetOf()) { it.accountId.toString() to it.articleId }
            candidates.forEach { (file, id) -> if (file.parentFile?.name to id !in identities) file.delete() }
        }
    }
}
