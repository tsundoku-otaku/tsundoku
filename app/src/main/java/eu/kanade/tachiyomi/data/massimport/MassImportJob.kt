package eu.kanade.tachiyomi.data.massimport

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

// Clamp at 50% to trigger GC early and avoid heap fragmentation near 512MB limit
private const val MEMORY_PRESSURE_THRESHOLD = 0.50
private const val MAX_HEAP_BYTES = 512 * 1024 * 1024L
private const val GC_DELAY_MS = 500L
private const val MIN_FREE_MEMORY_BYTES = 50 * 1024 * 1024L
// Cap the throttle wait so a permanently-pressured heap can't deadlock the import.
private const val MAX_MEMORY_WAIT_ITERATIONS = 20
// Upper bound on parallel fetches; matches the settings slider max.
private const val MAX_CONCURRENCY = 30
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

        setForegroundSafely()

        val streamFilePath = urlsFilePath ?: rawTextFilePath
        if (urls == null && streamFilePath != null) {
            val file = File(streamFilePath)
            if (!file.exists()) return Result.failure()

            val batchId = inputData.getString(KEY_BATCH_ID) ?: ""
            val categoryId = inputData.getLong(KEY_CATEGORY_ID, 0L)
            val addToLibrary = inputData.getBoolean(KEY_ADD_TO_LIBRARY, true)
            val fetchDetails = inputData.getBoolean(KEY_FETCH_DETAILS, true)
            val fetchChapters = inputData.getBoolean(KEY_FETCH_CHAPTERS, false)

            try {
                // Cheap streaming pass for the progress denominator — never materializes the
                // tokens (a 2M-line file would OOM otherwise).
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

                // Cold flow re-reads the file line-by-line on collection; O(1) memory, no dedup
                // (duplicates are skipped downstream by the already-favorite check).
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
                )

                return Result.success()
            } finally {
                context.cancelNotification(Notifications.ID_MASS_IMPORT_PROGRESS)
                runCatching { File(streamFilePath).delete() }
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
                )
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    Result.success()
                } else {
                    logcat(LogPriority.ERROR, e)
                    Result.failure()
                }
            } finally {
                context.cancelNotification(Notifications.ID_MASS_IMPORT_PROGRESS)
                inputData.getString(KEY_URLS_FILE)?.let { path -> runCatching { File(path).delete() } }
                inputData.getString(KEY_RAW_TEXT_FILE)?.let { path -> runCatching { File(path).delete() } }
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
    ): ImportResult {
        hydrateBatchFromStore(batchId)
        startRunningUnlessPaused(batchId)

        val importSources = getImportSources()
        if (importSources.isEmpty()) {
            showCompletionNotification(0, 0, totalCount, "No compatible sources installed")
            updateBatchStatus(batchId, BatchStatus.Completed)
            return ImportResult(added = 0, skipped = 0, errored = totalCount)
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

        // Stream tokens instead of materializing them: a multi-million-URL file would otherwise
        // build huge token + dedup structures and OOM the worker. Blank/non-http and no-source
        // tokens are filtered inline and counted as skipped; duplicates are caught by the
        // already-favorite check downstream, so no in-memory dedup set is needed.
        _sharedQueue.update { list ->
            list.map { if (it.id == batchId) it.copy(total = totalCount) else it }
        }

        val concurrency = if (!fetchDetails && !fetchChapters) {
            16
        } else {
            novelDownloadPreferences.parallelMassImport().get().coerceIn(1, MAX_CONCURRENCY)
        }

        val completedCount = AtomicInteger(0)
        val addedCount = AtomicInteger(0)
        val skippedCount = AtomicInteger(0)
        val erroredCount = AtomicInteger(0)
        val pendingAddIds = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val flushBatchSize = 50
        val activeImports = ConcurrentHashMap<String, Boolean>()

        val erroredUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val errorMessages = ConcurrentHashMap<String, String>()

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
            .flatMapMerge(concurrency) { url ->
                if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    return@flatMapMerge flow {
                        skippedCount.incrementAndGet()
                        val done = completedCount.incrementAndGet()
                        updateBatchProgress(batchId, done, addedCount.get(), skippedCount.get(), erroredCount.get())
                    }
                }
                val source = getCachedSource(url) ?: return@flatMapMerge flow {
                    skippedCount.incrementAndGet()
                    val done = completedCount.incrementAndGet()
                    updateBatchProgress(batchId, done, addedCount.get(), skippedCount.get(), erroredCount.get())
                }

                flow {
                    while (_sharedQueue.value.find { it.id == batchId }?.status == BatchStatus.Paused) {
                        delay(1000L)
                    }
                    if (_sharedQueue.value.find { it.id == batchId }?.status == BatchStatus.Cancelled) {
                        return@flow
                    }

                    val failures = sourceConsecutiveFailures.computeIfAbsent(source.id) { AtomicInteger(0) }.get()
                    if (maxSourceFailures > 0 && failures >= maxSourceFailures) {
                        erroredCount.incrementAndGet()
                        synchronized(erroredUrls) {
                            if (erroredUrls.size < MAX_TRACKED_ERRORS) {
                                erroredUrls.add(url)
                                errorMessages[url] = "Source skipped: $failures consecutive failures"
                            }
                        }
                        val done = completedCount.incrementAndGet()
                        updateBatchProgress(batchId, done, addedCount.get(), skippedCount.get(), erroredCount.get(), erroredUrls = synchronized(erroredUrls) { erroredUrls.take(10) })
                        return@flow
                    }

                    waitForMemoryPressure()

                    activeImports[url] = true
                    updateNotification(
                        completedCount.get(),
                        totalCount,
                        "Processing: ${activeImports.size} active",
                    )

                    try {
                        // Hold the per-source permit across delay + fetch so same-source
                        // requests run serially spaced by delayMs, not just spaced at start.
                        // Different sources keep their own permit and stay parallel.
                        val success = if (shouldThrottle) {
                            val sourceSemaphore = sourceSemaphores.computeIfAbsent(source.id) { Semaphore(1) }
                            val (baseDelay, randomRange) = getDelayForSource(source.id)
                            val delayMs = baseDelay + if (randomRange > 0) Random.nextLong(0, randomRange) else 0L
                            sourceSemaphore.withPermit {
                                delay(delayMs)
                                processUrlWithSource(
                                    url, source, addToLibrary, fetchDetails, categoryId,
                                    fetchChapters, pendingAddIds, flushBatchSize,
                                )
                            }
                        } else {
                            processUrlWithSource(
                                url, source, addToLibrary, fetchDetails, categoryId,
                                fetchChapters, pendingAddIds, flushBatchSize,
                            )
                        }
                        if (success) {
                            sourceConsecutiveFailures[source.id]?.set(0)
                            addedCount.incrementAndGet()
                        } else {
                            skippedCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        sourceConsecutiveFailures[source.id]?.incrementAndGet()
                        logcat(LogPriority.ERROR, e) { "Error importing $url" }
                        erroredCount.incrementAndGet()
                        // Cap the tracked detail to bound memory: a mostly-failing huge import
                        // would otherwise pin one String per URL (plus a map entry) in the
                        // worker and in the persisted/serialized batch meta.
                        synchronized(erroredUrls) {
                            if (erroredUrls.size < MAX_TRACKED_ERRORS) {
                                erroredUrls.add(url)
                                errorMessages[url] = e.message ?: "Unknown error"
                            }
                        }
                    } finally {
                        activeImports.remove(url)
                        val done = completedCount.incrementAndGet()
                        updateNotification(done, totalCount, "Processed $done/$totalCount")

                        updateBatchProgress(
                            batchId,
                            done,
                            addedCount.get(),
                            skippedCount.get(),
                            erroredCount.get(),
                            erroredUrls = synchronized(erroredUrls) { erroredUrls.take(10) },
                        )
                        if (!shouldThrottle) {
                            delay(10)
                        }
                    }
                    emit(Unit)
                }
            }
            .collect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Unexpected error during import collection for batch $batchId" }
        }

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
            showCompletionNotification(addedCount.get(), skippedCount.get(), erroredCount.get(), null)
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

    private fun hydrateBatchFromStore(batchId: String) {
        if (batchId.isEmpty()) return
        if (_sharedQueue.value.any { it.id == batchId }) return
        val meta = MassImportStore.loadMeta(context, batchId) ?: return
        val urls = MassImportStore.loadUrls(context, batchId)
        val restored = meta.toBatch(urls)
        _sharedQueue.update { list -> if (list.any { it.id == batchId }) list else list + restored }
    }


    private fun startRunningUnlessPaused(batchId: String) {
        if (batchId.isEmpty()) return
        val current = _sharedQueue.value.find { it.id == batchId }?.status
        if (current == BatchStatus.Paused) return
        updateBatchStatus(batchId, BatchStatus.Running)
    }

    private fun updateBatchProgress(
        batchId: String,
        progress: Int,
        added: Int,
        skipped: Int,
        errored: Int,
        erroredUrls: List<String> = emptyList(),
        errorMessages: Map<String, String> = emptyMap(),
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
                        erroredUrls = erroredUrls,
                        errorMessages = errorMessages,
                    )
                } else {
                    it
                }
            }
        }
        persistMeta(context, batchId, force = false)
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
        // A `false` return means "intentionally skipped" (already in library); a genuine failure
        // must throw so it's classified errored and captured for retry, not silently skipped.
        val rawPath = massImportInteractor.extractPathFromUrl(url, massImportInteractor.getSourceBaseUrl(source), source)
        if (rawPath.isEmpty()) throw IllegalStateException("Could not extract a path from URL")

        val normalizedPath = massImportInteractor.normalizeUrl(rawPath)

        var finalUrl = normalizedPath
        if (url.startsWith("http", ignoreCase = true)) {
            val resolvedManga = runCatching {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    if (source is eu.kanade.tachiyomi.source.online.ResolvableSource &&
                        source.getUriType(url) == eu.kanade.tachiyomi.source.online.UriType.Manga) {
                        source.getManga(url)
                    } else if (source is HttpSource) {
                        source.getSearchManga(1, url, eu.kanade.tachiyomi.source.model.FilterList()).mangas.firstOrNull()
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

        // withTimeoutOrNull (not withTimeout): a raw TimeoutCancellationException is a
        // CancellationException, which the caller rethrows as a full-job cancel. Convert it to a
        // normal failure so only this URL is marked errored.
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

    private fun showCompletionNotification(added: Int, skipped: Int, errored: Int, message: String?) {
        val text = message ?: "Added: $added, Skipped: $skipped, Errors: $errored"

        val resultFile = writeResultFile(added, skipped, errored)

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

    private fun writeResultFile(added: Int, skipped: Int, errored: Int): File? {
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

                val currentBatch = _sharedQueue.value.lastOrNull {
                    it.status == BatchStatus.Completed ||
                        it.status == BatchStatus.Running
                }

                if (currentBatch != null) {
                    if (currentBatch.erroredUrls.isNotEmpty()) {
                        out.write("=== Failed URLs (${currentBatch.erroredUrls.size}) ===\n")
                        currentBatch.erroredUrls.forEach { url ->
                            out.write("$url\n")
                            val msg = currentBatch.errorMessages[url]
                            if (!msg.isNullOrBlank()) out.write("  Error: $msg\n")
                        }
                        out.write("\n")
                    }

                    out.write("=== All Input URLs (${currentBatch.urls.size}) ===\n")
                    currentBatch.urls.forEach { url ->
                        out.write("$url\n")
                    }
                }
            }
            return file
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write mass import result file" }
            return null
        }
    }

    private fun getImportSources(): List<CatalogueSource> {
        return sourceManager.getCatalogueSources()
            .filter { it is HttpSource || it is JsSource }
    }

    private suspend fun waitForMemoryPressure() {
        val runtime = Runtime.getRuntime()
        // Actually block new fetches until the heap drops below threshold, instead of GC-ing
        // once and proceeding regardless: a single GC rarely reclaims enough, so the old code
        // let work keep piling on at 99% heap until the next allocation OOM'd. Bounded so a
        // heap that never recovers can't stall the import forever.
        var iteration = 0
        while (iteration < MAX_MEMORY_WAIT_ITERATIONS) {
            val maxMem = runtime.maxMemory().coerceAtMost(MAX_HEAP_BYTES)
            val usedMem = runtime.totalMemory() - runtime.freeMemory()
            val freeMem = maxMem - usedMem

            val exceedsThreshold = usedMem.toDouble() / maxMem > MEMORY_PRESSURE_THRESHOLD
            val lowFreeMemory = freeMem < MIN_FREE_MEMORY_BYTES
            if (!exceedsThreshold && !lowFreeMemory) return

            val usagePercent = (usedMem.toDouble() / maxMem * 100).toInt()
            val reason = if (exceedsThreshold) "threshold" else "low free"
            logcat(LogPriority.WARN) {
                "MassImport: Memory pressure $usagePercent% ($reason): ${usedMem / 1024 / 1024}MB / " +
                    "${maxMem / 1024 / 1024}MB, waiting ${iteration + 1}/$MAX_MEMORY_WAIT_ITERATIONS..."
            }
            try {
                System.gc()
            } catch (_: Throwable) {
            }
            delay(GC_DELAY_MS)
            iteration++
        }
        logcat(LogPriority.WARN) {
            "MassImport: heap still pressured after ${MAX_MEMORY_WAIT_ITERATIONS} waits; proceeding to avoid deadlock"
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

        // Number of URLs retained in the live queue for display; full list stays on disk.
        private const val URL_PREVIEW_LIMIT = 100

        private val _sharedQueue = MutableStateFlow<List<Batch>>(emptyList())
        val sharedQueue = _sharedQueue.asStateFlow()

        private val persistScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
        )

        private val lastMetaWrite = ConcurrentHashMap<String, Long>()
        private const val META_WRITE_THROTTLE_MS = 2000L

        private val globalSourceSemaphores = ConcurrentHashMap<Long, Semaphore>()

        private fun Batch.toMeta() = MassImportStore.PersistedMeta(
            id = id,
            status = status.name,
            progress = progress,
            total = total,
            added = added,
            skipped = skipped,
            errored = errored,
            erroredUrls = erroredUrls,
            errorMessages = errorMessages,
            categoryId = categoryId,
            addToLibrary = addToLibrary,
            fetchDetails = fetchDetails,
            fetchChapters = fetchChapters,
            preferredSourceId = preferredSourceId,
            excludedHosts = excludedHosts,
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
            persistScope.launch { MassImportStore.saveMeta(appContext, meta) }
        }

        // Lazily tokenizes rawText. No dedup: the worker counts tokens the same way for the
        // progress total, so persisted count and worker total must agree (duplicates are skipped
        // downstream by the already-favorite check). Never materializes the full list.
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

            _sharedQueue.update { current ->
                val existingIds = current.map { it.id }.toMutableSet()
                val additions = mutableListOf<Batch>()

                for (meta in persisted) {
                    if (meta.id in existingIds) continue
                    val urls = MassImportStore.loadUrls(context, meta.id)
                    var batch = meta.toBatch(urls)
                    val wmState = wmStateById[meta.id]
                    val wmActive = wmState == WorkInfo.State.RUNNING ||
                        wmState == WorkInfo.State.ENQUEUED ||
                        wmState == WorkInfo.State.BLOCKED
                    val terminal = batch.status == BatchStatus.Completed || batch.status == BatchStatus.Cancelled
                    if (!terminal && !wmActive) {
                        // Worker died (e.g. process killed) with no live WorkManager entry. If it
                        // had finished all URLs treat it as done; otherwise leave it Paused so the
                        // user can resume — resumeBatch() re-enqueues a fresh worker.
                        val finishedAll = batch.total > 0 && batch.progress >= batch.total
                        batch = batch.copy(
                            status = if (finishedAll) BatchStatus.Completed else BatchStatus.Paused,
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
                    additions += Batch(
                        id = id,
                        urls = emptyList(),
                        categoryId = 0L,
                        addToLibrary = true,
                        fetchChapters = false,
                        status = if (state == WorkInfo.State.RUNNING) BatchStatus.Running else BatchStatus.Pending,
                    )
                    existingIds += id
                }

                current + additions
            }
        }

        fun isRunning(context: Context): Boolean {
            val workInfos = context.workManager.getWorkInfosByTag(TAG).get()
            return workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
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
            val offloadToFile = rawText != null || urls.size > 50 || urls.sumOf { it.length } > 500_000
            // For rawText the token list is built lazily off-thread (see below) so the full
            // multi-MB list never lives in the UI process; the urls path already has the list.
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
                // Keep only a preview in the live queue; the full list lives on disk
                // (MassImportStore) and would otherwise pin megabytes per batch forever.
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
                // Tokenize + dedupe + persist in a single streaming pass; backfill the real
                // count and preview into the queue once known.
                persistScope.launch {
                    val preview = ArrayList<String>(URL_PREVIEW_LIMIT)
                    val count = MassImportStore.saveUrlsStreaming(
                        appContext,
                        batchId,
                        rawTextTokenSequence(rawText).onEach {
                            if (preview.size < URL_PREVIEW_LIMIT) preview.add(it)
                        },
                    )
                    _sharedQueue.update { list ->
                        list.map {
                            if (it.id == batchId) it.copy(total = count, urls = preview, sourceUrls = preview) else it
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

            if (offloadToFile) {
                val cacheFile = File(context.cacheDir, "mass_import_$batchId.txt")
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
                payload += (if (rawText != null) KEY_RAW_TEXT_FILE else KEY_URLS_FILE) to cacheFile.absolutePath
            } else {
                payload += KEY_URLS to urls.toTypedArray()
            }
            if (excludedHosts.isNotEmpty()) {
                val normalized = excludedHosts.map { it.trim().lowercase().removePrefix("www.") }
                payload += KEY_EXCLUDED_HOSTS to normalized.joinToString(",")
            }
            if (preferredSourceId != null) {
                payload += KEY_PREFERRED_SOURCE_ID to preferredSourceId
            }

            val workRequest = OneTimeWorkRequestBuilder<MassImportJob>()
                .addTag(TAG)
                .addTag("batch_$batchId")
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(*payload.toTypedArray()))
                .build()

            context.workManager.enqueueUniqueWork(
                "${TAG}_$batchId",
                ExistingWorkPolicy.KEEP,
                workRequest,
            )
        }

        // Start an import from a newline-delimited URL file already on disk (e.g. a picked file
        // streamed to cache by the dialog). The file content is never materialized as one String:
        // it becomes the worker's cache file directly, and is streamed once to the store for the
        // queue preview/export. Tokenization + dedup happen lazily in the worker.
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
            // Use the canonical per-batch cache name so existing cleanup/cancel paths find it.
            val cacheFile = File(appContext.cacheDir, "mass_import_$batchId.txt")
            val staged = if (runCatching { urlsFile.renameTo(cacheFile) }.getOrDefault(false)) {
                cacheFile
            } else {
                // rename can fail across mount points; copy so cleanup/cancel paths still find
                // the canonical name. Keep the original only if the copy fails too.
                runCatching {
                    urlsFile.inputStream().use { input ->
                        cacheFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    urlsFile.delete()
                    cacheFile
                }.getOrDefault(urlsFile)
            }

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

            // Stream the file to the store BEFORE enqueueing the worker: the worker deletes the
            // staged file when it finishes, so a fast import could otherwise race the stream and
            // persist an empty url list (and backfill total = 0).
            persistScope.launch {
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
                _sharedQueue.update { list ->
                    list.map {
                        if (it.id == batchId) it.copy(total = count, urls = preview, sourceUrls = preview) else it
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

                val workRequest = OneTimeWorkRequestBuilder<MassImportJob>()
                    .addTag(TAG)
                    .addTag("batch_$batchId")
                    .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(workDataOf(*payload.toTypedArray()))
                    .build()

                appContext.workManager.enqueueUniqueWork(
                    "${TAG}_$batchId",
                    ExistingWorkPolicy.KEEP,
                    workRequest,
                )
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
                        it.copy(status = BatchStatus.Paused)
                    } else {
                        it
                    }
                }
            }
            persistMeta(context, batchId, force = true)
        }

        fun resumeBatch(context: Context, batchId: String) {
            _sharedQueue.update { list ->
                list.map {
                    if (it.id == batchId && it.status == BatchStatus.Paused) {
                        it.copy(status = BatchStatus.Running)
                    } else {
                        it
                    }
                }
            }
            persistMeta(context, batchId, force = true)
            // A live worker parked in the pause-loop picks up the status flip on its own. But after
            // a process restart that worker is dead (WorkManager FAILED/removed), so flipping the
            // status alone leaves the batch "Running" with nothing executing. Re-enqueue a worker
            // when none is active.
            reenqueueIfNoWorker(context, batchId)
        }

        // Enqueue a fresh worker for an existing batch, reconstructing input from the persisted
        // store, unless a worker is already running/enqueued for it.
        private fun reenqueueIfNoWorker(context: Context, batchId: String) {
            if (batchId.isEmpty()) return
            val appContext = context.applicationContext
            persistScope.launch {
                val active = runCatching {
                    appContext.workManager.getWorkInfosByTag("batch_$batchId").get()
                }.getOrDefault(emptyList()).any {
                    it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
                }
                if (active) return@launch

                val batch = _sharedQueue.value.find { it.id == batchId }
                    ?: MassImportStore.loadMeta(appContext, batchId)
                        ?.toBatch(emptyList())
                    ?: return@launch
                val urls = MassImportStore.loadUrls(appContext, batchId)
                if (urls.isEmpty()) return@launch

                val cacheFile = File(appContext.cacheDir, "mass_import_$batchId.txt")
                runCatching {
                    cacheFile.bufferedWriter().use { w ->
                        for (u in urls) {
                            w.write(u)
                            w.newLine()
                        }
                    }
                }.onFailure {
                    logcat(LogPriority.WARN, it) { "MassImport: failed to stage urls for resume of $batchId" }
                    return@launch
                }

                val payload = mutableListOf<Pair<String, Any?>>(
                    KEY_CATEGORY_ID to batch.categoryId,
                    KEY_ADD_TO_LIBRARY to batch.addToLibrary,
                    KEY_FETCH_DETAILS to batch.fetchDetails,
                    KEY_FETCH_CHAPTERS to batch.fetchChapters,
                    KEY_BATCH_ID to batchId,
                    KEY_URLS_FILE to cacheFile.absolutePath,
                )
                if (batch.excludedHosts.isNotEmpty()) {
                    payload += KEY_EXCLUDED_HOSTS to batch.excludedHosts.joinToString(",")
                }
                batch.preferredSourceId?.let { payload += KEY_PREFERRED_SOURCE_ID to it }

                val workRequest = OneTimeWorkRequestBuilder<MassImportJob>()
                    .addTag(TAG)
                    .addTag("batch_$batchId")
                    .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(workDataOf(*payload.toTypedArray()))
                    .build()
                // REPLACE: any stale/zombie entry for this batch is swapped for a worker that runs now.
                appContext.workManager.enqueueUniqueWork(
                    "${TAG}_$batchId",
                    ExistingWorkPolicy.REPLACE,
                    workRequest,
                )
            }
        }

        fun pauseAll(context: Context) {
            val affected = _sharedQueue.value
                .filter { it.status == BatchStatus.Pending || it.status == BatchStatus.Running }
                .map { it.id }
            _sharedQueue.update { list ->
                list.map {
                    if (it.status == BatchStatus.Pending || it.status == BatchStatus.Running) {
                        it.copy(status = BatchStatus.Paused)
                    } else {
                        it
                    }
                }
            }
            affected.forEach { persistMeta(context, it, force = true) }
        }

        fun resumeAll(context: Context) {
            val affected = _sharedQueue.value.filter { it.status == BatchStatus.Paused }.map { it.id }
            _sharedQueue.update { list ->
                list.map {
                    if (it.status == BatchStatus.Paused) {
                        it.copy(status = BatchStatus.Running)
                    } else {
                        it
                    }
                }
            }
            affected.forEach {
                persistMeta(context, it, force = true)
                reenqueueIfNoWorker(context, it)
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

        // Read the full URL list from disk rather than memory: the live queue only retains a
        // preview, so the complete set must be loaded from MassImportStore on demand.
        fun exportBatchUrls(context: Context, batchId: String): String {
            return MassImportStore.loadUrls(context, batchId).joinToString("\n")
        }


        fun retryFailed(context: Context, batchId: String) {
            val appContext = context.applicationContext
            val inMemory = _sharedQueue.value.find { it.id == batchId }
            persistScope.launch {
                val batch = inMemory
                    ?: MassImportStore.loadMeta(appContext, batchId)
                        ?.toBatch(MassImportStore.loadUrls(appContext, batchId))
                    ?: return@launch
                val failed = batch.erroredUrls.map { it.trim() }.filter { it.isNotEmpty() }
                if (failed.isEmpty()) return@launch
                start(
                    context = appContext,
                    urls = failed,
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



