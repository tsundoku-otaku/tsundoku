package tachiyomi.domain.manga.interactor

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibraryPageSpec
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@Execution(ExecutionMode.CONCURRENT)
class GetLibraryMangaPagingTest {

    private val specA = LibraryPageSpec(sortAscending = false)
    private val specB = LibraryPageSpec(sortAscending = true)

    private fun libraryManga(id: Long, categoryId: Long) = LibraryManga(
        manga = Manga.create().copy(id = id),
        categories = listOf(categoryId),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    // Rows per (category, isNovel). specA pages them as listed, specB in reverse.
    private suspend fun create(rows: Map<Pair<Long, Boolean>, List<Long>>): GetLibraryManga {
        val repository = mockk<MangaRepository>()
        coEvery { repository.getLibraryMangaPage(any(), any(), any(), any(), any()) } answers {
            val categoryId = arg<Long>(0)
            val isNovel = arg<Boolean>(1)
            val limit = arg<Long>(2)
            val offset = arg<Long>(3)
            val spec = arg<LibraryPageSpec>(4)
            val ids = rows[categoryId to isNovel].orEmpty()
            val ordered = if (spec.sortAscending) ids.reversed() else ids
            ordered.drop(offset.toInt()).take(limit.toInt()).map { libraryManga(it, categoryId) }
        }
        val preferences = LibraryPreferences(InMemoryPreferenceStore())
        preferences.experimentalLibraryPagination.set(true)
        preferences.experimentalLibraryPageSize.set(2)
        return GetLibraryManga(repository, preferences).also { it.awaitRefresh() }
    }

    private suspend fun GetLibraryManga.residentIds() = await().map { it.id }

    @Test
    fun `changing a key spec drops rows loaded under the old spec`() = runTest {
        val getLibraryManga = create(mapOf((1L to false) to listOf(1L, 2L, 3L, 4L, 5L, 6L)))

        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))
        getLibraryManga.loadCategoryNextPage(1L, false)
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L, 4L)

        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specB))
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(6L, 5L)
    }

    @Test
    fun `stale key rows are evicted`() = runTest {
        val getLibraryManga = create(
            mapOf(
                (1L to false) to listOf(1L, 2L),
                (2L to false) to listOf(10L, 11L),
            ),
        )

        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA, (2L to false) to specA))
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 2L, 10L, 11L)

        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `empty specs with an explicit scope evict that content type only`() = runTest {
        val getLibraryManga = create(
            mapOf(
                (1L to false) to listOf(1L, 2L),
                (1L to true) to listOf(20L, 21L),
            ),
        )

        getLibraryManga.applyPageSpecs(mapOf((1L to true) to specA))
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 2L, 20L, 21L)

        getLibraryManga.applyPageSpecs(emptyMap(), contentTypes = setOf(false))
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(20L, 21L)
    }

    @Test
    fun `other content type rows are untouched`() = runTest {
        val getLibraryManga = create(
            mapOf(
                (1L to false) to listOf(1L, 2L, 3L, 4L),
                (1L to true) to listOf(20L, 21L),
            ),
        )

        getLibraryManga.applyPageSpecs(mapOf((1L to true) to specA))
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specB))

        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(20L, 21L, 4L, 3L)
    }

    @Test
    fun `manga owned by another key survives eviction`() = runTest {
        val getLibraryManga = create(
            mapOf(
                (1L to false) to listOf(1L, 100L),
                (2L to false) to listOf(100L, 10L),
            ),
        )

        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA, (2L to false) to specA))
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))

        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 100L)
    }

    @Test
    fun `active category restricts the eager load`() = runTest {
        val getLibraryManga = create(
            mapOf(
                (1L to false) to listOf(1L, 2L),
                (2L to false) to listOf(10L, 11L),
            ),
        )

        getLibraryManga.applyPageSpecs(
            mapOf((1L to false) to specA, (2L to false) to specA),
            activeCategoryId = 2L,
        )

        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(10L, 11L)
    }

    @Test
    fun `no active category loads every key`() = runTest {
        val getLibraryManga = create(
            mapOf(
                (1L to false) to listOf(1L, 2L),
                (2L to false) to listOf(10L, 11L),
            ),
        )

        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA, (2L to false) to specA))

        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 2L, 10L, 11L)
    }
}
