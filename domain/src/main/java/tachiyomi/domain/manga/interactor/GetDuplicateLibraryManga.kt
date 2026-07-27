package tachiyomi.domain.manga.interactor

import tachiyomi.domain.library.service.LibraryPreferences
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
        return mangaRepository.getDuplicateLibraryManga(manga.id, manga.title.lowercase(), manga.alternativeTitles)
    }
}
