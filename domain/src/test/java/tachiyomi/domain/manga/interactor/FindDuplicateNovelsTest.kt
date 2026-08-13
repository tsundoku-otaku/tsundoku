package tachiyomi.domain.manga.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.DuplicateGroup
import tachiyomi.domain.manga.repository.MangaRepository

@Execution(ExecutionMode.CONCURRENT)
class FindDuplicateNovelsTest {

    private val mangaRepository = mockk<MangaRepository>()
    private val findDuplicateNovels = FindDuplicateNovels(mangaRepository)

    private fun mangaWithCount(id: Long, title: String) = MangaWithChapterCount(
        manga = Manga.create().copy(id = id, title = title),
        chapterCount = 0,
    )

    @Test
    fun `default blank filter excludes blank titles at the query level`() = kotlinx.coroutines.test.runTest {
        coEvery { mangaRepository.findDuplicatesExact(includeBlank = false) } returns emptyList()
        coEvery { mangaRepository.getMangaWithCountsLightWithGenre(emptyList()) } returns emptyList()

        findDuplicateNovels.findDuplicatesGrouped(DuplicateMatchMode.EXACT)

        coVerify(exactly = 1) { mangaRepository.findDuplicatesExact(includeBlank = false) }
    }

    @Test
    fun `include filter passes includeBlank through to the query`() = kotlinx.coroutines.test.runTest {
        coEvery { mangaRepository.findDuplicatesExact(includeBlank = true) } returns emptyList()
        coEvery { mangaRepository.getMangaWithCountsLightWithGenre(emptyList()) } returns emptyList()

        findDuplicateNovels.findDuplicatesGrouped(DuplicateMatchMode.EXACT, BlankTitleFilter.INCLUDE)

        coVerify(exactly = 1) { mangaRepository.findDuplicatesExact(includeBlank = true) }
    }

    @Test
    fun `only filter keeps just the blank-key group`() = kotlinx.coroutines.test.runTest {
        val blankGroup = DuplicateGroup(normalizedTitle = "", ids = listOf(1L, 2L), count = 2)
        val namedGroup = DuplicateGroup(normalizedTitle = "one piece", ids = listOf(3L, 4L), count = 2)
        coEvery { mangaRepository.findDuplicatesExact(includeBlank = true) } returns listOf(blankGroup, namedGroup)
        coEvery { mangaRepository.getMangaWithCountsLightWithGenre(any()) } returns listOf(
            mangaWithCount(1L, ""),
            mangaWithCount(2L, ""),
            mangaWithCount(3L, "One Piece"),
            mangaWithCount(4L, "One Piece"),
        )

        val result = findDuplicateNovels.findDuplicatesGrouped(DuplicateMatchMode.EXACT, BlankTitleFilter.ONLY)

        result.displayGroups.keys shouldBe setOf("")
    }

    @Test
    fun `a group larger than the cap is truncated before manga rows are materialized`() = kotlinx.coroutines.test.runTest {
        val hugeIds = (1L..5000L).toList()
        val hugeGroup = DuplicateGroup(normalizedTitle = "", ids = hugeIds, count = hugeIds.size)
        coEvery { mangaRepository.findDuplicatesExact(includeBlank = true) } returns listOf(hugeGroup)
        coEvery { mangaRepository.getTotalCountsForIds(any()) } returns emptyList()
        coEvery { mangaRepository.getMangaWithCountsLightWithGenre(any()) } answers {
            val ids = firstArg<List<Long>>()
            ids.map { mangaWithCount(it, "") }
        }

        findDuplicateNovels.findDuplicatesGrouped(DuplicateMatchMode.EXACT, BlankTitleFilter.INCLUDE)

        coVerify(exactly = 1) {
            mangaRepository.getMangaWithCountsLightWithGenre(withArg { it.size shouldBe 2000 })
        }
    }

    @Test
    fun `fullGroupIds keeps every member even though display is capped`() = kotlinx.coroutines.test.runTest {
        val hugeIds = (1L..5000L).toList()
        val hugeGroup = DuplicateGroup(normalizedTitle = "", ids = hugeIds, count = hugeIds.size)
        coEvery { mangaRepository.findDuplicatesExact(includeBlank = true) } returns listOf(hugeGroup)
        coEvery { mangaRepository.getTotalCountsForIds(any()) } returns emptyList()
        coEvery { mangaRepository.getMangaWithCountsLightWithGenre(any()) } answers {
            val ids = firstArg<List<Long>>()
            ids.map { mangaWithCount(it, "") }
        }

        val result = findDuplicateNovels.findDuplicatesGrouped(DuplicateMatchMode.EXACT, BlankTitleFilter.INCLUDE)

        result.displayGroups[""]?.size shouldBe 2000
        result.fullGroupIds[""]?.size shouldBe 5000
    }

    @Test
    fun `truncation keeps the highest chapter-count members, not an arbitrary id-order slice`() =
        kotlinx.coroutines.test.runTest {
            val hugeIds = (1L..5000L).toList()
            val hugeGroup = DuplicateGroup(normalizedTitle = "", ids = hugeIds, count = hugeIds.size)
            coEvery { mangaRepository.findDuplicatesExact(includeBlank = true) } returns listOf(hugeGroup)
            // The true best copy (id 4999) sits outside the first 2000 ids returned by the DB.
            coEvery { mangaRepository.getTotalCountsForIds(any()) } returns
                hugeIds.map { id -> id to if (id == 4999L) 999L else 1L }
            coEvery { mangaRepository.getMangaWithCountsLightWithGenre(any()) } answers {
                val ids = firstArg<List<Long>>()
                ids.map { mangaWithCount(it, "") }
            }

            val result = findDuplicateNovels.findDuplicatesGrouped(DuplicateMatchMode.EXACT, BlankTitleFilter.INCLUDE)

            result.displayGroups[""]?.map { it.manga.id } shouldBe listOf(4999L) +
                (1L..1999L).toList()
        }

    @Test
    fun `findSimilarTo includes blank titles even though findDuplicatesExact now defaults to excluding them`() =
        kotlinx.coroutines.test.runTest {
            val blankGroup = DuplicateGroup(normalizedTitle = "", ids = listOf(1L, 2L), count = 2)
            coEvery { mangaRepository.findDuplicatesExact(includeBlank = true) } returns listOf(blankGroup)
            coEvery { mangaRepository.findDuplicatesContains() } returns emptyList()
            coEvery { mangaRepository.getMangaWithCounts(listOf(2L)) } returns listOf(mangaWithCount(2L, ""))

            findDuplicateNovels.findSimilarTo(1L, "")

            coVerify(exactly = 1) { mangaRepository.findDuplicatesExact(includeBlank = true) }
        }

    @Test
    fun `URL groups sharing the same url key across sources are not collapsed into one`() =
        kotlinx.coroutines.test.runTest {
            val groupA = DuplicateGroup(normalizedTitle = "", ids = listOf(1L, 2L), count = 2)
            val groupB = DuplicateGroup(normalizedTitle = "", ids = listOf(3L, 4L), count = 2)
            coEvery { mangaRepository.findDuplicatesByUrl(includeBlank = true) } returns listOf(groupA, groupB)
            coEvery { mangaRepository.getMangaWithCountsLightWithGenre(any()) } answers {
                val ids = firstArg<List<Long>>()
                ids.map { mangaWithCount(it, "") }
            }

            val result = findDuplicateNovels.findDuplicatesGrouped(DuplicateMatchMode.URL, BlankTitleFilter.INCLUDE)

            result.fullGroupIds.values.flatten().toSet() shouldBe setOf(1L, 2L, 3L, 4L)
        }
}
