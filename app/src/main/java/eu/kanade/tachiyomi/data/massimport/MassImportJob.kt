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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.download.service.NovelDownloadPreferences
import tachiyomi.domain.library.interactor.RefreshLibraryCache
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
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
private const val MAX_HEAP_BYTES = 512 * 1024 * 1024L // 512MB hard limit
private const val GC_DELAY_MS = 500L
private const val NOTIFICATION_THROTTLE_MS = 1000L
private const val NOTIFICATION_MIN_DELTA = 5

class MassImportJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val massImportInteractor: eu.kanade.domain.manga.interactor.MassImport = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get()
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get()
    private val novelDownloadPreferences: NovelDownloadPreferences = Injekt.get()
    private val mangaRepository: MangaRepository = Injekt.get()
    private val setMangaCategories: SetMangaCategories = Injekt.get()
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get()
    private val refreshLibraryCache: RefreshLibraryCache = Injekt.get()
    private val getLibraryManga: tachiyomi.domain.manga.interactor.GetLibraryManga = Injekt.get()
    private val storageManager: StorageManager = Injekt.get()
    private val sourcePreferences: eu.kanade.domain.source.service.SourcePreferences = Injekt.get()
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
        val urls = inputData.getStringArray(KEY_URLS)?.toList()
            ?: inputData.getString(KEY_URLS_FILE)?.let { path ->
                // Stream file reading instead of loading entire file into memory
                runCatching {
                    File(path).bufferedReader().use { reader ->
                        reader.lineSequence().filter { it.isNotBlank() }.toList()
                    }
                }.getOrNull()
            }
            ?: return Result.failure()
        val categoryId = inputData.getLong(KEY_CATEGORY_ID, 0L)
        val addToLibrary = inputData.getBoolean(KEY_ADD_TO_LIBRARY, true)
        val fetchDetails = inputData.getBoolean(KEY_FETCH_DETAILS, true)
        val fetchChapters = inputData.getBoolean(KEY_FETCH_CHAPTERS, false)
        val batchId = inputData.getString(KEY_BATCH_ID) ?: ""

        // Save queue as a simple text file to the user's app storage directory
        // Format: first line = category ID, rest are URLs
        val recoveryFile = try {
            val storageManager: StorageManager = Injekt.get()
            val dir = storageManager.getMassImportDirectory()
            val fileName = "queue_${batchId.ifEmpty { System.currentTimeMillis().toString() }}.txt"
            dir?.findFile(fileName)?.delete()
            dir?.createFile(fileName)?.also { file ->
                file.openOutputStream().bufferedWriter().use { writer ->
                    writer.write(categoryId.toString())
                    writer.newLine()
                    urls.forEach { url ->
                        writer.write(url)
                        writer.newLine()
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to write recovery queue file" }
            null
        }

        setForegroundSafely()

        return withIOContext {
            try {
                performImport(urls, categoryId, addToLibrary, fetchDetails, fetchChapters, batchId)
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
                // Clear recovery queue file on completion
                runCatching { recoveryFile?.delete() }
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
        urls: List<String>,
        categoryId: Long,
        addToLibrary: Boolean,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        batchId: String,
    ): ImportResult {
        updateBatchStatus(batchId, BatchStatus.Running)

        // Clear any previous import state is no longer needed; pendingAddIds is local

        val importSources = getImportSources()
        if (importSources.isEmpty()) {
            showCompletionNotification(0, 0, urls.size, "No compatible sources installed")
            updateBatchStatus(batchId, BatchStatus.Completed)
            return ImportResult(added = 0, skipped = 0, errored = urls.size)
        }

        // Cache source lookups to avoid N+1 queries
        val sourceCache = ConcurrentHashMap<String, CatalogueSource?>()
        fun getCachedSource(url: String): CatalogueSource? {
            return sourceCache.computeIfAbsent(url) { findMatchingSource(url, importSources) }
        }

        // Cache DB lookups to avoid repeated queries for same URL
        val dbCache = ConcurrentHashMap<Pair<Long, String>, Boolean>()
        suspend fun isAlreadyInLibrary(sourceId: Long, path: String): Boolean {
            val key = sourceId to path
            dbCache[key]?.let { return it }
            val value = getMangaByUrlAndSourceId.await(path, sourceId)?.favorite ?: false
            val prev = dbCache.putIfAbsent(key, value)
            return prev ?: value
        }

        // Stream URLs without materializing full list upfront
        // Validate on first pass: count valid URLs for progress tracking
        val validUrlsSequence = urls.asSequence()
            .filter { it.isNotBlank() }
            .filter { url -> url.startsWith("http://") || url.startsWith("https://") }

        // Materialize and validate URLs asynchronously to check DB per-URL
        var validCount = 0
        val validUrlsList = mutableListOf<String>()
        for (url in validUrlsSequence) {
            try {
                val source = getCachedSource(url)
                if (source != null) {
                    val path = massImportInteractor.normalizeUrl(massImportInteractor.extractPathFromUrl(url, massImportInteractor.getSourceBaseUrl(source), source))
                    if (path.isNotEmpty() && !isAlreadyInLibrary(source.id, path)) {
                        validUrlsList.add(url)
                        validCount++
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG, e) { "Error validating URL: $url" }
            }
        }
        if (validCount == 0) {
            val skippedCount = urls.size - validCount
            showCompletionNotification(0, skippedCount, 0, "All novels already in library")
            updateBatchStatus(batchId, BatchStatus.Completed)
            return ImportResult(added = 0, skipped = skippedCount, errored = 0)
        }

        // Update batch total to reflect actual work items
        _sharedQueue.update { list ->
            list.map { if (it.id == batchId) it.copy(total = validCount) else it }
        }
        val validUrls = validUrlsList

        val concurrency = if (!fetchDetails && !fetchChapters) {
            // Offline mode: cap at reasonable concurrency (16) to avoid overwhelming library updates
            // Skip throttling delays, but keep concurrency bounded to prevent OOM and library model choking
            16
        } else {
            novelDownloadPreferences.parallelMassImport().get()
        }
        val completedCount = AtomicInteger(0)
        val addedCount = AtomicInteger(0)
        val skippedCount = AtomicInteger(urls.size - validUrls.size)
        val erroredCount = AtomicInteger(0)
        // Buffer for bulk library adds - flush every 50 items to spread UI updates
        val pendingAddIds = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val flushBatchSize = 50
        val activeImports = ConcurrentHashMap<String, Boolean>()

        val skippedUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val erroredUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val errorMessages = ConcurrentHashMap<String, String>() // URL -> error message

        // Add initially skipped URLs (duplicates/invalid)
        // We don't have the list of invalid/duplicate URLs here easily as we just filtered them out.
        // But we can infer them or just ignore them for now as they are pre-filtered.
        // The dialog handles pre-filtering feedback.

        updateNotification(0, validUrls.size, "Starting import...")

        val throttlingEnabled = novelDownloadPreferences.enableMassImportThrottling().get()
        // Skip throttling if neither fetch details nor fetch chapters is enabled (dummy entries only)
        // This applies to offline queue mode where only DB check is needed
        val shouldThrottle = throttlingEnabled && (fetchDetails || fetchChapters)
        val globalBaseDelay = novelDownloadPreferences.massImportDelay().get().toLong() // Already in ms
        val globalRandomRange = novelDownloadPreferences.randomDelayRange().get().toLong() // Already in ms

        // Helper function to get delay for a source
        fun getDelayForSource(sourceId: Long): Pair<Long, Long> {
            val override = novelDownloadPreferences.getSourceOverride(sourceId)
            if (override != null && override.enabled) {
                val baseDelay = override.massImportDelay?.toLong() ?: globalBaseDelay
                val randomRange = override.randomDelayRange?.toLong() ?: globalRandomRange
                return Pair(baseDelay, randomRange)
            }
            return Pair(globalBaseDelay, globalRandomRange)
        }

        // Per-source semaphores to serialize requests to the same source
        val sourceSemaphores = ConcurrentHashMap<Long, Semaphore>()

        validUrls.asFlow()
            .flatMapMerge(concurrency) { url ->
                val source = getCachedSource(url) ?: return@flatMapMerge flow<Unit> { } // Skip if no source

                flow {
                    // Apply per-source throttling before processing
                    if (shouldThrottle) {
                        val sourceId = source.id
                        // Get or create semaphore for this source (permits = 1 for serial access)
                        val sourceSemaphore = sourceSemaphores.computeIfAbsent(sourceId) { Semaphore(1) }

                        val (baseDelay, randomRange) = getDelayForSource(sourceId)
                        val delayMs = baseDelay + if (randomRange > 0) Random.nextLong(0, randomRange) else 0L

                        // Acquire permit - this ensures only one request per source processes at a time
                        sourceSemaphore.withPermit {
                            // Check memory pressure before processing
                            waitForMemoryPressure()

                            // Process the request while holding the permit
                            activeImports[url] = true
                            updateNotification(
                                completedCount.get(),
                                validUrls.size,
                                "Processing: ${activeImports.size} active",
                            )

                            try {
                                val success =
                                    processUrlWithSource(
                                        url,
                                        source,
                                        addToLibrary,
                                        fetchDetails,
                                        categoryId,
                                        fetchChapters,
                                        pendingAddIds,
                                        flushBatchSize,
                                        dbCache,
                                    )
                                if (success) {
                                    // processUrlWithSource buffers and flushes manga ids
                                    addedCount.incrementAndGet()
                                } else {
                                    skippedCount.incrementAndGet()
                                    skippedUrls.add(url)
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR, e) { "Error importing $url" }
                                erroredCount.incrementAndGet()
                                erroredUrls.add(url)
                                errorMessages[url] = e.message ?: "Unknown error"
                            } finally {
                                activeImports.remove(url)
                                val done = completedCount.incrementAndGet()
                                updateNotification(done, validUrls.size, "Processed $done/${validUrls.size}")

                                updateBatchProgress(
                                    batchId,
                                    done,
                                    validUrls.size,
                                    addedCount.get(),
                                    skippedCount.get(),
                                    erroredCount.get(),
                                    erroredUrls.toList(),
                                    skippedUrls.toList(),
                                    errorMessages.toMap(),
                                )
                            }
                        }

                        // Delay AFTER releasing permit to avoid blocking other coroutines waiting for the semaphore
                        if (delayMs > 0) {
                            logcat(LogPriority.DEBUG) {
                                "Throttling source $sourceId: delaying ${delayMs}ms before next request"
                            }
                            delay(delayMs)
                        }
                    } else {
                        // No throttling - process normally, but still check memory
                        waitForMemoryPressure()

                        activeImports[url] = true
                        updateNotification(
                            completedCount.get(),
                            validUrls.size,
                            "Processing: ${activeImports.size} active",
                        )

                            try {
                                val success =
                                    processUrlWithSource(url, source, addToLibrary, fetchDetails, categoryId, fetchChapters, pendingAddIds, flushBatchSize, dbCache)
                            if (success) {
                                // processUrlWithSource buffers and flushes manga ids
                                addedCount.incrementAndGet()
                            } else {
                                skippedCount.incrementAndGet()
                                skippedUrls.add(url)
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "Error importing $url" }
                            erroredCount.incrementAndGet()
                            erroredUrls.add(url)
                            errorMessages[url] = e.message ?: "Unknown error"
                        } finally {
                            activeImports.remove(url)
                            val done = completedCount.incrementAndGet()
                            updateNotification(done, validUrls.size, "Processed $done/${validUrls.size}")

                            updateBatchProgress(
                                batchId,
                                done,
                                validUrls.size,
                                addedCount.get(),
                                skippedCount.get(),
                                erroredCount.get(),
                                erroredUrls.toList(),
                                skippedUrls.toList(),
                                errorMessages.toMap(),
                            )

                            // Minimum inter-item delay in offline mode (10ms) to let library updates process
                            if (!shouldThrottle) {
                                delay(10)
                            }
                        }
                    }
                    emit(Unit)
                }
            }
            .collect()

        // Update shared state for UI
        val finalResult = ImportResult(
            added = addedCount.get(),
            skipped = skippedCount.get(),
            errored = erroredCount.get(),
            skippedUrls = skippedUrls.toList(),
            erroredUrls = erroredUrls.toList(),
        )

        _sharedResult.update {
            finalResult
        }

        // Flush any remaining pending IDs to library (in case there were < flushBatchSize at end)
        if (pendingAddIds.isNotEmpty()) {
            flushPendingToLibrary(pendingAddIds)
        }

        updateBatchStatus(batchId, BatchStatus.Completed)

        showCompletionNotification(addedCount.get(), skippedCount.get(), erroredCount.get(), null)
        return finalResult
    }

    private fun updateBatchStatus(batchId: String, status: BatchStatus) {
        if (batchId.isEmpty()) return
        _sharedQueue.update { list ->
            list.map { if (it.id == batchId) it.copy(status = status) else it }
        }
    }

    private fun updateBatchProgress(
        batchId: String,
        progress: Int,
        total: Int,
        added: Int,
        skipped: Int,
        errored: Int,
        erroredUrls: List<String> = emptyList(),
        skippedUrls: List<String> = emptyList(),
        errorMessages: Map<String, String> = emptyMap(),
    ) {
        if (batchId.isEmpty()) return
        _sharedQueue.update { list ->
            list.map {
                if (it.id == batchId) {
                    it.copy(
                        progress = progress,
                        // total might be different from initial urls size due to filtering, but let's keep initial total
                        added = added,
                        skipped = skipped,
                        errored = errored,
                        erroredUrls = erroredUrls,
                        skippedUrls = skippedUrls,
                        errorMessages = errorMessages,
                    )
                } else {
                    it
                }
            }
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
        dbCache: ConcurrentHashMap<Pair<Long, String>, Boolean>,
    ): Boolean {
        val rawPath = massImportInteractor.extractPathFromUrl(url, massImportInteractor.getSourceBaseUrl(source), source)
        if (rawPath.isEmpty()) return false

        // Normalize URL before any operations
        val normalizedPath = massImportInteractor.normalizeUrl(rawPath)

        // Attempt to resolve the canonical internal URL if the user provided a full web URL.
        // This is crucial because some extensions (like Comix) map web URLs (e.g. /title/xxxxx)
        // to different internal references (e.g. /xxxxx).
        var finalUrl = normalizedPath
        if (url.startsWith("http", ignoreCase = true)) {
            val resolvedManga = runCatching {
                if (source is eu.kanade.tachiyomi.source.online.ResolvableSource && 
                    source.getUriType(url) == eu.kanade.tachiyomi.source.online.UriType.Manga) {
                    source.getManga(url)
                } else if (source is HttpSource) {
                    source.getSearchManga(1, url, eu.kanade.tachiyomi.source.model.FilterList()).mangas.firstOrNull()
                } else {
                    null
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
                } catch (e: UninitializedPropertyAccessException) {
                    // Ignore uninitialized URL
                }
            }
        }

        // Check if already exists with resolved URL
        val existingManga = getMangaByUrlAndSourceId.await(finalUrl, source.id)
        if (existingManga != null && existingManga.favorite) {
            return false
        }

        // If neither fetch details nor fetch chapters is selected,
        // create a minimal dummy entry without any network requests
        if (!fetchDetails && !fetchChapters) {
            if (existingManga == null) {
                val placeholderManga = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                    this.url = finalUrl
                    this.title = finalUrl.substringAfterLast('/').replace("-", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    this.initialized = false
                }
                val manga = networkToLocalManga(placeholderManga.toDomainManga(source.id, source.isNovelSource()))
                // Still add to library if requested
                if (addToLibrary) {
                    mangaRepository.update(
                        MangaUpdate(
                            id = manga.id,
                            favorite = true,
                            dateAdded = System.currentTimeMillis(),
                        ),
                    )
                    // Mark as present in local cache so concurrent checks know it's added
                    dbCache[source.id to finalUrl] = true
                    // Buffer for batched library updates to reduce reactive churn
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
                // Update cache to reflect new favorite state
                dbCache[source.id to finalUrl] = true
                // Buffer for batched library updates to reduce reactive churn
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

        // Fetch novel details using the shared interactor logic
        val manga = massImportInteractor.resolveMangaUrl(url, finalUrl, source)

        if (addToLibrary) {
            mangaRepository.update(
                MangaUpdate(
                    id = manga.id,
                    favorite = true,
                    dateAdded = System.currentTimeMillis(),
                ),
            )
            // Update cache so concurrent checks won't re-add
            dbCache[source.id to normalizedPath] = true

            // Buffer for batched library updates to reduce reactive churn
            pendingAddIds.add(manga.id)
            if (pendingAddIds.size >= flushBatchSize) {
                flushPendingToLibrary(pendingAddIds)
            }

            if (categoryId > 0L) {
                setMangaCategories.await(manga.id, listOf(categoryId))
            }

            if (fetchChapters) {
                try {
                    val sChapters = source.getChapterList(manga.toSManga())
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
            _sharedProgress.update {
                Progress(current, total, status)
            }
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

        // Write results to file for persistence (like LibraryUpdateJob does for errors)
        val resultFile = writeResultFile(added, skipped, errored)

        val notificationBuilder = context.notificationBuilder(Notifications.CHANNEL_MASS_IMPORT) {
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setContentTitle(context.stringResource(TDMR.strings.mass_import_complete_title))
            setContentText(text)
            setAutoCancel(true)

            // Add content intent to open the result file if it exists
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

    /**
     * Writes import results to a file for persistence.
     * This allows users to see results even if the app is killed.
     */
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

                // Get current batch info from shared queue
                val currentBatch = _sharedQueue.value.lastOrNull {
                    it.status == BatchStatus.Completed ||
                        it.status == BatchStatus.Running
                }

                if (currentBatch != null) {
                    if (currentBatch.erroredUrls.isNotEmpty()) {
                        out.write("=== Failed URLs (${currentBatch.erroredUrls.size}) ===\n")
                        currentBatch.erroredUrls.forEach { url ->
                            out.write("$url\n")
                        }
                        out.write("\n")
                    }

                    if (currentBatch.skippedUrls.isNotEmpty()) {
                        out.write("=== Skipped URLs (${currentBatch.skippedUrls.size}) ===\n")
                        currentBatch.skippedUrls.forEach { url ->
                            out.write("$url\n")
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

    /**
     * Wait if memory usage exceeds the threshold to let GC reclaim before continuing.
     * Triggers at 50% usage to maintain headroom before 512MB limit.
     * Also checks for low free memory to prevent fragmentation OOM.
     */
    private suspend fun waitForMemoryPressure() {
        val runtime = Runtime.getRuntime()
        val maxMemRaw = runtime.maxMemory()
        val maxMem = maxMemRaw.coerceAtMost(MAX_HEAP_BYTES) // Clamp to 512MB
        val usedMem = runtime.totalMemory() - runtime.freeMemory()
        val freeMem = maxMem - usedMem

        // Trigger GC if:
        // 1. Used memory exceeds 50% threshold, OR
        // 2. Free memory drops below 50MB (prevent fragmentation)
        val exceedsThreshold = usedMem.toDouble() / maxMem > MEMORY_PRESSURE_THRESHOLD
        val lowFreeMemory = freeMem < 50 * 1024 * 1024L

        if (exceedsThreshold || lowFreeMemory) {
            val usagePercent = (usedMem.toDouble() / maxMem * 100).toInt()
            val reason = if (exceedsThreshold) "threshold" else "low free"
            logcat(LogPriority.WARN) {
                "MassImport: Memory pressure $usagePercent% ($reason): ${usedMem / 1024 / 1024}MB / ${maxMem / 1024 / 1024}MB, pausing..."
            }
            try {
                System.gc()
            } catch (_: Throwable) {
            }
            delay(GC_DELAY_MS)
        }
    }

    /**
     * Flush buffered manga IDs to library in a single batch operation.
     * This reduces reactive churn compared to per-item updates.
     */
    private suspend fun flushPendingToLibrary(pendingIds: MutableList<Long>) {
        if (pendingIds.isEmpty()) return

        val toFlush = pendingIds.toList()
        try {
            getLibraryManga.addToLibraryBulk(toFlush)
            logcat(LogPriority.DEBUG) { "Flushed ${toFlush.size} manga IDs to library" }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to flush ${toFlush.size} IDs to library, falling back" }
            // Fallback: refresh entire library
            try {
                getLibraryManga.refresh()
            } catch (inner: Exception) {
                logcat(LogPriority.ERROR, inner) { "Even refresh failed" }
            }
        }

        // Clear the buffer after flush
        pendingIds.clear()
    }

    /**
     * Find source that matches the given URL.
     * Prioritizes Kotlin extensions over JS plugins when multiple sources match the same URL.
     */
    private fun findMatchingSource(url: String, sources: List<CatalogueSource>): CatalogueSource? {
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

        // Prioritize languages the user actually has enabled in extension settings
        // and filter out any sources the user explicitly disabled
        val enabledLanguages = sourcePreferences.enabledLanguages.get()
        val disabledSources = sourcePreferences.disabledSources.get()
        
        val enabledSources = matchingSources.filter { 
            it.lang in enabledLanguages && it.id.toString() !in disabledSources 
        }
        
        // Prioritize Kotlin extensions over JS plugins from within the filtered match
        // If no user-enabled language matches, fallback to the first available source natively (index zero)
        val bestLangSources = if (enabledSources.isNotEmpty()) enabledSources else matchingSources
        val kotlinSources = bestLangSources.filter { it !is JsSource }

        return kotlinSources.firstOrNull() ?: bestLangSources.first()
    }

    data class ImportResult(
        val added: Int = 0,
        val skipped: Int = 0,
        val errored: Int = 0,
        val skippedUrls: List<String> = emptyList(),
        val erroredUrls: List<String> = emptyList(),
    )

    data class Progress(
        val current: Int,
        val total: Int,
        val status: String,
    )

    data class Batch(
        val id: String,
        val urls: List<String>,
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
        val skippedUrls: List<String> = emptyList(),
        val errorMessages: Map<String, String> = emptyMap(), // URL -> error message mapping
    )

    enum class BatchStatus {
        Pending,
        Running,
        Completed,
        Cancelled,
    }

    companion object {
        private const val TAG = "MassImportJob"
        const val KEY_URLS = "urls"
        const val KEY_URLS_FILE = "urlsFile"
        const val KEY_CATEGORY_ID = "categoryId"
        const val KEY_ADD_TO_LIBRARY = "addToLibrary"
        const val KEY_FETCH_DETAILS = "fetchDetails"
        const val KEY_FETCH_CHAPTERS = "fetchChapters"
        const val KEY_BATCH_ID = "batchId"

        // Shared state for UI to observe
        private val _sharedResult = MutableStateFlow<ImportResult?>(null)
        val sharedResult = _sharedResult.asStateFlow()

        private val _sharedProgress = MutableStateFlow<Progress?>(null)
        val sharedProgress = _sharedProgress.asStateFlow()

        private val _sharedQueue = MutableStateFlow<List<Batch>>(emptyList())
        val sharedQueue = _sharedQueue.asStateFlow()

        private data class RecoveryBatchData(
            val categoryId: Long,
            val urls: List<String>,
        )

        fun clearResult() {
            _sharedResult.value = null
            _sharedProgress.value = null
        }

        fun restoreQueueFromWorkManager(context: Context) {
            val workInfos: List<WorkInfo> = runCatching {
                context.workManager.getWorkInfosByTag(TAG).get()
            }.getOrDefault(emptyList())

            if (workInfos.isEmpty()) return

            _sharedQueue.update { current ->
                val batches = current.associateBy { it.id }.toMutableMap()

                workInfos.forEach { info: WorkInfo ->
                    if (info.state == WorkInfo.State.SUCCEEDED || info.state == WorkInfo.State.FAILED) return@forEach

                    val batchId = info.tags.firstOrNull { it.startsWith("batch_") }?.removePrefix("batch_")
                        ?: return@forEach

                    val recoveryBatch = readRecoveryBatch(batchId)
                    val urls: List<String> = recoveryBatch?.urls.orEmpty()

                    if (urls.isEmpty()) return@forEach

                    val status = when (info.state) {
                        WorkInfo.State.RUNNING -> BatchStatus.Running
                        WorkInfo.State.CANCELLED -> BatchStatus.Cancelled
                        else -> BatchStatus.Pending
                    }

                    val existing = batches[batchId]
                    batches[batchId] = if (existing != null) {
                        existing.copy(status = status)
                    } else {
                        Batch(
                            id = batchId,
                            urls = urls,
                            categoryId = recoveryBatch?.categoryId ?: 0L,
                            addToLibrary = true,
                            fetchChapters = false,
                            status = status,
                            total = urls.size,
                        )
                    }
                }

                batches.values.toList()
            }
        }

        private fun readRecoveryBatch(batchId: String): RecoveryBatchData? {
            return try {
                val storageManager: StorageManager = Injekt.get()
                val dir = storageManager.getMassImportDirectory() ?: return null
                val file = dir.findFile("queue_$batchId.txt") ?: return null
                val lines = file.openInputStream().bufferedReader().use { it.readLines() }
                // Recovery format: first line is category ID, remaining lines are URLs.
                val categoryId = lines.firstOrNull()?.toLongOrNull() ?: 0L
                val urls = lines.drop(1).filter { it.isNotBlank() }
                RecoveryBatchData(categoryId = categoryId, urls = urls)
            } catch (_: Exception) {
                null
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
        ) {
            val batchId = java.util.UUID.randomUUID().toString()
            val batch = Batch(
                id = batchId,
                urls = urls,
                categoryId = categoryId,
                addToLibrary = addToLibrary,
                fetchChapters = fetchChapters,
                fetchDetails = fetchDetails,
                total = urls.size,
            )

            _sharedQueue.update { it + batch }

            // Offload to file if URL list is large to avoid TransactionTooLargeException
            // Transaction limit is ~1MB, be conservative and offload at 500KB
            val offloadToFile = urls.size > 50 || urls.sumOf { it.length } > 500_000
            val payload = mutableListOf<Pair<String, Any?>>(
                KEY_CATEGORY_ID to categoryId,
                KEY_ADD_TO_LIBRARY to addToLibrary,
                KEY_FETCH_DETAILS to fetchDetails,
                KEY_FETCH_CHAPTERS to fetchChapters,
                KEY_BATCH_ID to batchId,
            )

            if (offloadToFile) {
                val cacheFile = File(context.cacheDir, "mass_import_$batchId.txt")
                cacheFile.writeText(urls.joinToString("\n"))
                payload += KEY_URLS_FILE to cacheFile.absolutePath
            } else {
                payload += KEY_URLS to urls.toTypedArray()
            }

            val workRequest = OneTimeWorkRequestBuilder<MassImportJob>()
                .addTag(TAG)
                .addTag("batch_$batchId")
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(*payload.toTypedArray()))
                .build()

            // Use unique work name per batch so each can execute independently
            context.workManager.enqueueUniqueWork(
                "${TAG}_$batchId",
                ExistingWorkPolicy.KEEP,
                workRequest,
            )
        }

        fun stop(context: Context) {
            context.workManager.cancelAllWorkByTag(TAG)
            _sharedQueue.update { list ->
                list.map {
                    if (it.status == BatchStatus.Pending ||
                        it.status == BatchStatus.Running
                    ) {
                        it.copy(status = BatchStatus.Cancelled)
                    } else {
                        it
                    }
                }
            }
        }

        fun cancelBatch(context: Context, batchId: String) {
            // Cancel the actual WorkManager job
            context.workManager.cancelUniqueWork("${TAG}_$batchId")
            _sharedQueue.update { list ->
                list.map {
                    if (it.id == batchId &&
                        (it.status == BatchStatus.Pending || it.status == BatchStatus.Running)
                    ) {
                        it.copy(status = BatchStatus.Cancelled)
                    } else {
                        it
                    }
                }
            }
        }

        fun removeBatch(batchId: String) {
            _sharedQueue.update { list ->
                list.filter { it.id != batchId }
            }
        }

        fun clearCompleted() {
            _sharedQueue.update { list ->
                list.filter { it.status != BatchStatus.Completed && it.status != BatchStatus.Cancelled }
            }
        }

        /**
         * Reinsert errored URLs from a batch back into the queue as a new batch.
         */
        fun reinsertErrored(context: Context, batch: Batch) {
            if (batch.erroredUrls.isEmpty()) return
            start(
                context = context,
                urls = batch.erroredUrls,
                categoryId = batch.categoryId,
                addToLibrary = batch.addToLibrary,
                fetchChapters = batch.fetchChapters,
            )
        }

        /**
         * Requeue a cancelled batch - processes remaining unprocessed URLs.
         * Only works for cancelled batches where progress < total.
         */
        fun requeueCancelled(context: Context, batch: Batch) {
            if (batch.status != BatchStatus.Cancelled) return
            if (batch.progress >= batch.total) return

            // Get URLs that weren't processed (from progress onwards)
            val processedCount = batch.progress
            val remainingUrls = if (processedCount < batch.urls.size) {
                batch.urls.drop(processedCount)
            } else {
                // All URLs were attempted, requeue errored ones
                batch.erroredUrls
            }

            if (remainingUrls.isEmpty()) return

            start(
                context = context,
                urls = remainingUrls,
                categoryId = batch.categoryId,
                addToLibrary = batch.addToLibrary,
                fetchChapters = batch.fetchChapters,
            )
        }

        /**
         * Export all URLs from a batch to a string (for file saving).
         */
        fun exportBatchUrls(batch: Batch): String {
            return batch.urls.joinToString("\n")
        }

        /**
         * Generate a detailed text report for a batch import.
         * Useful for debugging and sharing import results.
         */
        fun generateReport(batch: Batch): String {
            return buildString {
                appendLine("=== Mass Import Report ===")
                appendLine("Batch ID: ${batch.id}")
                appendLine("Status: ${batch.status}")
                appendLine()
                appendLine("=== Summary ===")
                appendLine("Total URLs: ${batch.urls.size}")
                appendLine("Successfully Added: ${batch.added}")
                appendLine("Skipped (already in library): ${batch.skipped}")
                appendLine("Errors: ${batch.errored}")
                appendLine()

                if (batch.erroredUrls.isNotEmpty()) {
                    appendLine("=== Failed URLs (${batch.erroredUrls.size}) ===")
                    batch.erroredUrls.forEach { url ->
                        val errorMsg = batch.errorMessages[url]
                        if (errorMsg != null) {
                            appendLine("$url")
                            appendLine("  Error: $errorMsg")
                        } else {
                            appendLine(url)
                        }
                    }
                    appendLine()
                }

                if (batch.skippedUrls.isNotEmpty()) {
                    appendLine("=== Skipped URLs (${batch.skippedUrls.size}) ===")
                    batch.skippedUrls.forEach { url ->
                        appendLine(url)
                    }
                    appendLine()
                }

                appendLine("=== All Input URLs (${batch.urls.size}) ===")
                batch.urls.forEach { url ->
                    appendLine(url)
                }
            }
        }

        /**
         * Generate errors with messages for clipboard copy.
         */
        fun generateErrorsWithMessages(batch: Batch): String {
            return buildString {
                batch.erroredUrls.forEach { url ->
                    appendLine(url)
                    val errorMsg = batch.errorMessages[url]
                    if (errorMsg != null) {
                        appendLine("  → $errorMsg")
                    }
                }
            }
        }
    }
}
