package tachiyomi.domain.manga.interactor

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    private fun libraryManga(id: Long, categories: List<Long>) = LibraryManga(
        manga = Manga.create().copy(id = id),
        categories = categories,
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    // Rows per (category, isNovel), mutable to simulate DB changes. specA pages them as listed,
    // specB in reverse.
    // The test dispatcher runs the constructor's initial refresh eagerly, so nothing is left running
    // in the background.
    private fun TestScope.create(
        rows: MutableMap<Pair<Long, Boolean>, MutableList<Long>>,
        failPages: (offset: Long) -> Boolean = { false },
    ): GetLibraryManga {
        fun categoriesOf(id: Long) = rows.filterValues { id in it }.keys.map { it.first }.distinct()
        val repository = mockk<MangaRepository>()
        coEvery { repository.getLibraryMangaPage(any(), any(), any(), any(), any()) } answers {
            val categoryId = arg<Long>(0)
            val isNovel = arg<Boolean>(1)
            val limit = arg<Long>(2)
            val offset = arg<Long>(3)
            val spec = arg<LibraryPageSpec>(4)
            if (failPages(offset)) error("page fetch failed")
            val ids = rows[categoryId to isNovel].orEmpty()
            val ordered = if (spec.sortAscending) ids.reversed() else ids
            val window = ordered.drop(offset.toInt())
            (if (limit < 0) window else window.take(limit.toInt())).map { libraryManga(it, categoriesOf(it)) }
        }
        coEvery { repository.refreshLibraryCacheForManga(any()) } just runs
        coEvery { repository.refreshLibraryCacheForMangas(any()) } just runs
        coEvery { repository.getLibraryMangaById(any()) } answers {
            val id = arg<Long>(0)
            libraryManga(id, categoriesOf(id))
        }
        coEvery { repository.getLibraryMangaByIds(any()) } answers {
            arg<List<Long>>(0).map { libraryManga(it, categoriesOf(it)) }
        }
        val preferences = LibraryPreferences(InMemoryPreferenceStore())
        preferences.experimentalLibraryPagination.set(true)
        preferences.experimentalLibraryPageSize.set(2)
        return GetLibraryManga(repository, preferences, UnconfinedTestDispatcher(testScheduler))
    }

    private fun rows(vararg pairs: Pair<Pair<Long, Boolean>, List<Long>>) =
        pairs.associateTo(mutableMapOf()) { (key, ids) -> key to ids.toMutableList() }

    private suspend fun GetLibraryManga.residentIds() = await().map { it.id }

    @Test
    fun `changing a key spec drops rows loaded under the old spec`() = runTest {
        val getLibraryManga = create(rows((1L to false) to listOf(1L, 2L, 3L, 4L, 5L, 6L)))

        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))
        getLibraryManga.loadCategoryNextPage(1L, false)
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L, 4L)

        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specB))
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(6L, 5L)
    }

    @Test
    fun `stale key rows are evicted`() = runTest {
        val getLibraryManga = create(
            rows(
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
            rows(
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
            rows(
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
            rows(
                (1L to false) to listOf(1L, 100L),
                (2L to false) to listOf(100L, 10L),
            ),
        )

        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA, (2L to false) to specA))
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))

        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 100L)
    }

    @Test
    fun `every key gets its first page even when another is empty`() = runTest {
        val getLibraryManga = create(
            rows(
                (0L to false) to emptyList(),
                (1L to false) to listOf(1L, 2L, 3L),
                (2L to false) to listOf(10L, 11L),
            ),
        )

        getLibraryManga.applyPageSpecs(
            mapOf((0L to false) to specA, (1L to false) to specA, (2L to false) to specA),
        )

        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 2L, 10L, 11L)
    }

    @Test
    fun `unpaged spec loads the whole key`() = runTest {
        val getLibraryManga = create(rows((1L to false) to listOf(1L, 2L, 3L, 4L, 5L)))

        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA.copy(unpaged = true)))
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L, 4L, 5L)

        getLibraryManga.loadCategoryNextPage(1L, false)
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L, 4L, 5L)
    }

    @Test
    fun `added manga follow page bounds and are evicted with their key`() = runTest {
        val data = rows((1L to false) to listOf(1L, 2L, 3L))
        val getLibraryManga = create(data)
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))

        data.getValue(1L to false).add(50L)
        getLibraryManga.addToLibrary(50L)
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 2L)

        data.getValue(1L to false).add(0, 60L)
        getLibraryManga.addToLibraryBulk(listOf(60L))
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(60L, 1L)

        getLibraryManga.applyPageSpecs(emptyMap(), contentTypes = setOf(false))
        getLibraryManga.residentIds().shouldBeEmpty()
    }

    @Test
    fun `moved manga stays when its old category is reset`() = runTest {
        val data = rows(
            (1L to false) to listOf(1L, 2L),
            (2L to false) to listOf(10L),
        )
        val getLibraryManga = create(data)
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA, (2L to false) to specA))

        data.getValue(1L to false).remove(1L)
        data.getValue(2L to false).add(0, 1L)
        getLibraryManga.setCategoriesForManga(listOf(1L), listOf(2L))

        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specB, (2L to false) to specA))

        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(2L, 1L, 10L)
    }

    @Test
    fun `unfavorited manga does not make the next page skip a row`() = runTest {
        val data = rows((1L to false) to listOf(1L, 2L, 3L, 4L, 5L))
        val getLibraryManga = create(data)
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))

        data.getValue(1L to false).remove(1L)
        getLibraryManga.removeFromLibrary(1L)
        getLibraryManga.loadCategoryNextPage(1L, false)

        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(2L, 3L, 4L, 5L)
    }

    @Test
    fun `reopening an exhausted key signals the sentinel`() = runTest {
        val data = rows((1L to false) to listOf(1L, 2L))
        val getLibraryManga = create(data)
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))
        getLibraryManga.loadCategoryNextPage(1L, false) shouldBe false
        val before = getLibraryManga.pagingInvalidations().value

        data.getValue(1L to false).add(0, 3L)
        getLibraryManga.addToLibrary(3L)

        getLibraryManga.pagingInvalidations().value shouldNotBe before
        getLibraryManga.loadCategoryNextPage(1L, false) shouldBe true
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(3L, 1L, 2L)
    }

    @Test
    fun `failed first page reload restarts the key from page one`() = runTest {
        var failing = false
        val getLibraryManga = create(rows((1L to false) to listOf(1L, 2L, 3L, 4L, 5L))) { failing }
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))
        getLibraryManga.loadCategoryNextPage(1L, false)

        failing = true
        getLibraryManga.refreshForced()
        getLibraryManga.residentIds().shouldBeEmpty()

        failing = false
        getLibraryManga.loadCategoryPageIfNeeded(1L, false) shouldBe true
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `ownership only lists rows a category loaded itself`() = runTest {
        val getLibraryManga = create(
            rows(
                (1L to false) to listOf(100L, 1L),
                (2L to false) to listOf(10L, 11L, 100L),
            ),
        )
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA, (2L to false) to specA))

        val owned = getLibraryManga.pagingSnapshot().value.ownedIds
        owned.getValue(1L) shouldBe setOf(100L, 1L)
        owned.getValue(2L) shouldBe setOf(10L, 11L)
    }

    @Test
    fun `adding a non resident manga to a category reloads that category`() = runTest {
        val data = rows(
            (1L to false) to listOf(1L, 2L),
            (2L to false) to listOf(10L, 11L),
        )
        val getLibraryManga = create(data)
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA, (2L to false) to specA))

        data.getValue(2L to false).add(0, 50L)
        getLibraryManga.applyCategoryUpdates(listOf(50L), addCategories = listOf(2L), removeCategories = emptyList())

        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(1L, 2L, 50L, 10L)
    }

    @Test
    fun `failed key reload is retried from page one`() = runTest {
        var failing = false
        val data = rows((1L to false) to listOf(1L, 2L, 3L, 4L, 5L))
        val getLibraryManga = create(data) { failing }
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))
        getLibraryManga.loadCategoryNextPage(1L, false)
        val before = getLibraryManga.pagingInvalidations().value

        failing = true
        data.getValue(1L to false).remove(1L)
        getLibraryManga.removeFromLibrary(1L)
        getLibraryManga.pagingInvalidations().value shouldNotBe before
        getLibraryManga.residentIds().shouldBeEmpty()

        failing = false
        getLibraryManga.loadMissingFirstPages() shouldBe true
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(2L, 3L)
    }

    @Test
    fun `exhausted key reports no more pages`() = runTest {
        val getLibraryManga = create(rows((1L to false) to listOf(1L, 2L, 3L)))
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))
        getLibraryManga.mayHaveMorePages(1L, false) shouldBe true

        getLibraryManga.loadCategoryNextPage(1L, false)
        getLibraryManga.mayHaveMorePages(1L, false) shouldBe false
    }

    @Test
    fun `frontier tracks the last row of keys with more pages`() = runTest {
        val getLibraryManga = create(
            rows(
                (1L to false) to listOf(1L, 2L, 3L, 4L),
                (1L to true) to listOf(20L),
            ),
        )
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA, (1L to true) to specA))
        getLibraryManga.pagingSnapshot().value.frontiers.getValue(1L) shouldBe listOf(2L)

        getLibraryManga.loadCategoryNextPage(1L, false)
        getLibraryManga.pagingSnapshot().value.frontiers[1L] shouldBe null
    }

    @Test
    fun `reload cut short by a failed later page signals the sentinel`() = runTest {
        var failing = false
        val data = rows((1L to false) to listOf(1L, 2L, 3L, 4L, 5L))
        val getLibraryManga = create(data) { offset -> failing && offset > 0 }
        getLibraryManga.applyPageSpecs(mapOf((1L to false) to specA))
        getLibraryManga.loadCategoryNextPage(1L, false)
        val before = getLibraryManga.pagingInvalidations().value

        failing = true
        data.getValue(1L to false).remove(1L)
        getLibraryManga.removeFromLibrary(1L)
        getLibraryManga.pagingInvalidations().value shouldNotBe before
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(2L, 3L)

        failing = false
        getLibraryManga.loadCategoryNextPage(1L, false) shouldBe true
        getLibraryManga.residentIds() shouldContainExactlyInAnyOrder listOf(2L, 3L, 4L, 5L)
    }
}
