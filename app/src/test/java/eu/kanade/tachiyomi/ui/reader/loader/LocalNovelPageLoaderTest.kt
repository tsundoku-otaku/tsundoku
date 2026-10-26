package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.util.concurrent.atomic.AtomicInteger

class LocalNovelPageLoaderTest {

    private val chapter = ReaderChapter(Chapter.create().copy(id = 1, name = "c", url = "/c"))

    private fun source(fetch: suspend () -> String): Pair<Source, AtomicInteger> {
        val calls = AtomicInteger()
        val source = mockk<Source> {
            every { isNovelSource } returns true
            coEvery { fetchPageText(any()) } coAnswers {
                calls.incrementAndGet()
                fetch()
            }
        }
        return source to calls
    }

    @Test
    fun `concurrent loads share one fetch`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val (source, calls) = source {
            gate.await()
            "text"
        }
        val loader = LocalNovelPageLoader(chapter, source)
        val page = ReaderPage(0, "/c")

        val first = async { loader.loadPage(page) }
        page.statusFlow.first { it == Page.State.LoadPage }
        val second = async { loader.loadPage(page) }
        delay(50)
        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, calls.get())
        assertEquals(Page.State.Ready, page.status)
    }

    @Test
    fun `a cancelled load goes back to the queue`() = runBlocking {
        val (source, _) = source { CompletableDeferred<String>().await() }
        val loader = LocalNovelPageLoader(chapter, source)
        val page = ReaderPage(0, "/c")

        val job = launch { loader.loadPage(page) }
        page.statusFlow.first { it == Page.State.LoadPage }
        job.cancelAndJoin()

        assertEquals(Page.State.Queue, page.status)
    }

    @Test
    fun `a timeout thrown by the source is an error`() = runBlocking {
        val (source, _) = source { withTimeout(1) { CompletableDeferred<String>().await() } }
        val loader = LocalNovelPageLoader(chapter, source)
        val page = ReaderPage(0, "/c")

        loader.loadPage(page)

        val status = page.status
        assertTrue(status is Page.State.Error && status.error is TimeoutCancellationException)
    }
}
