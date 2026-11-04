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
import tachiyomi.domain.library.model.LibraryKeyedPage
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibraryPageCursor
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
        coEvery { repository.getLibraryMangaKeyedPage(any(), any(), any(), any(), any(), any(), any()) } answers {
            val limit = arg<Long>(2).toInt()
            val after = arg<LibraryPageCursor?>(4)
            val start = if (after != null) matches.indexOf(after.id) + 1 else arg<Long>(3).toInt()
            val page = matches.drop(start).take(limit)
            LibraryKeyedPage(page.map(::row), page.lastOrNull()?.let { LibraryPageCursor(it, it) })
        }
        coEvery { repository.getLibraryMangaPageIds(any(), any(), any(), any(), any(), any()) } answers {
            val limit = arg<Long>(2)
            if (limit < 0) matches else matches.take(limit.toInt())
        }
        coEvery { repository.getLibraryMangaPageRows(any(), any(), any(), any()) } answers {
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
    fun `a page starting where a loaded one ended seeks from its last row`() = runTest {
        val repository = repository((1L..100L).toList())
        val generation = generation(repository, LibraryPageSpec())

        generation.fetch(10, 0)
        generation.fetch(10, 10)
        generation.fetch(10, 55).ids shouldContainExactly (56L..65L).toList()
        generation.fetch(10, 65).ids shouldContainExactly (66L..75L).toList()

        val spec = LibraryPageSpec()
        coVerify(exactly = 1) { repository.getLibraryMangaKeyedPage(3L, true, 10L, 0L, null, spec, false) }
        coVerify(exactly = 1) {
            repository.getLibraryMangaKeyedPage(3L, true, 10L, 0L, LibraryPageCursor(10L, 10L), spec, false)
        }
        coVerify(exactly = 1) { repository.getLibraryMangaKeyedPage(3L, true, 10L, 55L, null, spec, false) }
        coVerify(exactly = 1) {
            repository.getLibraryMangaKeyedPage(3L, true, 10L, 0L, LibraryPageCursor(65L, 65L), spec, false)
        }
    }

    @Test
    fun `a narrowed category reads every page from one snapshot`() = runTest {
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
        coVerify(exactly = 0) { repository.getLibraryMangaKeyedPage(any(), any(), any(), any(), any(), any(), any()) }
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
        coVerify(exactly = 1) {
            repository.getLibraryMangaKeyedPage(3L, true, 20L, size + 10L, null, narrowed, false)
        }
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
        coVerify(exactly = 0) { repository.getLibraryMangaKeyedPage(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a filtered load keeps only the passing rows in order`() = runTest {
        val repository = repository((1L..25L).toList())
        val paged = GetLibraryMangaPaged(repository, mockk(), mockk(), StandardTestDispatcher(testScheduler))

        val rows = paged.awaitFiltered(3L, true, LibraryPageSpec()) { it.id % 2 == 0L }

        rows.map { it.id } shouldContainExactly (2L..24L step 2).toList()
    }

    @Test
    fun `the whole library count of a source pinned search reads the source index`() = runTest {
        val spec = LibraryPageSpec(search = LibraryPageSpec.Search(sourceIds = listOf(1L)))
        val repository = repository(emptyList(), sourceDriven = true)
        coEvery { repository.countCategoryMembers(LibraryPageSpec.ALL_CATEGORIES) } returns 0
        coEvery { repository.countLibraryMangaPage(any(), any(), any(), any(), any()) } returns 7
        val paged = GetLibraryMangaPaged(repository, mockk(), mockk(), StandardTestDispatcher(testScheduler))

        paged.awaitCount(LibraryPageSpec.ALL_CATEGORIES, null, spec) shouldBe 7

        coVerify { repository.countLibraryMangaPage(LibraryPageSpec.ALL_CATEGORIES, null, spec, false, true) }
    }

    @Test
    fun `the uncategorized bucket reads its pages from one snapshot`() = runTest {
        val repository = repository((1L..25L).toList())
        val generation = GetLibraryMangaPaged(repository, mockk(), mockk(), StandardTestDispatcher(testScheduler))
            .QueryGeneration(categoryId = 0L, isNovel = true, spec = LibraryPageSpec())

        generation.fetch(10, 0).ids shouldContainExactly (1L..10L).toList()
        generation.fetch(10, 10).ids shouldContainExactly (11L..20L).toList()

        coVerify(exactly = 0) { repository.getLibraryMangaKeyedPage(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { repository.getLibraryMangaPageIds(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a filtered load of a small category reads its members directly`() = runTest {
        val repository = repository((1L..5L).toList())
        coEvery { repository.countCategoryMembers(3L) } returns 5
        val paged = GetLibraryMangaPaged(repository, mockk(), mockk(), StandardTestDispatcher(testScheduler))

        paged.awaitFiltered(3L, true, LibraryPageSpec()) { true }.map { it.id } shouldContainExactly (1L..5L).toList()

        coVerify { repository.getLibraryMangaPageIds(3L, true, -1L, LibraryPageSpec(), true, false) }
    }
}
