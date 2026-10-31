package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.NovelPageLoader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class HttpPageLoaderTest {

    @Test
    fun `a page loading when the loader is recycled ends in error`() = runBlocking {
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, name = "c", url = "/c"))
        val source = mockk<HttpSource> {
            every { isNovelSource } returns true
            coEvery { fetchPageText(any()) } coAnswers { awaitCancellation() }
        }
        val loader = HttpPageLoader(chapter, source, mockk(relaxed = true))
        val page = ReaderPage(0, "/c").also { it.chapter = chapter }

        val result = async { NovelPageLoader.awaitPageText("test", page, loader, 30_000, this@runBlocking) }
        withTimeout(5_000) { page.statusFlow.first { it == Page.State.LoadPage } }
        loader.recycle()

        assertFalse(withTimeout(5_000) { result.await() })
        assertTrue(page.status is Page.State.Error)
    }
}
