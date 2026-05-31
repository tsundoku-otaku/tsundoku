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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
                val allTokens = file.bufferedReader().useLines { lines ->
                    lines.flatMap { raw ->
                        if (raw.contains(',') || raw.contains(';')) {
                            raw.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }.asSequence()
                        } else {
                            val t = raw.trim()
                            if (t.isNotBlank()) sequenceOf(t) else emptySequence()
                        }
                    }.distinct().toList()
                }

                performImport(
                    allTokens,
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
                    urls,
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
        urls: List<String>,
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
            showCompletionNotification(0, 0, urls.size, "No compatible sources installed")
            updateBatchStatus(batchId, BatchStatus.Completed)
            return ImportResult(added = 0, skipped = 0, errored = urls.size)
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

        val dbCache = ConcurrentHashMap<Pair<Long, String>, Boolean>()

        val validUrls = urls.asSequence()
            .filter { it.isNotBlank() }
            .filter { url -> url.startsWith("http://") || url.startsWith("https://") }
            .filter { url -> getCachedSource(url) != null }
            .toList()

        if (validUrls.isEmpty()) {
            showCompletionNotification(0, urls.size, 0, "No valid sources or all novels already in library")
            updateBatchStatus(batchId, BatchStatus.Completed)
            return ImportResult(added = 0, skipped = urls.size, errored = 0)
        }

        _sharedQueue.update { list ->
            list.map { if (it.id == batchId) it.copy(total = validUrls.size) else it }
        }

        val concurrency = if (!fetchDetails && !fetchChapters) 16 else novelDownloadPreferences.parallelMassImport().get()

        val completedCount = AtomicInteger(0)
        val addedCount = AtomicInteger(0)
        val skippedCount = AtomicInteger(urls.size - validUrls.size)
        val erroredCount = AtomicInteger(0)
        val pendingAddIds = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val flushBatchSize = 50
        val activeImports = ConcurrentHashMap<String, Boolean>()

        val erroredUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val errorMessages = ConcurrentHashMap<String, String>()

        updateNotification(0, validUrls.size, "Starting import...")

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
        validUrls.asFlow()
            .flatMapMerge(concurrency) { url ->
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
                        erroredUrls.add(url)
                        errorMessages[url] = "Source skipped: $failures consecutive failures"
                        val done = completedCount.incrementAndGet()
                        updateBatchProgress(batchId, done, addedCount.get(), skippedCount.get(), erroredCount.get(), erroredUrls = synchronized(erroredUrls) { erroredUrls.take(10) })
                        return@flow
                    }

                    waitForMemoryPressure()

                    activeImports[url] = true
                    updateNotification(
                        completedCount.get(),
                        validUrls.size,
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
                                    fetchChapters, pendingAddIds, flushBatchSize, dbCache,
                                )
                            }
                        } else {
                            processUrlWithSource(
                                url, source, addToLibrary, fetchDetails, categoryId,
                                fetchChapters, pendingAddIds, flushBatchSize, dbCache,
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
                        erroredUrls.add(url)
                        errorMessages[url] = e.message ?: "Unknown error"
                    } finally {
                        activeImports.remove(url)
                        val done = completedCount.incrementAndGet()
                        updateNotification(done, validUrls.size, "Processed $done/${validUrls.size}")

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
        dbCache: ConcurrentHashMap<Pair<Long, String>, Boolean>,
    ): Boolean {
        val rawPath = massImportInteractor.extractPathFromUrl(url, massImportInteractor.getSourceBaseUrl(source), source)
        if (rawPath.isEmpty()) return false

        val normalizedPath = massImportInteractor.normalizeUrl(rawPath)

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
                    dbCache[source.id to finalUrl] = true
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
                dbCache[source.id to finalUrl] = true
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

        val manga = massImportInteractor.resolveMangaUrl(url, finalUrl, source)

        if (addToLibrary) {
            mangaRepository.update(
                MangaUpdate(
                    id = manga.id,
                    favorite = true,
                    dateAdded = System.currentTimeMillis(),
                ),
            )
            dbCache[source.id to finalUrl] = true
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
        val maxMemRaw = runtime.maxMemory()
        val maxMem = maxMemRaw.coerceAtMost(MAX_HEAP_BYTES)
        val usedMem = runtime.totalMemory() - runtime.freeMemory()
        val freeMem = maxMem - usedMem

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
                sourceUrls = urls,
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

        private fun tokenizeRawText(rawText: String): List<String> {
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
                .distinct()
                .toList()
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
                        batch = batch.copy(status = BatchStatus.Completed)
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
            val sourceUrls = when {
                rawText != null -> tokenizeRawText(rawText)
                else -> urls
            }
            val batchUrlsForQueue = when {
                rawText != null -> sourceUrls.take(100)
                offloadToFile -> sourceUrls.take(100)
                else -> urls
            }
            val batchTotal = when {
                rawText != null -> sourceUrls.size
                else -> sourceUrls.size
            }
            val normalizedExcludedHosts = excludedHosts
                .map { it.trim().lowercase().removePrefix("www.") }
                .filter { it.isNotBlank() }
            val batch = Batch(
                id = batchId,
                urls = batchUrlsForQueue,
                sourceUrls = sourceUrls,
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
            persistScope.launch { MassImportStore.saveUrls(appContext, batchId, sourceUrls) }
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
            affected.forEach { persistMeta(context, it, force = true) }
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

        fun exportBatchUrls(batch: Batch): String {
            return batch.sourceUrls.joinToString("\n")
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



