package tachiyomi.data.manga

import tachiyomi.domain.library.model.LibraryPageSpec

internal class LibraryPageQuery(val sql: String, val args: List<Any>)

/**
 * Paged library SQL. Each sort gets a static ORDER BY that its index in [indexes] can walk and stop
 * at the LIMIT. Ties break on _id in the sort direction so one (is_novel, key) index serves both
 * directions.
 */
internal object LibraryPageSql {

    // Column order is what [MangaRepositoryImpl] reads by index.
    private const val COLUMNS = """
        M._id, M.source, M.url, M.genre, M.title, M.status, M.thumbnail_url, M.favorite,
        M.last_update, M.next_update, M.cover_last_modified, M.date_added, M.calculate_interval,
        M.is_novel, M.total_count, M.read_count, M.latest_upload, M.chapter_fetched_at, M.last_read,
        M.bookmark_count,
        coalesce((SELECT group_concat(category_id) FROM mangas_categories WHERE manga_id = M._id), '0')
    """

    private const val UNREAD = "(M.total_count - M.read_count)"

    // Zero-unread entries go last in both directions, matching the in-memory comparator.
    private const val UNREAD_ASC_KEY = "CASE WHEN $UNREAD = 0 THEN 9223372036854775807 ELSE $UNREAD END"
    private const val UNREAD_DESC_KEY = "CASE WHEN $UNREAD = 0 THEN -1 ELSE $UNREAD END"

    val indexes: List<Pair<String, String>> = listOf(
        "idx_library_page_title" to "title COLLATE NOCASE",
        "idx_library_page_last_read" to "last_read",
        "idx_library_page_last_update" to "coalesce(last_update, 0)",
        "idx_library_page_unread_asc" to UNREAD_ASC_KEY.unqualified(),
        "idx_library_page_unread_desc" to UNREAD_DESC_KEY.unqualified(),
        "idx_library_page_total" to "total_count",
        "idx_library_page_latest_upload" to "latest_upload",
        "idx_library_page_fetched_at" to "chapter_fetched_at",
        "idx_library_page_date_added" to "date_added",
    )

    fun createIndexStatements(): List<String> = indexes.map { (name, key) ->
        "CREATE INDEX IF NOT EXISTS $name ON mangas(is_novel, $key) WHERE favorite = 1"
    }

    fun dropIndexStatements(): List<String> = indexes.map { (name, _) -> "DROP INDEX IF EXISTS $name" }

    fun page(
        categoryId: Long,
        isNovel: Boolean?,
        spec: LibraryPageSpec,
        limit: Long?,
        offset: Long,
        smallCategory: Boolean = false,
    ): LibraryPageQuery = select(COLUMNS, categoryId, isNovel, spec, limit, offset, smallCategory, bySource = false)

    /** The ids of [page] in the same order. */
    fun ids(
        categoryId: Long,
        isNovel: Boolean?,
        spec: LibraryPageSpec,
        limit: Long,
        smallCategory: Boolean = false,
        bySource: Boolean = false,
    ): LibraryPageQuery = select("M._id", categoryId, isNovel, spec, limit, 0, smallCategory, bySource)

    fun rows(ids: List<Long>): LibraryPageQuery =
        LibraryPageQuery("SELECT $COLUMNS FROM mangas M WHERE M._id IN (${placeholders(ids.size)})", ids)

    /** Rows of [sourceIds], favorite or not, capped at [limit]. */
    fun sourceRows(sourceIds: List<Long>, limit: Long): LibraryPageQuery = LibraryPageQuery(
        "SELECT count(*) FROM (SELECT 1 FROM mangas INDEXED BY idx_mangas_source " +
            "WHERE source IN (${placeholders(sourceIds.size)}) LIMIT ?)",
        sourceIds + limit,
    )

    /** The sources a search is pinned to, when it matches nothing but them. */
    fun pinnedSources(spec: LibraryPageSpec): List<Long>? {
        val search = spec.search ?: return null
        val matchesText = search.likeTerm.isNotEmpty() && (search.matchTitle || search.matchUrl || search.matchGenre)
        return search.sourceIds.takeIf { it.isNotEmpty() && search.mangaIds.isEmpty() && !matchesText }
    }

