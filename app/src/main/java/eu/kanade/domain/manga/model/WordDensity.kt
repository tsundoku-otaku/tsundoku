package eu.kanade.domain.manga.model

import eu.kanade.tachiyomi.util.chapter.HtmlTextScanner

/**
 * Word statistics for the downloaded chapters of a novel.
 *
 * @property totalWords words across every counted chapter.
 * @property countedChapters chapters whose content could be read and counted.
 * @property totalChapters every chapter of the entry, downloaded or not.
 * @property unreadableChapters chapters that were available but whose content could not be read.
 */
data class WordDensity(
    val totalWords: Long,
    val countedChapters: Int,
    val totalChapters: Int,
    val unreadableChapters: Int = 0,
) {
    /** Chapters left out because they are not downloaded. */
    val notDownloadedChapters: Int
        get() = (totalChapters - countedChapters - unreadableChapters).coerceAtLeast(0)

    /** Average words per counted chapter, the novel's word density. */
    val averageWords: Long
        get() = if (countedChapters == 0) 0 else totalWords / countedChapters

    val tier: Int
        get() = densityTier(averageWords)

    companion object {
        /** Each tier spans this many average words per chapter. */
        const val TIER_STEP_WORDS = 500L

        /**
         * Maps an average chapter length onto a tier: 1-500 words is 1, 501-1000 is 2,
         * 1001-1500 is 3, and so on without an upper bound.
         */
        fun densityTier(averageWords: Long): Int {
            if (averageWords <= 0) return 1
            return ((averageWords - 1) / TIER_STEP_WORDS + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /** Lowest average word count that lands in [tier]. */
        fun tierMinWords(tier: Int): Long = if (tier <= 1) 1 else (tier - 1) * TIER_STEP_WORDS + 1

        /** Highest average word count that lands in [tier]. */
        fun tierMaxWords(tier: Int): Long = tier.coerceAtLeast(1) * TIER_STEP_WORDS

        /** Counts whitespace-separated words in chapter HTML or plain text. Markup, scripts and styles are skipped. */
        fun countWords(content: String): Int {
            var words = 0
            var inWord = false
            HtmlTextScanner.scan(
                content,
                onChar = { char ->
                    if (char.isWhitespace() || Character.isSpaceChar(char) || char == ZERO_WIDTH_SPACE) {
                        inWord = false
                    } else {
                        if (!inWord) words++
                        inWord = true
                    }
                },
                onBreak = { inWord = false },
            )
            return words
        }

        private const val ZERO_WIDTH_SPACE = '\u200B'
    }
}
