package eu.kanade.tachiyomi.ui.manga.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterSearchRangeTest {

    private fun chapter(id: Long, number: Double) = Chapter.create().copy(id = id, chapterNumber = number)

    private val chapters = listOf(
        chapter(1, 1.0),
        chapter(2, 2.0),
        chapter(3, 2.5),
        chapter(4, 3.0),
        chapter(5, -1.0),
        chapter(6, 4.0),
    )

    @Test
    fun `no range keeps every chapter`() {
        assertEquals(chapters, chapters.inSearchRange(null))
    }

    @Test
    fun `range is inclusive and keeps fractional numbers`() {
        val kept = chapters.inSearchRange(ChapterSearchRange(from = 2.0, to = 3.0)).map { it.id }
        assertEquals(listOf(2L, 3L, 4L), kept)
    }

    @Test
    fun `open ends stretch to the first and last chapter`() {
        assertEquals(listOf(4L, 6L), chapters.inSearchRange(ChapterSearchRange(from = 3.0, to = null)).map { it.id })
        assertEquals(listOf(1L, 2L), chapters.inSearchRange(ChapterSearchRange(from = null, to = 2.0)).map { it.id })
    }

    @Test
    fun `chapters without a recognized number are left out of a bounded range`() {
        val kept = chapters.inSearchRange(ChapterSearchRange(from = 0.0, to = 100.0)).map { it.id }
        assertFalse(5L in kept)
    }

    @Test
    fun `reversed bounds are swapped`() {
        val kept = chapters.inSearchRange(ChapterSearchRange(from = 3.0, to = 2.0)).map { it.id }
        assertEquals(listOf(2L, 3L, 4L), kept)
    }

    @Test
    fun `an unbounded range is no range`() {
        assertNull(ChapterSearchRange.of(from = null, to = null))
        assertTrue(ChapterSearchRange.of(from = 1.0, to = null) != null)
    }

    @Test
    fun `parse reads chapter numbers typed by the user`() {
        assertEquals(12.0, ChapterSearchRange.parseBound("12"))
        assertEquals(12.5, ChapterSearchRange.parseBound(" 12.5 "))
        assertEquals(12.5, ChapterSearchRange.parseBound("12,5"))
        assertNull(ChapterSearchRange.parseBound(""))
        assertNull(ChapterSearchRange.parseBound("abc"))
    }
}
