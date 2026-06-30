package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo.Companion.writeInto
import tachiyomi.domain.manga.model.CustomMangaInfo.Companion.writeSourceInto
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class SetCustomMangaInfo(
    private val mangaRepository: MangaRepository,
) {

    /**
     * Atomically apply field-level overrides for [mangaId] in one DB write.
     *
     * On the very first edit (no existing override and no source snapshot yet), [sourceSnapshot]
     * is captured as the revert baseline. Pass null for any field that should remain unchanged.
     * A blank string clears the override for that field so the source value flows through on
     * the next refresh.
     */
    suspend fun awaitWithFields(
        mangaId: Long,
        sourceSnapshot: CustomMangaInfo,
        author: String? = null,
        artist: String? = null,
        description: String? = null,
        status: Long? = null,
        genre: List<String>? = null,
    ): Boolean {
        val memo = mangaRepository.getMemo(mangaId)
        val baseMemo = if (CustomMangaInfo.from(memo) == null && CustomMangaInfo.fromSource(memo) == null) {
            sourceSnapshot.writeSourceInto(memo)
        } else {
            memo
        }
        val current = CustomMangaInfo.from(baseMemo) ?: CustomMangaInfo()
        val updated = current.copy(
            author = if (author != null) author.trim().ifBlank { null } else current.author,
            artist = if (artist != null) artist.trim().ifBlank { null } else current.artist,
            description = if (description != null) description.trim().ifBlank { null } else current.description,
            status = status ?: current.status,
            genre = genre ?: current.genre,
        ).takeUnless { it.isEmpty() }
        val newMemo = updated.writeInto(baseMemo)
        return mangaRepository.update(
            MangaUpdate(
                id = mangaId,
                memo = newMemo,
                author = author,
                artist = artist,
                description = description,
                status = status,
                genre = genre,
            ),
        )
    }

    /**
     * Remove all overrides for [mangaId]. Returns true when row fields were restored from the source
     * snapshot; false when nothing to clear or no snapshot exists (caller should refresh from source).
     */
    suspend fun clear(mangaId: Long): Boolean {
        val memo = mangaRepository.getMemo(mangaId)
        if (CustomMangaInfo.from(memo) == null) return false
        val source = CustomMangaInfo.fromSource(memo)
        val newMemo = (null as CustomMangaInfo?).writeInto((null as CustomMangaInfo?).writeSourceInto(memo))
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
