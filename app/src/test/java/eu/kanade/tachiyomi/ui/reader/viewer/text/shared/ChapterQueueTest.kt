package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterQueueTest {

    private data class Item(val id: Long?, val label: String = "x")

    private fun queue() = ChapterQueue<Item> { it.id }

    @Test
    fun `empty queue reports correct flags`() {
        val q = queue()
        assertTrue(q.isEmpty())
        assertFalse(q.isNotEmpty())
        assertEquals(0, q.size)
        assertNull(q.current())
        assertNull(q.firstOrNull())
        assertNull(q.lastOrNull())
        assertEquals(0, q.currentIndex)
        assertFalse(q.isLoadingNext)
    }

    @Test
    fun `append adds item and reports it`() {
        val q = queue()
        assertTrue(q.append(Item(1)))
        assertEquals(1, q.size)
        assertEquals(Item(1), q.firstOrNull())
        assertEquals(Item(1), q.lastOrNull())
        assertTrue(q.contains(1))
    }

    @Test
    fun `append rejects duplicate id`() {
        val q = queue()
        q.append(Item(1, "a"))
        assertFalse(q.append(Item(1, "b")))
        assertEquals(1, q.size)
        assertEquals("a", q.firstOrNull()?.label)
    }

    @Test
    fun `append rejects items with null id`() {
        val q = queue()
        assertFalse(q.append(Item(null)))
        assertTrue(q.isEmpty())
    }

    @Test
    fun `prepend adds to front and shifts cursor`() {
        val q = queue()
        q.append(Item(1))
        q.append(Item(2))
        q.currentIndex = 1
        assertTrue(q.prepend(Item(3)))
        assertEquals(Item(3), q.firstOrNull())
        assertEquals(3, q.size)
        // Cursor shifted by 1 so user's visible chapter (Item(2)) is still at the cursor
        assertEquals(2, q.currentIndex)
        assertEquals(Item(2), q.current())
    }

    @Test
    fun `prepend rejects duplicate`() {
        val q = queue()
        q.append(Item(1))
        assertFalse(q.prepend(Item(1)))
    }

    @Test
    fun `contains is O of 1 after batch inserts`() {
        val q = queue()
        for (i in 1L..100L) q.append(Item(i))
        for (i in 1L..100L) assertTrue(q.contains(i))
        assertFalse(q.contains(101))
    }

    @Test
    fun `indexOf returns position or minus one`() {
        val q = queue()
        q.append(Item(10))
        q.append(Item(20))
        q.append(Item(30))
        assertEquals(0, q.indexOf(10))
        assertEquals(1, q.indexOf(20))
        assertEquals(2, q.indexOf(30))
        assertEquals(-1, q.indexOf(99))
    }

    @Test
    fun `removeFirst pops head and shifts cursor`() {
        val q = queue()
        q.append(Item(1))
        q.append(Item(2))
        q.append(Item(3))
        q.currentIndex = 2
        assertEquals(Item(1), q.removeFirst())
        assertEquals(2, q.size)
        // Cursor pointed at index 2, item 3. After removal it should still
        // point at item 3 — now at index 1.
        assertEquals(1, q.currentIndex)
        assertEquals(Item(3), q.current())
        // Removed id no longer in id set
        assertFalse(q.contains(1))
    }

    @Test
    fun `removeFirst on empty returns null`() {
        val q = queue()
        assertNull(q.removeFirst())
    }

    @Test
    fun `removeFirstN drops requested count`() {
        val q = queue()
        for (i in 1L..5L) q.append(Item(i))
        q.currentIndex = 4
        q.removeFirstN(3)
        assertEquals(2, q.size)
        assertEquals(1, q.currentIndex)
        assertEquals(Item(4), q.firstOrNull())
        assertFalse(q.contains(1))
        assertFalse(q.contains(2))
        assertFalse(q.contains(3))
    }

    @Test
    fun `removeFirstN with zero is a no-op`() {
        val q = queue()
        q.append(Item(1))
        q.removeFirstN(0)
        assertEquals(1, q.size)
    }

    @Test
    fun `removeFirstN with count larger than size empties queue and clamps cursor`() {
        val q = queue()
        q.append(Item(1))
        q.append(Item(2))
        q.append(Item(3))
        q.currentIndex = 2
        q.removeFirstN(10) // remove more than exist
        assertEquals(0, q.size)
        assertEquals(0, q.currentIndex) // must not underflow
        assertTrue(q.isEmpty())
        assertFalse(q.contains(1))
        assertFalse(q.contains(2))
        assertFalse(q.contains(3))
    }

    @Test
    fun `removeFirstN partial removal keeps cursor pointing at same chapter`() {
        val q = queue()
        for (i in 1L..5L) q.append(Item(i))
        q.currentIndex = 3 // pointing at Item(4)
        q.removeFirstN(2) // remove Item(1) and Item(2)
        assertEquals(3, q.size)
        // cursor was at 3, 2 items removed → now at 1, still pointing at Item(4)
        assertEquals(1, q.currentIndex)
        assertEquals(Item(4), q.current())
    }

    @Test
    fun `reset wipes the queue and seeds with item`() {
        val q = queue()
        q.append(Item(1))
        q.append(Item(2))
        q.currentIndex = 1
        q.reset(Item(99))
        assertEquals(1, q.size)
        assertEquals(0, q.currentIndex)
        assertEquals(Item(99), q.current())
        assertFalse(q.contains(1))
        assertFalse(q.contains(2))
        assertTrue(q.contains(99))
    }

    @Test
    fun `clear empties the queue and resets cursor`() {
        val q = queue()
        q.append(Item(1))
        q.append(Item(2))
        q.currentIndex = 1
        q.clear()
        assertTrue(q.isEmpty())
        assertEquals(0, q.currentIndex)
    }

    @Test
    fun `loadedIds matches inserted ids in any order`() {
        val q = queue()
        q.append(Item(1))
        q.append(Item(2))
        q.prepend(Item(3))
        assertEquals(setOf(1L, 2L, 3L), q.loadedIds)
    }

    @Test
    fun `all returns items in insertion order`() {
        val q = queue()
        q.append(Item(2))
        q.append(Item(3))
        q.prepend(Item(1))
        assertEquals(listOf(Item(1), Item(2), Item(3)), q.all)
    }

    @Test
    fun `isLoadingNext flag round-trips`() {
        val q = queue()
        assertFalse(q.isLoadingNext)
        q.isLoadingNext = true
        assertTrue(q.isLoadingNext)
        q.isLoadingNext = false
        assertFalse(q.isLoadingNext)
    }
}
