package tachiyomi.domain.library.model

/**
 * Experimental pagination: the subset of library filtering/sorting that can be expressed in SQL,
 * so a paged query returns the globally-correct slice instead of only filtering the loaded pages.
 *
 * Dimensions that can't live in the DB (downloaded state, tracker membership, exact include/exclude
 * tag matching, no-tags) are deliberately absent here and stay applied in memory on the loaded pages.
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
    val filterChapterCount: Int = 0,
    val filterChapterCountThreshold: Int = 0,
    val excludedSourceIds: List<Long> = emptyList(),
    // Lowercased ASCII tags, matched as substrings: a superset the in-memory filter narrows.
    val includedTags: List<String> = emptyList(),
    val search: Search? = null,
) {
    val isNarrowed: Boolean
        get() = filterUnread != 0 || filterStarted != 0 || filterBookmarked != 0 || filterCompleted != 0 ||
            filterIntervalCustom != 0 || filterChapterCount != 0 || excludedSourceIds.isNotEmpty() ||
            includedTags.isNotEmpty() || search != null

    // Parts are OR'ed. likeTerm is already LIKE-escaped and ASCII.
    data class Search(
        val likeTerm: String = "",
        val matchTitle: Boolean = false,
        val matchUrl: Boolean = false,
        val matchGenre: Boolean = false,
        val sourceIds: List<Long> = emptyList(),
        val mangaIds: List<Long> = emptyList(),
    )

    companion object {
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
