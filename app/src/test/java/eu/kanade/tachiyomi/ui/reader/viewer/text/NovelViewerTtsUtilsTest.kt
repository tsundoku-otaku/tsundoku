package eu.kanade.tachiyomi.ui.reader.viewer.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelViewerTtsUtilsTest {

    // ── splitTextForTts ──────────────────────────────────────────────────────

    @Test
    fun `splitTextForTts returns single chunk when text fits within maxLength`() {
        val text = "Short sentence."
        val chunks = NovelViewerTextUtils.splitTextForTts(text, 100)
        assertEquals(listOf("Short sentence."), chunks)
    }

    @Test
    fun `splitTextForTts splits at sentence boundary when available`() {
        val sentence1 = "This is the first sentence."
        val sentence2 = "This is the second sentence which makes the combined text too long for the limit."
        val text = "$sentence1 $sentence2"
        val chunks = NovelViewerTextUtils.splitTextForTts(text, sentence1.length + 5)
        // First chunk should end at the sentence boundary (after the period).
        assertTrue(chunks.first().endsWith("."), "Expected first chunk to end at sentence boundary")
        assertTrue(chunks.size >= 2)
        // Reconstruct: trimmed chunks should cover all meaningful content.
        val rejoined = chunks.joinToString(" ")
        assertTrue(rejoined.contains("first sentence"))
        assertTrue(rejoined.contains("second sentence"))
    }

    @Test
    fun `splitTextForTts splits at word boundary when no sentence end found in first half`() {
        // 30 chars, no sentence ender in first 15 chars, but has a space
        val text = "abcdefghij klmnopqrstuvwxyzabcde"
        val chunks = NovelViewerTextUtils.splitTextForTts(text, 15)
        // The break should land at the space (index 10), not mid-word.
        assertTrue(chunks.first().trim().isNotEmpty())
        chunks.forEach { chunk -> assertTrue(chunk.isNotEmpty()) }
        assertEquals(text.replace("\\s+".toRegex(), " ").trim(), chunks.joinToString(" ").trim())
    }

    @Test
    fun `splitTextForTts handles text exactly at maxLength`() {
        val text = "x".repeat(100)
        val chunks = NovelViewerTextUtils.splitTextForTts(text, 100)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    @Test
    fun `splitTextForTts produces chunks all within maxLength`() {
        val text = "Word ".repeat(200).trim() // 1000 chars
        val maxLen = 50
        val chunks = NovelViewerTextUtils.splitTextForTts(text, maxLen)
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= maxLen, "Chunk exceeded maxLength: '${chunk.take(60)}…'")
        }
    }

    @Test
    fun `splitTextForTts empty string returns empty list`() {
        val chunks = NovelViewerTextUtils.splitTextForTts("", 100)
        assertTrue(chunks.isEmpty() || chunks == listOf(""))
    }

    // ── computeTtsStepTargetChunk ────────────────────────────────────────────

    private fun step(
        delta: Int,
        currentChunk: Int,
        chunks: List<String>,
        paragraphIndexes: List<Int>,
        paused: Boolean = false,
        resumeChunk: Int = 0,
    ) = NovelViewerTextUtils.computeTtsStepTargetChunk(
        delta = delta,
        ttsPaused = paused,
        ttsResumeChunkIndex = resumeChunk,
        ttsCurrentChunkIndex = currentChunk,
        ttsChunks = chunks,
        ttsChunkParagraphIndexes = paragraphIndexes,
    )

    // 3 paragraphs, 1 chunk each
    private val threeParaChunks = listOf("p0", "p1", "p2")
    private val threeParaIndexes = listOf(0, 1, 2)

    @Test
    fun `step +1 from first paragraph moves to second`() {
        assertEquals(1, step(1, 0, threeParaChunks, threeParaIndexes))
    }

    @Test
    fun `step +1 from last paragraph stays at last`() {
        assertEquals(2, step(1, 2, threeParaChunks, threeParaIndexes))
    }

    @Test
    fun `step -1 from second paragraph moves to first`() {
        assertEquals(0, step(-1, 1, threeParaChunks, threeParaIndexes))
    }

    @Test
    fun `step -1 from first paragraph stays at first`() {
        assertEquals(0, step(-1, 0, threeParaChunks, threeParaIndexes))
    }

    @Test
    fun `step +2 skips two paragraphs`() {
        assertEquals(2, step(2, 0, threeParaChunks, threeParaIndexes))
    }

    @Test
    fun `step uses resumeChunk when paused`() {
        // paused at chunk 2 (paragraph 2), step back should go to paragraph 1
        val result = step(
            delta = -1,
            currentChunk = 0,
            chunks = threeParaChunks,
            paragraphIndexes = threeParaIndexes,
            paused = true,
            resumeChunk = 2,
        )
        assertEquals(1, result)
    }

    @Test
    fun `step uses ttsCurrentChunkIndex when not paused`() {
        // currentChunk=1 (paragraph 1), step forward should go to paragraph 2
        val result = step(
            delta = 1,
            currentChunk = 1,
            chunks = threeParaChunks,
            paragraphIndexes = threeParaIndexes,
            paused = false,
            resumeChunk = 0,
        )
        assertEquals(2, result)
    }

    @Test
    fun `step with multiple chunks per paragraph lands on first chunk of target paragraph`() {
        // paragraph 0 → chunks 0,1,2; paragraph 1 → chunks 3,4
        val chunks = listOf("p0c0", "p0c1", "p0c2", "p1c0", "p1c1")
        val indexes = listOf(0, 0, 0, 1, 1)
        // From chunk 0 (para 0), step +1 → first chunk of para 1 = chunk 3
        assertEquals(3, step(1, 0, chunks, indexes))
    }

    @Test
    fun `step back from middle of multi-chunk paragraph goes to para 0 start`() {
        val chunks = listOf("p0c0", "p0c1", "p1c0")
        val indexes = listOf(0, 0, 1)
        // At chunk 1 (still para 0), step -1 should clamp at para 0 → chunk 0
        assertEquals(0, step(-1, 1, chunks, indexes))
    }

    @Test
    fun `step with empty chunks returns 0`() {
        assertEquals(0, step(1, 0, emptyList(), emptyList()))
    }

    @Test
    fun `step 0 delta stays in same paragraph`() {
        val result = step(0, 1, threeParaChunks, threeParaIndexes)
        val resultParagraph = threeParaIndexes[result]
        assertEquals(threeParaIndexes[1], resultParagraph)
    }
}
