package tachiyomi.domain.category.interactor

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.repository.MangaRepository

class SetMangaCategoriesTest {

    private val repository = mockk<MangaRepository>(relaxed = true)
    private val getLibraryManga = mockk<GetLibraryManga>(relaxed = true)
    private val setMangaCategories = SetMangaCategories(repository, getLibraryManga)

    @Test
    fun `update patches the library with both changes`() = runTest {
        assertEquals(
            SetMangaCategories.UpdateResult.Saved,
            setMangaCategories.update(listOf(1L), listOf(2L), listOf(3L)),
        )

        coVerify { getLibraryManga.applyCategoryUpdates(listOf(1L), listOf(2L), listOf(3L)) }
    }

    @Test
    fun `update leaves out a change that failed to save`() = runTest {
        coEvery { repository.addMangasCategories(any(), any()) } throws IllegalStateException()

        assertEquals(
            SetMangaCategories.UpdateResult.Partial,
            setMangaCategories.update(listOf(1L), listOf(2L), listOf(3L)),
        )

        coVerify { getLibraryManga.applyCategoryUpdates(listOf(1L), emptyList(), listOf(3L)) }
    }

    @Test
    fun `update skips the patch when nothing was saved`() = runTest {
        coEvery { repository.removeMangasCategories(any(), any()) } throws IllegalStateException()

        assertEquals(
            SetMangaCategories.UpdateResult.Failed,
            setMangaCategories.update(listOf(1L), emptyList(), listOf(3L)),
        )

        coVerify(exactly = 0) { getLibraryManga.applyCategoryUpdates(any(), any(), any()) }
    }
}
