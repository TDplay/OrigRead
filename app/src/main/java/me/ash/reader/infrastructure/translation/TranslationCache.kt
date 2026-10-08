package me.ash.reader.infrastructure.translation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.infrastructure.di.IODispatcher
import timber.log.Timber

/** LIST/FULL 共用本机持久存储；所有者显式传入，不再读取当前 UI 账户。 */
@Singleton
class TranslationCache @Inject constructor(
    @ApplicationContext context: Context,
    private val articleDao: ArticleDao,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val store = TranslationDiskStore(context.noBackupFilesDir.resolve("translations"), ::existingOwners)
    private val legacy = LegacyTranslationCache(context.cacheDir.resolve("translations"), articleDao)
    val invalidations = store.revision

    suspend fun existingOwners(owners: List<TranslationOwner>): Set<TranslationOwner> = withContext(ioDispatcher) {
        if (owners.isEmpty()) emptySet() else owners.map { it.articleId }.distinct().chunked(400)
            .flatMap { articleDao.queryTranslationOwners(it) }.toSet().intersect(owners.toSet())
    }

    suspend fun read(owner: TranslationOwner, kind: TranslationContentKind, hash: String,
        target: TranslationTarget, language: String, variant: String): PersistedTranslation? = withContext(ioDispatcher) {
        store.read(owner, kind, hash, target, language, variant)
    }

    suspend fun selected(owner: TranslationOwner, kind: TranslationContentKind, hash: String,
        language: String): SavedTranslationSelection? = withContext(ioDispatcher) {
        store.selected(owner, kind, hash, language)
    }

    /** 保存失败不吞取消、不影响已得到的正文；调用方展示独立的未保存提示。 */
    suspend fun write(entry: PersistedTranslation): Boolean = withContext(ioDispatcher) {
        try {
            store.ensureIndexed()
            store.write(entry)
        } catch (error: IOException) {
            Timber.w(error, "Translation could not be saved locally")
            false
        }
    }

    suspend fun select(entry: PersistedTranslation, show: Boolean): Boolean = withContext(ioDispatcher) {
        try { store.select(entry, show); true }
        catch (error: IOException) { Timber.w(error, "Translation display preference could not be saved"); false }
    }

    suspend fun removeMissingOwners() = withContext(ioDispatcher) { store.removeMissingOwners() }

    suspend fun maintain() = withContext(ioDispatcher) {
        store.sweep()
        legacy.cleanup()
    }

    suspend fun readLegacy(owner: TranslationOwner, title: String, content: String, target: TranslationTarget,
        language: String, mode: TranslationDisplayMode, variant: String): TranslationDocument? = withContext(ioDispatcher) {
        if (owner !in existingOwners(listOf(owner))) null
        else legacy.read(owner, title, content, target, language, mode, variant)
    }
}
