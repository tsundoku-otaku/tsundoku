package mihon.domain.migration

import android.app.Application
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.track.source.SourceTrackerDispatcher
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.quote.QuoteManager
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Runs "Quick migrate" (bulk source-swap for already-favorited entries) as a durable background
 * job instead of inside MigrateMangaViewModel's viewModelScope. That coroutine died with the
 * ViewModel on process death - an app kill mid-run (e.g. after the phone was dropped) on a
 * 1000+ entry batch left whatever hadn't reached the DB-flip step in limbo: files already
 * relocated to the new source's folder, but the DB row still pointing at the old source/url, so
 * the entry effectively vanished from the library. It also ran with zero UI feedback beyond a
 * frozen confirm dialog for however long the batch took.
 */
class QuickMigrateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val sourceManager: SourceManager by lazy { Injekt.get() }
    private val getFavorites: GetFavorites by lazy { Injekt.get() }
    private val updateManga: UpdateManga by lazy { Injekt.get() }
    private val getCategories: GetCategories by lazy { Injekt.get() }
    private val createCategoryWithName: CreateCategoryWithName by lazy { Injekt.get() }
    private val setMangaCategories: SetMangaCategories by lazy { Injekt.get() }
    private val sourceTrackerDispatcher: SourceTrackerDispatcher by lazy { Injekt.get() }
    private val trackPreferences: TrackPreferences by lazy { Injekt.get() }
    private val getManga: GetManga by lazy { Injekt.get() }
    private val downloadManager: DownloadManager by lazy { Injekt.get() }
    private val translatedChapterRepository: TranslatedChapterRepository by lazy { Injekt.get() }
    private val quoteManager: QuoteManager by lazy { QuoteManager(Injekt.get<Application>()) }

    private val notificationBuilder = context.notificationBuilder(Notifications.CHANNEL_MIGRATION) {
        setSmallIcon(android.R.drawable.stat_notify_sync)
        setContentTitle(context.stringResource(MR.strings.action_quick_migrate))
        setContentText(context.stringResource(TDMR.strings.quick_migrate_notification_starting))
        setOngoing(true)
        setOnlyAlertOnce(true)
        addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.stringResource(MR.strings.action_cancel),
            eu.kanade.tachiyomi.data.notification.NotificationReceiver.cancelQuickMigratePendingBroadcast(context),
        )
    }

    private val progressNotifier =
        MigrationProgressNotifier(context, Notifications.ID_QUICK_MIGRATE_PROGRESS, notificationBuilder)

    private suspend fun updateProgress(done: Int, total: Int) {
        setProgress(workDataOf(KEY_PROGRESS_CURRENT to done, KEY_PROGRESS_TOTAL to total))
        progressNotifier.update(done, total)
    }

    override suspend fun doWork(): Result {
        val sourceId = inputData.getLong(KEY_SOURCE_ID, -1L)
        val targetSourceId = inputData.getLong(KEY_TARGET_SOURCE_ID, -1L)
        val mangaIdsFile = inputData.getString(KEY_MANGA_IDS_FILE)?.let { File(it) }
        val categoryName = inputData.getString(KEY_CATEGORY_NAME)
        val removeSkipped = inputData.getBoolean(KEY_REMOVE_SKIPPED, false)

        if (sourceId == -1L || targetSourceId == -1L || mangaIdsFile == null || !mangaIdsFile.exists()) {
            return Result.failure()
        }

        val mangaIds = try {
            readMangaIds(mangaIdsFile)
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, e) { "Failed to read quick migrate job data" }
            return Result.failure()
        } finally {
            mangaIdsFile.delete()
        }

        setForegroundSafely()

        val migratedIds = mutableListOf<Long>()

        return try {
            val selectedManga = mangaIds.toList().mapNotNull { getManga.await(it) }
            val newSource = sourceManager.getOrStub(targetSourceId)
            val targetFavorites = getFavorites.subscribe(targetSourceId).first()
            val targetFavoriteUrls = targetFavorites.mapTo(mutableSetOf()) { it.url }
            val targets = quickMigrateTargets(selectedManga, targetFavoriteUrls, newSource)
            val skipped = if (removeSkipped) {
                quickMigrateSkipped(selectedManga, targetFavoriteUrls, newSource)
            } else {
                emptyList()
            }
            val total = targets.size + skipped.size
            if (total == 0) {
                context.cancelNotification(Notifications.ID_QUICK_MIGRATE_PROGRESS)
                return Result.success(workDataOf(KEY_RESULT_MIGRATED to 0, KEY_RESULT_REMOVED to 0))
            }

            val oldSource = sourceManager.getOrStub(sourceId)
            val targetTitles = targets.mapTo(mutableSetOf()) { it.first.title }
            // The entry that stays behind for a skipped one, matched the same way the skip was:
            // on the normalized url, not the title, which the two can disagree on.
            val keptByUrl = targetFavorites.associateBy { it.url }
            val keptForSkipped = skipped.associate {
                it.id to keptByUrl[normalizeQuickMigrateUrl(it.url, newSource)]
            }

            val countsUsable = downloadManager.awaitDownloadCacheReady()
            val downloadCounts = if (countsUsable) {
                downloadManager.getDownloadCounts(
                    (targets.map { it.first } + skipped + keptForSkipped.values.filterNotNull())
                        .distinctBy { it.id },
                )
            } else {
                emptyMap()
            }

            val queuedMangaIds = downloadManager.queueState.value.mapTo(mutableSetOf()) { it.mangaId }
            val progressCount = java.util.concurrent.atomic.AtomicInteger(0)

            val withTranslations = runCatching {
                translatedChapterRepository.filterNovelsWithTranslations(oldSource.toString(), targetTitles)
            }.onFailure {
                logcat(LogPriority.ERROR, it) { "Failed to list translations on quick migrate" }
            }.getOrNull()
            val withQuotes = runCatching {
                quoteManager.filterNovelsWithQuotes(oldSource.toString(), targetTitles)
            }.onFailure {
                logcat(LogPriority.ERROR, it) { "Failed to list quotes on quick migrate" }
            }.getOrNull()
            val pendingUpdates = mutableListOf<MangaUpdate>()
            val pendingIds = mutableListOf<Long>()
            suspend fun flushUpdates(force: Boolean) {
                if (pendingUpdates.isEmpty() || (!force && pendingUpdates.size < UPDATE_CHUNK_SIZE)) return
                val chunk = pendingUpdates.toList()
                val chunkIds = pendingIds.toList()
                pendingUpdates.clear()
                pendingIds.clear()

                if (updateManga.awaitAll(chunk)) {
                    migratedIds.addAll(chunkIds)
                } else {
                    logcat(LogPriority.ERROR) {
                        "Quick migrate: failed to write a chunk of ${chunk.size} entries"
                    }
                }
            }
            var attemptedDownloadMove = false
            val removal = relocateAndRemoveSkipped(
                skipped = skipped,
                keptForSkipped = keptForSkipped,
                oldSource = oldSource,
                newSource = newSource,
                downloadCounts = downloadCounts,
                countsUsable = countsUsable,
                queuedMangaIds = queuedMangaIds,
                onItemDone = { updateProgress(progressCount.incrementAndGet(), total) },
                onChunkRemoved = {},
            )
            if (removal.touchedDownloads) attemptedDownloadMove = true
            for ((manga, newUrl) in targets) {
                try {
                    // Relocate source-keyed data (downloads/translations/quotes) BEFORE flipping the
                    // DB source, so a crash in between can't leave files under the old source name
                    // while the DB already points at the new one. Only preserves data for same-URL
                    // migrations (e.g. JS->KT); different-site moves change chapter URLs.
                    // moveMangaToNewSource reports non-crash failures (destination collision,
                    // partial copy) with false rather than an exception, so branch on the value:
                    // leave the manga on its old source rather than flipping the DB onto downloads
                    // that never moved. It can be retried once the conflict is resolved.
                    val hasDownloads = !countsUsable ||
                        (downloadCounts[manga.id] ?: 0) > 0 ||
                        manga.id in queuedMangaIds
                    if (hasDownloads) attemptedDownloadMove = true
                    val downloadsMoved = !hasDownloads || runCatching {
                        downloadManager.moveMangaToNewSource(manga, oldSource, newSource, invalidateCache = false)
                    }.onFailure {
                        logcat(LogPriority.ERROR, it) { "Failed to move downloads on quick migrate" }
                    }.getOrDefault(false)
                    if (!downloadsMoved) {
                        logcat(LogPriority.WARN) {
                            "Skipping quick migrate for ${manga.title}: download relocation did not complete"
                        }
                        continue
                    }
                    if (withTranslations == null || manga.title in withTranslations) {
                        runCatching {
                            translatedChapterRepository.moveNovel(
                                oldSource.toString(),
                                manga.title,
                                newSource.toString(),
                                manga.title,
                            )
                        }.onFailure {
                            logcat(LogPriority.ERROR, it) { "Failed to move translations on quick migrate" }
                        }
                    }
                    if (withQuotes == null || manga.title in withQuotes) {
                        runCatching {
                            quoteManager.moveNovel(
                                oldSource.toString(),
                                manga.title,
                                newSource.toString(),
                                manga.title,
                            )
                        }.onFailure {
                            logcat(LogPriority.ERROR, it) { "Failed to move quotes on quick migrate" }
                        }
                    }
                    pendingUpdates.add(MangaUpdate(id = manga.id, source = targetSourceId, url = newUrl))
                    pendingIds.add(manga.id)
                    flushUpdates(force = false)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Skip entries that fail to update; the rest still migrate.
                } finally {
                    updateProgress(progressCount.incrementAndGet(), total)
                }
            }
            flushUpdates(force = true)
            // One rebuild for the whole run instead of one per relocated entry, each of which
            // deletes the on-disk index and restarts the full SAF scan.
            if (attemptedDownloadMove) downloadManager.invalidateDownloadCache()
            val migrated = migratedIds.size

            if (migratedIds.isNotEmpty() && trackPreferences.migrationTriggersSourceTracker.get()) {
                migratedIds.forEach { id ->
                    val freshManga = getManga.await(id) ?: return@forEach
                    sourceTrackerDispatcher.notifyFavorited(freshManga)
                }
            }

            if (migratedIds.isNotEmpty() && !categoryName.isNullOrBlank()) {
                var categoryId: Long? = null
                val existingCategory = getCategories.await().find {
                    it.name.equals(categoryName, ignoreCase = true)
                }
                if (existingCategory != null) {
                    categoryId = existingCategory.id
                } else {
                    val result = createCategoryWithName.await(categoryName)
                    if (result is CreateCategoryWithName.Result.Success) {
                        categoryId = getCategories.await().find { it.name.equals(categoryName, ignoreCase = true) }?.id
                    }
                }
                if (categoryId != null) {
                    setMangaCategories.await(mangaIds = migratedIds, categoryIds = listOf(categoryId))
                }
            }

            notificationBuilder
                .setOngoing(false)
                .setProgress(0, 0, false)
                .setContentText(
                    context.stringResource(
                        TDMR.strings.quick_migrate_notification_result,
                        migrated,
                        targets.size,
                        removal.removed,
                    ),
                )
                .clearActions()
            context.notify(Notifications.ID_QUICK_MIGRATE_COMPLETE, notificationBuilder.build())

            Result.success(
                workDataOf(
                    KEY_RESULT_MIGRATED to migrated,
                    KEY_RESULT_REMOVED to removal.removed,
                ),
            )
        } catch (e: CancellationException) {
            // WorkManager discards any Result returned after external cancellation and reports
            // WorkInfo.State.CANCELLED with no output data, so there's no point building one here.
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Quick migrate job failed" }
            notificationBuilder
                .setOngoing(false)
                .setProgress(0, 0, false)
                .setContentText(e.message ?: context.stringResource(TDMR.strings.quick_migrate_notification_failed))
                .clearActions()
            context.notify(Notifications.ID_QUICK_MIGRATE_COMPLETE, notificationBuilder.build())
            Result.failure()
        } finally {
            context.cancelNotification(Notifications.ID_QUICK_MIGRATE_PROGRESS)
        }
    }

    /**
     * Hands a skipped entry's data to the entry that stays and then takes the skipped one out of
     * the library, in the same chunks the migration writes with.
     *
     * A skipped entry is one the target source already has, so its downloads, translations and
     * quotes are relocated exactly like a migrated entry's, under the kept entry's title, which
     * is where that entry looks for them. The old copy is deleted only when the kept one already
     * holds downloads of its own: every other relocation failure leaves the only copy on disk.
     */
    private suspend fun relocateAndRemoveSkipped(
        skipped: List<Manga>,
        keptForSkipped: Map<Long, Manga?>,
        oldSource: Source,
        newSource: Source,
        downloadCounts: Map<Long, Int>,
        countsUsable: Boolean,
        queuedMangaIds: Set<Long>,
        onItemDone: suspend () -> Unit,
        onChunkRemoved: suspend (Int) -> Unit,
    ): SkippedRemoval {
        if (skipped.isEmpty()) return SkippedRemoval(removed = 0, touchedDownloads = false)
        val titles = skipped.mapTo(mutableSetOf()) { it.title }
        val withTranslations = runCatching {
            translatedChapterRepository.filterNovelsWithTranslations(oldSource.toString(), titles)
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to list translations for skipped entries" }
        }.getOrNull()
        val withQuotes = runCatching {
            quoteManager.filterNovelsWithQuotes(oldSource.toString(), titles)
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to list quotes for skipped entries" }
        }.getOrNull()

        var removed = 0
        var touchedDownloads = false
        skipped.chunked(UPDATE_CHUNK_SIZE).forEach { chunk ->
            chunk.forEach { manga ->
                val kept = keptForSkipped[manga.id]
                val keptTitle = kept?.title ?: manga.title
                val hasDownloads = !countsUsable ||
                    (downloadCounts[manga.id] ?: 0) > 0 ||
                    manga.id in queuedMangaIds
                if (hasDownloads) {
                    touchedDownloads = true
                    val keptHasDownloads = if (countsUsable && kept != null) {
                        (downloadCounts[kept.id] ?: 0) > 0
                    } else {
                        downloadManager.hasDownloadedChapters(keptTitle, newSource)
                    }
                    if (keptHasDownloads) {
                        downloadManager.deleteManga(manga, oldSource)
                    } else {
                        runCatching {
                            if (keptTitle != manga.title) {
                                downloadManager.renameManga(manga, keptTitle)
                            }
                            downloadManager.moveMangaToNewSource(
                                manga.copy(title = keptTitle),
                                oldSource,
                                newSource,
                                invalidateCache = false,
                            )
                        }.onFailure {
                            logcat(LogPriority.ERROR, it) { "Failed to move downloads for a skipped entry" }
                        }
                    }
                }
                if (withTranslations == null || manga.title in withTranslations) {
                    runCatching {
                        translatedChapterRepository.moveNovel(
                            oldSource.toString(),
                            manga.title,
                            newSource.toString(),
                            keptTitle,
                        )
                    }.onFailure {
                        logcat(LogPriority.ERROR, it) { "Failed to move translations for a skipped entry" }
                    }
                }
                if (withQuotes == null || manga.title in withQuotes) {
                    runCatching {
                        quoteManager.moveNovel(
                            oldSource.toString(),
                            manga.title,
                            newSource.toString(),
                            keptTitle,
                        )
                    }.onFailure {
                        logcat(LogPriority.ERROR, it) { "Failed to move quotes for a skipped entry" }
                    }
                }
                onItemDone()
            }
            if (updateManga.awaitAll(chunk.map { MangaUpdate(id = it.id, favorite = false) })) {
                removed += chunk.size
                onChunkRemoved(chunk.size)
            } else {
                logcat(LogPriority.ERROR) { "Failed to remove a chunk of ${chunk.size} skipped entries" }
            }
        }
        return SkippedRemoval(removed = removed, touchedDownloads = touchedDownloads)
    }

    private data class SkippedRemoval(val removed: Int, val touchedDownloads: Boolean)

    private fun readMangaIds(file: File): LongArray {
        return DataInputStream(file.inputStream().buffered()).use { input ->
            val count = input.readInt()
            LongArray(count) { input.readLong() }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_QUICK_MIGRATE_PROGRESS,
            notificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        const val TAG = "QuickMigrateJob"
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_TARGET_SOURCE_ID = "target_source_id"
        const val KEY_MANGA_IDS_FILE = "manga_ids_file"
        const val KEY_CATEGORY_NAME = "category_name"
        const val KEY_REMOVE_SKIPPED = "remove_skipped"
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_RESULT_MIGRATED = "result_migrated"
        const val KEY_RESULT_REMOVED = "result_removed"
        private const val UPDATE_CHUNK_SIZE = 200

        // WorkManager's Data payload is capped at 10240 bytes when serialized; a large quick
        // migrate selection's id array can exceed that on its own, so the ids are written to a
        // cache file instead and only its path travels through Data.
        //
        // Returns false if a job was already running: with ExistingWorkPolicy.KEEP, an existing
        // unique job silently drops the new request instead of queuing it, so the request's own
        // WorkInfo is checked to tell that apart from a genuine enqueue - otherwise the caller has
        // no way to know its request was a no-op and would attach progress/completion reporting
        // for its own selection to whatever unrelated job is actually running.
        fun start(
            context: Context,
            sourceId: Long,
            targetSourceId: Long,
            mangaIds: List<Long>,
            categoryName: String?,
            removeSkipped: Boolean,
        ): Boolean {
            val mangaIdsFile = File(context.cacheDir, "quick_migrate_job_${System.nanoTime()}.dat")
            DataOutputStream(mangaIdsFile.outputStream().buffered()).use { out ->
                out.writeInt(mangaIds.size)
                mangaIds.forEach { out.writeLong(it) }
            }
            val data = workDataOf(
                KEY_SOURCE_ID to sourceId,
                KEY_TARGET_SOURCE_ID to targetSourceId,
                KEY_MANGA_IDS_FILE to mangaIdsFile.absolutePath,
                KEY_CATEGORY_NAME to categoryName,
                KEY_REMOVE_SKIPPED to removeSkipped,
            )
            val request = OneTimeWorkRequestBuilder<QuickMigrateJob>()
                .addTag(TAG)
                .setInputData(data)
                .build()
            val workManager = context.workManager
            workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request).result.get()
            if (workManager.getWorkInfoById(request.id).get() == null) {
                mangaIdsFile.delete()
                return false
            }
            return true
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }

        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG, includeEnqueued = true)
        }
    }
}
