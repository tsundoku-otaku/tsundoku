package eu.kanade.tachiyomi.data.track.source

import android.app.Application
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceTrackerMethod
import eu.kanade.tachiyomi.source.invokeSourceTrackerCallback
import eu.kanade.tachiyomi.source.isSourceTracker
import eu.kanade.tachiyomi.source.sourceTrackerBoolean
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/**
 * Fans out chapter-read / unread / favorite / unfavorite events to sources that
 * implement [SourceTracker].
 *
 * - Per-manga debounce (3s).
 * - Min-chapters preference gates chapter callbacks, as for first-party trackers.
 * - Failures are logged + toasted.
 */
class SourceTrackerDispatcher(
    private val sourceManager: SourceManager,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getCategories: GetCategories,
    private val trackPreferences: TrackPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class PendingChapterEvent(
        var manga: Manga,
        var read: Boolean,
        val changedChapterIds: MutableSet<Long> = mutableSetOf(),
    )

    private val pendingChapterJobs = ConcurrentHashMap<Long, Job>()
    private val pendingChapterArgs = ConcurrentHashMap<Long, PendingChapterEvent>()

    fun notifyChaptersRead(manga: Manga, chapters: List<Chapter>) = enqueueChapterEvent(manga, chapters, read = true)

    fun notifyChaptersUnread(manga: Manga, chapters: List<Chapter>) = enqueueChapterEvent(manga, chapters, read = false)

    fun notifyFavorited(manga: Manga) = fireFavoriteEvent(manga, favorite = true)

    fun notifyUnfavorited(manga: Manga) = fireFavoriteEvent(manga, favorite = false)

    private fun enqueueChapterEvent(manga: Manga, chapters: List<Chapter>, read: Boolean) {
        if (chapters.isEmpty()) return
        val source = sourceManager.get(manga.source)
        if (source == null || !source.isSourceTracker()) return
        if (!source.sourceTrackerBoolean("supportsChapterTracking", default = true)) return

        val mangaId = manga.id
        pendingChapterArgs.compute(mangaId) { _, existing ->
            if (existing == null || existing.read != read) {
                PendingChapterEvent(manga = manga, read = read).also {
                    it.changedChapterIds += chapters.map { ch -> ch.id }
                }
            } else {
                existing.manga = manga
                existing.changedChapterIds += chapters.map { ch -> ch.id }
                existing
            }
        }

        pendingChapterJobs[mangaId]?.cancel()
        pendingChapterJobs[mangaId] = scope.launch {
            delay(DEBOUNCE_MS)
            val args = pendingChapterArgs.remove(mangaId) ?: return@launch
            pendingChapterJobs.remove(mangaId)
            runChapterEvent(source, args)
        }
    }

    private suspend fun runChapterEvent(source: Source, args: PendingChapterEvent) {
        try {
            val manga = args.manga
            val allChapters = getChaptersByMangaId.await(manga.id)
            if (!passesMinChaptersGate(manga, allChapters)) return

            val changed = allChapters.filter { it.id in args.changedChapterIds }
            val sourceManga = manga.toSManga()
            val allSChapters = allChapters.map { it.toSChapter() }
            val categories = loadCategoryNames(manga.id)

            val (method, changedSChapters) = if (!args.read) {
                val highestStillRead = allChapters
                    .filter { it.read && it.chapterNumber > 0.0 }
                    .maxByOrNull { it.chapterNumber }
                if (highestStillRead != null) {
                    SourceTrackerMethod.ON_CHAPTERS_READ to listOf(highestStillRead.toSChapter())
                } else {
                    SourceTrackerMethod.ON_CHAPTERS_UNREAD to changed.map { it.toSChapter() }
                }
            } else {
                SourceTrackerMethod.ON_CHAPTERS_READ to changed.map { it.toSChapter() }
            }

            source.invokeSourceTrackerCallback(method, sourceManga, changedSChapters, allSChapters, categories)
        } catch (e: Throwable) {
            reportFailure(source, e)
        }
    }

    private fun fireFavoriteEvent(manga: Manga, favorite: Boolean) {
        val source = sourceManager.get(manga.source)
        if (source == null || !source.isSourceTracker()) return
        if (!source.sourceTrackerBoolean("supportsFavoritesTracking", default = false)) return
        scope.launch {
            try {
                val sourceManga = manga.toSManga()
                val categories = loadCategoryNames(manga.id)
                val method = if (favorite) SourceTrackerMethod.ON_FAVORITED else SourceTrackerMethod.ON_UNFAVORITED
                source.invokeSourceTrackerCallback(method, sourceManga, emptyList(), emptyList(), categories)
            } catch (e: Throwable) {
                reportFailure(source, e)
            }
        }
    }

    private suspend fun loadCategoryNames(mangaId: Long): List<String> {
        return try {
            getCategories.await(mangaId)
                .filter { it.id != Category.UNCATEGORIZED_ID }
                .map { it.name }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "SourceTrackerDispatcher: category lookup failed for $mangaId" }
            emptyList()
        }
    }

    private fun passesMinChaptersGate(manga: Manga, allChapters: List<Chapter>): Boolean {
        val pref = if (manga.isNovel) {
            trackPreferences.minChaptersBeforeTrackingNovel
        } else {
            trackPreferences.minChaptersBeforeTrackingManga
        }
        val threshold = pref.get().toIntOrNull() ?: 0
        if (threshold <= 0) return true
        val readCount = allChapters.count { it.read }
        return readCount >= threshold
    }

    private suspend fun reportFailure(source: Source, e: Throwable) {
        logcat(LogPriority.ERROR, e) { "SourceTrackerDispatcher: callback failed for ${source::class.java.name}" }
        runCatching {
            withUIContext {
                val app = Injekt.get<Application>()
                app.toast("${source.name} tracker failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 3_000L
    }
}
