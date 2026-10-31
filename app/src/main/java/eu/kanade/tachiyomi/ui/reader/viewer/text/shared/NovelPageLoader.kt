package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import logcat.logcat

object NovelPageLoader {

    private val intMapJsonCache: MutableMap<String, Map<String, Int>> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, Map<String, Int>>(64, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, Map<String, Int>>,
                ) = size > 64
            },
        )

    suspend fun awaitPageText(
        tag: String,
        page: ReaderPage,
        loader: PageLoader,
        timeoutMs: Long,
        scope: CoroutineScope,
    ): Boolean {
        if (!page.text.isNullOrBlank()) {
            logcat(LogPriority.DEBUG) { "$tag: page text already available, text.length=${page.text?.length ?: 0}" }
            return true
        }

        var loadJob: kotlinx.coroutines.Job? = null
        fun launchLoad() = scope.launch(Dispatchers.IO) {
            try {
                loader.loadPage(page)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Expected when scope is cancelled
            }
        }
        val initialStatus = page.status
        if (initialStatus is Page.State.Error) {
            // Re-fetch a page cached in Error from a prior failed attempt, else statusFlow returns
            // Error immediately and the load can never recover once the network is back. retryPage
            // resets it to Queue and re-queues.
            loader.retryPage(page)
            // retryPage is a no-op in the PageLoader base class; if the loader didn't reset the
            // state, fall back to launching the load directly so recovery still works.
            // Clear the stale Error first: statusFlow replays its current value, so leaving it
            // Error makes the first{} below match immediately and return before loadPage runs.
            if (page.tryClaim(initialStatus, Page.State.Queue)) loadJob = launchLoad()
        } else if (initialStatus is Page.State.Queue) {
            loadJob = launchLoad()
        }
        // A page the loader re-queued itself is its own to load; only a load this call or another
        // caller had started is restarted when it is cancelled back to Queue.
        val restartOnQueue = loadJob != null ||
            page.status.let { it is Page.State.LoadPage || it is Page.State.DownloadImage }
        fun settled(state: Page.State) = state is Page.State.Ready ||
            state is Page.State.Error ||
            (restartOnQueue && state is Page.State.Queue && loadJob?.isActive != true)

        return try {
            val finalState = withTimeout(timeoutMs) {
                var state = page.statusFlow.first(::settled)
                while (state is Page.State.Queue) {
                    loadJob = launchLoad()
                    state = page.statusFlow.first(::settled)
                }
                state
            }

            when (finalState) {
                is Page.State.Ready -> {
                    logcat(LogPriority.DEBUG) { "$tag: page ready, text.length=${page.text?.length ?: 0}" }
                    !page.text.isNullOrBlank()
                }
                is Page.State.Error -> {
                    logcat(LogPriority.ERROR) { "$tag: page error: ${finalState.error.message}" }
                    false
                }
                else -> false
            }
        } finally {
            loadJob?.cancel()
        }
    }
}
