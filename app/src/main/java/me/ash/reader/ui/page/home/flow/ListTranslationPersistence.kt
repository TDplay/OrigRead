package me.ash.reader.ui.page.home.flow

import me.ash.reader.infrastructure.translation.TranslationOwner
import me.ash.reader.infrastructure.translation.TranslationService
import me.ash.reader.infrastructure.translation.TranslationTarget

/** 列表边界只传标题/摘要；读取缓存和生成翻译是两个独立入口。 */
internal class ListTranslationPersistence(private val service: TranslationService) {
    suspend fun read(input: ListTranslationInput, target: TranslationTarget?, language: String): ListTranslationResult? {
        val cached = service.readListTranslation(input.owner(), input.title, input.description, target, language) ?: return null
        return ListTranslationResult(input, cached.entry.texts[0], cached.entry.texts[1],
            persisted = cached.entry, showTranslation = cached.showTranslation)
    }
    suspend fun save(result: ListTranslationResult, target: TranslationTarget, language: String): ListTranslationResult {
        val input = result.source
        val saved = service.saveListTranslation(input.owner(), input.title, input.description,
            result.title, result.description, target, language)
        return result.copy(persisted = saved, cacheWriteFailed = saved == null)
    }
    suspend fun select(result: ListTranslationResult, show: Boolean) {
        result.persisted?.let { service.setTranslationVisible(it, show) }
    }
    private fun ListTranslationInput.owner() = TranslationOwner(accountId, feedId, articleId)
}
