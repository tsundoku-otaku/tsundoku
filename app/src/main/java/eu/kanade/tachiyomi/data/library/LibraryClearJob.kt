package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.RemoveChapters
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.novel.TDMR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class LibraryClearJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val coverCache: CoverCache = Injekt.get()
    private val removeChapters: RemoveChapters = Injekt.get()
    private val mangaRepository: MangaRepository = Injekt.get()
    private val getLibraryManga: GetLibraryManga = Injekt.get()

    private val progressBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_LIBRARY_CLEAR) {
            setSmallIcon(android.R.drawable.ic_menu_delete)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
    }
    private var lastNotificationTime = 0L

    override suspend fun doWork(): Result {
        val mangaIds = loadMangaIds() ?: return Result.failure()
        val operations = inputData.getString(KEY_OPERATIONS)
            ?.split(",")
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: return Result.failure()

        setForegroundSafely()

        return withIOContext {
            try {
                val combineDescTags = OP_CLEAR_DESCRIPTIONS in operations && OP_CLEAR_TAGS in operations
                operations.forEach { operation ->
                    when (operation) {
                        OP_CLEAR_COVERS -> clearCovers(mangaIds)
                        OP_CLEAR_DESCRIPTIONS -> if (!combineDescTags) clearDescriptions(mangaIds)
                        OP_CLEAR_TAGS -> if (combineDescTags) {
                            clearDescriptionsAndTags(
                                mangaIds,
                            )
                        } else {
                            clearTags(mangaIds)
                        }
                        OP_CLEAR_CHAPTERS -> clearChapters(mangaIds)
                    }
                }
                showComplete(context.stringResource(TDMR.strings.library_clear_cleared_generic, mangaIds.size))
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Library clear job failed: $operations" }
                Result.failure()
            } finally {
                context.cancelNotification(Notifications.ID_LIBRARY_CLEAR_PROGRESS)
                getLibraryManga.notifyChanged()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = progressBuilder
            .setContentTitle(context.stringResource(TDMR.strings.library_clear_clearing))
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(
            Notifications.ID_LIBRARY_CLEAR_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private suspend fun clearCovers(mangaIds: LongArray) {
        val title = context.stringResource(TDMR.strings.library_clear_clearing_covers)
        runChunked(mangaIds, title) { chunk ->
            val mangas = mangaRepository.getMangasByIds(chunk)
            coroutineScope {
                mangas.filterNot { it.isLocal() }
                    .map { manga -> async { coverCache.deleteFromCache(manga, true) } }
                    .awaitAll()
            }
            mangaRepository.clearCoversForMangaIds(chunk, System.currentTimeMillis())
        }
    }

    private suspend fun clearDescriptions(mangaIds: LongArray) {
        val title = context.stringResource(TDMR.strings.library_clear_clearing_descriptions)
        runChunked(mangaIds, title) { chunk -> mangaRepository.clearDescriptionsForMangaIds(chunk) }
    }

    private suspend fun clearTags(mangaIds: LongArray) {
        val title = context.stringResource(TDMR.strings.library_clear_clearing_tags)
        runChunked(mangaIds, title) { chunk -> mangaRepository.clearGenresForMangaIds(chunk) }
    }

    private suspend fun clearDescriptionsAndTags(mangaIds: LongArray) {
        val title = context.stringResource(TDMR.strings.library_clear_clearing)
        runChunked(mangaIds, title) { chunk -> mangaRepository.clearDescriptionsAndGenresForMangaIds(chunk) }
    }

    private suspend fun clearChapters(mangaIds: LongArray) {
        val title = context.stringResource(TDMR.strings.library_clear_clearing_chapters)
        runChunked(mangaIds, title) { chunk ->
            removeChapters.awaitByMangaIds(chunk)
        }
    }

    private suspend fun runChunked(mangaIds: LongArray, title: String, block: suspend (List<Long>) -> Unit) {
        val total = mangaIds.size
        var processed = 0
        mangaIds.toList().chunked(CHUNK_SIZE).forEach { chunk ->
            block(chunk)
            processed += chunk.size
            updateProgress(processed, total, title, force = processed >= total)
        }
    }

    private fun updateProgress(current: Int, total: Int, title: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationTime < NOTIFICATION_THROTTLE_MS) return
        lastNotificationTime = now
        val notification = progressBuilder
            .setContentTitle(title)
            .setContentText("$current / $total")
            .setProgress(total, current, false)
            .build()
        context.notify(Notifications.ID_LIBRARY_CLEAR_PROGRESS, notification)
    }

    private fun showComplete(message: String) {
        context.cancelNotification(Notifications.ID_LIBRARY_CLEAR_PROGRESS)
        val notification = context.notificationBuilder(Notifications.CHANNEL_LIBRARY_CLEAR) {
            setSmallIcon(android.R.drawable.ic_menu_delete)
            setContentTitle(context.stringResource(TDMR.strings.library_clear_complete_title))
            setContentText(message)
            setAutoCancel(true)
        }.build()
        context.notify(Notifications.ID_LIBRARY_CLEAR_COMPLETE, notification)
    }

    private fun loadMangaIds(): LongArray? {
        inputData.getLongArray(KEY_MANGA_IDS)?.let { return it }
        val filePath = inputData.getString(KEY_IDS_FILE) ?: return null
        return try {
            val file = File(filePath)
            val ids = file.readText().split(",").mapNotNull { it.trim().toLongOrNull() }.toLongArray()
            file.delete()
            ids
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read manga IDs from file: $filePath" }
            null
        }
    }

    companion object {
        private const val TAG = "LibraryClearJob"
        private const val KEY_MANGA_IDS = "manga_ids"
        private const val KEY_IDS_FILE = "ids_file"
        private const val KEY_OPERATIONS = "operations"
        private const val MAX_DIRECT_IDS = 500
        private const val CHUNK_SIZE = 200
        private const val NOTIFICATION_THROTTLE_MS = 400L
        const val OP_CLEAR_COVERS = "clear_covers"
        const val OP_CLEAR_DESCRIPTIONS = "clear_descriptions"
        const val OP_CLEAR_TAGS = "clear_tags"
        const val OP_CLEAR_CHAPTERS = "clear_chapters"

        fun start(context: Context, mangaIds: List<Long>, operations: List<String>) {
            if (operations.isEmpty() || mangaIds.isEmpty()) return

            val inputDataBuilder = workDataOf(KEY_OPERATIONS to operations.joinToString(",")).let {
                androidx.work.Data.Builder().putAll(it)
            }

            if (mangaIds.size <= MAX_DIRECT_IDS) {
                inputDataBuilder.putLongArray(KEY_MANGA_IDS, mangaIds.toLongArray())
            } else {
                val idsFile = File(context.cacheDir, "clear_job_ids_${System.currentTimeMillis()}.txt")
                idsFile.writeText(mangaIds.joinToString(","))
                inputDataBuilder.putString(KEY_IDS_FILE, idsFile.absolutePath)
            }

            val workRequest = OneTimeWorkRequestBuilder<LibraryClearJob>()
                .addTag(TAG)
                .setInputData(inputDataBuilder.build())
                .build()
            context.workManager.enqueueUniqueWork(
                TAG,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest,
            )
        }
    }
}
