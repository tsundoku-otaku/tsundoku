package eu.kanade.tachiyomi.ui.reader.quote

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Data class representing a saved quote from a novel chapter
 */
@Serializable
data class Quote(
    val id: String = UUID.randomUUID().toString(),
    val novelName: String,
    val chapterName: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        /**
         * Create a new quote with the given parameters
         */
        fun create(novelName: String, chapterName: String, content: String): Quote {
            return Quote(
                novelName = novelName,
                chapterName = chapterName,
                content = content.trim(),
            )
        }
    }
}

/**
 * Data class representing a collection of quotes for a specific novel
 */
@Serializable
data class NovelQuotes(
    val novelId: Long,
    val quotes: List<Quote> = emptyList(),
)
