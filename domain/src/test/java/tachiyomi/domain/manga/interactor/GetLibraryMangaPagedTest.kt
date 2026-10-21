package tachiyomi.domain.manga.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibraryPageSpec
import tachiyomi.domain.library.paging.LibraryPagingSource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

class GetLibraryMangaPagedTest {

    private val narrowed = LibraryPageSpec(filterBookmarked = 1)

    private fun row(id: Long) = LibraryManga(
        manga = Manga.create().copy(id = id),
        categories = listOf(0L),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    private fun repository(
        matches: List<Long>,
        sourceDriven: Boolean = false,
        deleted: Set<Long> = emptySet(),
    ): MangaRepository {
        val repository = mockk<MangaRepository>()
        coEvery { repository.countCategoryMembers(any()) } returns 1_000_000
        coEvery { repository.isLibraryPageSourceDriven(any(), any()) } returns sourceDriven
        coEvery { repository.getLibraryMangaPage(any(), any(), any(), any(), any(), any()) } answers {
            val limit = arg<Long?>(2) ?: Long.MAX_VALUE
            val offset = arg<Long>(3)
            matches.drop(offset.toInt()).take(limit.toInt()).map(::row)
        }
        coEvery { repository.getLibraryMangaPageIds(any(), any(), any(), any(), any(), any()) } answers {
            val limit = arg<Long>(2)
            if (limit < 0) matches else matches.take(limit.toInt())
        }
        coEvery { repository.getLibraryMangaPageRows(any()) } answers {
            arg<List<Long>>(0).filterNot { it in deleted }.reversed().map(::row)
        }
        return repository
    }

    private fun TestScope.generation(repository: MangaRepository, spec: LibraryPageSpec) =
        GetLibraryMangaPaged(repository, mockk(), mockk(), StandardTestDispatcher(testScheduler))
            .QueryGeneration(categoryId = 3L, isNovel = true, spec = spec)

    private val LibraryPagingSource.Chunk.ids get() = rows.map { it.id }

    @Test
    fun `an unnarrowed category walks every page`() = runTest {
        val repository = repository((1L..100L).toList())
        val generation = generation(repository, LibraryPageSpec())

        generation.fetch(10, 0).ids shouldContainExactly (1L..10L).toList()
        generation.fetch(10, 10).ids shouldContainExactly (11L..20L).toList()

        coVerify(exactly = 0) { repository.getLibraryMangaPageIds(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a narrowed category walks the first page and reads the rest from one snapshot`() = runTest {
        val repository = repository((1L..25L).toList())
        val generation = generation(repository, narrowed)

        generation.fetch(10, 0).ids shouldContainExactly (1L..10L).toList()
        val second = generation.fetch(10, 10)
        val last = generation.fetch(10, 20)

        second.ids shouldContainExactly (11L..20L).toList()
        second.nextOffset shouldBe 20
        last.ids shouldContainExactly (21L..25L).toList()
        last.nextOffset shouldBe null
        generation.fetch(10, 25).nextOffset shouldBe null
        coVerify(exactly = 1) { repository.getLibraryMangaPage(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            repository.getLibraryMangaPageIds(3L, true, GetLibraryMangaPaged.SNAPSHOT_SIZE + 1L, narrowed, false, false)
        }
    }

    @Test
    fun `pages past a full snapshot walk again`() = runTest {
        val size = GetLibraryMangaPaged.SNAPSHOT_SIZE
        val repository = repository((1L..size + 30L).toList())
        val generation = generation(repository, narrowed)

        val boundary = generation.fetch(20, size - 10)
        val past = generation.fetch(20, size + 10)

        boundary.ids shouldContainExactly ((size - 9L)..size.toLong()).toList()
        boundary.nextOffset shouldBe size
        past.ids shouldContainExactly ((size + 11L)..(size + 30L)).toList()
        coVerify(exactly = 1) { repository.getLibraryMangaPage(3L, true, 20L, size + 10L, narrowed, false) }
    }

    @Test
    fun `entries gone since the snapshot do not end it early`() = runTest {
        val repository = repository((1L..30L).toList(), deleted = setOf(12L, 13L))
        val generation = generation(repository, narrowed)

        val page = generation.fetch(10, 10)

        page.ids shouldContainExactly listOf(11L, 14L, 15L, 16L, 17L, 18L, 19L, 20L)
        page.nextOffset shouldBe 20
    }

    @Test
    fun `a search pinned to small sources starts from a source index snapshot`() = runTest {
        val spec = LibraryPageSpec(search = LibraryPageSpec.Search(sourceIds = listOf(1L)))
        val repository = repository((1L..7L).toList(), sourceDriven = true)
        val generation = generation(repository, spec)

        val page = generation.fetch(10, 0)

        page.ids shouldContainExactly (1L..7L).toList()
        page.nextOffset shouldBe null
        coVerify(exactly = 1) { repository.getLibraryMangaPageIds(3L, true, -1L, spec, false, true) }
        coVerify(exactly = 0) { repository.getLibraryMangaPage(any(), any(), any(), any(), any(), any()) }
    }
}
