package tachiyomi.domain.library.model

/** Sort key (the title for alphabetical, a Long otherwise) and id of the last row of a library page. */
data class LibraryPageCursor(val key: Any, val id: Long)

class LibraryKeyedPage(val rows: List<LibraryManga>, val last: LibraryPageCursor?)
