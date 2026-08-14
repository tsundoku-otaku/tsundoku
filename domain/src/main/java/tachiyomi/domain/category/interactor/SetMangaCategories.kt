package tachiyomi.domain.category.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.repository.MangaRepository

class SetMangaCategories(
    private val mangaRepository: MangaRepository,
    private val getLibraryManga: GetLibraryManga,
) {

    /**
     * Set categories for a single manga.
     * @param skipRefresh If true, skip the library refresh. Useful when caller manages refresh.
     */
    suspend fun await(mangaId: Long, categoryIds: List<Long>, skipRefresh: Boolean = false) {
        try {
            mangaRepository.setMangaCategories(mangaId, categoryIds)
            if (!skipRefresh) {
                getLibraryManga.setCategoriesForManga(
                    mangaIds = listOf(mangaId),
                    categoryIds = categoryIds,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Set categories for multiple manga at once.
     * @param skipRefresh If true, skip the library refresh. Useful for batch operations.
     */
    suspend fun await(mangaIds: List<Long>, categoryIds: List<Long>, skipRefresh: Boolean = false) {
        try {
            mangaRepository.setMangasCategories(mangaIds, categoryIds)
            if (!skipRefresh) {
                getLibraryManga.setCategoriesForManga(
                    mangaIds = mangaIds,
                    categoryIds = categoryIds,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Add categories to mangas.
     * @param skipRefresh If true, skip the library refresh. Useful for batch operations
     *                    where the caller will handle refresh at the end.
     */
    suspend fun add(mangaIds: List<Long>, categoryIds: List<Long>, skipRefresh: Boolean = false): Boolean {
        return try {
            mangaRepository.addMangasCategories(mangaIds, categoryIds)
            if (!skipRefresh) {
                getLibraryManga.applyCategoryUpdates(
                    mangaIds = mangaIds,
                    addCategories = categoryIds,
                    removeCategories = emptyList(),
                )
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    /**
     * Remove categories from mangas.
     * @param skipRefresh If true, skip the library refresh. Useful for batch operations
     *                    where the caller will handle refresh at the end.
     */
    suspend fun remove(mangaIds: List<Long>, categoryIds: List<Long>, skipRefresh: Boolean = false): Boolean {
        return try {
            mangaRepository.removeMangasCategories(mangaIds, categoryIds)
            if (!skipRefresh) {
                getLibraryManga.applyCategoryUpdates(
                    mangaIds = mangaIds,
                    addCategories = emptyList(),
                    removeCategories = categoryIds,
                )
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    /**
     * Force a library refresh. Call this after batch operations with skipRefresh=true.
     * Uses forced refresh to bypass the minimum refresh interval since user explicitly
     * made changes that need to be reflected immediately.
     */
    suspend fun refreshLibrary() {
        getLibraryManga.refreshForced()
    }
}
