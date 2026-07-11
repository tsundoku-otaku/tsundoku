package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelProgressTest {

    @Test
    fun `progressToPercent rounds instead of truncating`() {
        assertEquals(98, NovelProgress.progressToPercent(0.976f))
        assertEquals(100, NovelProgress.progressToPercent(0.999f))
        assertEquals(50, NovelProgress.progressToPercent(0.5f))
        assertEquals(0, NovelProgress.progressToPercent(0f))
        assertEquals(100, NovelProgress.progressToPercent(1f))
    }

    @Test
    fun `progressToPercent clamps out-of-range input`() {
        assertEquals(0, NovelProgress.progressToPercent(-0.3f))
        assertEquals(100, NovelProgress.progressToPercent(1.4f))
    }

    @Test
    fun `snapProgress snaps at or above the done threshold`() {
        assertEquals(1f, NovelProgress.snapProgress(0.99f))
        assertEquals(1f, NovelProgress.snapProgress(0.995f))
        assertEquals(1f, NovelProgress.snapProgress(1f))
        assertEquals(0.98f, NovelProgress.snapProgress(0.98f))
        assertEquals(0.5f, NovelProgress.snapProgress(0.5f))
    }

    @Test
    fun `snapped near-complete progress stores 100 percent`() {
        assertEquals(100, NovelProgress.progressToPercent(NovelProgress.snapProgress(0.991f)))
    }

    @Test
    fun `percentChanged only fires when rounded percent differs`() {
        assertFalse(NovelProgress.percentChanged(0.501f, 0.503f))
        assertTrue(NovelProgress.percentChanged(0.501f, 0.512f))
    }

    @Test
    fun `forwardChaptersToMarkRead marks the outgoing chapter on a single-step move`() {
        assertEquals(listOf(0), NovelProgress.forwardChaptersToMarkRead(0, 1, 5))
        assertEquals(listOf(3), NovelProgress.forwardChaptersToMarkRead(3, 4, 5))
    }

    @Test
    fun `forwardChaptersToMarkRead marks every chapter flung past on a multi-step move`() {
        assertEquals(listOf(1, 2, 3), NovelProgress.forwardChaptersToMarkRead(1, 4, 5))
        assertEquals(listOf(0, 1, 2, 3), NovelProgress.forwardChaptersToMarkRead(0, 4, 5))
    }

    @Test
    fun `forwardChaptersToMarkRead clamps newIndex to size`() {
        assertEquals(listOf(2, 3, 4), NovelProgress.forwardChaptersToMarkRead(2, 9, 5))
    }

    @Test
    fun `forwardChaptersToMarkRead is empty when not moving forward`() {
        assertEquals(emptyList<Int>(), NovelProgress.forwardChaptersToMarkRead(3, 3, 5))
        assertEquals(emptyList<Int>(), NovelProgress.forwardChaptersToMarkRead(4, 2, 5))
    }

    @Test
    fun `forwardChaptersToMarkRead is empty when outgoing index is out of bounds`() {
        assertEquals(emptyList<Int>(), NovelProgress.forwardChaptersToMarkRead(-1, 2, 5))
        assertEquals(emptyList<Int>(), NovelProgress.forwardChaptersToMarkRead(9, 12, 4))
    }
}
