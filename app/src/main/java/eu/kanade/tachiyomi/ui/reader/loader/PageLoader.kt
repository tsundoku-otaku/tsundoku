package eu.kanade.tachiyomi.ui.reader.loader

import androidx.annotation.CallSuper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/**
 * A loader used to load pages into the reader. Any open resources must be cleaned up when the
 * method [recycle] is called.
 */
abstract class PageLoader {

    /**
     * Whether this loader has been already recycled.
     */
    var isRecycled = false
        private set

    abstract var isLocal: Boolean

    /**
     * Returns the list of pages of a chapter.
     */
    abstract suspend fun getPages(): List<ReaderPage>

    /**
     * Reads a specific resource file synchronously from local storage.
     * Useful for EPUB inner images parsed inside WebView clients.
     */
    open suspend fun getPageDataStream(url: String): java.io.InputStream? = null

    /**
     * Loads the page. May also preload other pages.
     * Progress of the page loading should be followed via [page.statusFlow].
     * [loadPage] is not currently guaranteed to complete, so it should be launched asynchronously.
     */
    open suspend fun loadPage(page: ReaderPage) {}

    /**
     * Retries the given [page] in case it failed to load. This method only makes sense when an
     * online source is used.
     */
    open fun retryPage(page: ReaderPage) {}

    /**
     * Records [error] as the result of loading [page]. A load the caller cancelled goes back to the
     * queue and the cancellation is rethrown, whatever the source threw while being cancelled; a
     * cancellation the source threw itself, such as its own timeout, is a real error.
     */
    protected suspend fun onPageLoadFailed(page: ReaderPage, error: Throwable) {
        val context = currentCoroutineContext()
        if (!context.isActive) {
            page.tryClaim(Page.State.LoadPage, Page.State.Queue) ||
                page.tryClaim(Page.State.DownloadImage, Page.State.Queue)
            context.ensureActive()
        }
        page.status = Page.State.Error(error)
    }

    /**
     * Recycles this loader. Implementations must override this method to clean up any active
     * resources.
     */
    @CallSuper
    open fun recycle() {
        isRecycled = true
    }
}
