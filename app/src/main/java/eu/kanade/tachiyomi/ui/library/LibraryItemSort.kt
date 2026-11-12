package eu.kanade.tachiyomi.ui.library

import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.library.model.LibrarySort
import kotlin.random.Random

/**
 * Orders the entries of one library category.
 *
 * Pinned entries always come first, whatever the sort type or direction. Inside the pinned group and
 * inside the unpinned group the category's sort still applies, so pinning never changes the saved sort.
 *
 * @param trackerScores mean tracker score per manga id, only read for [LibrarySort.Type.TrackerMean].
 * @param sourceName display name for a source id, only read for [LibrarySort.Type.SourceName].
 * @param randomSeed seed for [LibrarySort.Type.Random], so the order is stable until the seed changes.
 */
internal fun List<LibraryItem>.sortedForLibrary(
    sort: LibrarySort,
    trackerScores: Map<Long, Double?> = emptyMap(),
    sourceName: (sourceId: Long) -> String = { it.toString() },
    randomSeed: Int = 0,
): List<LibraryItem> {
    if (sort.type == LibrarySort.Type.Random) {
        return shuffled(Random(randomSeed)).pinnedFirst()
    }
    val comparator = libraryItemComparator(sort, trackerScores, sourceName)
        .let { if (sort.isAscending) it else it.reversed() }
        .thenComparator(::compareByTitle)
    // The direction flip above must not reach the pin check, so pinned-first wraps the whole order.
    return sortedWith(compareByDescending<LibraryItem> { it.isPinned }.then(comparator))
}

/** Stable partition: pinned entries first, each group keeping its current relative order. */
internal fun List<LibraryItem>.pinnedFirst(): List<LibraryItem> {
    val (pinned, unpinned) = partition { it.isPinned }
    return pinned + unpinned
}

private val LibraryItem.isPinned: Boolean
    get() = libraryManga.manga.isPinned

private fun compareByTitle(a: LibraryItem, b: LibraryItem): Int =
    a.libraryManga.manga.title.compareToWithCollator(b.libraryManga.manga.title)

private fun libraryItemComparator(
    sort: LibrarySort,
    trackerScores: Map<Long, Double?>,
    sourceName: (Long) -> String,
): Comparator<LibraryItem> {
    val defaultTrackerScoreSortValue = -1.0
    val sourceNameCache = HashMap<Long, String>()
    return Comparator { manga1, manga2 ->
        when (sort.type) {
            LibrarySort.Type.Alphabetical -> {
                compareByTitle(manga1, manga2)
            }
            LibrarySort.Type.LastRead -> {
                manga1.libraryManga.lastRead.compareTo(manga2.libraryManga.lastRead)
            }
            LibrarySort.Type.LastUpdate -> {
                manga1.libraryManga.manga.lastUpdate.compareTo(manga2.libraryManga.manga.lastUpdate)
            }
            LibrarySort.Type.UnreadCount -> when {
                // Ensure unread content comes first
                manga1.libraryManga.unreadCount == manga2.libraryManga.unreadCount -> 0
                manga1.libraryManga.unreadCount == 0L -> if (sort.isAscending) 1 else -1
                manga2.libraryManga.unreadCount == 0L -> if (sort.isAscending) -1 else 1
                else -> manga1.libraryManga.unreadCount.compareTo(manga2.libraryManga.unreadCount)
            }
            LibrarySort.Type.TotalChapters -> {
                manga1.libraryManga.totalChapters.compareTo(manga2.libraryManga.totalChapters)
            }
            LibrarySort.Type.DownloadedChapters -> {
                manga1.downloadCount.compareTo(manga2.downloadCount)
            }
            LibrarySort.Type.LatestChapter -> {
                manga1.libraryManga.latestUpload.compareTo(manga2.libraryManga.latestUpload)
            }
            LibrarySort.Type.ChapterFetchDate -> {
                manga1.libraryManga.chapterFetchedAt.compareTo(manga2.libraryManga.chapterFetchedAt)
            }
            LibrarySort.Type.DateAdded -> {
                manga1.libraryManga.manga.dateAdded.compareTo(manga2.libraryManga.manga.dateAdded)
            }
            LibrarySort.Type.TrackerMean -> {
                val item1Score = trackerScores[manga1.id] ?: defaultTrackerScoreSortValue
                val item2Score = trackerScores[manga2.id] ?: defaultTrackerScoreSortValue
                item1Score.compareTo(item2Score)
            }
            LibrarySort.Type.SourceName -> {
                val sourceName1 = sourceNameCache.getOrPut(manga1.libraryManga.manga.source) {
                    sourceName(manga1.libraryManga.manga.source)
                }
                val sourceName2 = sourceNameCache.getOrPut(manga2.libraryManga.manga.source) {
                    sourceName(manga2.libraryManga.manga.source)
                }
                sourceName1.compareToWithCollator(sourceName2)
            }
            LibrarySort.Type.Random -> {
                error("Random is shuffled, not compared")
            }
        }
    }
}
