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
        if (page.status is Page.State.Error) {
            // Re-fetch a page cached in Error from a prior failed attempt, else statusFlow returns
            // Error immediately and the load can never recover once the network is back. retryPage
            // resets it to Queue and re-queues, so don't also launch loadPage below.
            loader.retryPage(page)
        } else if (page.status is Page.State.Queue) {
            loadJob = scope.launch(Dispatchers.IO) {
                try {
                    loader.loadPage(page)
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Expected when scope is cancelled
                }
            }
        }

        return try {
            val finalState = withTimeout(timeoutMs) {
                page.statusFlow.first { state ->
                    state is Page.State.Ready || state is Page.State.Error
                }
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
