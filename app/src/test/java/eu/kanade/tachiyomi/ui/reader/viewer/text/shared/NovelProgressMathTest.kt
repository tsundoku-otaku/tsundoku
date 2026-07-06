package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelProgressMathTest {

    @Test
    fun `progressToPercent rounds instead of truncating`() {
        assertEquals(98, NovelProgressMath.progressToPercent(0.976f))
        assertEquals(100, NovelProgressMath.progressToPercent(0.999f))
        assertEquals(50, NovelProgressMath.progressToPercent(0.5f))
        assertEquals(0, NovelProgressMath.progressToPercent(0f))
        assertEquals(100, NovelProgressMath.progressToPercent(1f))
    }

    @Test
    fun `progressToPercent clamps out-of-range input`() {
        assertEquals(0, NovelProgressMath.progressToPercent(-0.3f))
        assertEquals(100, NovelProgressMath.progressToPercent(1.4f))
    }

    @Test
    fun `snapProgress snaps at or above the done threshold`() {
        assertEquals(1f, NovelProgressMath.snapProgress(0.99f))
        assertEquals(1f, NovelProgressMath.snapProgress(0.995f))
        assertEquals(1f, NovelProgressMath.snapProgress(1f))
        assertEquals(0.98f, NovelProgressMath.snapProgress(0.98f))
        assertEquals(0.5f, NovelProgressMath.snapProgress(0.5f))
    }

    @Test
    fun `snapped near-complete progress stores 100 percent`() {
        assertEquals(100, NovelProgressMath.progressToPercent(NovelProgressMath.snapProgress(0.991f)))
    }

    @Test
    fun `percentChanged only fires when rounded percent differs`() {
        assertFalse(NovelProgressMath.percentChanged(0.501f, 0.503f))
        assertTrue(NovelProgressMath.percentChanged(0.501f, 0.512f))
    }

    @Test
    fun `forwardChaptersToMarkRead returns outgoing plus skipped when moving forward`() {
        assertEquals(listOf(0), NovelProgressMath.forwardChaptersToMarkRead(0, 1, 5))
        assertEquals(listOf(1, 2, 3), NovelProgressMath.forwardChaptersToMarkRead(1, 4, 5))
    }

    @Test
    fun `forwardChaptersToMarkRead is empty when not moving forward`() {
        assertEquals(emptyList<Int>(), NovelProgressMath.forwardChaptersToMarkRead(3, 3, 5))
        assertEquals(emptyList<Int>(), NovelProgressMath.forwardChaptersToMarkRead(4, 2, 5))
    }

    @Test
    fun `forwardChaptersToMarkRead clamps to loaded size and floor`() {
        assertEquals(listOf(2, 3), NovelProgressMath.forwardChaptersToMarkRead(2, 9, 4))
        assertEquals(listOf(0, 1), NovelProgressMath.forwardChaptersToMarkRead(-1, 2, 5))
    }
}
