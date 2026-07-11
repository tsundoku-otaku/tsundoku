package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import kotlin.math.roundToInt

/**
 * Pure progress-to-percent and chapter-marking helpers shared by the novel viewers.
 * Kept renderer-agnostic so the WebView and TextView paths persist progress identically.
 */
object NovelProgress {

    /**
     * Scroll ratio at/above which a chapter counts as fully read. Shared by every renderer and by
     * the scroll-tracking JS so a chapter marks read at the same point regardless of viewer.
     */
    const val DONE_THRESHOLD = 0.99f

    /** Snap a near-complete ratio to a clean 1f so the stored percent lands on 100. */
    fun snapProgress(progress: Float): Float = if (progress >= DONE_THRESHOLD) 1f else progress

    /**
     * Convert a 0f..1f scroll ratio to a stored percent. Rounds (not truncates) so a position
     * matches between renderers; the TextView path already rounds.
     */
    fun progressToPercent(progress: Float): Int = (progress * 100f).roundToInt().coerceIn(0, 100)

    /** True when [newProgress] rounds to a different percent than [lastProgress] (dedup guard). */
    fun percentChanged(newProgress: Float, lastProgress: Float): Boolean =
        progressToPercent(newProgress) != progressToPercent(lastProgress)

    /**
     * Chapters to mark 100% read when the visible chapter moves forward from [oldIndex] to
     * [newIndex]. Only the outgoing chapter: a multi-step jump comes from the slider and skips
     * chapters that were never shown, while a real fling fires sequential +1 steps that each mark
     * their own outgoing chapter. Empty when not moving forward.
     */
    fun forwardChaptersToMarkRead(oldIndex: Int, newIndex: Int, size: Int): List<Int> {
        if (newIndex <= oldIndex) return emptyList()
        if (oldIndex < 0 || oldIndex >= size) return emptyList()
        return listOf(oldIndex)
    }
}
