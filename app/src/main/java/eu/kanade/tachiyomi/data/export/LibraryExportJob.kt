package eu.kanade.tachiyomi.data.export

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LibraryExportJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val mangaRepository: MangaRepository = Injekt.get()

    private val notificationBuilder = context.notificationBuilder(Notifications.CHANNEL_LIBRARY_EXPORT) {
        setSmallIcon(android.R.drawable.stat_sys_upload)
        setContentTitle("Library Export")
        setContentText("Starting...")
        setOngoing(true)
        setOnlyAlertOnce(true)
        addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.stringResource(MR.strings.action_cancel),
            eu.kanade.tachiyomi.data.notification.NotificationReceiver.cancelLibraryExportPendingBroadcast(context),
        )
    }

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_OUTPUT_URI) ?: return Result.failure()
        val options = exportOptionsFromData(inputData)

        try {
            setForegroundSafely()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Library export: failed to set foreground" }
        }

        return try {
            val total = mangaRepository.getFavoritesCount().toInt()
            LibraryExporter.exportToCsv(
                context = context,
                uri = Uri.parse(uriString),
                total = total,
                loadPage = { limit, offset -> mangaRepository.getFavoritesPaged(limit, offset) },
                options = options,
                onProgress = { progress ->
                    notificationBuilder
                        .setContentText("${progress.current}/${progress.total}")
                        .setProgress(progress.total, progress.current, false)
                    context.notify(Notifications.ID_LIBRARY_EXPORT_PROGRESS, notificationBuilder.build())
                },
                onExportComplete = {
                    notificationBuilder
                        .setOngoing(false)
                        .setProgress(0, 0, false)
                        .setContentText("Export complete")
                    context.notify(Notifications.ID_LIBRARY_EXPORT_COMPLETE, notificationBuilder.build())
                },
            )
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) {
                Result.success()
            } else {
                logcat(LogPriority.ERROR, e) { "Library export failed" }
                notificationBuilder
                    .setOngoing(false)
                    .setProgress(0, 0, false)
                    .setContentText(e.message ?: "Export failed")
                context.notify(Notifications.ID_LIBRARY_EXPORT_COMPLETE, notificationBuilder.build())
                Result.failure()
            }
        } finally {
            context.cancelNotification(Notifications.ID_LIBRARY_EXPORT_PROGRESS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_LIBRARY_EXPORT_PROGRESS,
            notificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        private const val TAG = "LibraryExportJob"
        private const val KEY_OUTPUT_URI = "output_uri"
        private const val KEY_INCLUDE_TITLE = "include_title"
        private const val KEY_INCLUDE_AUTHOR = "include_author"
        private const val KEY_INCLUDE_ARTIST = "include_artist"
        private const val KEY_INCLUDE_URL = "include_url"
        private const val KEY_INCLUDE_CHAPTER_COUNT = "include_chapter_count"
        private const val KEY_INCLUDE_CATEGORY = "include_category"
        private const val KEY_INCLUDE_IS_NOVEL = "include_is_novel"
        private const val KEY_INCLUDE_DESCRIPTION = "include_description"
        private const val KEY_INCLUDE_TAGS = "include_tags"

        fun start(
            context: Context,
            outputUri: Uri,
            options: LibraryExporter.ExportOptions,
        ) {
            val data = workDataOf(
                KEY_OUTPUT_URI to outputUri.toString(),
                KEY_INCLUDE_TITLE to options.includeTitle,
                KEY_INCLUDE_AUTHOR to options.includeAuthor,
                KEY_INCLUDE_ARTIST to options.includeArtist,
                KEY_INCLUDE_URL to options.includeUrl,
                KEY_INCLUDE_CHAPTER_COUNT to options.includeChapterCount,
                KEY_INCLUDE_CATEGORY to options.includeCategory,
                KEY_INCLUDE_IS_NOVEL to options.includeIsNovel,
                KEY_INCLUDE_DESCRIPTION to options.includeDescription,
                KEY_INCLUDE_TAGS to options.includeTags,
            )
            val request = OneTimeWorkRequestBuilder<LibraryExportJob>()
                .addTag(TAG)
                .setInputData(data)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }

        private fun exportOptionsFromData(data: androidx.work.Data): LibraryExporter.ExportOptions {
            return LibraryExporter.ExportOptions(
                includeTitle = data.getBoolean(KEY_INCLUDE_TITLE, true),
                includeAuthor = data.getBoolean(KEY_INCLUDE_AUTHOR, true),
                includeArtist = data.getBoolean(KEY_INCLUDE_ARTIST, true),
                includeUrl = data.getBoolean(KEY_INCLUDE_URL, false),
                includeChapterCount = data.getBoolean(KEY_INCLUDE_CHAPTER_COUNT, false),
                includeCategory = data.getBoolean(KEY_INCLUDE_CATEGORY, false),
                includeIsNovel = data.getBoolean(KEY_INCLUDE_IS_NOVEL, false),
                includeDescription = data.getBoolean(KEY_INCLUDE_DESCRIPTION, false),
                includeTags = data.getBoolean(KEY_INCLUDE_TAGS, false),
            )
        }
    }
}
