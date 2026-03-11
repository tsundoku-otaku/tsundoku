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
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.RemoveChapters
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.i18n.novel.TDMR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class LibraryClearJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val getManga: GetManga = Injekt.get()
    private val updateManga: UpdateManga = Injekt.get()
    private val coverCache: CoverCache = Injekt.get()
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get()
    private val removeChapters: RemoveChapters = Injekt.get()

    override suspend fun doWork(): Result {
        val mangaIds = loadMangaIds() ?: return Result.failure()
        val operation = inputData.getString(KEY_OPERATION) ?: return Result.failure()

        setForegroundSafely()

        return withIOContext {
            try {
                when (operation) {
                    OP_CLEAR_COVERS -> clearCovers(mangaIds)
                    OP_CLEAR_DESCRIPTIONS -> clearDescriptions(mangaIds)
                    OP_CLEAR_TAGS -> clearTags(mangaIds)
                    OP_CLEAR_CHAPTERS -> clearChapters(mangaIds)
                    else -> return@withIOContext Result.failure()
                }
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Library clear job failed: $operation" }
                Result.failure()
            } finally {
                context.cancelNotification(Notifications.ID_LIBRARY_CLEAR_PROGRESS)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val operation = inputData.getString(KEY_OPERATION) ?: "Clear"
        val title = when (operation) {
            OP_CLEAR_COVERS -> context.stringResource(TDMR.strings.library_clear_clearing_covers)
            OP_CLEAR_DESCRIPTIONS -> context.stringResource(TDMR.strings.library_clear_clearing_descriptions)
            OP_CLEAR_TAGS -> context.stringResource(TDMR.strings.library_clear_clearing_tags)
            OP_CLEAR_CHAPTERS -> context.stringResource(TDMR.strings.library_clear_clearing_chapters)
            else -> context.stringResource(TDMR.strings.library_clear_clearing)
        }
        val notification = context.notificationBuilder(Notifications.CHANNEL_LIBRARY_CLEAR) {
            setSmallIcon(android.R.drawable.ic_menu_delete)
            setContentTitle(title)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(0, 0, true)
        }.build()
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
        val total = mangaIds.size
        mangaIds.forEachIndexed { index, id ->
            val manga = getManga.await(id) ?: return@forEachIndexed
            if (!manga.isLocal()) {
                coverCache.deleteFromCache(manga, true)
            }
            updateManga.await(
                MangaUpdate(
                    id = id,
                    thumbnailUrl = "",
                    coverLastModified = System.currentTimeMillis(),
                ),
            )
            updateProgress(index + 1, total, context.stringResource(TDMR.strings.library_clear_clearing_covers))
        }
        showComplete(context.stringResource(TDMR.strings.library_clear_cleared_covers, total))
    }

    private suspend fun clearDescriptions(mangaIds: LongArray) {
        val updates = mangaIds.map { MangaUpdate(id = it, description = "") }
        updateManga.awaitAll(updates)
        showComplete(context.stringResource(TDMR.strings.library_clear_cleared_descriptions, mangaIds.size))
    }

    private suspend fun clearTags(mangaIds: LongArray) {
        val updates = mangaIds.map { MangaUpdate(id = it, genre = emptyList()) }
        updateManga.awaitAll(updates)
        showComplete(context.stringResource(TDMR.strings.library_clear_cleared_tags, mangaIds.size))
    }

    private suspend fun clearChapters(mangaIds: LongArray) {
        val total = mangaIds.size
        mangaIds.forEachIndexed { index, id ->
            val chapters = getChaptersByMangaId.await(id)
            if (chapters.isNotEmpty()) {
                removeChapters.await(chapters)
            }
            updateProgress(index + 1, total, context.stringResource(TDMR.strings.library_clear_clearing_chapters))
        }
        showComplete(context.stringResource(TDMR.strings.library_clear_cleared_chapters, total))
    }

    private fun updateProgress(current: Int, total: Int, title: String) {
        val notification = context.notificationBuilder(Notifications.CHANNEL_LIBRARY_CLEAR) {
            setSmallIcon(android.R.drawable.ic_menu_delete)
            setContentTitle(title)
            setContentText("$current / $total")
            setProgress(total, current, false)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }.build()
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
        private const val KEY_OPERATION = "operation"
        private const val MAX_DIRECT_IDS = 500
        const val OP_CLEAR_COVERS = "clear_covers"
        const val OP_CLEAR_DESCRIPTIONS = "clear_descriptions"
        const val OP_CLEAR_TAGS = "clear_tags"
        const val OP_CLEAR_CHAPTERS = "clear_chapters"

        fun start(context: Context, mangaIds: List<Long>, operation: String) {
            val inputDataBuilder = workDataOf(KEY_OPERATION to operation).let {
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
                "${TAG}_$operation",
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
        }
    }
}
