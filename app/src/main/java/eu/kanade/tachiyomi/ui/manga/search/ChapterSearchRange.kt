package eu.kanade.tachiyomi.ui.manga.search

import androidx.compose.runtime.Immutable
import tachiyomi.domain.chapter.model.Chapter

/**
 * Limits a chapter search to chapters numbered [from] to [to], both inclusive. A null bound
 * leaves that end open. Chapters without a recognized number fall outside every range.
 */
@Immutable
data class ChapterSearchRange(
    val from: Double?,
    val to: Double?,
) {
    operator fun contains(chapter: Chapter): Boolean {
        if (!chapter.isRecognizedNumber) return false
        val low = from ?: Double.NEGATIVE_INFINITY
        val high = to ?: Double.POSITIVE_INFINITY
        return chapter.chapterNumber in minOf(low, high)..maxOf(low, high)
    }

    companion object {
        /** Returns null, meaning every chapter, when neither end is set. */
        fun of(from: Double?, to: Double?): ChapterSearchRange? =
            if (from == null && to == null) null else ChapterSearchRange(from, to)

        /** Reads a chapter number typed by the user; accepts a decimal comma. */
        fun parseBound(text: String): Double? =
            text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}

fun List<Chapter>.inSearchRange(range: ChapterSearchRange?): List<Chapter> =
    if (range == null) this else filter { it in range }
