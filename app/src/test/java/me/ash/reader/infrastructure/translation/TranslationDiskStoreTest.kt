package me.ash.reader.infrastructure.translation

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 真实临时文件 + 可控时钟/文章归属；不访问网络或用户数据库。 */
class TranslationDiskStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = TranslationOwner(1, "feed", "article")
    private val target = TranslationTarget.Traditional(TranslationProviderType.MICROSOFT)
    private var now = 1_791_000_000_000L
    private val alive = mutableSetOf(owner)
    private val root get() = File(temporary.root, "translations")
    private fun store(maxBytes: Long = 1024 * 1024) = TranslationDiskStore(root,
        { owners -> owners.filterTo(hashSetOf()) { it in alive } }, { now }, maxBytes)
    private fun entry(kind: TranslationContentKind = TranslationContentKind.LIST) = PersistedTranslation(
        owner, kind, translationSourceHash("Title", "Preview"), target, "zh-CN", "default",
        listOf("标题", "摘要"), now)
    private suspend fun TranslationDiskStore.find(value: PersistedTranslation) = read(value.owner, value.kind,
        value.sourceHash, value.target, value.language, value.promptVariant)

    @Test fun completedResultAndSelectionSurviveStoreRecreation() = runBlocking {
        val value = entry()
        assertTrue(store().write(value))
        val reopened = store()
        assertEquals(value, reopened.find(value))
        assertEquals(true, reopened.selected(owner, value.kind, value.sourceHash, value.language)?.showTranslation)
        reopened.select(value, false)
        assertEquals(false, store().selected(owner, value.kind, value.sourceHash, value.language)?.showTranslation)
    }

    @Test fun readingAndChangingDisplayDoNotExtendThirtyDays() = runBlocking {
        val value = entry()
        val cache = store()
        cache.write(value)
        now += TRANSLATION_RETENTION_MS - 1
        assertEquals(value.createdAt, cache.find(value)?.createdAt)
        cache.select(value, false)
        now++
        assertNull(cache.find(value))
        assertNull(cache.selected(owner, value.kind, value.sourceHash, value.language))
    }

    @Test fun inputTargetLanguageAndKindAreIsolated() = runBlocking {
        val value = entry()
        val cache = store()
        cache.write(value)
        assertNull(cache.find(value.copy(sourceHash = translationSourceHash("Edited", "Preview"))))
        assertNull(cache.find(value.copy(target = TranslationTarget.Traditional(TranslationProviderType.DEEPL))))
        assertNull(cache.find(value.copy(language = "ja")))
        assertNull(cache.find(value.copy(kind = TranslationContentKind.FULL)))
        assertNull(cache.find(value.copy(promptVariant = "changed-prompt")))
        assertNull(cache.find(value.copy(owner = owner.copy(accountId = 2))))
    }

    @Test fun deletedArticleRejectsReadAndLateWriteAndRemovesFiles() = runBlocking {
        val value = entry()
        val cache = store()
        cache.write(value)
        alive.clear()
        assertNull(cache.find(value))
        assertFalse(cache.write(value))
        assertFalse(root.walkTopDown().any { it.isFile && it.name.endsWith(".json") })
    }

    @Test fun databaseInvalidationCleansOnlyMissingOwners() = runBlocking {
        val first = entry()
        val other = owner.copy(feedId = "other-feed", articleId = "other-article")
        alive += other
        val cache = store()
        cache.write(first)
        cache.write(first.copy(owner = other))
        alive.remove(owner)
        cache.removeMissingOwners()
        assertNull(cache.find(first))
        assertNotNull(cache.find(first.copy(owner = other)))
    }

    @Test fun failedAtomicPublishLeavesPreviousResultAndNoTemporaryFile() = runBlocking {
        val value = entry()
        var fail = false
        val cache = TranslationDiskStore(root, { it.filterTo(hashSetOf()) { o -> o in alive } }, { now },
            publish = { source, destination ->
                if (fail) throw IOException("injected move failure")
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            })
        cache.write(value)
        fail = true
        try { cache.write(value.copy(texts = listOf("新标题", "新摘要"))); fail("Expected IOException") }
        catch (_: IOException) { /* expected */ }
        assertEquals(value, cache.find(value))
        assertFalse(root.walkTopDown().any { it.name.endsWith(".pending") })
    }

    @Test fun malformedAndAbandonedFilesCannotBeRead() = runBlocking {
        val value = entry()
        val cache = store()
        cache.write(value)
        val payload = root.walkTopDown().first { it.name == "${value.key}.json" }
        payload.writeText("{truncated")
        assertNull(cache.find(value))
        root.resolve("orphan.pending").writeText("unfinished")
        cache.sweep()
        assertFalse(root.walkTopDown().any { it.isFile })
    }

    @Test fun expirySweepAndSizeEvictionNeverRenewOldEntries() = runBlocking {
        val first = entry()
        val secondOwner = owner.copy(articleId = "second")
        alive += secondOwner
        val cache = store(first.encode().toByteArray().size + 30L)
        cache.write(first)
        now++
        val second = first.copy(owner = secondOwner, createdAt = now)
        cache.write(second)
        assertNull(cache.find(first))
        assertNotNull(cache.find(second))
        now += TRANSLATION_RETENTION_MS
        cache.sweep()
        assertFalse(root.walkTopDown().any { it.isFile })
    }

    @Test fun ownerIdentifiersCannotEscapeCacheDirectory() = runBlocking {
        val unusual = TranslationOwner(1, "../feed", "../../article")
        alive += unusual
        val value = entry().copy(owner = unusual)
        val cache = store()
        assertTrue(cache.write(value))
        assertEquals(value, cache.find(value))
        assertTrue(root.walkTopDown().filter(File::isFile).all { it.canonicalPath.startsWith(root.canonicalPath + File.separator) })
    }

    @Test fun fullBlocksCanRenderBothModesFromTheSameResult() = runBlocking {
        val processor = TranslationContentProcessor()
        val source = "<p>Original paragraph</p>"
        val prepared = processor.prepare(source)
        val value = entry(TranslationContentKind.FULL).copy(texts = listOf("标题", "译文段落"))
        val cache = store()
        cache.write(value)
        val saved = requireNotNull(store().find(value))
        val translated = processor.render(prepared, saved.texts.drop(1), TranslationDisplayMode.TRANSLATED)
        val bilingual = processor.render(prepared, saved.texts.drop(1), TranslationDisplayMode.BILINGUAL)
        assertFalse(translated.contains("Original paragraph"))
        assertTrue(bilingual.contains("Original paragraph"))
        assertTrue(translated.contains("译文段落") && bilingual.contains("译文段落"))
        assertEquals(1, root.walkTopDown().count { it.name == "${value.key}.json" })
    }
}
