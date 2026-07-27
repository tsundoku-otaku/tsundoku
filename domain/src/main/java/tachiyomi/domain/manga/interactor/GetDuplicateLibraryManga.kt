package tachiyomi.domain.manga.interactor

import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.DuplicateSortMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository

class GetDuplicateLibraryManga(
    private val mangaRepository: MangaRepository,
    private val libraryPreferences: LibraryPreferences,
) {

    // "Find duplicates" passes force: it is a request for the check, not a guard on adding.
    suspend operator fun invoke(manga: Manga, force: Boolean = false): List<MangaWithChapterCount> {
        if (!force && !libraryPreferences.checkDuplicateEntryOnAdd.get()) return emptyList()
        val sortByChapterCount = libraryPreferences.duplicateSortMode.get() == DuplicateSortMode.ChapterCount
        // Ordering is done here, not in SQL: the title predicate is unindexable, so the query is a
        // scan of every favorite that only stops early once it has LIMIT rows. Ordering by chapter
        // count has to see every match to pick the largest, alphabetical does not - duplicates share
        // a title, so which candidates are cut is arbitrary either way.
        val duplicates = mangaRepository.getDuplicateLibraryManga(
            manga.id,
            manga.title.lowercase(),
            manga.alternativeTitles,
            limit = if (sortByChapterCount) -1 else DISPLAY_LIMIT,
        )
        return if (sortByChapterCount) {
            duplicates.sortedByDescending { it.chapterCount }.take(DISPLAY_LIMIT.toInt())
        } else {
            duplicates.sortedWith { a, b -> a.manga.title.compareToWithCollator(b.manga.title) }
        }
    }

    companion object {
        private const val DISPLAY_LIMIT = 10L
    }
}
