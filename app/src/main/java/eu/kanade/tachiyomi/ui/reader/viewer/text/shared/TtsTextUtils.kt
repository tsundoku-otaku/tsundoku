package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import org.jsoup.Jsoup

/**
 * Pure text utilities for TTS playback: chunking, paragraph indexing, and
 * step computation. No Android dependencies; safe to unit-test.
 */
object TtsTextUtils {

    /**
     * Position metadata for a paragraph inside a chapter's plain-text projection.
     */
    data class ParagraphInfo(
        val index: Int,
        val startChar: Int,
        val endChar: Int,
        val text: String,
    )

    /**
     * Splits [text] into chunks suitable for TTS playback,
     * breaking at sentence boundaries or spaces when possible.
     */
    fun splitTextForTts(text: String, maxLength: Int): List<String> {
        val chunks = mutableListOf<String>()
        val hasSpaces = ' ' in text
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLength) {
                chunks.add(remaining)
                break
            }

            var breakPoint = maxLength

            val slice = remaining.substring(0, maxLength)
            val sentenceEnd = slice.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
            if (sentenceEnd > maxLength / 2) {
                breakPoint = sentenceEnd + 1
            } else {
                val lastSpace = slice.lastIndexOf(' ')
                if (lastSpace > maxLength / 2) {
                    breakPoint = lastSpace + 1
                } else if (hasSpaces) {
                    val nextSpace = remaining.indexOf(' ')
                    if (nextSpace > 0) {
                        breakPoint = nextSpace + 1
                    } else {
                        // No more spaces — remaining is one unsplittable word; add as-is.
                        chunks.add(remaining.trim())
                        break
                    }
                }
            }

            chunks.add(remaining.substring(0, breakPoint).trim())
            remaining = remaining.substring(breakPoint).trim()
        }

        return chunks
    }

    /**
     * Finds all paragraph boundaries in [text] by parsing block elements or splitting
     * on empty lines as a fallback.
     */
    fun findParagraphs(text: String): List<ParagraphInfo> {
        val paragraphs = mutableListOf<ParagraphInfo>()

        val doc = try { Jsoup.parseBodyFragment(text) } catch (_: Exception) { null }
        val plainText = doc?.text() ?: text.replace(Regex("<[^>]+>"), " ")
        val blocks = doc?.select("p, div, section, article, h1, h2, h3, h4, h5, h6, li")

        if (blocks == null || blocks.isEmpty()) {
            val lines = text.split(Regex("\\n\\s*\\n"))
            var charOffset = 0
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    charOffset += line.length + 2
                    continue
                }
                val startChar = plainText.indexOf(trimmed, charOffset)
                if (startChar < 0) continue
                val endChar = startChar + trimmed.length
                paragraphs.add(ParagraphInfo(paragraphs.size, startChar, endChar, trimmed))
                charOffset = endChar
            }
            return paragraphs
        }

        var charOffset = 0
        for (elem in blocks) {
            val trimmed = elem.text().trim()
            if (trimmed.isEmpty()) continue
            val startChar = plainText.indexOf(trimmed, charOffset)
            if (startChar < 0) continue
            val endChar = startChar + trimmed.length
            paragraphs.add(ParagraphInfo(paragraphs.size, startChar, endChar, trimmed))
            charOffset = endChar
        }

        return paragraphs
    }

    /**
     * Finds the paragraph index for a given character offset in text.
     * Used to resume TTS from a saved position.
     */
    fun findParagraphAtOffset(text: String, charOffset: Int, paragraphs: List<ParagraphInfo>): Int {
        return paragraphs.indexOfFirst { it.startChar <= charOffset && charOffset < it.endChar }
            .takeIf { it >= 0 } ?: 0
    }

    /**
     * Converts a character offset into a chunk index based on cumulative chunk lengths.
     */
    fun getChunkIndexFromOffset(charOffset: Int, ttsChunks: List<String>): Int {
        var currentOffset = 0
        for ((index, chunk) in ttsChunks.withIndex()) {
            if (currentOffset + chunk.length > charOffset) return index
            currentOffset += chunk.length
        }
        return (ttsChunks.size - 1).coerceAtLeast(0)
    }

    /**
     * Returns the chunk index to seek to after a +/- paragraph step.
     */
    fun computeTtsStepTargetChunk(
        delta: Int,
        ttsPaused: Boolean,
        ttsResumeChunkIndex: Int,
        ttsCurrentChunkIndex: Int,
        ttsChunks: List<String>,
        ttsChunkParagraphIndexes: List<Int>,
    ): Int {
        val currentChunk = (if (ttsPaused) ttsResumeChunkIndex else ttsCurrentChunkIndex)
            .coerceIn(0, (ttsChunks.size - 1).coerceAtLeast(0))
        val currentParagraph = ttsChunkParagraphIndexes.getOrElse(currentChunk) { currentChunk }
        val maxParagraph = (ttsChunkParagraphIndexes.maxOrNull() ?: currentParagraph).coerceAtLeast(0)
        val targetParagraph = (currentParagraph + delta).coerceIn(0, maxParagraph)
        return ttsChunkParagraphIndexes.indexOfFirst { it >= targetParagraph }
            .takeIf { it >= 0 }
            ?: currentChunk
    }
}
