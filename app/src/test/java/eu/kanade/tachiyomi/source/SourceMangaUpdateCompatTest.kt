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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rx.Observable

private fun details(author: String) = SManga.create().apply {
    url = "/entry"
    title = "Entry"
    this.author = author
}

/**
 * Callers only use [Source.getMangaUpdate]. Every extension generation the app still loads has to
 * come out of that single entry point.
 */
class SourceMangaUpdateCompatTest {

    private val manga = SManga.create().apply {
        url = "/entry"
        title = "Entry"
    }

    private fun chapter(url: String) = SChapter.create().apply {
        this.url = url
        name = url
    }

    private abstract class TestSource : CatalogueSource {
        override val id = 1L
        override val name = "Test"
        override val lang = "en"
        override val supportsLatest = false
        override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException()
    }

    /** Pre-suspend extension: only the RxJava methods are implemented. */
    private class RxSource(private val chapters: List<SChapter>) : TestSource() {
        @Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
        override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.just(details("rx"))

        @Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
        override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.just(chapters)
    }

    /** Novel, JS and local sources: suspend methods, no RxJava, no combined API. */
    private class SuspendSource(private val chapters: List<SChapter>) : TestSource() {
        @Suppress("OVERRIDE_DEPRECATION")
        override suspend fun getMangaDetails(manga: SManga): SManga = details("suspend")

        @Suppress("OVERRIDE_DEPRECATION")
        override suspend fun getChapterList(manga: SManga): List<SChapter> = chapters
    }

    private class ContextSource : TestSource() {
        var receivedExisting: List<SChapter>? = null

        @Suppress("OVERRIDE_DEPRECATION")
        override suspend fun getMangaDetails(manga: SManga): SManga = details("context")

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override suspend fun getChapterList(manga: SManga, context: RefreshContext): List<SChapter> {
            receivedExisting = context.existingChapters
            return context.existingChapters
        }
    }

    /**
     * Migrated extension: implements the combined API and throws from every helper it no longer
     * needs, the way the current extension repo does.
     */
    private class CombinedSource(private val chapters: List<SChapter>) : TestSource() {
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ) = SMangaUpdate(
            manga = if (fetchDetails) details("combined") else manga,
            chapters = if (fetchChapters) this.chapters else chapters,
        )

        @Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
        override fun fetchMangaDetails(manga: SManga): Observable<SManga> = throw UnsupportedOperationException()

        @Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
        override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
            throw UnsupportedOperationException()
    }

    private suspend fun Source.update(
        existing: List<SChapter> = emptyList(),
        fetchDetails: Boolean = true,
        fetchChapters: Boolean = true,
    ) = getMangaUpdate(manga, existing, fetchDetails, fetchChapters)

    @Test
    fun `rxjava extension is served through the combined api`() = runBlocking {
        val update = RxSource(listOf(chapter("/c1"))).update()

        assertEquals("rx", update.manga.author)
        assertEquals(listOf("/c1"), update.chapters.map { it.url })
    }

    @Test
    fun `suspend only extension is served through the combined api`() = runBlocking {
        val update = SuspendSource(listOf(chapter("/c1"), chapter("/c2"))).update()

        assertEquals("suspend", update.manga.author)
        assertEquals(listOf("/c1", "/c2"), update.chapters.map { it.url })
    }

    @Test
    fun `refresh context extension still receives the existing chapters`() = runBlocking {
        val source = ContextSource()
        val existing = listOf(chapter("/c1"))

        val update = source.update(existing = existing)

        assertEquals(existing, source.receivedExisting)
        assertEquals(existing, update.chapters)
    }

    @Test
    fun `migrated extension never falls back to the deprecated helpers`() = runBlocking {
        val update = CombinedSource(listOf(chapter("/c1"))).update()

        assertEquals("combined", update.manga.author)
        assertEquals(listOf("/c1"), update.chapters.map { it.url })
    }

    @Test
    fun `unrequested halves are passed through untouched`() = runBlocking {
        val existing = listOf(chapter("/kept"))

        val detailsOnly = SuspendSource(listOf(chapter("/c1"))).update(existing, fetchChapters = false)
        assertEquals(existing, detailsOnly.chapters)
        assertEquals("suspend", detailsOnly.manga.author)

        val chaptersOnly = SuspendSource(listOf(chapter("/c1"))).update(existing, fetchDetails = false)
        assertEquals(listOf("/c1"), chaptersOnly.chapters.map { it.url })
        assertTrue(chaptersOnly.manga.author == null)
    }
}