    fun count(
        categoryId: Long,
        isNovel: Boolean?,
        spec: LibraryPageSpec,
        smallCategory: Boolean = false,
        bySource: Boolean = false,
    ): LibraryPageQuery {
        val args = ArrayList<Any>()
        // Probing membership per row beats random row lookups for every member of a big category.
        val membership = if (smallCategory || categoryId == 0L) Membership.LOOKUP else Membership.WALK
        val where = where(categoryId, isNovel, spec, args, membership)
        return LibraryPageQuery("SELECT count(*) FROM ${from(bySource)} WHERE $where", args)
    }

    private fun select(
        columns: String,
        categoryId: Long,
        isNovel: Boolean?,
        spec: LibraryPageSpec,
        limit: Long?,
        offset: Long,
        smallCategory: Boolean,
        bySource: Boolean,
    ): LibraryPageQuery {
        val args = ArrayList<Any>()
        val membership = if (smallCategory) Membership.LOOKUP else Membership.WALK
        val where = where(categoryId, isNovel, spec, args, membership)
        val direction = if (spec.sortAscending) "ASC" else "DESC"
        val sql = buildString {
            append("SELECT ").append(columns).append(" FROM ").append(from(bySource)).append(" WHERE ").append(where)
            append(" ORDER BY ").append(sortKey(spec)).append(' ').append(direction)
            append(", M._id ").append(direction)
            if (limit != null) {
                append(" LIMIT ? OFFSET ?")
                args.add(limit)
                args.add(offset)
            }
        }
        return LibraryPageQuery(sql, args)
    }

    private fun from(bySource: Boolean): String =
        if (bySource) "mangas M INDEXED BY idx_mangas_source" else "mangas M"

    fun countFavorites(isNovel: Boolean?): LibraryPageQuery {
        val args = ArrayList<Any>()
        val sql = buildString {
            append("SELECT count(*) FROM mangas M WHERE M.favorite = 1")
            if (isNovel != null) {
                append(" AND M.is_novel = ?")
                args.add(isNovel.toLong())
            }
        }
        return LibraryPageQuery(sql, args)
    }

    fun categoryMembers(categoryId: Long): LibraryPageQuery =
        LibraryPageQuery("SELECT count(*) FROM mangas_categories WHERE category_id = ?", listOf(categoryId))

    fun hasUncategorized(isNovel: Boolean?): LibraryPageQuery {
        val args = ArrayList<Any>()
        val sql = buildString {
            append("SELECT EXISTS (SELECT 1 FROM mangas M WHERE M.favorite = 1")
            if (isNovel != null) {
                append(" AND M.is_novel = ?")
                args.add(isNovel.toLong())
            }
            append(" AND ").append(membership(0L, args, Membership.WALK)).append(")")
        }
        return LibraryPageQuery(sql, args)
    }

    private fun sortKey(spec: LibraryPageSpec): String = when (spec.sortType) {
        LibraryPageSpec.SORT_ALPHABETICAL -> "M.title COLLATE NOCASE"
        LibraryPageSpec.SORT_LAST_READ -> "M.last_read"
        LibraryPageSpec.SORT_LAST_UPDATE -> "coalesce(M.last_update, 0)"
        LibraryPageSpec.SORT_UNREAD_COUNT -> if (spec.sortAscending) UNREAD_ASC_KEY else UNREAD_DESC_KEY
        LibraryPageSpec.SORT_TOTAL_CHAPTERS -> "M.total_count"
        LibraryPageSpec.SORT_LATEST_CHAPTER -> "M.latest_upload"
        LibraryPageSpec.SORT_CHAPTER_FETCH_DATE -> "M.chapter_fetched_at"
        else -> "M.date_added"
    }

    private enum class Membership {
        // A correlated EXISTS can't drive the scan, so the planner walks the sort index.
        WALK,

