package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderChapterTest {

    private class CountingLoader : PageLoader() {
        override var isLocal = true
        var recycleCount = 0

        override suspend fun getPages(): List<ReaderPage> = emptyList()

        override fun recycle() {
            super.recycle()
            recycleCount++
        }
    }

    private fun chapter() = ReaderChapter(ChapterImpl().apply { name = "Chapter 1" })

    @Test
    fun `retired loader stays open while the chapter is referenced`() {
        val chapter = chapter()
        val old = CountingLoader()
        chapter.ref()
        chapter.pageLoader = CountingLoader()

        chapter.retirePageLoader(old)

        assertFalse(old.isRecycled)
    }

    @Test
    fun `retired loader is recycled once when the chapter is unreferenced`() {
        val chapter = chapter()
        val old = CountingLoader()
        val current = CountingLoader()
        chapter.ref()
        chapter.pageLoader = current

        chapter.retirePageLoader(old)
        chapter.retirePageLoader(old)
        chapter.unref()

        assertEquals(1, old.recycleCount)
        assertEquals(1, current.recycleCount)
    }

    @Test
    fun `retiring on an unreferenced chapter recycles immediately`() {
        val chapter = chapter()
        val old = CountingLoader()

        chapter.retirePageLoader(old)

        assertEquals(1, old.recycleCount)
    }

    @Test
    fun `loader already recycled by unref is not recycled again`() {
        val chapter = chapter()
        val old = CountingLoader()
        chapter.ref()
        chapter.pageLoader = old
        chapter.unref()

        chapter.retirePageLoader(old)

        assertEquals(1, old.recycleCount)
    }

    @Test
    fun `current loader is never retired`() {
        val chapter = chapter()
        val current = CountingLoader()
        chapter.ref()
        chapter.pageLoader = current

        chapter.retirePageLoader(current)
        chapter.unref()

        assertTrue(current.isRecycled)
        assertEquals(1, current.recycleCount)
    }
}
