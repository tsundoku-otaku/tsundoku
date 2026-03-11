package eu.kanade.tachiyomi.data.database

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DatabaseMaintenanceJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val handler: DatabaseHandler = Injekt.get()

    override suspend fun doWork(): Result {
        setForegroundSafely()

        return withIOContext {
            try {
                val statsBefore = handler.getDatabaseStats()
                val sizeBefore = statsBefore["total_size_bytes"] ?: 0L

                updateNotification(context.stringResource(TDMR.strings.db_maintenance_running_analyze))
                handler.analyze()

                updateNotification(context.stringResource(TDMR.strings.db_maintenance_running_reindex))
                handler.reindex()

                updateNotification(context.stringResource(TDMR.strings.db_maintenance_running_vacuum))
                handler.vacuum()

                val statsAfter = handler.getDatabaseStats()
                val sizeAfter = statsAfter["total_size_bytes"] ?: 0L
                val saved = sizeBefore - sizeAfter

                val savedStr = when {
                    saved >= 1024 * 1024 -> "%.2f MB".format(saved / (1024.0 * 1024.0))
                    saved >= 1024 -> "%.2f KB".format(saved / 1024.0)
                    else -> "$saved bytes"
                }

                val message = if (saved > 0) {
                    context.stringResource(TDMR.strings.db_maintenance_optimized_saved, savedStr)
                } else {
                    context.stringResource(TDMR.strings.db_maintenance_optimized)
                }
                showComplete(message)
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Database maintenance failed" }
                showComplete(context.stringResource(TDMR.strings.db_maintenance_failed, e.message ?: ""))
                Result.failure()
            } finally {
                context.cancelNotification(Notifications.ID_DB_MAINTENANCE_PROGRESS)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = context.notificationBuilder(Notifications.CHANNEL_DB_MAINTENANCE) {
            setSmallIcon(android.R.drawable.ic_popup_sync)
            setContentTitle(context.stringResource(TDMR.strings.db_maintenance_title))
            setContentText(context.stringResource(TDMR.strings.db_maintenance_starting))
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(0, 0, true)
        }.build()
        return ForegroundInfo(
            Notifications.ID_DB_MAINTENANCE_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun updateNotification(step: String) {
        val notification = context.notificationBuilder(Notifications.CHANNEL_DB_MAINTENANCE) {
            setSmallIcon(android.R.drawable.ic_popup_sync)
            setContentTitle(context.stringResource(TDMR.strings.db_maintenance_title))
            setContentText(step)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(0, 0, true)
        }.build()
        context.notify(Notifications.ID_DB_MAINTENANCE_PROGRESS, notification)
    }

    private fun showComplete(message: String) {
        context.cancelNotification(Notifications.ID_DB_MAINTENANCE_PROGRESS)
        val notification = context.notificationBuilder(Notifications.CHANNEL_DB_MAINTENANCE) {
            setSmallIcon(android.R.drawable.ic_popup_sync)
            setContentTitle(context.stringResource(TDMR.strings.db_maintenance_complete_title))
            setContentText(message)
            setAutoCancel(true)
        }.build()
        context.notify(Notifications.ID_DB_MAINTENANCE_COMPLETE, notification)
    }

    companion object {
        private const val TAG = "DatabaseMaintenanceJob"

        fun start(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<DatabaseMaintenanceJob>()
                .addTag(TAG)
                .build()
            context.workManager.enqueueUniqueWork(
                TAG,
                ExistingWorkPolicy.KEEP,
                workRequest,
            )
        }

        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG)
        }
    }
}
