package tachiyomi.domain.library.model

/**
 * Experimental pagination: the subset of library filtering/sorting that can be expressed in SQL,
 * so a paged query returns the globally-correct slice instead of only filtering the loaded pages.
 *
 * Dimensions that can't live in the DB (downloaded state, source name, tracker membership, exact
 * include/exclude tag matching, author/artist/description/chapter-name search) are deliberately
 * absent here and stay applied in memory on the loaded pages (hybrid model).
 *
 * Filter tri-states use the convention: 0 = ignore, 1 = must match, 2 = must NOT match.
 */
data class LibraryPageSpec(
    val sortType: Int = SORT_DATE_ADDED,
    val sortAscending: Boolean = false,
    val filterUnread: Int = 0,
    val filterStarted: Int = 0,
    val filterBookmarked: Int = 0,
    val filterCompleted: Int = 0,
    val filterIntervalCustom: Int = 0,
    val filterNoTags: Int = 0,
    val filterChapterCount: Int = 0,
    val filterChapterCountThreshold: Int = 0,
    // Empty = no exclusion. Bound as a NOT IN list, so callers pad with a sentinel to keep the
    // binding non-empty.
    val excludedSourceIds: List<Long> = emptyList(),
    // Default-field search term applied as a title/genre/url LIKE. Blank = no search.
    val searchTerm: String = "",
    // Included-tag prefilter: U+001F-joined, lowercased ASCII tags. DB keeps rows whose genre
    // contains ANY of them (case-insensitive substring), a superset of the exact in-memory match,
    // so it only narrows and never drops a row. Blank = skip. Populated only when every tag is
    // ASCII (SQLite lower() folds ASCII only).
    val includedTagsCsv: String = "",
) {
    companion object {
        // Sort columns that exist in the mangas table. Anything else maps to date_added and is
        // re-sorted in memory per page.
        const val SORT_ALPHABETICAL = 0
        const val SORT_LAST_READ = 1
        const val SORT_LAST_UPDATE = 2
        const val SORT_UNREAD_COUNT = 3
        const val SORT_TOTAL_CHAPTERS = 4
        const val SORT_LATEST_CHAPTER = 5
        const val SORT_CHAPTER_FETCH_DATE = 6
        const val SORT_DATE_ADDED = 7
    }
}
