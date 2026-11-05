package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

object TtsTextUtils {

    fun newlineParagraphBounds(text: String): List<Pair<Int, Int>> {
        val bounds = mutableListOf<Pair<Int, Int>>()
        var offset = 0
        for (line in text.split("\n")) {
            if (line.isNotBlank()) bounds.add(offset to offset + line.length)
            offset += line.length + 1
        }
        return bounds
    }

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

    fun getChunkIndexFromOffset(charOffset: Int, ttsChunks: List<String>): Int {
        var currentOffset = 0
        for ((index, chunk) in ttsChunks.withIndex()) {
            if (currentOffset + chunk.length > charOffset) return index
            currentOffset += chunk.length
        }
        return (ttsChunks.size - 1).coerceAtLeast(0)
    }

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
