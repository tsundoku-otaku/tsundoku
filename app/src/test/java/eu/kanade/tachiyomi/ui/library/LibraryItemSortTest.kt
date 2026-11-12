package eu.kanade.tachiyomi.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga
import kotlin.random.Random

class LibraryItemSortTest {

    private val sorts: List<LibrarySort> = LibrarySort.types
        .filterNot { it == LibrarySort.Type.Random }
        .flatMap { type ->
            listOf(LibrarySort.Direction.Ascending, LibrarySort.Direction.Descending)
                .map { direction -> LibrarySort(type, direction) }
        }

    private val trackerScores = mapOf(1L to 6.0, 2L to 9.5, 3L to null, 4L to 2.0, 5L to 7.0, 7L to 4.5)
    private val sourceNames = mapOf(10L to "Alpha", 20L to "Bravo", 30L to "Charlie")

    // Every sort key differs between entries, so a given sort has exactly one correct order.
    private val entries = listOf(
        entry(id = 1, title = "Mushishi", pinned = false, unread = 4, lastRead = 500, source = 20),
        entry(id = 2, title = "Berserk", pinned = true, unread = 0, lastRead = 100, source = 10),
        entry(id = 3, title = "Vagabond", pinned = false, unread = 9, lastRead = 900, source = 30),
        entry(id = 4, title = "Akira", pinned = false, unread = 1, lastRead = 300, source = 10),
        entry(id = 5, title = "Pluto", pinned = true, unread = 12, lastRead = 800, source = 30),
        entry(id = 6, title = "Planetes", pinned = false, unread = 0, lastRead = 200, source = 20),
        entry(id = 7, title = "Monster", pinned = true, unread = 3, lastRead = 700, source = 10),
        entry(id = 8, title = "Dorohedoro", pinned = false, unread = 7, lastRead = 600, source = 20),
    )

    private fun entry(
        id: Long,
        title: String,
        pinned: Boolean,
        unread: Long,
        lastRead: Long,
        source: Long,
    ): LibraryItem {
        val manga = Manga.create().copy(
            id = id,
            title = title,
            source = source,
            isPinned = pinned,
            lastUpdate = (id * 37) % 11,
            dateAdded = (id * 53) % 13,
        )
        return LibraryItem(
            libraryManga = LibraryManga(
                manga = manga,
                categories = listOf(0L),
                totalChapters = 20 + (id * 7) % 5,
                readCount = 20 + (id * 7) % 5 - unread,
                bookmarkCount = 0,
                latestUpload = (id * 29) % 17,
                chapterFetchedAt = (id * 31) % 19,
                lastRead = lastRead,
            ),
            downloadCount = (id * 5) % 9,
        )
    }

    private fun List<LibraryItem>.sortedFor(sort: LibrarySort) = sortedForLibrary(
        sort = sort,
        trackerScores = trackerScores,
        sourceName = { sourceNames.getValue(it) },
    )

    private fun List<LibraryItem>.ids() = map { it.id }

    private fun List<LibraryItem>.unpinned() = filterNot { it.libraryManga.manga.isPinned }

    private fun List<LibraryItem>.pinned() = filter { it.libraryManga.manga.isPinned }

    @Test
    fun `pinned entries come first for every sort type and direction`() {
        sorts.forEach { sort ->
            val sorted = entries.sortedFor(sort)
            val firstUnpinned = sorted.indexOfFirst { !it.libraryManga.manga.isPinned }
            assertEquals(entries.pinned().size, firstUnpinned, "pinned entries must lead for $sort")
            assertEquals(
                emptyList<LibraryItem>(),
                sorted.drop(firstUnpinned).pinned(),
                "no pinned entry after the first unpinned one for $sort",
            )
        }
    }

    @Test
    fun `each group keeps the order the active sort gives it on its own`() {
        sorts.forEach { sort ->
            val sorted = entries.sortedFor(sort)
            assertEquals(entries.pinned().sortedFor(sort).ids(), sorted.pinned().ids(), "pinned group for $sort")
            assertEquals(entries.unpinned().sortedFor(sort).ids(), sorted.unpinned().ids(), "unpinned group for $sort")
        }
    }

    @Test
    fun `unpinned group is ordered exactly as the library was before pinning existed`() {
        sorts.forEach { sort ->
            val unpinnedLibrary = entries.map { item ->
                item.copy(libraryManga = item.libraryManga.copy(manga = item.libraryManga.manga.copy(isPinned = false)))
            }
            val before = unpinnedLibrary.sortedFor(sort).ids().filter { id -> id in entries.unpinned().ids() }
            assertEquals(before, entries.sortedFor(sort).unpinned().ids(), "unpinned group for $sort")
        }
    }

    @Test
    fun `descending sort still lists pinned entries first`() {
        val ascending = entries.sortedFor(LibrarySort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Ascending))
        val descending = entries.sortedFor(LibrarySort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Descending))
        assertEquals(listOf(2L, 7L, 5L, 4L, 8L, 1L, 6L, 3L), ascending.ids())
        assertEquals(listOf(5L, 7L, 2L, 3L, 6L, 1L, 8L, 4L), descending.ids())
    }

    @Test
    fun `random sort lists pinned entries first and keeps the shuffled order inside each group`() {
        val seed = 1234
        val sorted = entries.sortedForLibrary(
            sort = LibrarySort(LibrarySort.Type.Random, LibrarySort.Direction.Ascending),
            randomSeed = seed,
        )
        val shuffled = entries.shuffled(Random(seed))
        assertEquals(shuffled.pinned().ids() + shuffled.unpinned().ids(), sorted.ids())
    }

    @Test
    fun `nothing pinned leaves the active sort untouched`() {
        val none = entries.map { item ->
            item.copy(libraryManga = item.libraryManga.copy(manga = item.libraryManga.manga.copy(isPinned = false)))
        }
        val sort = LibrarySort(LibrarySort.Type.LastRead, LibrarySort.Direction.Descending)
        assertEquals(listOf(3L, 5L, 7L, 8L, 1L, 4L, 6L, 2L), none.sortedFor(sort).ids())
    }

    @Test
    fun `everything pinned behaves like an unpinned library`() {
        val all = entries.map { item ->
            item.copy(libraryManga = item.libraryManga.copy(manga = item.libraryManga.manga.copy(isPinned = true)))
        }
        val sort = LibrarySort(LibrarySort.Type.LastRead, LibrarySort.Direction.Ascending)
        assertEquals(listOf(2L, 6L, 4L, 1L, 8L, 7L, 5L, 3L), all.sortedFor(sort).ids())
    }
}
