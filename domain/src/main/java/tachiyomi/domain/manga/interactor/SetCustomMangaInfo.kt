package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo.Companion.writeInto
import tachiyomi.domain.manga.model.CustomMangaInfo.Companion.writeSourceInto
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class SetCustomMangaInfo(
    private val mangaRepository: MangaRepository,
) {

    /** Store [source] as the snapshot for [mangaId] when none exists yet; no-op otherwise. */
    suspend fun snapshotSourceIfAbsent(mangaId: Long, source: CustomMangaInfo): Boolean {
        val memo = mangaRepository.getMemo(mangaId)
        if (CustomMangaInfo.fromSource(memo) != null) return false
        return mangaRepository.update(MangaUpdate(id = mangaId, memo = source.writeSourceInto(memo)))
    }

    /** Read-modify-write the override for [mangaId]; other memo keys kept, an all-null result clears it. */
    suspend fun await(mangaId: Long, transform: (CustomMangaInfo) -> CustomMangaInfo): Boolean {
        val memo = mangaRepository.getMemo(mangaId)
        val current = CustomMangaInfo.from(memo) ?: CustomMangaInfo()
        val updated = transform(current).takeUnless { it.isEmpty() }
        val newMemo = updated.writeInto(memo)
        return mangaRepository.update(MangaUpdate(id = mangaId, memo = newMemo))
    }

    /**
     * Remove all overrides for [mangaId]. Returns true when row fields were restored from the source
     * snapshot; false when nothing to clear or no snapshot exists (caller should refresh from source).
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
