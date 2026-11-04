package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class NovelPageLoaderTest {

    private class FakeLoader(private val requeueOnRetry: Boolean = false) : PageLoader() {
        val loads = AtomicInteger()
        override var isLocal = true
        override suspend fun getPages(): List<ReaderPage> = emptyList()
        override suspend fun loadPage(page: ReaderPage) {
            loads.incrementAndGet()
            page.text = "text"
            page.status = Page.State.Ready
        }
        override fun retryPage(page: ReaderPage) {
            if (requeueOnRetry) page.status = Page.State.Queue
        }
    }

    @Test
    fun `restarts a load another caller cancelled back to the queue`() = runBlocking {
        val loader = FakeLoader()
        val page = ReaderPage(0, "/c")
        page.status = Page.State.LoadPage

        val result = async { NovelPageLoader.awaitPageText("test", page, loader, 5_000, this@runBlocking) }
        delay(50)
        assertEquals(0, loader.loads.get())
        page.status = Page.State.Queue

        assertTrue(result.await())
        assertEquals(1, loader.loads.get())
    }

    @Test
    fun `leaves a page the loader re-queued on retry to the loader`() = runBlocking {
        val loader = FakeLoader(requeueOnRetry = true)
        val page = ReaderPage(0, "/c")
        page.status = Page.State.Error(Exception())

        val result = async { NovelPageLoader.awaitPageText("test", page, loader, 5_000, this@runBlocking) }
        delay(50)
        assertEquals(0, loader.loads.get())
        page.text = "text"
        page.status = Page.State.Ready

        assertTrue(result.await())
        assertEquals(0, loader.loads.get())
    }

    @Test
    fun `reloads an errored page when the loader has no retry`() = runBlocking {
        val loader = FakeLoader()
        val page = ReaderPage(0, "/c")
        page.status = Page.State.Error(Exception())

        assertTrue(NovelPageLoader.awaitPageText("test", page, loader, 5_000, this))
        assertEquals(1, loader.loads.get())
    }
}
