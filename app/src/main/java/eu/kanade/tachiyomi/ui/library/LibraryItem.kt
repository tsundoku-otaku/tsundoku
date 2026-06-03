package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.source.getNameForMangaInfo
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class LibraryItem(
    val libraryManga: LibraryManga,
    val downloadCount: Long = -1,
    val unreadCount: Long = -1,
    val isLocal: Boolean = false,
    val sourceLanguage: String = "",
) {
    val id: Long = libraryManga.id

    companion object {
        private val sourceManager: SourceManager by lazy { Injekt.get() }
    }

    /**
     * Gets the full URL by combining the source base URL with the manga URL path
     */
    val fullUrl: String by lazy {
        val source = sourceManager.getOrStub(libraryManga.manga.source)
        val baseUrl = (source as? eu.kanade.tachiyomi.source.online.HttpSource)?.baseUrl ?: ""
        val mangaUrl = libraryManga.manga.url
        if (baseUrl.isNotEmpty() && mangaUrl.isNotEmpty()) {
            if (mangaUrl.startsWith("http://") || mangaUrl.startsWith("https://")) {
                mangaUrl // Already a full URL
            } else {
                baseUrl.trimEnd('/') + (if (mangaUrl.startsWith("/")) mangaUrl else "/$mangaUrl")
            }
        } else {
            mangaUrl
        }
    }

    /**
     * Checks if a parsed query matches this manga.
     *
     * Author/artist/description matches are resolved via [metadataMatchIds] and chapter-name
     * matches via [chapterMatchIds] because those fields are not loaded into the in-memory
     * library list (they'd add strings per favorite on a large library).
     */
    fun matches(
        spec: LibrarySearchSpec,
        chapterMatchIds: Set<Long> = emptySet(),
        metadataMatchIds: LibraryScreenModel.MetadataMatchIds = LibraryScreenModel.MetadataMatchIds(),
    ): Boolean {
        val manga = libraryManga.manga
        return when (spec.field) {
            LibrarySearchSpec.Field.ID -> id == spec.term.toLongOrNull()
            LibrarySearchSpec.Field.TITLE -> matchField(manga.title, spec.term, spec.termRegex, spec.useRegex)
            LibrarySearchSpec.Field.AUTHOR -> metadataMatchIds.author.contains(id)
            LibrarySearchSpec.Field.ARTIST -> metadataMatchIds.artist.contains(id)
            LibrarySearchSpec.Field.DESCRIPTION -> metadataMatchIds.description.contains(id)
            LibrarySearchSpec.Field.TAG ->
                manga.genre?.any { matchField(it, spec.term, spec.termRegex, spec.useRegex) } ?: false
            LibrarySearchSpec.Field.SOURCE -> matchField(sourceName, spec.term, spec.termRegex, spec.useRegex)
            LibrarySearchSpec.Field.URL -> matchField(manga.url, spec.term, spec.termRegex, spec.useRegex)
            LibrarySearchSpec.Field.CHAPTER -> chapterMatchIds.contains(id)
            LibrarySearchSpec.Field.DEFAULT -> matchesDefault(spec, chapterMatchIds, metadataMatchIds)
        }
    }

    // Memoized: source resolution + name build is otherwise repeated per sub-term per item,
    // which is wasteful when filtering a 200k library.
    private val sourceName: String by lazy {
        sourceManager.getOrStub(libraryManga.manga.source).getNameForMangaInfo()
    }

    private fun matchesDefault(
        spec: LibrarySearchSpec,
        chapterMatchIds: Set<Long>,
        metadataMatchIds: LibraryScreenModel.MetadataMatchIds,
    ): Boolean {
        if (spec.subTerms.isEmpty()) return true
        val manga = libraryManga.manga

        // Every comma sub-term must match some in-memory field (negatable per sub-term). Author,
        // artist and description aren't resident, so they're handled via the DB id-sets below.
        val inMemoryMatch = spec.subTerms.all { sub ->
            val hit = matchField(manga.title, sub.text, sub.regex, spec.useRegex) ||
                (spec.searchByUrl && matchField(manga.url, sub.text, sub.regex, spec.useRegex)) ||
                matchField(sourceName, sub.text, sub.regex, spec.useRegex) ||
                (manga.genre?.any { matchField(it, sub.text, sub.regex, spec.useRegex) } ?: false)
            if (sub.negate) !hit else hit
        }
        if (inMemoryMatch) return true

        // Author/artist/description/chapter live in the DB; their id-sets cover the whole query.
        return metadataMatchIds.author.contains(id) ||
            metadataMatchIds.artist.contains(id) ||
            metadataMatchIds.description.contains(id) ||
            chapterMatchIds.contains(id)
    }

    private fun matchField(text: String, term: String, regex: Regex?, useRegex: Boolean): Boolean {
        return if (useRegex) {
            regex?.containsMatchIn(text) ?: text.contains(term, ignoreCase = true)
        } else {
            text.contains(term, ignoreCase = true)
        }
    }
}
