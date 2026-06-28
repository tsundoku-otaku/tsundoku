package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo.Companion.writeInto
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class SetCustomMangaInfo(
    private val mangaRepository: MangaRepository,
) {

    /**
     * Read the current override for [mangaId], apply [transform], and persist the result back into
     * the manga's memo. Other memo keys are preserved; an all-null result clears the override.
     */
    suspend fun await(mangaId: Long, transform: (CustomMangaInfo) -> CustomMangaInfo): Boolean {
        val memo = mangaRepository.getMemo(mangaId)
        val current = CustomMangaInfo.from(memo) ?: CustomMangaInfo()
        val updated = transform(current).takeUnless { it.isEmpty() }
        val newMemo = updated.writeInto(memo)
        return mangaRepository.update(MangaUpdate(id = mangaId, memo = newMemo))
    }

    /**
     * Remove all custom overrides for [mangaId]. When a source snapshot is present, the row fields
     * are restored from it so the original metadata returns immediately (no network). Returns true
     * when restored from the snapshot, false when there was nothing to clear or no snapshot exists
     * (in which case the caller should refresh from the source).
     */
    suspend fun clear(mangaId: Long): Boolean {
        val memo = mangaRepository.getMemo(mangaId)
        if (CustomMangaInfo.from(memo) == null) return false
        val source = CustomMangaInfo.fromSource(memo)
        val newMemo = (null as CustomMangaInfo?).writeInto(memo)
        val update = if (source != null) {
            MangaUpdate(
                id = mangaId,
                memo = newMemo,
                author = source.author,
                artist = source.artist,
                description = source.description,
                genre = source.genre,
                status = source.status,
            )
        } else {
            MangaUpdate(id = mangaId, memo = newMemo)
        }
        mangaRepository.update(update)
        return source != null
    }
}
