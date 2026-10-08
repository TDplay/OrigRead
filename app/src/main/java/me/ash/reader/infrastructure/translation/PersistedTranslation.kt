package me.ash.reader.infrastructure.translation

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** 只描述译文的所有者，不携带正文、账号凭据或网络配置。 */
data class TranslationOwner(val accountId: Int, val feedId: String, val articleId: String)

enum class TranslationContentKind { LIST, FULL }

/** 显示模式不进入缓存键；FULL 保存标题和有序译文块，LIST 保存标题和短摘要。 */
data class PersistedTranslation(
    val owner: TranslationOwner,
    val kind: TranslationContentKind,
    val sourceHash: String,
    val target: TranslationTarget,
    val language: String,
    val promptVariant: String,
    val texts: List<String>,
    val createdAt: Long,
    val sourceLanguage: String? = null,
) {
    val expiresAt: Long get() = createdAt + TRANSLATION_RETENTION_MS
    val key: String get() = translationHash(JSONArray(listOf(
        kind.name, sourceHash, target.storageKey(), language, promptVariant,
    )).toString())
    fun isFresh(now: Long): Boolean = createdAt > 0L && createdAt <= now && now < expiresAt
}

data class SavedTranslationSelection(val entry: PersistedTranslation, val showTranslation: Boolean)

internal const val TRANSLATION_RETENTION_MS = 30L * 24 * 60 * 60 * 1_000
internal const val TRANSLATION_STORAGE_VERSION = 3
internal fun translationSourceHash(title: String, content: String): String =
    translationHash(JSONArray(listOf(title, content)).toString())
internal fun translationHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun TranslationTarget.storageKey(): String = when (this) {
    is TranslationTarget.Traditional -> "traditional:${provider.name}"
    is TranslationTarget.Ai -> "ai:$providerId:$model"
}

internal fun encodeTranslationTarget(target: TranslationTarget): JSONObject = when (target) {
    is TranslationTarget.Traditional -> JSONObject().put("type", "traditional").put("provider", target.provider.name)
    is TranslationTarget.Ai -> JSONObject().put("type", "ai").put("providerId", target.providerId)
        .put("providerName", target.providerName).put("model", target.model)
}
internal fun decodeTranslationTarget(json: JSONObject): TranslationTarget = when (json.getString("type")) {
    "traditional" -> TranslationTarget.Traditional(TranslationProviderType.valueOf(json.getString("provider")))
    "ai" -> TranslationTarget.Ai(json.getString("providerId"), json.optString("providerName", "AI"), json.getString("model"))
    else -> error("Unknown translation target")
}

internal fun PersistedTranslation.encode(): String = JSONObject()
    .put("version", TRANSLATION_STORAGE_VERSION)
    .put("accountId", owner.accountId).put("feedId", owner.feedId).put("articleId", owner.articleId)
    .put("kind", kind.name).put("sourceHash", sourceHash).put("target", encodeTranslationTarget(target))
    .put("language", language).put("promptVariant", promptVariant).put("texts", JSONArray(texts))
    .put("createdAt", createdAt).put("sourceLanguage", sourceLanguage ?: JSONObject.NULL).toString()

internal fun decodePersistedTranslation(value: String): PersistedTranslation {
    val json = JSONObject(value)
    require(json.getInt("version") == TRANSLATION_STORAGE_VERSION)
    val array = json.getJSONArray("texts")
    val entry = PersistedTranslation(
        TranslationOwner(json.getInt("accountId"), json.getString("feedId"), json.getString("articleId")),
        TranslationContentKind.valueOf(json.getString("kind")), json.getString("sourceHash"),
        decodeTranslationTarget(json.getJSONObject("target")), json.getString("language"),
        json.getString("promptVariant"), List(array.length()) { array.getString(it) }, json.getLong("createdAt"),
        if (json.isNull("sourceLanguage")) null else json.getString("sourceLanguage"),
    )
    require(entry.owner.feedId.isNotBlank() && entry.owner.articleId.isNotBlank())
    require(entry.sourceHash.matches(Regex("[a-f0-9]{64}")) && entry.language.isNotBlank())
    require(entry.texts.isNotEmpty() && entry.createdAt > 0 && entry.createdAt <= Long.MAX_VALUE - TRANSLATION_RETENTION_MS)
    require(entry.kind != TranslationContentKind.LIST || entry.texts.size == 2)
    return entry
}
