package eu.kanade.tachiyomi.data.massimport

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.download.service.NovelDownloadPreferences
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

// Trigger GC when heap usage crosses this fraction, before fragmentation near the 512MB limit
private const val MEMORY_PRESSURE_THRESHOLD = 0.75
private const val MAX_HEAP_BYTES = 512 * 1024 * 1024L
private const val GC_DELAY_MS = 500L
private const val MIN_FREE_MEMORY_BYTES = 50 * 1024 * 1024L
// Cap the throttle wait so a permanently-pressured heap can't deadlock the import.
private const val MAX_MEMORY_WAIT_ITERATIONS = 20
// Upper bound on parallel fetches; matches the settings slider max.
private const val MAX_CONCURRENCY = 30
// How many URLs to look ahead when spreading same-host URLs apart (see interleaveByHost).
// Caps the reorder buffer so a huge file stays small in memory.
private const val MAX_LOOKAHEAD = 512
// Bound the retained per-URL error detail so a mostly-failing huge import can't OOM.
private const val MAX_TRACKED_ERRORS = 2000
// Hard ceiling on a single source fetch so one hanging/oversized request can't freeze
// the batch (at low concurrency one stuck URL blocks all the rest).
private const val FETCH_TIMEOUT_MS = 60_000L
private const val NOTIFICATION_THROTTLE_MS = 1000L
private const val NOTIFICATION_MIN_DELTA = 5

class MassImportJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val massImportInteractor: eu.kanade.domain.manga.interactor.MassImport by lazy { Injekt.get() }
    private val sourceManager: SourceManager by lazy { Injekt.get() }
    private val networkToLocalManga: NetworkToLocalManga by lazy { Injekt.get() }
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId by lazy { Injekt.get() }
    private val novelDownloadPreferences: NovelDownloadPreferences by lazy { Injekt.get() }
    private val mangaRepository: MangaRepository by lazy { Injekt.get() }
    private val setMangaCategories: SetMangaCategories by lazy { Injekt.get() }
    private val syncChaptersWithSource: SyncChaptersWithSource by lazy { Injekt.get() }
    private val getLibraryManga: tachiyomi.domain.manga.interactor.GetLibraryManga by lazy { Injekt.get() }
    private val sourcePreferences: eu.kanade.domain.source.service.SourcePreferences by lazy { Injekt.get() }
    private var lastNotificationTime = 0L
    private var lastNotifiedProgress = -1
    private var lastNotificationStatus: String? = null

    // Serializes the memory-pressure back-off so concurrent fetch coroutines don't all spin
    // (and previously all fire System.gc()) at once.
    private val memoryPressureMutex = Mutex()

    private val notificationBuilder = context.notificationBuilder(Notifications.CHANNEL_MASS_IMPORT) {
        setSmallIcon(android.R.drawable.stat_sys_download)
        setContentTitle("Mass Import")
        setContentText("Starting...")
        setOngoing(true)
        setOnlyAlertOnce(true)
    }

    override suspend fun doWork(): Result {
        val urlsArray = inputData.getStringArray(KEY_URLS)
        val urlsFilePath = inputData.getString(KEY_URLS_FILE)
        val rawTextFilePath = inputData.getString(KEY_RAW_TEXT_FILE)
        val excludedHostsParam = inputData.getString(KEY_EXCLUDED_HOSTS) ?: ""
        val excludedHostsSet = excludedHostsParam.split(',')
            .map { it.trim().lowercase().removePrefix("www.") }
            .filter { it.isNotBlank() }
            .toSet()

        val urls = urlsArray?.toList()
        val preferredSourceId = inputData.getLong(KEY_PREFERRED_SOURCE_ID, -1L).takeIf { it != -1L }
        val hostSourceCache = ConcurrentHashMap<String, CatalogueSource>()
        val missingSourceHosts = ConcurrentHashMap.newKeySet<String>()

        val jobBatchId = inputData.getString(KEY_BATCH_ID) ?: ""
        val resumeOffset = inputData.getInt(KEY_RESUME_OFFSET, 0).coerceAtLeast(0)
        // Exit instead of parking in the pause loop: a parked foreground worker burns the
        // Android 15 dataSync time budget. Resume flips the status before re-enqueueing.
        hydrateBatchFromStore(jobBatchId)
        when (batchStatus(jobBatchId)) {
            BatchStatus.Paused, BatchStatus.Cancelled -> return Result.success()
            else -> {}
        }

        // Resume from the persisted progress, not just the input offset. A WorkManager retry
        // (foreground-budget backoff below) re-runs with the ORIGINAL input data, so without this
        // every retry would restart the batch from zero.
        val persistedProgress = _sharedQueue.value.find { it.id == jobBatchId }?.progress ?: 0
        val effectiveResumeOffset = maxOf(
            resumeOffset,
            (persistedProgress - (MAX_LOOKAHEAD + MAX_CONCURRENCY)).coerceAtLeast(0),
        )

        if (!trySetForeground()) {
            // Foreground start refused. Two causes: dataSync budget exhausted (6h/day on Android
            // 15+), or the OS won't let a background worker start a foreground service at all (e.g.
            // run from a cold start). Retrying helps the budget case, but NOT the second: WorkManager
            // re-runs the still-ENQUEUED job at the next process start, where it is again denied,
            // looping forever and jamming the splash window every launch. Bound the retries; once
            // exhausted, park the batch as interrupted (resumable) and finish so the WorkSpec goes
            // terminal instead of re-firing. It resumes when the mass-import dialog is next opened
            // (app in foreground, where the foreground start is allowed).
            notifyForegroundLimitReached()
            return if (runAttemptCount < MAX_FOREGROUND_START_RETRIES) {
                Result.retry()
            } else {
                markBatchInterrupted(jobBatchId)
                Result.success()
            }
        }

        val streamFilePath = urlsFilePath ?: rawTextFilePath
        if (urls == null && streamFilePath != null) {
            val batchId = inputData.getString(KEY_BATCH_ID) ?: ""
            val file = File(streamFilePath)
            if (!file.exists()) {
                // Staged file gone (cache cleared); resume restages from MassImportStore.
                markBatchInterrupted(batchId)
                return Result.failure()
            }

            val categoryId = inputData.getLong(KEY_CATEGORY_ID, 0L)
            val addToLibrary = inputData.getBoolean(KEY_ADD_TO_LIBRARY, true)
            val fetchDetails = inputData.getBoolean(KEY_FETCH_DETAILS, true)
            val fetchChapters = inputData.getBoolean(KEY_FETCH_CHAPTERS, false)

            return withIOContext {
                try {
                    // Streaming count for the progress denominator; materializing would OOM.
                    val totalCount = file.bufferedReader().useLines { lines ->
                        lines.sumOf { raw ->
                            if (raw.contains(',') || raw.contains(';')) {
                                raw.split(',', ';').count { it.isNotBlank() }
                            } else if (raw.trim().isNotBlank()) {
                                1
                            } else {
                                0
                            }
                        }
                    }

                    // Cold flow re-reads the file on collection; O(1) memory. No dedup: the
                    // in-flight guard and insert-if-not-exists handle duplicates.
                    val urlFlow = kotlinx.coroutines.flow.flow {
                        file.bufferedReader().useLines { lines ->
                            for (raw in lines) {
                                if (raw.contains(',') || raw.contains(';')) {
                                    for (part in raw.split(',', ';')) {
                                        val t = part.trim()
                                        if (t.isNotBlank()) emit(t)
                                    }
                                } else {
                                    val t = raw.trim()
                                    if (t.isNotBlank()) emit(t)
                                }
                            }
                        }
                    }

                    performImport(
                        urlFlow, totalCount,
                        categoryId, addToLibrary, fetchDetails, fetchChapters, batchId,
                        excludedHostsSet, hostSourceCache, missingSourceHosts, preferredSourceId,
                        resumeOffset = effectiveResumeOffset,
                    )

                    Result.success()
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        // Still Running = system stop (time limit, process pressure), not a
                        // user pause/cancel — schedule a delayed background resume.
                        if (batchStatus(batchId) == BatchStatus.Running) scheduleBackgroundResume(batchId)
                        Result.success()
                    } else {
                        logcat(LogPriority.ERROR, e)
                        markBatchInterrupted(batchId)
                        Result.failure()
                    }
                } finally {
                    context.cancelNotification(Notifications.ID_MASS_IMPORT_PROGRESS)
                    runCatching { File(streamFilePath).delete() }
                    liveErroredUrls.remove(batchId)
                }
            }
        }
        if (urls == null) return Result.failure()
        val categoryId = inputData.getLong(KEY_CATEGORY_ID, 0L)
        val addToLibrary = inputData.getBoolean(KEY_ADD_TO_LIBRARY, true)
        val fetchDetails = inputData.getBoolean(KEY_FETCH_DETAILS, true)
        val fetchChapters = inputData.getBoolean(KEY_FETCH_CHAPTERS, false)
        val batchId = inputData.getString(KEY_BATCH_ID) ?: ""

        return withIOContext {
            try {
                performImport(
                    urls.asFlow(),
                    urls.size,
                    categoryId,
                    addToLibrary,
                    fetchDetails,
                    fetchChapters,
                    batchId,
                    excludedHostsSet,
                    hostSourceCache,
                    missingSourceHosts,
                    preferredSourceId,
                    resumeOffset = effectiveResumeOffset,
                )
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    // Still Running = system stop, not user pause/cancel — schedule a delayed
                    // background resume.
                    if (batchStatus(batchId) == BatchStatus.Running) scheduleBackgroundResume(batchId)
                    Result.success()
                } else {
                    logcat(LogPriority.ERROR, e)
                    markBatchInterrupted(batchId)
                    Result.failure()
                }
            } finally {
                context.cancelNotification(Notifications.ID_MASS_IMPORT_PROGRESS)
                inputData.getString(KEY_URLS_FILE)?.let { path -> runCatching { File(path).delete() } }
                inputData.getString(KEY_RAW_TEXT_FILE)?.let { path -> runCatching { File(path).delete() } }
                liveErroredUrls.remove(batchId)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_MASS_IMPORT_PROGRESS,
            notificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    // Like setForegroundSafely, but reports an exhausted dataSync time budget (Android 15+):
    // without the foreground service the worker is killed by JobScheduler within minutes.
    private suspend fun trySetForeground(): Boolean {
        return try {
            setForeground(getForegroundInfo())
            delay(500)
            true
        } catch (e: IllegalStateException) {
            logcat(LogPriority.ERROR, e) { "Not allowed to run mass import in the foreground" }
            !(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                )
        }
    }

    private fun notifyForegroundLimitReached() {
        val notification = context.notificationBuilder(Notifications.CHANNEL_MASS_IMPORT) {
            setSmallIcon(android.R.drawable.stat_sys_warning)
            setContentTitle("Mass import paused")
            setContentText("Android background time limit reached — open the app and resume the import")
            setAutoCancel(true)
        }.build()
        context.notify(Notifications.ID_MASS_IMPORT_COMPLETE, notification)
    }

    private suspend fun performImport(
        urlFlow: Flow<String>,
        totalCount: Int,
        categoryId: Long,
        addToLibrary: Boolean,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        batchId: String,
        excludedHosts: Set<String> = emptySet(),
        hostSourceCache: ConcurrentHashMap<String, CatalogueSource> = ConcurrentHashMap(),
        missingSourceHosts: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        preferredSourceId: Long? = null,
        resumeOffset: Int = 0,
    ): ImportResult {
        hydrateBatchFromStore(batchId)
        startRunningUnlessPaused(batchId)

        val importSources = getImportSources()
        if (importSources.isEmpty()) {
            showCompletionNotification(batchId, 0, 0, totalCount, "No compatible sources installed")
            updateBatchStatus(batchId, BatchStatus.Completed)
            return ImportResult(added = 0, skipped = 0, errored = totalCount)
        }

        // Each run re-walks the full URL list, so prior log entries are stale. Cleared only once
        // processing actually starts — the degenerate aborts above keep the previous logs intact.
        if (batchId.isNotEmpty()) {
            MassImportStore.clearErrors(context, batchId)
            MassImportStore.clearSkipped(context, batchId)
        }

        val sourceCache = hostSourceCache
        fun getCachedSource(url: String): CatalogueSource? {
            val host = try {
                URI(url).host?.lowercase()?.removePrefix("www.")
            } catch (e: Exception) {
                null
            } ?: return null

            if (excludedHosts.contains(host)) return null

            sourceCache[host]?.let { return it }
            if (host in missingSourceHosts) return null

            val matched = runCatching { findMatchingSource(url, importSources, preferredSourceId) }.getOrNull()
            if (matched != null) {
                sourceCache[host] = matched
            } else {
                missingSourceHosts.add(host)
            }

            return matched
        }

        // Tokens stay streamed; a multi-million-URL list materialized would OOM the worker.
        // Invalid tokens are filtered inline and counted as skipped.
        _sharedQueue.update { list ->
            list.map { if (it.id == batchId) it.copy(total = totalCount) else it }
        }

        val concurrency = if (!fetchDetails && !fetchChapters) {
            16
        } else {
            novelDownloadPreferences.parallelMassImport().get().coerceIn(1, MAX_CONCURRENCY)
        }

        // Resume: seed counters from the persisted batch so progress/tallies continue
        // instead of restarting at 0. The dropped prefix counts as already-completed.
        val seed = if (resumeOffset > 0) _sharedQueue.value.find { it.id == batchId } else null
        val completedCount = AtomicInteger(resumeOffset)
        val addedCount = AtomicInteger(seed?.added ?: 0)
        val skippedCount = AtomicInteger(seed?.skipped ?: 0)
        val erroredCount = AtomicInteger(seed?.errored ?: 0)
        val pendingAddIds = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val flushBatchSize = 50
        val activeImports = ConcurrentHashMap<String, Boolean>()
        // Guards against the same URL being processed concurrently; size bounded by concurrency.
        val inFlightUrls = ConcurrentHashMap.newKeySet<String>()

        val erroredUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val errorMessages = ConcurrentHashMap<String, String>()
        // Live list exposed for mid-run copy/export; unregistered in doWork's finally.
        if (batchId.isNotEmpty()) liveErroredUrls[batchId] = erroredUrls
        // Every error also goes to the uncapped on-disk log so copy/export/retry survive
        // truncation, process death, and resume. Buffered to avoid one SAF write per URL.
        val errorLogBuffer = mutableListOf<Pair<String, String>>()
        fun recordError(url: String, message: String) {
            val toFlush: List<Pair<String, String>>?
            synchronized(erroredUrls) {
                if (erroredUrls.size < MAX_TRACKED_ERRORS) {
                    erroredUrls.add(url)
                    errorMessages[url] = message
                }
                errorLogBuffer.add(url to message)
                toFlush = if (errorLogBuffer.size >= 25) {
                    val copy = errorLogBuffer.toList()
                    errorLogBuffer.clear()
                    copy
                } else {
                    null
                }
            }
            if (toFlush != null && batchId.isNotEmpty()) {
                MassImportStore.appendErrors(context, batchId, toFlush)
            }
        }
        fun flushErrorLog() {
            val toFlush = synchronized(erroredUrls) {
                if (errorLogBuffer.isEmpty()) return
                val copy = errorLogBuffer.toList()
                errorLogBuffer.clear()
                copy
            }
            if (batchId.isNotEmpty()) MassImportStore.appendErrors(context, batchId, toFlush)
        }

        // Skipped URLs are streamed to a single-column `.txt` (`mi_<id>_skipped.txt`);
        // only the URL is persisted, the reason is dropped (there is a single skip reason).
        val skipLogBuffer = mutableListOf<Pair<String, String>>()
        fun recordSkip(url: String, reason: String) {
            val toFlush: List<Pair<String, String>>?
            synchronized(skipLogBuffer) {
                skipLogBuffer.add(url to reason)
                toFlush = if (skipLogBuffer.size >= 100) {
                    val copy = skipLogBuffer.toList()
                    skipLogBuffer.clear()
                    copy
                } else {
                    null
                }
            }
            if (toFlush != null && batchId.isNotEmpty()) {
                MassImportStore.appendSkipped(context, batchId, toFlush)
            }
        }
        fun flushSkipLog() {
            val toFlush = synchronized(skipLogBuffer) {
                if (skipLogBuffer.isEmpty()) return
                val copy = skipLogBuffer.toList()
                skipLogBuffer.clear()
                copy
            }
            if (batchId.isNotEmpty()) MassImportStore.appendSkipped(context, batchId, toFlush)
        }

        updateNotification(0, totalCount, "Starting import...")

        val throttlingEnabled = novelDownloadPreferences.enableMassImportThrottling().get()
        val shouldThrottle = throttlingEnabled && (fetchDetails || fetchChapters)
        val globalBaseDelay = novelDownloadPreferences.massImportDelay().get().toLong()
        val globalRandomRange = novelDownloadPreferences.randomDelayRange().get().toLong()

        fun getDelayForSource(sourceId: Long): Pair<Long, Long> {
            val override = novelDownloadPreferences.getSourceOverride(sourceId)
            if (override != null && override.enabled) {
                val baseDelay = override.massImportDelay?.toLong() ?: globalBaseDelay
                val randomRange = override.randomDelayRange?.toLong() ?: globalRandomRange
                return Pair(baseDelay, randomRange)
            }
            return Pair(globalBaseDelay, globalRandomRange)
        }

        val sourceSemaphores = globalSourceSemaphores

        val sourceConsecutiveFailures = ConcurrentHashMap<Long, AtomicInteger>()
        val maxSourceFailures = novelDownloadPreferences.skipSourceIfFailedXTimes().get()

        try {
        urlFlow
            // Resume: skip the already-processed prefix.
            .let { if (resumeOffset > 0) it.drop(resumeOffset) else it }
            // Spread same-host URLs apart so one slow source doesn't fill every slot below.
            .interleaveByHost(MAX_LOOKAHEAD)
            .flatMapMerge(concurrency) { url ->
                if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    return@flatMapMerge flow {
                        skippedCount.incrementAndGet()
                        recordSkip(url, "Not a valid URL")
                        val done = completedCount.incrementAndGet()
                        updateBatchProgress(batchId, done, addedCount.get(), skippedCount.get(), erroredCount.get())
                    }
                }
                val source = getCachedSource(url) ?: return@flatMapMerge flow {
                    skippedCount.incrementAndGet()
                    recordSkip(url, "No matching source installed (or host excluded)")
                    val done = completedCount.incrementAndGet()
                    updateBatchProgress(batchId, done, addedCount.get(), skippedCount.get(), erroredCount.get())
                }

                flow {
                    // Concurrent duplicate: processing both wastes a fetch and double-counts
                    // "added" (the insert itself is insert-if-not-exists, so no corruption).
                    if (!inFlightUrls.add(url)) {
                        skippedCount.incrementAndGet()
                        recordSkip(url, "Duplicate")
                        val done = completedCount.incrementAndGet()
                        updateBatchProgress(batchId, done, addedCount.get(), skippedCount.get(), erroredCount.get())
                        return@flow
                    }
                    if (!awaitResumed(batchId)) {
                        inFlightUrls.remove(url)
                        return@flow
                    }

                    val failures = sourceConsecutiveFailures.computeIfAbsent(source.id) { AtomicInteger(0) }.get()
                    if (maxSourceFailures > 0 && failures >= maxSourceFailures) {
                        erroredCount.incrementAndGet()
                        recordError(url, "Source skipped: $failures consecutive failures")
                        val done = completedCount.incrementAndGet()
                        updateBatchProgress(
                            batchId, done, addedCount.get(), skippedCount.get(), erroredCount.get(),
                            erroredUrlsPreview = synchronized(erroredUrls) { erroredUrls.take(10) },
                        )
                        inFlightUrls.remove(url)
                        return@flow
                    }

                    // Cheap DB pre-check before the throttle queue: on re-runs most URLs are
                    // already in the library and would otherwise each pay the per-source delay
                    // plus a network resolve just to be skipped one by one. A miss (resolved DB
                    // url differs from the normalized input) falls through to the full path,
                    // which re-checks after resolving.
                    val preMatch = runCatching {
                        val raw = massImportInteractor.extractPathFromUrl(
                            url,
                            massImportInteractor.getSourceBaseUrl(source),
                            source,
                        )
                        if (raw.isEmpty()) {
                            null
                        } else {
                            getMangaByUrlAndSourceId.await(massImportInteractor.normalizeUrl(raw), source.id)
                        }
                    }.getOrNull()
                    if (preMatch?.favorite == true) {
                        skippedCount.incrementAndGet()
                        recordSkip(url, "Already in library")
                        val done = completedCount.incrementAndGet()
                        updateBatchProgress(batchId, done, addedCount.get(), skippedCount.get(), erroredCount.get())
                        inFlightUrls.remove(url)
                        return@flow
                    }

                    waitForMemoryPressure()

                    var erroredThisUrl = false
                    var cancelledWhileQueued = false
                    try {
                        // Hold the per-source permit across delay + fetch so same-source requests
                        // run serially spaced by delayMs. Pause is re-checked after acquiring the
                        // permit (queued URLs would otherwise keep importing long after pause),
                        // but parking happens OUTSIDE it — semaphores are global, so a paused
                        // batch holding one would starve other batches on the same source.
                        val success = if (shouldThrottle) {
                            val sourceSemaphore = sourceSemaphores.computeIfAbsent(source.id) { Semaphore(1) }
                            val (baseDelay, randomRange) = getDelayForSource(source.id)
                            val delayMs = baseDelay + if (randomRange > 0) Random.nextLong(0, randomRange) else 0L
                            var result: Boolean? = null
                            var settled = false
                            while (!settled) {
                                if (!awaitResumed(batchId)) break // cancelled; result stays null
                                settled = sourceSemaphore.withPermit {
                                    when (batchStatus(batchId)) {
                                        BatchStatus.Cancelled -> true // done; result stays null
                                        // Release the permit and park outside the loop.
                                        BatchStatus.Paused -> false
                                        else -> {
                                            delay(delayMs)
                                            activeImports[url] = true
                                            updateNotification(
                                                completedCount.get(),
                                                totalCount,
                                                "Processing: ${activeImports.size} active",
                                            )
                                            // Remove when the fetch returns so the "active" count
                                            // in the notification reflects only in-progress URLs.
                                            result = try {
                                                processUrlWithSource(
                                                    url, source, addToLibrary, fetchDetails, categoryId,
                                                    fetchChapters, pendingAddIds, flushBatchSize,
                                                )
                                            } finally {
                                                activeImports.remove(url)
                                            }
                                            true
                                        }
                                    }
                                }
                            }
                            result
                        } else {
                            if (!awaitResumed(batchId)) {
                                null
                            } else {
                                activeImports[url] = true
                                updateNotification(
                                    completedCount.get(),
                                    totalCount,
                                    "Processing: ${activeImports.size} active",
                                )
                                try {
                                    processUrlWithSource(
                                        url, source, addToLibrary, fetchDetails, categoryId,
                                        fetchChapters, pendingAddIds, flushBatchSize,
                                    )
                                } finally {
                                    activeImports.remove(url)
                                }
                            }
                        }
                        when (success) {
                            true -> {
                                sourceConsecutiveFailures[source.id]?.set(0)
                                addedCount.incrementAndGet()
                            }
                            false -> {
                                skippedCount.incrementAndGet()
                                recordSkip(url, "Already in library")
                            }
                            // null: batch was cancelled while queued — nothing processed.
                            null -> {
                                cancelledWhileQueued = true
                                return@flow
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        sourceConsecutiveFailures[source.id]?.incrementAndGet()
                        logcat(LogPriority.ERROR, e) { "Error importing $url" }
                        erroredCount.incrementAndGet()
                        erroredThisUrl = true
                        recordError(url, e.message ?: "Unknown error")
                    } finally {
                        inFlightUrls.remove(url)
                        activeImports.remove(url)
                        // Cancelled-while-queued URLs were never processed; don't count them.
                        if (!cancelledWhileQueued) {
                            val done = completedCount.incrementAndGet()
                            updateNotification(done, totalCount, "Processed $done/$totalCount")

                            updateBatchProgress(
                                batchId,
                                done,
                                addedCount.get(),
                                skippedCount.get(),
                                erroredCount.get(),
                                erroredUrlsPreview = if (erroredThisUrl) synchronized(erroredUrls) { erroredUrls.take(10) } else null,
                            )
                            if (!shouldThrottle) {
                                delay(10)
                            }
                        }
                    }
                    emit(Unit)
                }
            }
            .collect()
        } catch (e: CancellationException) {
            // Non-suspending IO, safe in a cancelled coroutine; keeps the buffered log tails.
            flushErrorLog()
            flushSkipLog()
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Unexpected error during import collection for batch $batchId" }
        }

        flushErrorLog()
        flushSkipLog()

        val finalResult = ImportResult(
            added = addedCount.get(),
            skipped = skippedCount.get(),
            errored = erroredCount.get(),
            erroredUrls = erroredUrls.toList(),
            errorMessages = errorMessages.toMap(),
        )

        updateBatchProgress(
            batchId, completedCount.get(),
            addedCount.get(), skippedCount.get(), erroredCount.get(),
            erroredUrls.toList(), errorMessages.toMap(),
        )

        try {
            if (pendingAddIds.isNotEmpty()) flushPendingToLibrary(pendingAddIds)
            updateBatchStatus(batchId, BatchStatus.Completed)
            showCompletionNotification(batchId, addedCount.get(), skippedCount.get(), erroredCount.get(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Post-processing failed for batch $batchId" }
            updateBatchStatus(batchId, BatchStatus.Completed)
        }

        return finalResult
    }

    private fun updateBatchStatus(batchId: String, status: BatchStatus) {
        if (batchId.isEmpty()) return
        _sharedQueue.update { list ->
            list.map { if (it.id == batchId) it.copy(status = status) else it }
        }
        persistMeta(context, batchId, force = true)
    }

    // Flip a non-terminal batch to Paused so it stays resumable instead of stuck Running
    // with no worker behind it.
    private fun markBatchInterrupted(batchId: String) {
        if (batchId.isEmpty()) return
        hydrateBatchFromStore(batchId)
        val status = _sharedQueue.value.find { it.id == batchId }?.status ?: return
        if (status == BatchStatus.Completed || status == BatchStatus.Cancelled) return
        // interrupted = true marks this as a system stop (not a user pause), so the batch shows
        // up Paused-but-resumable on the next app start (restore never auto-resumes).
        _sharedQueue.update { list ->
            list.map { if (it.id == batchId) it.copy(status = BatchStatus.Paused, interrupted = true) else it }
        }
        persistMeta(context, batchId, force = true)
    }

    // System stopped the worker mid-run (6h foreground budget / process pressure). Keep the batch
    // Running and schedule a DELAYED worker that re-stages from the store and resumes from the
    // persisted offset, so the import auto-continues in the background without reopening the app.
    // The delay matters: an immediate worker can't get foreground either while the budget is still
    // exhausted, so it would just thrash. interrupted = true is the app-start fallback if the
    // scheduled work is lost to a hard process kill.
    private fun scheduleBackgroundResume(batchId: String) {
        if (batchId.isEmpty()) return
        _sharedQueue.update { list ->
            list.map { if (it.id == batchId) it.copy(interrupted = true) else it }
        }
        persistMeta(context, batchId, force = true)
        reenqueueIfNoWorker(
            context,
            batchId,
            initialDelayMinutes = BACKGROUND_RESUME_DELAY_MIN,
            requireNoActiveWorker = false,
        )
    }

    private fun hydrateBatchFromStore(batchId: String) {
        if (batchId.isEmpty()) return
        if (_sharedQueue.value.any { it.id == batchId }) return
        val meta = MassImportStore.loadMeta(context, batchId) ?: return
        // Preview only — the full list can be millions of lines and lives on disk.
        val urls = MassImportStore.loadUrlsPreview(context, batchId, URL_PREVIEW_LIMIT)
        val restored = meta.toBatch(urls)
        _sharedQueue.update { list -> if (list.any { it.id == batchId }) list else list + restored }
    }


    private fun startRunningUnlessPaused(batchId: String) {
        if (batchId.isEmpty()) return
        val current = _sharedQueue.value.find { it.id == batchId }?.status
        if (current == BatchStatus.Paused) return
        updateBatchStatus(batchId, BatchStatus.Running)
    }

    // null erroredUrlsPreview/errorMessages = keep what the batch already holds; defaulting to
    // empty would wipe the error detail on every per-URL progress tick.
    private fun updateBatchProgress(
        batchId: String,
        progress: Int,
        added: Int,
        skipped: Int,
        errored: Int,
        erroredUrlsPreview: List<String>? = null,
        errorMessages: Map<String, String>? = null,
    ) {
        if (batchId.isEmpty()) return
        _sharedQueue.update { list ->
            list.map {
                if (it.id == batchId) {
                    it.copy(
                        progress = progress,
                        added = added,
                        skipped = skipped,
                        errored = errored,
                        erroredUrls = erroredUrlsPreview ?: it.erroredUrls,
                        errorMessages = errorMessages ?: it.errorMessages,
                    )
                } else {
                    it
                }
            }
        }
        persistMeta(context, batchId, force = false)
    }

    private fun batchStatus(batchId: String): BatchStatus? =
        _sharedQueue.value.find { it.id == batchId }?.status

    private fun hostKey(url: String): String =
        try {
            URI(url).host?.lowercase()?.removePrefix("www.") ?: url
        } catch (e: Exception) {
            url
        }

    /**
     * Reorders the stream so URLs from the same host aren't bunched together. Holds up to
     * [maxBuffer] URLs grouped by host; when full, emits one from each host in turn, then
     * refills. Memory is capped at [maxBuffer] so big files stay safe.
     *
     * Without it, a long run of one host's URLs fills every parallel slot and they all wait
     * on that one source while the other slots do nothing.
     */
    private fun Flow<String>.interleaveByHost(maxBuffer: Int): Flow<String> {
        val upstream = this
        return flow {
            val buckets = LinkedHashMap<String, ArrayDeque<String>>()

            suspend fun emitRound() {
                val iter = buckets.entries.iterator()
                while (iter.hasNext()) {
                    val deque = iter.next().value
                    emit(deque.removeFirst())
                    if (deque.isEmpty()) iter.remove()
                }
            }

            var buffered = 0
            upstream.collect { url ->
                buckets.getOrPut(hostKey(url)) { ArrayDeque() }.addLast(url)
                buffered++
                if (buffered >= maxBuffer) {
                    emitRound()
                    buffered = buckets.values.sumOf { it.size }
                }
            }
            while (buckets.isNotEmpty()) emitRound()
        }
    }

    // Parks while the batch is Paused; returns false when it was cancelled instead of resumed.
    private suspend fun awaitResumed(batchId: String): Boolean {
        if (batchId.isEmpty()) return true
        while (true) {
            val status = batchStatus(batchId)
            if (status == BatchStatus.Cancelled) return false
            if (status != BatchStatus.Paused) return true
            delay(500L)
        }
    }

    private suspend fun processUrlWithSource(
        url: String,
        source: CatalogueSource,
        addToLibrary: Boolean,
        fetchDetails: Boolean,
        categoryId: Long,
        fetchChapters: Boolean,
        pendingAddIds: MutableList<Long>,
        flushBatchSize: Int,
    ): Boolean {
        // `false` = intentionally skipped (already in library); genuine failures must throw so
        // they're classified errored and captured for retry.
        val rawPath = massImportInteractor.extractPathFromUrl(url, massImportInteractor.getSourceBaseUrl(source), source)
        if (rawPath.isEmpty()) throw IllegalStateException("Could not extract a path from URL")

        val normalizedPath = massImportInteractor.normalizeUrl(rawPath)

        var finalUrl = normalizedPath
        if (url.startsWith("http", ignoreCase = true)) {
            // Only ResolvableSource gets a canonical-URL resolve; searching with the raw URL as a
            // query never matched anything reliably and just burned a request per import.
            val resolvedManga = runCatching {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    if (source is eu.kanade.tachiyomi.source.online.ResolvableSource &&
                        source.getUriType(url) == eu.kanade.tachiyomi.source.online.UriType.Manga) {
                        source.getManga(url)
                    } else {
                        null
                    }
                }
            }.getOrNull()
            if (resolvedManga != null) {
                try {
                    if (resolvedManga.url.isNotEmpty()) {
                        finalUrl = resolvedManga.url
                        if (!finalUrl.startsWith("/") && !finalUrl.startsWith("http")) {
                            finalUrl = "/$finalUrl"
                        }
                    }
                } catch (_: UninitializedPropertyAccessException) {
                }
            }
        }

        val existingManga = getMangaByUrlAndSourceId.await(finalUrl, source.id)
        if (existingManga != null && existingManga.favorite) {
            return false
        }

        if (!fetchDetails && !fetchChapters) {
            if (existingManga == null) {
                val placeholderManga = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                    this.url = finalUrl
                    this.title = finalUrl.substringAfterLast('/').replace("-", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    this.initialized = false
                }
                val manga = networkToLocalManga(placeholderManga.toDomainManga(source.id, source.isNovelSource()))
                if (addToLibrary) {
                    mangaRepository.update(
                        MangaUpdate(
                            id = manga.id,
                            favorite = true,
                            dateAdded = System.currentTimeMillis(),
                        ),
                    )
                    pendingAddIds.add(manga.id)
                    if (pendingAddIds.size >= flushBatchSize) {
                        flushPendingToLibrary(pendingAddIds)
                    }
                    if (categoryId > 0L) {
                        setMangaCategories.await(manga.id, listOf(categoryId))
                    }
                }
            } else if (addToLibrary && !existingManga.favorite) {
                mangaRepository.update(
                    MangaUpdate(
                        id = existingManga.id,
                        favorite = true,
                        dateAdded = System.currentTimeMillis(),
                    ),
                )
                pendingAddIds.add(existingManga.id)
                if (pendingAddIds.size >= flushBatchSize) {
                    flushPendingToLibrary(pendingAddIds)
                }
                if (categoryId > 0L) {
                    setMangaCategories.await(existingManga.id, listOf(categoryId))
                }
            }
            return true
        }

        // withTimeoutOrNull, not withTimeout: TimeoutCancellationException would be rethrown by
        // the caller as a full-job cancel; convert to a per-URL failure instead.
        val manga = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            massImportInteractor.resolveMangaUrl(url, finalUrl, source)
        } ?: throw java.io.IOException("Timed out resolving $url")

        if (addToLibrary) {
            mangaRepository.update(
                MangaUpdate(
                    id = manga.id,
                    favorite = true,
                    dateAdded = System.currentTimeMillis(),
                ),
            )
            pendingAddIds.add(manga.id)
            if (pendingAddIds.size >= flushBatchSize) {
                flushPendingToLibrary(pendingAddIds)
            }

            if (categoryId > 0L) {
                setMangaCategories.await(manga.id, listOf(categoryId))
            }

            if (fetchChapters) {
                try {
                    val sChapters = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                        source.getChapterList(manga.toSManga())
                    } ?: throw java.io.IOException("Timed out fetching chapters for $url")
                    syncChaptersWithSource.await(sChapters, manga.copy(favorite = true), source)
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Failed to sync chapters for $url" }
                }
            }
        }

        return true
    }

    private fun updateNotification(current: Int, total: Int, status: String) {
        val now = System.currentTimeMillis()
        val progressDelta = current - lastNotifiedProgress
        val statusChanged = status != lastNotificationStatus
        val shouldNotify = statusChanged || progressDelta >= NOTIFICATION_MIN_DELTA || (now - lastNotificationTime) >= NOTIFICATION_THROTTLE_MS

        if (shouldNotify) {
            // Create a new notification builder each time to avoid ConcurrentModificationException
            // when addAction() is called repeatedly on the same builder
            val notification = context.notificationBuilder(Notifications.CHANNEL_MASS_IMPORT) {
                setSmallIcon(android.R.drawable.stat_sys_download)
                setContentTitle(context.stringResource(TDMR.strings.mass_import_progress_title))
                setContentText(status)
                setProgress(total, current, false)
                setOngoing(true)
                setOnlyAlertOnce(true)
                addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.stringResource(MR.strings.action_cancel),
                    eu.kanade.tachiyomi.data.notification.NotificationReceiver.cancelMassImportPendingBroadcast(context),
                )
            }.build()
            context.notify(Notifications.ID_MASS_IMPORT_PROGRESS, notification)

            lastNotificationTime = now
            lastNotifiedProgress = current
            lastNotificationStatus = status
        }
    }

    private fun showCompletionNotification(batchId: String, added: Int, skipped: Int, errored: Int, message: String?) {
        val text = message ?: "Added: $added, Skipped: $skipped, Errors: $errored"

        val resultFile = writeResultFile(batchId, added, skipped, errored)

        val notificationBuilder = context.notificationBuilder(Notifications.CHANNEL_MASS_IMPORT) {
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setContentTitle(context.stringResource(TDMR.strings.mass_import_complete_title))
            setContentText(text)
            setAutoCancel(true)

            resultFile?.takeIf { it.exists() }?.let { file ->
                setContentIntent(
                    eu.kanade.tachiyomi.data.notification.NotificationReceiver.openErrorLogPendingActivity(
                        context,
                        file.getUriCompat(context),
                    ),
                )
            }
        }

        context.notify(Notifications.ID_MASS_IMPORT_COMPLETE, notificationBuilder.build())
    }

    private fun writeResultFile(batchId: String, added: Int, skipped: Int, errored: Int): File? {
        try {
            val file = context.createFileInCacheDir("tsundoku_mass_import_results.txt")
            file.bufferedWriter().use { out ->
                out.write("=== Mass Import Results ===\n")
                out.write(
                    "Time: ${java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.getDefault(),
                    ).format(java.util.Date())}\n\n",
                )
                out.write("Summary:\n")
                out.write("  Added: $added\n")
                out.write("  Skipped: $skipped\n")
                out.write("  Errors: $errored\n\n")

                // Persisted error log, not the shared queue: the in-memory list is only a
                // preview, and picking a batch by status grabs the wrong one when several run.
                val errors = if (batchId.isNotEmpty()) MassImportStore.loadErrors(context, batchId) else emptyList()
                if (errors.isNotEmpty()) {
                    out.write("=== Failed URLs (${errors.size}) ===\n")
                    errors.forEach { (url, msg) ->
                        out.write("$url\n")
                        if (msg.isNotBlank()) out.write("  Error: $msg\n")
                    }
                    out.write("\n")
                }
            }
            return file
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write mass import result file" }
            return null
        }
    }

    private fun getImportSources(): List<CatalogueSource> {
        return sourceManager.getAll().filterIsInstance<CatalogueSource>()
            .filter { it is HttpSource || it is JsSource }
    }

    private suspend fun waitForMemoryPressure() {
        val runtime = Runtime.getRuntime()

        fun pressured(): Boolean {
            val maxMem = runtime.maxMemory().coerceAtMost(MAX_HEAP_BYTES)
            val usedMem = runtime.totalMemory() - runtime.freeMemory()
            val freeMem = maxMem - usedMem
            val exceedsThreshold = usedMem.toDouble() / maxMem > MEMORY_PRESSURE_THRESHOLD
            val lowFreeMemory = freeMem < MIN_FREE_MEMORY_BYTES
            return exceedsThreshold || lowFreeMemory
        }

        if (!pressured()) return

         memoryPressureMutex.withLock {
            if (!pressured()) return
            var iteration = 0
            while (iteration < MAX_MEMORY_WAIT_ITERATIONS) {
                if (!pressured()) return
                val maxMem = runtime.maxMemory().coerceAtMost(MAX_HEAP_BYTES)
                val usedMem = runtime.totalMemory() - runtime.freeMemory()
                val usagePercent = (usedMem.toDouble() / maxMem * 100).toInt()
                logcat(LogPriority.WARN) {
                    "MassImport: Memory pressure $usagePercent%: ${usedMem / 1024 / 1024}MB / " +
                        "${maxMem / 1024 / 1024}MB, waiting ${iteration + 1}/$MAX_MEMORY_WAIT_ITERATIONS..."
                }
                delay(GC_DELAY_MS)
                iteration++
            }
            logcat(LogPriority.WARN) {
                "MassImport: heap still pressured after $MAX_MEMORY_WAIT_ITERATIONS waits; proceeding to avoid deadlock"
            }
        }
    }

    private suspend fun flushPendingToLibrary(pendingIds: MutableList<Long>) {
        val toFlush = synchronized(pendingIds) {
            if (pendingIds.isEmpty()) return
            val copy = pendingIds.toList()
            pendingIds.clear()
            copy
        }
        try {
            getLibraryManga.addToLibraryBulk(toFlush)
            logcat(LogPriority.DEBUG) { "Flushed ${toFlush.size} manga IDs to library" }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to flush ${toFlush.size} IDs to library, falling back" }
            try {
                getLibraryManga.refresh()
            } catch (inner: Exception) {
                logcat(LogPriority.ERROR, inner) { "Even refresh failed" }
            }
        }
    }

    private fun findMatchingSource(url: String, sources: List<CatalogueSource>, preferredSourceId: Long? = null): CatalogueSource? {
        val urlHost = try {
            URI(url).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
        val matchingSources = sources.filter { source ->
            try {
                val rawBase = massImportInteractor.getSourceBaseUrl(source)
                val baseForUri = if (rawBase.startsWith("http")) rawBase else "https://$rawBase"
                val baseUri = URI(baseForUri)
                val baseHost = baseUri.host?.lowercase()?.removePrefix("www.")
                val basePath = baseUri.path?.trimEnd('/')
                if (baseHost.isNullOrEmpty() || urlHost.isNullOrEmpty()) return@filter false

                val hostMatches = urlHost == baseHost ||
                    urlHost.endsWith(".$baseHost") ||
                    baseHost.endsWith(".$urlHost")
                if (!hostMatches) return@filter false

                if (!basePath.isNullOrBlank() && basePath != "/") {
                    val urlPath = URI(url).path ?: ""
                    urlPath.startsWith(basePath)
                } else {
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

        if (matchingSources.isEmpty()) {
            logcat(LogPriority.WARN) { "MassImport: No source match for $url host=$urlHost" }
        }

        if (matchingSources.isEmpty()) return null
        if (matchingSources.size == 1) return matchingSources.first()

        // If the caller has a preferred source (e.g. the currently browsed source) and it
        // matches, use it — avoids picking KT source when user is browsing via JsSource.
        if (preferredSourceId != null) {
            matchingSources.firstOrNull { it.id == preferredSourceId }?.let { return it }
        }

        val enabledLanguages = sourcePreferences.enabledLanguages.get()
        val disabledSources = sourcePreferences.disabledSources.get()
        val enabledSources = matchingSources.filter {
            it.lang in enabledLanguages && it.id.toString() !in disabledSources
        }
        val bestLangSources = if (enabledSources.isNotEmpty()) enabledSources else matchingSources

        val kotlinSources = bestLangSources.filter { it !is JsSource }

        return kotlinSources.firstOrNull() ?: bestLangSources.first()
    }

    data class ImportResult(
        val added: Int = 0,
        val skipped: Int = 0,
        val errored: Int = 0,
        val erroredUrls: List<String> = emptyList(),
        val errorMessages: Map<String, String> = emptyMap(),
    )

    data class Batch(
        val id: String,
        val urls: List<String>,
        val sourceUrls: List<String> = urls,
        val categoryId: Long,
        val addToLibrary: Boolean,
        val fetchChapters: Boolean,
        val fetchDetails: Boolean = true,
        val status: BatchStatus = BatchStatus.Pending,
        val progress: Int = 0,
        val total: Int = 0,
        val added: Int = 0,
        val skipped: Int = 0,
        val errored: Int = 0,
        val erroredUrls: List<String> = emptyList(),
        val errorMessages: Map<String, String> = emptyMap(),
        val preferredSourceId: Long? = null,
        val excludedHosts: List<String> = emptyList(),
        // System stop (time-limit / process kill), not a user pause: shows up Paused-but-resumable
        // on app start (never auto-resumed).
        val interrupted: Boolean = false,
    )

    enum class BatchStatus {
        Pending,
        Running,
        Completed,
        Cancelled,
        Paused
    }

    companion object {
        private const val TAG = "MassImportJob"
        const val KEY_URLS = "urls"
        const val KEY_URLS_FILE = "urlsFile"
        const val KEY_RAW_TEXT_FILE = "rawTextFile"
        const val KEY_CATEGORY_ID = "categoryId"
        const val KEY_ADD_TO_LIBRARY = "addToLibrary"
        const val KEY_FETCH_DETAILS = "fetchDetails"
        const val KEY_FETCH_CHAPTERS = "fetchChapters"
        const val KEY_BATCH_ID = "batchId"
        const val KEY_EXCLUDED_HOSTS = "excludedHosts"
        const val KEY_PREFERRED_SOURCE_ID = "preferredSourceId"
        // Resume cursor: how many leading URLs to skip because they were already done
        // before the pause.
        const val KEY_RESUME_OFFSET = "resumeOffset"

        // Number of URLs retained in the live queue for display; full list stays on disk.
        private const val URL_PREVIEW_LIMIT = 100

        // Delay before a system-stopped worker re-runs in the background. Long enough to let the
        // 6h foreground budget free up; short enough to resume promptly once it does.
        private const val BACKGROUND_RESUME_DELAY_MIN = 20L

        // Max times the worker re-tries when the foreground start is refused before it parks the
        // batch instead of looping. Stops a job that can never start a foreground service (denied
        // at cold start) from re-running every launch and jamming splash.
        private const val MAX_FOREGROUND_START_RETRIES = 3

        private val _sharedQueue = MutableStateFlow<List<Batch>>(emptyList())
        val sharedQueue = _sharedQueue.asStateFlow()

        private val persistScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
        )

        // Meta saves must land on disk in submission order, or a progress save (Running) racing
        // a pause save restores the wrong status after restart. Single lane = FIFO.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val metaPersistScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1) + kotlinx.coroutines.SupervisorJob(),
        )

        private val lastMetaWrite = ConcurrentHashMap<String, Long>()
        private const val META_WRITE_THROTTLE_MS = 2000L

        private val globalSourceSemaphores = ConcurrentHashMap<Long, Semaphore>()

        // Live error lists of running workers, so mid-run copy/export sees more than the
        // 10-entry batch preview plus the not-yet-flushed disk log.
        private val liveErroredUrls = ConcurrentHashMap<String, MutableList<String>>()

        // Meta keeps only an error preview — the full error log lives in the store's per-batch
        // errors file, so the json stays small no matter how many URLs fail.
        private const val META_ERROR_PREVIEW = 100

        private fun Batch.toMeta() = MassImportStore.PersistedMeta(
            id = id,
            status = status.name,
            progress = progress,
            total = total,
            added = added,
            skipped = skipped,
            errored = errored,
            erroredUrls = erroredUrls.take(META_ERROR_PREVIEW),
            errorMessages = erroredUrls.take(META_ERROR_PREVIEW)
                .mapNotNull { url -> errorMessages[url]?.let { url to it } }
                .toMap(),
            categoryId = categoryId,
            addToLibrary = addToLibrary,
            fetchDetails = fetchDetails,
            fetchChapters = fetchChapters,
            preferredSourceId = preferredSourceId,
            excludedHosts = excludedHosts,
            interrupted = interrupted,
        )

        private fun MassImportStore.PersistedMeta.toBatch(urls: List<String>): Batch {
            val parsedStatus = runCatching { BatchStatus.valueOf(status) }.getOrDefault(BatchStatus.Pending)
            return Batch(
                id = id,
                urls = urls.take(100),
                // Preview only; full list stays on disk (see start()).
                sourceUrls = urls.take(100),
                categoryId = categoryId,
                addToLibrary = addToLibrary,
                fetchChapters = fetchChapters,
                fetchDetails = fetchDetails,
                status = parsedStatus,
                progress = progress,
                total = total,
                added = added,
                skipped = skipped,
                errored = errored,
                erroredUrls = erroredUrls,
                errorMessages = errorMessages,
                preferredSourceId = preferredSourceId,
                excludedHosts = excludedHosts,
                interrupted = interrupted,
            )
        }

        private fun persistMeta(context: Context, batchId: String, force: Boolean = true) {
            if (batchId.isEmpty()) return
            if (!force) {
                val now = System.currentTimeMillis()
                val last = lastMetaWrite[batchId] ?: 0L
                if (now - last < META_WRITE_THROTTLE_MS) return
                lastMetaWrite[batchId] = now
            } else {
                lastMetaWrite[batchId] = System.currentTimeMillis()
            }
            val meta = _sharedQueue.value.find { it.id == batchId }?.toMeta() ?: return
            val appContext = context.applicationContext
            metaPersistScope.launch { MassImportStore.saveMeta(appContext, meta) }
        }

        // Lazy, no dedup: persisted count and the worker's progress total must agree, so both
        // tokenize identically. Never materializes the full list.
        private fun rawTextTokenSequence(rawText: String): Sequence<String> {
            return rawText.lineSequence()
                .flatMap { line ->
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) {
                        emptySequence()
                    } else if (trimmed.contains(',') || trimmed.contains(';')) {
                        trimmed.split(',', ';').asSequence().map { it.trim() }.filter { it.isNotBlank() }
                    } else {
                        sequenceOf(trimmed)
                    }
                }
        }

        fun restoreActiveJobsFromWorkManager(context: Context) {
            val workInfos = runCatching {
                context.workManager.getWorkInfosByTag(TAG).get()
            }.getOrDefault(emptyList())

            val wmStateById = HashMap<String, WorkInfo.State>()
            for (info in workInfos) {
                val id = info.tags.firstOrNull { it.startsWith("batch_") }?.removePrefix("batch_") ?: continue
                wmStateById[id] = info.state
            }

            val persisted = MassImportStore.loadAll(context)

            // IDs of restored, unfinished batches whose live WorkManager entry must be cancelled so
            // it cannot resume on its own during this cold start.
            val toCancel = mutableSetOf<String>()

            _sharedQueue.update { current ->
                val existingIds = current.map { it.id }.toMutableSet()
                val additions = mutableListOf<Batch>()

                for (meta in persisted) {
                    if (meta.id in existingIds) continue
                    // Preview only; the full list could be millions of lines per batch.
                    val urls = MassImportStore.loadUrlsPreview(context, meta.id, URL_PREVIEW_LIMIT)
                    var batch = meta.toBatch(urls)
                    val terminal = batch.status == BatchStatus.Completed || batch.status == BatchStatus.Cancelled
                    if (!terminal) {
                        // Never auto-resume on app start: an unfinished batch comes back Paused so
                        // the user resumes it explicitly. interrupted = true marks it resumable
                        // (system stop, not a user pause) without re-enqueueing a worker here.
                        val finishedAll = batch.total > 0 && batch.progress >= batch.total
                        if (!finishedAll) toCancel += meta.id
                        batch = batch.copy(
                            status = if (finishedAll) BatchStatus.Completed else BatchStatus.Paused,
                            interrupted = if (finishedAll) batch.interrupted else true,
                        )
                    }
                    additions += batch
                    existingIds += meta.id
                }

                for ((id, state) in wmStateById) {
                    if (id in existingIds) continue
                    if (state == WorkInfo.State.SUCCEEDED ||
                        state == WorkInfo.State.FAILED ||
                        state == WorkInfo.State.CANCELLED
                    ) {
                        continue
                    }
                    // Live worker with no persisted meta: park it Paused too and cancel it.
                    toCancel += id
                    additions += Batch(
                        id = id,
                        urls = emptyList(),
                        categoryId = 0L,
                        addToLibrary = true,
                        fetchChapters = false,
                        status = BatchStatus.Paused,
                        interrupted = true,
                    )
                    existingIds += id
                }

                current + additions
            }

            // Stop any worker WorkManager rescheduled after process death. doWork bails on a Paused
            // status, but cancelling removes the WorkSpec so it can't fire during splash at all.
            for (id in toCancel) {
                context.workManager.cancelUniqueWork("${TAG}_$id")
            }
            toCancel.forEach { persistMeta(context, it, force = true) }
        }

        fun isRunning(context: Context): Boolean {
            val workInfos = context.workManager.getWorkInfosByTag(TAG).get()
            return workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }

        private fun enqueueBatchWorker(
            context: Context,
            batchId: String,
            payload: List<Pair<String, Any?>>,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
            initialDelayMinutes: Long = 0,
        ) {
            val workRequest = OneTimeWorkRequestBuilder<MassImportJob>()
                .addTag(TAG)
                .addTag("batch_$batchId")
                // Deliberately NOT expedited: an expedited job runs as an exempted foreground job
                // immediately, and WorkManager persists that flag. After a process kill the system
                // re-runs it during the next cold start, where the SystemJobService bind times out
                // under splash contention and JobScheduler flags the app buggy ("Exempted app
                // considered buggy"). Normal deferrable work still promotes to foreground via
                // setForeground() inside doWork (same as the downloader) without the boot-time
                // exempted scheduling.
                // Foreground-budget exhaustion returns Result.retry(); back off so the worker
                // periodically re-attempts and auto-resumes once the rolling 24h window frees.
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .apply { if (initialDelayMinutes > 0) setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES) }
                .setInputData(workDataOf(*payload.toTypedArray()))
                .build()
            context.workManager.enqueueUniqueWork(
                "${TAG}_$batchId",
                policy,
                workRequest,
            )
        }

        fun start(
            context: Context,
            urls: List<String>,
            categoryId: Long = 0L,
            addToLibrary: Boolean = true,
            fetchDetails: Boolean = true,
            fetchChapters: Boolean = false,
            excludedHosts: List<String> = emptyList(),
            rawText: String? = null,
            preferredSourceId: Long? = null,
        ) {
            val batchId = java.util.UUID.randomUUID().toString()
            // WorkManager Data throws past ~10KB; the byte threshold must stay well under it.
            val offloadToFile = rawText != null || urls.size > 50 || urls.sumOf { it.length + 4 } > 8_000
            val sourceUrls = if (rawText != null) emptyList() else urls
            val batchUrlsForQueue = when {
                rawText != null -> emptyList()
                offloadToFile -> sourceUrls.take(URL_PREVIEW_LIMIT)
                else -> urls
            }
            val batchTotal = if (rawText != null) 0 else sourceUrls.size
            val normalizedExcludedHosts = excludedHosts
                .map { it.trim().lowercase().removePrefix("www.") }
                .filter { it.isNotBlank() }
            val batch = Batch(
                id = batchId,
                urls = batchUrlsForQueue,
                // Preview only; the full list lives on disk (MassImportStore) and would
                // otherwise pin megabytes per batch forever.
                sourceUrls = batchUrlsForQueue,
                categoryId = categoryId,
                addToLibrary = addToLibrary,
                fetchChapters = fetchChapters,
                fetchDetails = fetchDetails,
                total = batchTotal,
                preferredSourceId = preferredSourceId,
                excludedHosts = normalizedExcludedHosts,
            )

            _sharedQueue.update { it + batch }

            val appContext = context.applicationContext
            if (rawText != null) {
                // Single streaming pass; backfill count + preview into the queue once known.
                persistScope.launch {
                    val preview = ArrayList<String>(URL_PREVIEW_LIMIT)
                    val count = MassImportStore.saveUrlsStreaming(
                        appContext,
                        batchId,
                        rawTextTokenSequence(rawText).onEach {
                            if (preview.size < URL_PREVIEW_LIMIT) preview.add(it)
                        },
                    )
                    // count < 0 = store write failed; the worker backfills the real total.
                    _sharedQueue.update { list ->
                        list.map {
                            if (it.id == batchId) {
                                it.copy(total = count.coerceAtLeast(0), urls = preview, sourceUrls = preview)
                            } else {
                                it
                            }
                        }
                    }
                    persistMeta(appContext, batchId, force = true)
                }
            } else {
                persistScope.launch { MassImportStore.saveUrls(appContext, batchId, sourceUrls) }
            }
            persistMeta(context, batchId, force = true)

            val payload = mutableListOf<Pair<String, Any?>>(
                KEY_CATEGORY_ID to categoryId,
                KEY_ADD_TO_LIBRARY to addToLibrary,
                KEY_FETCH_DETAILS to fetchDetails,
                KEY_FETCH_CHAPTERS to fetchChapters,
                KEY_BATCH_ID to batchId,
            )
            if (excludedHosts.isNotEmpty()) {
                val normalized = excludedHosts.map { it.trim().lowercase().removePrefix("www.") }
                payload += KEY_EXCLUDED_HOSTS to normalized.joinToString(",")
            }
            if (preferredSourceId != null) {
                payload += KEY_PREFERRED_SOURCE_ID to preferredSourceId
            }

            if (offloadToFile) {
                val cacheFile = File(appContext.cacheDir, "mass_import_$batchId.txt")
                payload += (if (rawText != null) KEY_RAW_TEXT_FILE else KEY_URLS_FILE) to cacheFile.absolutePath
                // Stage + enqueue off-thread: a multi-MB write on the caller (often main) would
                // ANR. Enqueued only after staging; on staging failure the worker finds no file
                // and parks the batch as resumable.
                persistScope.launch {
                    runCatching {
                        cacheFile.outputStream().bufferedWriter().use { writer ->
                            if (rawText != null) {
                                writer.write(rawText)
                            } else {
                                for (u in urls) {
                                    writer.write(u)
                                    writer.newLine()
                                }
                            }
                        }
                    }.onFailure {
                        logcat(LogPriority.WARN, it) { "MassImport: failed to stage urls file for $batchId" }
                        runCatching { cacheFile.delete() }
                    }
                    enqueueBatchWorker(appContext, batchId, payload)
                }
            } else {
                payload += KEY_URLS to urls.toTypedArray()
                enqueueBatchWorker(appContext, batchId, payload)
            }
        }

        // Import from a newline-delimited URL file already on disk (e.g. picked file staged by
        // the dialog). Never materialized as one String: the file becomes the worker's cache
        // file directly and is streamed once to the store.
        fun startFromFile(
            context: Context,
            urlsFile: File,
            categoryId: Long = 0L,
            addToLibrary: Boolean = true,
            fetchDetails: Boolean = true,
            fetchChapters: Boolean = false,
            excludedHosts: List<String> = emptyList(),
            preferredSourceId: Long? = null,
        ) {
            if (!urlsFile.exists()) return
            val batchId = java.util.UUID.randomUUID().toString()
            val normalizedExcludedHosts = excludedHosts
                .map { it.trim().lowercase().removePrefix("www.") }
                .filter { it.isNotBlank() }

            val appContext = context.applicationContext

            val batch = Batch(
                id = batchId,
                urls = emptyList(),
                sourceUrls = emptyList(),
                categoryId = categoryId,
                addToLibrary = addToLibrary,
                fetchChapters = fetchChapters,
                fetchDetails = fetchDetails,
                total = 0,
                preferredSourceId = preferredSourceId,
                excludedHosts = normalizedExcludedHosts,
            )
            _sharedQueue.update { it + batch }

            // File IO off the caller thread. Store stream must finish BEFORE the worker is
            // enqueued: the worker deletes the staged file when done, so a fast import could
            // otherwise race the stream and persist an empty url list.
            persistScope.launch {
                // Canonical per-batch cache name so cleanup/cancel paths find it.
                val cacheFile = File(appContext.cacheDir, "mass_import_$batchId.txt")
                val staged = if (runCatching { urlsFile.renameTo(cacheFile) }.getOrDefault(false)) {
                    cacheFile
                } else {
                    // rename can fail across mount points; fall back to copy, keep the original
                    // only if the copy fails too.
                    runCatching {
                        urlsFile.inputStream().use { input ->
                            cacheFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        urlsFile.delete()
                        cacheFile
                    }.getOrDefault(urlsFile)
                }

                val preview = ArrayList<String>(URL_PREVIEW_LIMIT)
                val count = runCatching {
                    staged.bufferedReader().useLines { lines ->
                        val tokens = lines
                            .flatMap { line ->
                                val t = line.trim()
                                when {
                                    t.isBlank() -> emptySequence()
                                    t.contains(',') || t.contains(';') ->
                                        t.split(',', ';').asSequence().map { it.trim() }.filter { it.isNotBlank() }
                                    else -> sequenceOf(t)
                                }
                            }
                            .onEach { if (preview.size < URL_PREVIEW_LIMIT) preview.add(it) }
                        MassImportStore.saveUrlsStreaming(appContext, batchId, tokens)
                    }
                }.getOrDefault(0)
                // count < 0 = store write failed; the worker backfills the real total.
                _sharedQueue.update { list ->
                    list.map {
                        if (it.id == batchId) {
                            it.copy(total = count.coerceAtLeast(0), urls = preview, sourceUrls = preview)
                        } else {
                            it
                        }
                    }
                }
                persistMeta(appContext, batchId, force = true)

                val payload = mutableListOf<Pair<String, Any?>>(
                    KEY_CATEGORY_ID to categoryId,
                    KEY_ADD_TO_LIBRARY to addToLibrary,
                    KEY_FETCH_DETAILS to fetchDetails,
                    KEY_FETCH_CHAPTERS to fetchChapters,
                    KEY_BATCH_ID to batchId,
                    KEY_RAW_TEXT_FILE to staged.absolutePath,
                )
                if (normalizedExcludedHosts.isNotEmpty()) {
                    payload += KEY_EXCLUDED_HOSTS to normalizedExcludedHosts.joinToString(",")
                }
                if (preferredSourceId != null) {
                    payload += KEY_PREFERRED_SOURCE_ID to preferredSourceId
                }

                enqueueBatchWorker(appContext, batchId, payload)
            }
        }

        fun stop(context: Context) {
            context.workManager.cancelAllWorkByTag(TAG)
            val affected = _sharedQueue.value
                .filter {
                    it.status == BatchStatus.Pending ||
                        it.status == BatchStatus.Running ||
                        it.status == BatchStatus.Paused
                }
                .map { it.id }
            _sharedQueue.update { list ->
                list.map {
                    if (it.status == BatchStatus.Pending ||
                        it.status == BatchStatus.Running ||
                        it.status == BatchStatus.Paused
                    ) {
                        it.copy(status = BatchStatus.Cancelled)
                    } else {
                        it
                    }
                }
            }
            affected.forEach { persistMeta(context, it, force = true) }
        }

        fun cancelBatch(context: Context, batchId: String) {
            context.workManager.cancelUniqueWork("${TAG}_$batchId")
            // No live worker = no finally-block cleanup; delete the staged file here. Safe
            // alongside a worker mid-cancel — an open reader survives the unlink.
            runCatching { File(context.cacheDir, "mass_import_$batchId.txt").delete() }
            _sharedQueue.update { list ->
                list.map {
                    if (it.id == batchId &&
                        (it.status == BatchStatus.Pending || it.status == BatchStatus.Running || it.status == BatchStatus.Paused)
                    ) {
                        it.copy(status = BatchStatus.Cancelled)
                    } else {
                        it
                    }
                }
            }
            persistMeta(context, batchId, force = true)
        }

        fun pauseBatch(context: Context, batchId: String) {
            _sharedQueue.update { list ->
                list.map {
                    if (it.id == batchId && (it.status == BatchStatus.Pending || it.status == BatchStatus.Running)) {
                        // User pause: clear interrupted so it is NOT auto-resumed on app start.
                        it.copy(status = BatchStatus.Paused, interrupted = false)
                    } else {
                        it
                    }
                }
            }
            persistMeta(context, batchId, force = true)
            // Stop the worker rather than letting it idle-poll: a parked foreground worker
            // burns the Android 15 dataSync time budget (6h/day). Resume re-enqueues.
            context.workManager.cancelUniqueWork("${TAG}_$batchId")
        }

        fun resumeBatch(context: Context, batchId: String, initialDelayMinutes: Long = 0) {
            var wasPaused = false
            _sharedQueue.update { list ->
                list.map {
                    if (it.id == batchId && it.status == BatchStatus.Paused) {
                        wasPaused = true
                        it.copy(status = BatchStatus.Running, interrupted = false)
                    } else {
                        it
                    }
                }
            }
            // Resume must also recover a batch that already reads Running but has no worker actually
            // executing: a background-resume worker lost to process death, or an abortResume race,
            // leaves the status Running with nothing running. The old "only act on Paused" guard
            // made Resume a silent no-op in that state ("resume sometimes does nothing").
            val status = _sharedQueue.value.find { it.id == batchId }?.status ?: return
            if (status != BatchStatus.Running) return
            if (wasPaused) persistMeta(context, batchId, force = true)
            // wasPaused (Paused -> Running just now): pause cancelled the worker, but WorkManager
            // reports the cancellation asynchronously, so a stale entry can still read RUNNING for a
            // moment; force a fresh worker (REPLACE swaps any zombie). Already-Running recovery:
            // require no active worker so we DON'T cancel a genuinely live worker (REPLACE would make
            // it reschedule itself, looping) — enqueue only when nothing is actually executing.
            reenqueueIfNoWorker(
                context,
                batchId,
                initialDelayMinutes = initialDelayMinutes,
                requireNoActiveWorker = !wasPaused,
            )
        }

        // Enqueue a fresh worker for an existing batch, reconstructing input from the persisted
        // store, unless a worker is already running/enqueued for it. initialDelayMinutes > 0 lets
        // a dying worker schedule its own background resume; requireNoActiveWorker = false skips
        // the active-worker guard (the caller IS the soon-dead worker).
        private fun reenqueueIfNoWorker(
            context: Context,
            batchId: String,
            initialDelayMinutes: Long = 0,
            requireNoActiveWorker: Boolean = true,
        ) {
            if (batchId.isEmpty()) return
            val appContext = context.applicationContext
            persistScope.launch {
                if (requireNoActiveWorker) {
                    val active = runCatching {
                        appContext.workManager.getWorkInfosByTag("batch_$batchId").get()
                    }.getOrDefault(emptyList()).any {
                        it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.BLOCKED
                    }
                    if (active) return@launch
                }

                // Resume already flipped the status to Running; if we can't actually start a
                // worker, revert so the batch doesn't sit "Running" with nothing executing.
                fun abortResume(reason: String) {
                    logcat(LogPriority.WARN) { "MassImport: cannot resume $batchId: $reason" }
                    _sharedQueue.update { list ->
                        list.map {
                            if (it.id == batchId && it.status == BatchStatus.Running) {
                                it.copy(status = BatchStatus.Paused)
                            } else {
                                it
                            }
                        }
                    }
                    persistMeta(appContext, batchId, force = true)
                }

                val batch = _sharedQueue.value.find { it.id == batchId }
                    ?: MassImportStore.loadMeta(appContext, batchId)
                        ?.toBatch(emptyList())
                    ?: return@launch abortResume("no batch state")
                val cacheFile = File(appContext.cacheDir, "mass_import_$batchId.txt")
                // Stream store -> cache file; the url list can be huge.
                val staged = runCatching {
                    MassImportStore.openUrlsReader(appContext, batchId)?.use { reader ->
                        var any = false
                        cacheFile.bufferedWriter().use { w ->
                            reader.lineSequence().forEach { line ->
                                val t = line.trim()
                                if (t.isNotEmpty()) {
                                    w.write(t)
                                    w.newLine()
                                    any = true
                                }
                            }
                        }
                        any
                    } ?: false
                }.getOrDefault(false)
                if (!staged) {
                    runCatching { cacheFile.delete() }
                    return@launch abortResume("no persisted urls")
                }

                // Restart a bit before the saved progress, not exactly at it: URLs finish out
                // of order and the interleave buffer holds some back, so a few just past the
                // saved count may still be unfinished. Re-doing that overlap is cheap (already
                // imported ones skip instantly); skipping it could drop a URL.
                val resumeOffset = (batch.progress - (MAX_LOOKAHEAD + MAX_CONCURRENCY)).coerceAtLeast(0)
                val payload = mutableListOf<Pair<String, Any?>>(
                    KEY_CATEGORY_ID to batch.categoryId,
                    KEY_ADD_TO_LIBRARY to batch.addToLibrary,
                    KEY_FETCH_DETAILS to batch.fetchDetails,
                    KEY_FETCH_CHAPTERS to batch.fetchChapters,
                    KEY_BATCH_ID to batchId,
                    KEY_URLS_FILE to cacheFile.absolutePath,
                    KEY_RESUME_OFFSET to resumeOffset,
                )
                if (batch.excludedHosts.isNotEmpty()) {
                    payload += KEY_EXCLUDED_HOSTS to batch.excludedHosts.joinToString(",")
                }
                batch.preferredSourceId?.let { payload += KEY_PREFERRED_SOURCE_ID to it }

                // REPLACE: any stale/zombie entry for this batch is swapped for a worker that runs now.
                enqueueBatchWorker(appContext, batchId, payload, ExistingWorkPolicy.REPLACE, initialDelayMinutes)
            }
        }

        fun pauseAll(context: Context) {
            val affected = _sharedQueue.value
                .filter { it.status == BatchStatus.Pending || it.status == BatchStatus.Running }
                .map { it.id }
            _sharedQueue.update { list ->
                list.map {
                    if (it.status == BatchStatus.Pending || it.status == BatchStatus.Running) {
                        // User pause: clear interrupted so it is NOT auto-resumed on app start.
                        it.copy(status = BatchStatus.Paused, interrupted = false)
                    } else {
                        it
                    }
                }
            }
            affected.forEach {
                persistMeta(context, it, force = true)
                // See pauseBatch: don't keep workers parked on the foreground time budget.
                context.workManager.cancelUniqueWork("${TAG}_$it")
            }
        }

        fun resumeAll(context: Context) {
            // Paused batches flip to Running and force a fresh worker. Batches already reading
            // Running but with no live worker (lost background-resume / abort race) are recovered
            // too, but only if nothing is actually executing — otherwise we'd cancel a live worker.
            val pausedIds = _sharedQueue.value.filter { it.status == BatchStatus.Paused }.map { it.id }
            val runningIds = _sharedQueue.value.filter { it.status == BatchStatus.Running }.map { it.id }
            _sharedQueue.update { list ->
                list.map {
                    if (it.status == BatchStatus.Paused) {
                        it.copy(status = BatchStatus.Running, interrupted = false)
                    } else {
                        it
                    }
                }
            }
            pausedIds.forEach {
                persistMeta(context, it, force = true)
                // See resumeBatch: pauseAll's worker cancellations report asynchronously, so force
                // a fresh worker instead of skipping on a stale "active" reading.
                reenqueueIfNoWorker(context, it, requireNoActiveWorker = false)
            }
            runningIds.forEach {
                reenqueueIfNoWorker(context, it, requireNoActiveWorker = true)
            }
        }


        fun removeBatch(context: Context, batchId: String) {
            context.workManager.cancelUniqueWork("${TAG}_$batchId")
            runCatching { File(context.cacheDir, "mass_import_$batchId.txt").delete() }
            MassImportStore.delete(context, batchId)
            lastMetaWrite.remove(batchId)
            _sharedQueue.update { list ->
                list.filter { it.id != batchId }
            }
        }

        fun clearCompleted(context: Context) {
            _sharedQueue.update { list ->
                list.filter { batch ->
                    val done = batch.status == BatchStatus.Completed || batch.status == BatchStatus.Cancelled
                    if (done) {
                        runCatching { File(context.cacheDir, "mass_import_${batch.id}.txt").delete() }
                        MassImportStore.delete(context, batch.id)
                        lastMetaWrite.remove(batch.id)
                    }
                    !done
                }
            }
        }

        // From disk: the live queue only retains a preview.
        fun exportBatchUrls(context: Context, batchId: String): String {
            return MassImportStore.loadUrls(context, batchId).joinToString("\n")
        }

        // Stream store -> output without materializing the list; returns lines written.
        fun exportBatchUrlsTo(context: Context, batchId: String, output: java.io.OutputStream): Int {
            var count = 0
            MassImportStore.openUrlsReader(context, batchId)?.use { reader ->
                output.bufferedWriter().use { writer ->
                    reader.lineSequence().forEach { line ->
                        val t = line.trim()
                        if (t.isNotEmpty()) {
                            writer.write(t)
                            writer.write("\n")
                            count++
                        }
                    }
                }
            }
            return count
        }

        // Full errored-url set: persisted error log merged with the live run's tracked list
        // (the in-memory batch list alone is only a 10-entry preview).
        fun getErroredUrls(context: Context, batchId: String): List<String> {
            val merged = LinkedHashSet<String>()
            MassImportStore.loadErrors(context, batchId).forEach { (url, _) -> merged.add(url) }
            liveErroredUrls[batchId]?.let { live -> synchronized(live) { live.toList() } }
                ?.forEach { merged.add(it) }
            _sharedQueue.value.find { it.id == batchId }?.erroredUrls?.forEach { merged.add(it) }
            if (merged.isEmpty()) {
                // Batches persisted before the error log existed only have the meta preview.
                MassImportStore.loadMeta(context, batchId)?.erroredUrls?.forEach { merged.add(it) }
            }
            return merged.toList()
        }

        fun exportBatchErrorsTo(context: Context, batchId: String, output: java.io.OutputStream): Int {
            val errors = getErroredUrls(context, batchId)
            output.bufferedWriter().use { writer ->
                errors.forEach { url ->
                    writer.write(url)
                    writer.write("\n")
                }
            }
            return errors.size
        }

        fun retryFailed(context: Context, batchId: String) {
            val appContext = context.applicationContext
            val inMemory = _sharedQueue.value.find { it.id == batchId }
            persistScope.launch {
                val batch = inMemory
                    ?: MassImportStore.loadMeta(appContext, batchId)?.toBatch(emptyList())
                    ?: return@launch
                val failed = getErroredUrls(appContext, batchId)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                // A cancelled batch never reached every URL, so re-queue the errored URLs AND the
                // unprocessed tail — not just the errors. A completed batch walked the whole list,
                // so only its errors are retried.
                val includeRemaining = batch.total > 0 && batch.progress < batch.total
                if (failed.isEmpty() && !includeRemaining) return@launch

                // Stream errors + tail into a temp file so a multi-million-URL remainder never
                // materializes in memory; startFromFile streams it to the store and enqueues.
                val tmp = File(appContext.cacheDir, "mass_import_retry_${java.util.UUID.randomUUID()}.txt")
                var wrote = 0
                val ok = runCatching {
                    tmp.bufferedWriter().use { w ->
                        for (u in failed) {
                            w.write(u)
                            w.newLine()
                            wrote++
                        }
                        if (includeRemaining) {
                            // Conservative offset (matches resume): processing is out of order, so
                            // re-do the overlap rather than risk dropping an unfinished URL. URLs
                            // already imported skip instantly on the re-run.
                            val offset = (batch.progress - (MAX_LOOKAHEAD + MAX_CONCURRENCY)).coerceAtLeast(0)
                            MassImportStore.openUrlsReader(appContext, batchId)?.use { reader ->
                                var idx = 0
                                reader.lineSequence().forEach { line ->
                                    val t = line.trim()
                                    if (t.isNotEmpty()) {
                                        if (idx >= offset) {
                                            w.write(t)
                                            w.newLine()
                                            wrote++
                                        }
                                        idx++
                                    }
                                }
                            }
                        }
                    }
                }.isSuccess
                if (!ok || wrote == 0) {
                    runCatching { tmp.delete() }
                    return@launch
                }
                startFromFile(
                    context = appContext,
                    urlsFile = tmp,
                    categoryId = batch.categoryId,
                    addToLibrary = batch.addToLibrary,
                    fetchDetails = batch.fetchDetails,
                    fetchChapters = batch.fetchChapters,
                    excludedHosts = batch.excludedHosts,
                    preferredSourceId = batch.preferredSourceId,
                )
            }
        }

    }
}



