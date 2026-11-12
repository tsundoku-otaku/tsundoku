package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.repository.MangaRepository

class SetMangaPinned(
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(mangaIds: List<Long>, pinned: Boolean) {
        mangaRepository.setPinned(mangaIds, pinned)
    }
}
