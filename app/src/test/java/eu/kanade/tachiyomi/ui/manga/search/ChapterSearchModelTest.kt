package eu.kanade.tachiyomi.ui.manga.search

import eu.kanade.tachiyomi.data.download.ChapterContentReader
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterSearchModelTest {

    private val manga = Manga.create().copy(id = MANGA_ID, source = SOURCE_ID, title = "Novel")
    private val chapters = (1..5).map { number ->
        Chapter.create().copy(
            id = number.toLong(),
            mangaId = MANGA_ID,
            name = "Chapter $number",
            url = "/chapter/$number",
            chapterNumber = number.toDouble(),
            sourceOrder = (10 - number).toLong(),
        )
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model(): ChapterSearchScreen.Model {
        val source = mockk<Source>(relaxed = true) { every { id } returns SOURCE_ID }
        val getManga = mockk<GetManga> { coEvery { await(MANGA_ID) } returns manga }
        val getChapters = mockk<GetChaptersByMangaId> { coEvery { await(MANGA_ID, any()) } returns chapters }
        val sourceManager = mockk<SourceManager> { every { getOrStub(SOURCE_ID) } returns source }
        val downloadManager = mockk<DownloadManager> {
            coEvery { awaitDownloadCacheReady() } returns true
            every { isChapterDownloaded(any(), any(), any(), any(), any(), any()) } returns true
        }
        val reader = mockk<ChapterContentReader.DownloadedContentReader>(relaxed = true) {
            every { read(any()) } answers { "<p>A sword in ${firstArg<Chapter>().name}.</p>" }
        }
        val contentReader = mockk<ChapterContentReader> {
            every { openDownloadedContentReader(any(), any()) } returns reader
        }
        return ChapterSearchScreen.Model(
            mangaId = MANGA_ID,
            getManga = getManga,
            getChaptersByMangaId = getChapters,
            sourceManager = sourceManager,
            downloadManager = downloadManager,
            contentReader = contentReader,
        )
    }

    private fun ChapterSearchScreen.Model.awaitIdle(predicate: (ChapterSearchScreen.State) -> Boolean) = runBlocking {
        withTimeout(10_000) {
            while (!predicate(state.value) || state.value.isSearching) delay(10)
        }
    }

    private fun ChapterSearchScreen.Model.resultChapters() = state.value.results.map { it.chapter.id }.toSet()

    private fun ChapterSearchScreen.Model.searchAll(query: String = "sword"): ChapterSearchScreen.Model = apply {
        awaitIdle { it.chapters != null }
        updateQuery(query)
        search()
        awaitIdle { it.submittedQuery != null }
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), resultChapters())
    }

    @Test
    fun `changing the range after clearing the field re-runs the submitted search`() {
        val model = model().searchAll()

        model.updateQuery("")
        model.updateRange(ChapterSearchRange.of(2.0, 3.0))
        model.awaitIdle { it.results.size <= 2 }

        assertEquals(setOf(2L, 3L), model.resultChapters())
        assertEquals("sword", model.state.value.submittedQuery)
        assertEquals("", model.state.value.query)
    }

    @Test
    fun `changing an option after clearing the field re-runs the submitted search`() {
        val model = model().searchAll("Sword")

        model.updateQuery("")
        model.updateOptions(ChapterSearchOptions(caseSensitive = true))
        model.awaitIdle { it.submittedOptions.caseSensitive }

        // The chapters only hold a lowercase "sword", so the case-sensitive re-run finds nothing.
        assertEquals(emptySet<Long>(), model.resultChapters())
        assertEquals("Sword", model.state.value.submittedQuery)
    }

    private companion object {
        const val MANGA_ID = 7L
        const val SOURCE_ID = 1000L
    }
}
