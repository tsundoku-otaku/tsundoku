package eu.kanade.tachiyomi.ui.reader.quote

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Data class representing a saved quote from a novel chapter
 */
@Serializable
data class Quote(
    val id: String = UUID.randomUUID().toString(),
    val chapterName: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** 0-based index of the paragraph the selection starts in, or null if unknown. */
    val paragraphIndex: Int? = null,
) {
    companion object {
        /**
         * Create a new quote with the given parameters
         */
        fun create(
            chapterName: String,
            content: String,
            paragraphIndex: Int? = null,
        ): Quote {
            return Quote(
                chapterName = chapterName,
                content = content.trim(),
                paragraphIndex = paragraphIndex,
            )
        }
    }
}

/**
 * Data class representing a collection of quotes for a specific novel
 */
@Serializable
data class NovelQuotes(
    val quotes: List<Quote> = emptyList(),
)
