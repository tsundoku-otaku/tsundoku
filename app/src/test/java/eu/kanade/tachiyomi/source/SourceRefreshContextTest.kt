package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.RefreshContext
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SourceRefreshContextTest {

    private val manga = SManga.create().apply { url = "/novel"; title = "Novel" }

    private fun chapter(url: String) = SChapter.create().apply { this.url = url; name = url }

    private fun refreshContext(existing: List<SChapter> = emptyList(), force: Boolean = false) =
        RefreshContext(mangaId = 1L, existingChapters = existing, lastFetchTime = 0L, forceRefresh = force)

    /** Legacy source: only the old single-arg [getChapterList] is implemented. */
    private class LegacySource(private val chapters: List<SChapter>) : Source {
        override val id = 1L
        override val name = "Legacy"
        var legacyCallCount = 0
        override suspend fun getPopularManga(page: Int): MangasPage = throw UnsupportedOperationException()
        override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = throw UnsupportedOperationException()
        override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate = throw UnsupportedOperationException()
        override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException()
        override suspend fun getChapterList(manga: SManga): List<SChapter> {
            legacyCallCount++
            return chapters
        }
    }

    /** Context-aware source: overrides the new two-arg [getChapterList]. */
    private class ContextSource : Source {
        override val id = 2L
        override val name = "Context"
        var receivedContext: RefreshContext? = null
        override suspend fun getPopularManga(page: Int): MangasPage = throw UnsupportedOperationException()
        override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = throw UnsupportedOperationException()
        override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate = throw UnsupportedOperationException()
        override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException()
        override suspend fun getChapterList(manga: SManga): List<SChapter> =
            error("legacy path must not be used when override is present")
        override suspend fun getChapterList(manga: SManga, context: RefreshContext): List<SChapter> {
            receivedContext = context
            return context.existingChapters
        }
    }

    @Test
    fun `default context overload falls back to the legacy method`() = runBlocking {
        val expected = listOf(chapter("/c1"), chapter("/c2"))
        val source = LegacySource(expected)

        val result = source.getChapterList(manga, refreshContext())

        assertEquals(expected, result)
        assertEquals(1, source.legacyCallCount)
    }

    @Test
    fun `legacy fallback ignores context contents`() = runBlocking {
        val source = LegacySource(listOf(chapter("/only")))

        val result = source.getChapterList(
            manga,
            refreshContext(existing = listOf(chapter("/stale")), force = true),
        )

        assertEquals(listOf("/only"), result.map { it.url })
    }

    @Test
    fun `overriding source receives the context`() = runBlocking {
        val source = ContextSource()
        val existing = listOf(chapter("/c1"))
        val ctx = refreshContext(existing = existing)

        val result = source.getChapterList(manga, ctx)

        assertSame(ctx, source.receivedContext)
        assertEquals(existing, result)
    }
}
