package tachiyomi.data.manga

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.domain.library.model.LibraryPageSpec
import java.sql.Connection
import java.sql.DriverManager

class LibraryPageSqlTest {

    private lateinit var db: Connection

    private data class Row(
        val id: Long,
        val title: String,
        val source: Long = 1,
        val url: String = "/$id",
        val genre: String? = null,
        val isNovel: Boolean = false,
        val favorite: Boolean = true,
        val status: Long = 1,
        val lastUpdate: Long? = id * 10,
        val dateAdded: Long = id,
        val total: Long = 0,
        val read: Long = 0,
        val lastRead: Long = 0,
        val categories: List<Long> = emptyList(),
    )

    @BeforeEach
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE mangas(
                    _id INTEGER NOT NULL PRIMARY KEY,
                    source INTEGER NOT NULL,
                    url TEXT NOT NULL,
                    genre TEXT,
                    title TEXT NOT NULL,
                    alternative_titles TEXT,
                    status INTEGER NOT NULL,
                    thumbnail_url TEXT,
                    favorite INTEGER NOT NULL,
                    last_update INTEGER,
                    next_update INTEGER,
                    cover_last_modified INTEGER NOT NULL DEFAULT 0,
                    date_added INTEGER NOT NULL,
                    calculate_interval INTEGER NOT NULL DEFAULT 0,
                    is_novel INTEGER NOT NULL DEFAULT 0,
                    total_count INTEGER NOT NULL DEFAULT 0,
                    read_count INTEGER NOT NULL DEFAULT 0,
                    latest_upload INTEGER NOT NULL DEFAULT 0,
                    chapter_fetched_at INTEGER NOT NULL DEFAULT 0,
                    last_read INTEGER NOT NULL DEFAULT 0,
                    bookmark_count INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            st.executeUpdate(
                """
                CREATE TABLE mangas_categories(
                    _id INTEGER NOT NULL PRIMARY KEY,
                    manga_id INTEGER NOT NULL,
                    category_id INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            // Same alternatives the real schema gives the planner.
            st.executeUpdate("CREATE INDEX library_favorite_index ON mangas(favorite) WHERE favorite = 1")
            st.executeUpdate("CREATE INDEX idx_mangas_library_is_novel ON mangas(is_novel) WHERE favorite = 1")
            st.executeUpdate("CREATE INDEX idx_mangas_source ON mangas(source)")
            st.executeUpdate(
                "CREATE INDEX mangas_categories_manga_category_index ON mangas_categories(manga_id, category_id)",
            )
            st.executeUpdate("CREATE INDEX idx_mangas_categories_category_id ON mangas_categories(category_id)")
            LibraryPageSql.createIndexStatements().forEach(st::executeUpdate)
        }
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    private fun insert(rows: List<Row>) {
        db.prepareStatement(
            """
            INSERT INTO mangas(_id, source, url, genre, title, status, favorite, last_update, date_added,
                is_novel, total_count, read_count, last_read)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { st ->
            for (row in rows) {
                st.setLong(1, row.id)
                st.setLong(2, row.source)
                st.setString(3, row.url)
                st.setString(4, row.genre)
                st.setString(5, row.title)
                st.setLong(6, row.status)
                st.setInt(7, if (row.favorite) 1 else 0)
                if (row.lastUpdate == null) st.setNull(8, java.sql.Types.INTEGER) else st.setLong(8, row.lastUpdate)
                st.setLong(9, row.dateAdded)
                st.setInt(10, if (row.isNovel) 1 else 0)
                st.setLong(11, row.total)
                st.setLong(12, row.read)
                st.setLong(13, row.lastRead)
                st.addBatch()
            }
            st.executeBatch()
        }
        db.prepareStatement("INSERT INTO mangas_categories(manga_id, category_id) VALUES (?, ?)").use { st ->
            for (row in rows) {
                for (category in row.categories) {
                    st.setLong(1, row.id)
                    st.setLong(2, category)
                    st.addBatch()
                }
            }
            st.executeBatch()
        }
    }

    private fun ids(query: LibraryPageQuery): List<Long> {
        db.prepareStatement(query.sql).use { st ->
            bind(st, query)
            st.executeQuery().use { rs ->
                val result = ArrayList<Long>()
                while (rs.next()) result.add(rs.getLong(1))
                return result
            }
        }
    }

    private fun single(query: LibraryPageQuery): Long = ids(query).single()

    private fun plan(query: LibraryPageQuery): String {
        db.prepareStatement("EXPLAIN QUERY PLAN ${query.sql}").use { st ->
            bind(st, query)
            st.executeQuery().use { rs ->
                val lines = ArrayList<String>()
                while (rs.next()) lines.add(rs.getString("detail"))
                return lines.joinToString("\n")
            }
        }
    }

    private fun bind(st: java.sql.PreparedStatement, query: LibraryPageQuery) {
        query.args.forEachIndexed { index, arg ->
            when (arg) {
                is Long -> st.setLong(index + 1, arg)
                is String -> st.setString(index + 1, arg)
                else -> error("unexpected $arg")
            }
        }
    }

    private fun spec(sortType: Int, ascending: Boolean) =
        LibraryPageSpec(sortType = sortType, sortAscending = ascending)

    @ParameterizedTest
    @MethodSource("sorts")
    fun `every sort walks its index without sorting the category`(sortType: Int, ascending: Boolean) {
        insert((1L..200L).map { Row(it, "Title $it", isNovel = it % 2 == 0L) })

        val plan = plan(LibraryPageSql.page(0L, isNovel = true, spec(sortType, ascending), limit = 50, offset = 0))

        plan shouldContain "idx_library_page_"
        plan shouldNotContain "TEMP B-TREE"
    }

    @ParameterizedTest
    @MethodSource("sorts")
    fun `a real category also walks the sort index`(sortType: Int, ascending: Boolean) {
        insert((1L..200L).map { Row(it, "Title $it", categories = listOf(5L)) })

        val plan = plan(LibraryPageSql.page(5L, isNovel = false, spec(sortType, ascending), limit = 50, offset = 0))

        plan shouldContain "idx_library_page_"
        plan shouldNotContain "TEMP B-TREE"
    }

    @ParameterizedTest
    @MethodSource("sorts")
    fun `the sort index is still chosen once the database is analyzed`(sortType: Int, ascending: Boolean) {
        insert(
            (1L..5_000L).map {
                Row(
                    it,
                    "Title $it",
                    isNovel = it % 20 != 0L,
                    categories = if (it % 50 ==
                        0L
                    ) {
                        listOf(3L)
                    } else {
                        emptyList()
                    },
                )
            },
        )
        db.createStatement().use { it.executeUpdate("ANALYZE") }

        val plan = plan(LibraryPageSql.page(0L, isNovel = true, spec(sortType, ascending), limit = 50, offset = 0))

        plan shouldContain "idx_library_page_"
        plan shouldNotContain "TEMP B-TREE"
    }

    @ParameterizedTest
    @MethodSource("sorts")
    fun `a small category is read through its members in the same order`(sortType: Int, ascending: Boolean) {
        insert(
            (1L..3_000L).map {
                Row(
                    it,
                    "Title ${it % 97}",
                    lastRead = it % 13,
                    total = it % 7,
                    read = it % 5,
                    categories = if (it % 300 == 0L) listOf(8L) else emptyList(),
                )
            },
        )
        db.createStatement().use { it.executeUpdate("ANALYZE") }
        val spec = spec(sortType, ascending)

        val walked = ids(LibraryPageSql.page(8L, false, spec, limit = null, offset = 0))
        val lookup = LibraryPageSql.page(8L, false, spec, limit = null, offset = 0, smallCategory = true)

        ids(lookup) shouldContainExactly walked
        walked.size shouldBe 10
        plan(lookup) shouldContain "mangas_categories"
        single(LibraryPageSql.count(8L, false, spec)) shouldBe 10L
        single(LibraryPageSql.count(8L, false, spec, smallCategory = true)) shouldBe 10L
        single(LibraryPageSql.count(0L, false, spec)) shouldBe 2_990L
        single(LibraryPageSql.categoryMembers(8L)) shouldBe 10L
    }

    @Test
    fun `pages concatenate to the full order with id tiebreaks in the sort direction`() {
        insert(
            listOf(
                Row(1, "b", lastRead = 5),
                Row(2, "a", lastRead = 5),
                Row(3, "c", lastRead = 9),
                Row(4, "d", lastRead = 0),
                Row(5, "e", lastRead = 5),
            ),
        )
        val desc = spec(LibraryPageSpec.SORT_LAST_READ, ascending = false)

        val pages = (0L..4L step 2).flatMap {
            ids(LibraryPageSql.page(0L, false, desc, limit = 2, offset = it))
        }

        pages shouldContainExactly listOf(3L, 5L, 2L, 1L, 4L)
        ids(
            LibraryPageSql.page(0L, false, desc.copy(sortAscending = true), limit = null, offset = 0),
        ) shouldContainExactly
            listOf(4L, 1L, 2L, 5L, 3L)
    }

    @Test
    fun `alphabetical sort folds ASCII case`() {
        insert(listOf(Row(1, "beta"), Row(2, "Alpha"), Row(3, "alpha"), Row(4, "Gamma")))

        ids(LibraryPageSql.page(0L, false, spec(LibraryPageSpec.SORT_ALPHABETICAL, true), null, 0)) shouldContainExactly
            listOf(2L, 3L, 1L, 4L)
    }

    @Test
    fun `unread sort keeps fully read entries last in both directions`() {
        insert(
            listOf(
                Row(1, "a", total = 10, read = 10),
                Row(2, "b", total = 10, read = 7),
                Row(3, "c", total = 10, read = 1),
                Row(4, "d", total = 5, read = 5),
            ),
        )
        val asc = spec(LibraryPageSpec.SORT_UNREAD_COUNT, ascending = true)

        ids(LibraryPageSql.page(0L, false, asc, null, 0)) shouldContainExactly listOf(2L, 3L, 1L, 4L)
        ids(LibraryPageSql.page(0L, false, asc.copy(sortAscending = false), null, 0)) shouldContainExactly
            listOf(3L, 2L, 4L, 1L)
    }

    @Test
    fun `uncategorized bucket excludes entries with a real category`() {
        insert(
            listOf(
                Row(1, "a"),
                Row(2, "b", categories = listOf(7L)),
                Row(3, "c", categories = listOf(0L)),
                Row(4, "d", favorite = false),
                Row(5, "e", isNovel = true),
            ),
        )

        ids(LibraryPageSql.page(0L, false, spec(LibraryPageSpec.SORT_DATE_ADDED, true), null, 0)) shouldContainExactly
            listOf(1L, 3L)
        ids(LibraryPageSql.page(0L, null, spec(LibraryPageSpec.SORT_DATE_ADDED, true), null, 0)) shouldContainExactly
            listOf(1L, 3L, 5L)
        ids(LibraryPageSql.page(7L, false, spec(LibraryPageSpec.SORT_DATE_ADDED, true), null, 0)) shouldContainExactly
            listOf(2L)
        single(LibraryPageSql.hasUncategorized(isNovel = true)) shouldBe 1L
        single(LibraryPageSql.countFavorites(isNovel = null)) shouldBe 4L
    }

    @Test
    fun `tri-state filters include and exclude`() {
        insert(
            listOf(
                Row(1, "a", total = 3, read = 0, status = 2),
                Row(2, "b", total = 3, read = 3),
                Row(3, "c", total = 3, read = 1),
            ),
        )
        val base = spec(LibraryPageSpec.SORT_DATE_ADDED, true)

        ids(LibraryPageSql.page(0L, false, base.copy(filterUnread = 1), null, 0)) shouldContainExactly listOf(1L, 3L)
        ids(LibraryPageSql.page(0L, false, base.copy(filterUnread = 2), null, 0)) shouldContainExactly listOf(2L)
        ids(LibraryPageSql.page(0L, false, base.copy(filterStarted = 2), null, 0)) shouldContainExactly listOf(1L)
        ids(LibraryPageSql.page(0L, false, base.copy(filterCompleted = 2), null, 0)) shouldContainExactly listOf(2L, 3L)
        single(LibraryPageSql.count(0L, false, base.copy(filterUnread = 1))) shouldBe 2L
    }

    @Test
    fun `search matches enabled fields, source ids and extra ids`() {
        insert(
            listOf(
                Row(1, "Sword 100%", url = "/x"),
                Row(2, "Other", url = "/sword"),
                Row(3, "Other", genre = "Action, Sword"),
                Row(4, "Other", source = 9),
                Row(5, "Other"),
                Row(6, "Sword 100x"),
            ),
        )
        val base = spec(LibraryPageSpec.SORT_DATE_ADDED, true)
        fun search(
            search: LibraryPageSpec.Search,
        ) = ids(LibraryPageSql.page(0L, false, base.copy(search = search), null, 0))

        search(LibraryPageSpec.Search(likeTerm = "sword", matchTitle = true)) shouldContainExactly listOf(1L, 6L)
        search(
            LibraryPageSpec.Search(likeTerm = "sword", matchTitle = true, matchUrl = true, matchGenre = true),
        ) shouldContainExactly
            listOf(1L, 2L, 3L, 6L)
        search(LibraryPageSpec.Search(likeTerm = "100\\%", matchTitle = true)) shouldContainExactly listOf(1L)
        search(LibraryPageSpec.Search(sourceIds = listOf(9L), mangaIds = listOf(5L))) shouldContainExactlyInAnyOrder
            listOf(4L, 5L)
        search(LibraryPageSpec.Search()) shouldContainExactly emptyList()
    }

    @Test
    fun `included tags and excluded sources narrow the page`() {
        insert(
            listOf(
                Row(1, "a", genre = "Action, Drama"),
                Row(2, "b", genre = "Romance"),
                Row(3, "c", genre = "action", source = 4),
            ),
        )
        val base = spec(LibraryPageSpec.SORT_DATE_ADDED, true)

        ids(LibraryPageSql.page(0L, false, base.copy(includedTags = listOf("action")), null, 0)) shouldContainExactly
            listOf(1L, 3L)
        ids(
            LibraryPageSql.page(
                0L,
                false,
                base.copy(includedTags = listOf("action"), excludedSourceIds = listOf(4L)),
                null,
                0,
            ),
        ) shouldContainExactly
            listOf(1L)
    }

    @ParameterizedTest
    @MethodSource("sorts")
    fun `ids follow the page order, also when read through the source index`(sortType: Int, ascending: Boolean) {
        insert(
            (1L..400L).map {
                Row(
                    it,
                    "Title ${it % 37}",
                    source = it % 4,
                    lastRead = it % 11,
                    total = it % 6,
                    read = it % 3,
                    categories = if (it % 2 == 0L) listOf(6L) else emptyList(),
                )
            },
        )
        val spec = spec(sortType, ascending).copy(search = LibraryPageSpec.Search(sourceIds = listOf(1L, 3L)))

        for (category in listOf(0L, 6L)) {
            val pages = ids(LibraryPageSql.page(category, false, spec, limit = null, offset = 0))
            val bySource = LibraryPageSql.ids(category, false, spec, limit = -1, bySource = true)

            ids(LibraryPageSql.ids(category, false, spec, limit = -1)) shouldContainExactly pages
            ids(bySource) shouldContainExactly pages
            ids(LibraryPageSql.ids(category, false, spec, limit = 7)) shouldContainExactly pages.take(7)
            plan(bySource) shouldContain "idx_mangas_source"
            single(LibraryPageSql.count(category, false, spec, bySource = true)) shouldBe pages.size.toLong()
        }
    }

    @Test
    fun `rows reads the given ids`() {
        insert((1L..5L).map { Row(it, "Title $it") })

        ids(LibraryPageSql.rows(listOf(4L, 2L))) shouldContainExactlyInAnyOrder listOf(2L, 4L)
    }

    @Test
    fun `source rows count every entry of the sources up to the limit`() {
        insert((1L..10L).map { Row(it, "Title $it", source = it % 3, favorite = it % 2 == 0L) })

        single(LibraryPageSql.sourceRows(listOf(1L, 2L), limit = 100)) shouldBe 7L
        single(LibraryPageSql.sourceRows(listOf(1L, 2L), limit = 5)) shouldBe 5L
    }

    @Test
    fun `only a search matching nothing but sources is pinned to them`() {
        val base = LibraryPageSpec()

        LibraryPageSql.pinnedSources(base) shouldBe null
        LibraryPageSql.pinnedSources(base.copy(search = LibraryPageSpec.Search(sourceIds = listOf(1L)))) shouldBe
            listOf(1L)
        LibraryPageSql.pinnedSources(
            base.copy(search = LibraryPageSpec.Search(likeTerm = "x", sourceIds = listOf(1L))),
        ) shouldBe listOf(1L)
        LibraryPageSql.pinnedSources(
            base.copy(search = LibraryPageSpec.Search(likeTerm = "x", matchTitle = true, sourceIds = listOf(1L))),
        ) shouldBe null
        LibraryPageSql.pinnedSources(
            base.copy(search = LibraryPageSpec.Search(sourceIds = listOf(1L), mangaIds = listOf(5L))),
        ) shouldBe null
        LibraryPageSql.pinnedSources(base.copy(search = LibraryPageSpec.Search())) shouldBe null
    }

    companion object {
        @JvmStatic
        fun sorts(): List<Array<Any>> = (0..7).flatMap { sort ->
            listOf(arrayOf<Any>(sort, true), arrayOf<Any>(sort, false))
        }
    }
}
