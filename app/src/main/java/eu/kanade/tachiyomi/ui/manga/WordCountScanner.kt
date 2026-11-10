package eu.kanade.tachiyomi.ui.manga

import eu.kanade.domain.manga.model.WordDensity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Reads one chapter's content for a word count; `null` or an exception marks the chapter unreadable. */
interface WordCountChapterReader<C> : Closeable {
    suspend fun read(chapter: C): String?
}

object WordCountScanner {

    /**
     * Counts the words of [chapters] and returns the novel's [WordDensity].
     *
     * The chapters are split into up to [parallelism] contiguous runs that are read at the same time, each
     * through its own reader from [openReader], so storage calls overlap while every reader still walks
     * its chapters in order. [onProgress] receives the number of chapters checked so far.
     */
    suspend fun <C> scan(
        chapters: List<C>,
        totalChapters: Int,
        parallelism: Int,
        openReader: () -> WordCountChapterReader<C>,
        onProgress: (checkedChapters: Int) -> Unit,
    ): WordDensity {
        val checked = AtomicInteger()
        val runSize = ((chapters.size + parallelism - 1) / parallelism.coerceAtLeast(1)).coerceAtLeast(1)
        val runs = coroutineScope {
            chapters.chunked(runSize).map { run ->
                async {
                    openReader().use { reader -> countRun(run, reader) { onProgress(checked.incrementAndGet()) } }
                }
            }.awaitAll()
        }
        return WordDensity(
            totalWords = runs.sumOf { it.words },
            countedChapters = runs.sumOf { it.counted },
            totalChapters = totalChapters,
            unreadableChapters = runs.sumOf { it.unreadable },
        )
    }

    private class RunCount(val words: Long, val counted: Int, val unreadable: Int)

    private suspend fun <C> countRun(
        chapters: List<C>,
        reader: WordCountChapterReader<C>,
        onChapterChecked: () -> Unit,
    ): RunCount {
        var words = 0L
        var counted = 0
        var unreadable = 0
        chapters.forEach { chapter ->
            coroutineContext.ensureActive()
            val content = try {
                reader.read(chapter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Word count: failed to read a chapter" }
                null
            }
            if (content != null) {
                words += WordDensity.countWords(content)
                counted++
            } else {
                unreadable++
            }
            onChapterChecked()
        }
        return RunCount(words, counted, unreadable)
    }
}
