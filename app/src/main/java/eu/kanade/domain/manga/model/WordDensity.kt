package eu.kanade.domain.manga.model

import org.jsoup.Jsoup

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
        /** Each tier spans this many words of average chapter length. */
        const val TIER_STEP_WORDS = 500L

        /**
         * Maps an average chapter length onto the word density score in steps of
         * [TIER_STEP_WORDS] words: 1-500 words is 1, 501-1000 is 2, 1001-1500 is 3, and so on.
         * The score is open-ended so long chapters still tell apart; an empty average is 1.
         */
        fun densityTier(averageWords: Long): Int {
            if (averageWords <= 0) return 1
            return ((averageWords - 1) / TIER_STEP_WORDS + 1).toInt()
        }

        /** Lowest average word count that lands in [tier]. */
        fun tierMinWords(tier: Int): Long = (tier.coerceAtLeast(1) - 1) * TIER_STEP_WORDS + 1

        /** Highest average word count that lands in [tier]. */
        fun tierMaxWords(tier: Int): Long = tier.coerceAtLeast(1) * TIER_STEP_WORDS

        /**
         * Counts whitespace-separated words in chapter HTML or plain text. Only the body is
         * counted: markup, scripts and styles are dropped and entities such as `&nbsp;` are decoded.
         */
        fun countWords(content: String): Int {
            if (content.isBlank()) return 0
            val text = Jsoup.parse(content).body().text()
            var words = 0
            var inWord = false
            for (char in text) {
                // isSpaceChar also covers no-break spaces, which Jsoup keeps when decoding &nbsp;
                val isSeparator = char.isWhitespace() || Character.isSpaceChar(char)
                if (!isSeparator && !inWord) words++
                inWord = !isSeparator
            }
            return words
        }
    }
}