        // An IN list drives the query from the category's membership rows.
        LOOKUP,
    }

    private fun membership(categoryId: Long, args: MutableList<Any>, mode: Membership): String {
        return when {
            categoryId == 0L && mode == Membership.WALK ->
                "NOT EXISTS (SELECT 1 FROM mangas_categories MC WHERE MC.manga_id = M._id AND MC.category_id <> 0)"
            categoryId == 0L -> "M._id NOT IN (SELECT manga_id FROM mangas_categories WHERE category_id <> 0)"
            mode == Membership.WALK -> {
                args.add(categoryId)
                "EXISTS (SELECT 1 FROM mangas_categories MC WHERE MC.manga_id = M._id AND MC.category_id = ?)"
            }
            else -> {
                args.add(categoryId)
                "M._id IN (SELECT manga_id FROM mangas_categories WHERE category_id = ?)"
            }
        }
    }

    private fun where(
        categoryId: Long,
        isNovel: Boolean?,
        spec: LibraryPageSpec,
        args: MutableList<Any>,
        mode: Membership,
    ): String {
        val clauses = ArrayList<String>()
        clauses.add("M.favorite = 1")
        if (isNovel != null) {
            clauses.add("M.is_novel = ?")
            args.add(isNovel.toLong())
        }
        clauses.add(membership(categoryId, args, mode))

        triState(spec.filterUnread, "$UNREAD > 0")?.let(clauses::add)
        triState(spec.filterStarted, "M.read_count > 0")?.let(clauses::add)
        triState(spec.filterBookmarked, "M.bookmark_count > 0")?.let(clauses::add)
        triState(spec.filterCompleted, "M.status = 2")?.let(clauses::add)
        triState(spec.filterIntervalCustom, "M.calculate_interval < 0")?.let(clauses::add)
        triState(spec.filterChapterCount, "M.total_count >= ?")?.let {
            clauses.add(it)
            args.add(spec.filterChapterCountThreshold.toLong())
        }

        if (spec.excludedSourceIds.isNotEmpty()) {
            clauses.add("M.source NOT IN (${placeholders(spec.excludedSourceIds.size)})")
            args.addAll(spec.excludedSourceIds)
        }

        if (spec.includedTags.isNotEmpty()) {
            clauses.add(spec.includedTags.joinToString(" OR ", "(", ")") { "lower(coalesce(M.genre, '')) LIKE ?" })
            spec.includedTags.forEach { args.add("%$it%") }
        }

        spec.search?.let { clauses.add(search(it, args)) }

        return clauses.joinToString(" AND ")
    }

    private fun search(search: LibraryPageSpec.Search, args: MutableList<Any>): String {
        val parts = ArrayList<String>()
        val pattern = "%${search.likeTerm}%"
        if (search.likeTerm.isNotEmpty()) {
            if (search.matchTitle) {
                parts.add("M.title LIKE ? ESCAPE '\\'")
                args.add(pattern)
            }
            if (search.matchUrl) {
                parts.add("M.url LIKE ? ESCAPE '\\'")
                args.add(pattern)
            }
            if (search.matchGenre) {
                parts.add("coalesce(M.genre, '') LIKE ? ESCAPE '\\'")
                args.add(pattern)
            }
        }
        if (search.sourceIds.isNotEmpty()) {
            parts.add("M.source IN (${placeholders(search.sourceIds.size)})")
            args.addAll(search.sourceIds)
        }
        if (search.mangaIds.isNotEmpty()) {
            parts.add("M._id IN (${placeholders(search.mangaIds.size)})")
            args.addAll(search.mangaIds)
        }
        return if (parts.isEmpty()) "0" else parts.joinToString(" OR ", "(", ")")
    }

    private fun triState(value: Int, condition: String): String? = when (value) {
        1 -> condition
        2 -> "NOT ($condition)"
        else -> null
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

    private fun String.unqualified(): String = replace("M.", "")

    private fun Boolean.toLong(): Long = if (this) 1L else 0L
}
