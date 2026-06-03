package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Resolves favorite-manga matches for fields that are NOT kept in the in-memory library list
 * (author, artist, description). Keeping these out of memory saves two-to-three strings per
 * favorite on large libraries; matching streams the DB cursor on demand instead.
 *
 * Matching happens in Kotlin so it is Unicode case-insensitive and supports regex, with no
 * SQL LIKE wildcard/escaping concerns.
 */
class SearchMangaMetadata(
    private val mangaRepository: MangaRepository,
) {

    data class MatchIds(
        val author: Set<Long> = emptySet(),
        val artist: Set<Long> = emptySet(),
        val description: Set<Long> = emptySet(),
    )

    /**
     * @param term plain substring to match (ignored when [regex] is non-null).
     * @param regex optional compiled pattern; takes precedence over [term].
     */
    suspend fun await(
        term: String,
        regex: Regex?,
        searchAuthor: Boolean,
        searchArtist: Boolean,
        searchDescription: Boolean,
    ): MatchIds {
        if (regex == null && term.isBlank()) return MatchIds()
        if (!searchAuthor && !searchArtist && !searchDescription) return MatchIds()

        val predicate: (String) -> Boolean = if (regex != null) {
            { regex.containsMatchIn(it) }
        } else {
            { it.contains(term, ignoreCase = true) }
        }

        val (author, artist, description) = mangaRepository.findFavoriteIdsMatchingMetadata(
            matchAuthor = predicate.takeIf { searchAuthor },
            matchArtist = predicate.takeIf { searchArtist },
            matchDescription = predicate.takeIf { searchDescription },
        )
        return MatchIds(author = author, artist = artist, description = description)
    }
}
