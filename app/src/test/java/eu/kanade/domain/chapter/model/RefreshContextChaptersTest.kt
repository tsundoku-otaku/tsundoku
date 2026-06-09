package eu.kanade.domain.chapter.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class RefreshContextChaptersTest {

    private fun chapter(
        url: String,
        sourceOrder: Long,
        read: Boolean = false,
        lastPageRead: Long = 0,
    ) = Chapter.create().copy(
        url = url,
        name = url.trimStart('/'),
        sourceOrder = sourceOrder,
        read = read,
        lastPageRead = lastPageRead,
    )

    @Test
    fun `result is ordered newest-first by sourceOrder regardless of input order`() {
        val dbRowOrder = listOf(
            chapter("/c3", sourceOrder = 1),
            chapter("/c2", sourceOrder = 2),
            chapter("/c1", sourceOrder = 3),
            chapter("/c4", sourceOrder = 0),
        )

        val result = dbRowOrder.toRefreshContextChapters()

        assertEquals(listOf("/c4", "/c3", "/c2", "/c1"), result.map { it.url })
    }

    @Test
    fun `already sorted input stays newest-first`() {
        val sorted = listOf(
            chapter("/c4", sourceOrder = 0),
            chapter("/c3", sourceOrder = 1),
            chapter("/c2", sourceOrder = 2),
            chapter("/c1", sourceOrder = 3),
        )

        val result = sorted.toRefreshContextChapters()

        assertEquals(listOf("/c4", "/c3", "/c2", "/c1"), result.map { it.url })
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(emptyList<Chapter>().toRefreshContextChapters().isEmpty())
    }

    @Test
    fun `read state and progress are carried into the SChapter`() {
        val input = listOf(
            chapter("/c2", sourceOrder = 0, read = false, lastPageRead = 0),
            chapter("/c1", sourceOrder = 1, read = true, lastPageRead = 42),
        )

        val result = input.toRefreshContextChapters().associateBy { it.url }

        assertEquals(true, result.getValue("/c1").read)
        assertEquals(42, result.getValue("/c1").last_page_read)
        assertEquals(false, result.getValue("/c2").read)
        assertEquals(0, result.getValue("/c2").last_page_read)
    }

    @Test
    fun `no chapter is dropped`() {
        val input = (0L until 250L).map { chapter("/c$it", sourceOrder = it) }

        val result = input.toRefreshContextChapters()

        assertEquals(input.size, result.size)
        assertEquals(input.map { it.url }.toSet(), result.map { it.url }.toSet())
    }
}
