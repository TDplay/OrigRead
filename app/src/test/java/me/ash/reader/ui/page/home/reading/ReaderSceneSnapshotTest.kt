package me.ash.reader.ui.page.home.reading

import me.ash.reader.infrastructure.content.FullContentFailureReason
import me.ash.reader.ui.page.adaptive.ReaderState
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSceneSnapshotTest {
    @Test fun loadingSceneReceivesSameArticleContentAndMetadata() {
        val initial = ReaderState(articleId = "a")
        val snapshot = ReaderSceneSnapshot(initial)
        val loaded = initial.copy(title = "Loaded title", content = ReaderState.Description("body"),
            nextArticle = ReaderState.PrefetchResult("b", 2))
        assertEquals(loaded, snapshot.resolve(loaded))
        val cached = loaded.copy(content = ReaderState.FullContent("full", cacheWriteFailed = true))
        assertEquals(cached, snapshot.resolve(cached))
    }

    @Test fun outgoingSceneKeepsItsOwnLatestContent() {
        val initial = ReaderState(articleId = "a")
        val snapshot = ReaderSceneSnapshot(initial)
        val loaded = initial.copy(content = ReaderState.Description("article A"))
        snapshot.resolve(loaded)
        assertEquals(loaded, snapshot.resolve(ReaderState(articleId = "b", title = "article B")))
        assertEquals(loaded, snapshot.resolve(ReaderState()))
    }

    @Test fun sameArticleRefreshRetainsReadableTextUntilSuccessOrError() {
        val loaded = ReaderState(articleId = "a", content = ReaderState.Description("readable"))
        val snapshot = ReaderSceneSnapshot(loaded)
        assertEquals(loaded, snapshot.resolve(loaded.copy(content = ReaderState.Loading)))
        val full = loaded.copy(content = ReaderState.FullContent("full content"))
        assertEquals(full, snapshot.resolve(full))
        val error = loaded.copy(content = ReaderState.Error(FullContentFailureReason.ACCESS_RESTRICTED))
        assertEquals(error, snapshot.resolve(error))
    }

    @Test fun articleDirectionUsesPreviousArticleIdentity() {
        val initial = ReaderState(articleId = "b", previousArticle = ReaderState.PrefetchResult("a", 0))
        assertEquals(-1, readerArticleDirection(initial, ReaderState(articleId = "a")))
        assertEquals(1, readerArticleDirection(initial, ReaderState(articleId = "c")))
    }

    @Test fun contentModeIgnoresBodyAndCacheMetadataChanges() {
        val state = ReaderState(articleId = "a", content = ReaderState.FullContent("one"))
        assertEquals(state.contentMode(), state.copy(content = ReaderState.FullContent("two", true)).contentMode())
    }
}
