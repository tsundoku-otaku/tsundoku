package eu.kanade.tachiyomi.data.translation

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.first
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Worker for translating chapters in the background.
 * Handles persistence across app restarts and displays notifications.
 */
class TranslationJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val translationService: TranslationService = Injekt.get()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_TRANSLATION) {
            setContentTitle(applicationContext.stringResource(TDMR.strings.translation_job_translating))
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
        }.build()
        return ForegroundInfo(
            Notifications.ID_TRANSLATION_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        return try {
            setForegroundSafely()

            // Start queue processing if not already running
            translationService.start()

            // Wait for completion by collecting until no longer running
            translationService.progressState.first { progress ->
                if (progress.isRunning) {
                    // Update notification with progress
                    val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_TRANSLATION) {
                        setContentTitle(applicationContext.stringResource(TDMR.strings.translation_job_translating))
                        setContentText(
                            progress.currentChapterName
                                ?: applicationContext.stringResource(TDMR.strings.translation_job_processing),
                        )
                        setProgress(progress.totalChapters, progress.completedChapters, false)
                        setSmallIcon(android.R.drawable.stat_sys_download)
                        setOngoing(true)
                        setOnlyAlertOnce(true)
                    }.build()
                    applicationContext.notify(Notifications.ID_TRANSLATION_PROGRESS, notification)
                    false // keep collecting
                } else {
                    // Done (either finished or was never running)
                    true // stop collecting
                }
            }

            val completed = translationService.progressState.value.completedChapters
            if (completed > 0) {
                showCompletionNotification(completed)
            }
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Translation job failed" }
            Result.retry()
        }
    }

    private fun showCompletionNotification(completedCount: Int) {
        // Cancel progress notification
        applicationContext.notify(
            Notifications.ID_TRANSLATION_PROGRESS,
            applicationContext.notificationBuilder(Notifications.CHANNEL_TRANSLATION) {
                setContentTitle(applicationContext.stringResource(TDMR.strings.translation_job_complete))
                setContentText(
                    applicationContext.stringResource(TDMR.strings.translation_job_complete_count, completedCount),
                )
                setSmallIcon(android.R.drawable.stat_sys_download_done)
                setAutoCancel(true)
                setOngoing(false)
            }.build(),
        )
    }

    companion object {
        private const val TAG = "TranslationJob"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<TranslationJob>()
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}
