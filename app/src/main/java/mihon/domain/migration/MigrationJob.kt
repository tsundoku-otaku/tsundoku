package mihon.domain.migration

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.domain.migration.usecases.MigrateMangaUseCase
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a bulk source migration as a durable background job instead of inside a ViewModel's
 * viewModelScope, which is torn down on process death - a dropped/killed app mid-migration
 * previously lost every entry that hadn't been swapped over yet, with no record it happened.
 */
class MigrationJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val getManga: GetManga by lazy { Injekt.get() }
    private val migrateManga: MigrateMangaUseCase by lazy { Injekt.get() }

    private val notificationBuilder = context.notificationBuilder(Notifications.CHANNEL_MIGRATION) {
        setSmallIcon(android.R.drawable.stat_notify_sync)
        setContentTitle(context.stringResource(MR.strings.action_migrate))
        setContentText("Starting...")
        setOngoing(true)
        setOnlyAlertOnce(true)
        addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.stringResource(MR.strings.action_cancel),
            eu.kanade.tachiyomi.data.notification.NotificationReceiver.cancelMigrationPendingBroadcast(context),
        )
    }

    override suspend fun doWork(): Result {
        val dataFile = inputData.getString(KEY_DATA_FILE)?.let { File(it) }
        val replace = inputData.getBoolean(KEY_REPLACE, true)

        if (dataFile == null || !dataFile.exists()) {
            return Result.failure()
        }

        val (currentIds, targetIds) = try {
            readIdPairs(dataFile)
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, e) { "Failed to read migration job data" }
            return Result.failure()
        } finally {
            dataFile.delete()
        }

        setForegroundSafely()

        val total = currentIds.size
        val completed = AtomicInteger(0)
        val progressNotifier = MigrationProgressNotifier(context, Notifications.ID_MIGRATION_PROGRESS, notificationBuilder)

        suspend fun updateProgress() {
            val done = completed.incrementAndGet()
            setProgress(workDataOf(KEY_PROGRESS_CURRENT to done, KEY_PROGRESS_TOTAL to total))
            progressNotifier.update(done, total)
        }

        return try {
            coroutineScope {
                // Matches the concurrency the old in-ViewModel loop used - each migration
                // involves network calls that benefit from limited parallelism.
                val semaphore = Semaphore(3)
                currentIds.indices.map { i ->
                    async {
                        semaphore.withPermit {
                            val current = getManga.await(currentIds[i])
                            val target = getManga.await(targetIds[i])
                            if (current != null && target != null) {
                                migrateManga(current = current, target = target, replace = replace)
                            }
                            updateProgress()
                        }
                    }
                }.awaitAll()
            }

            notificationBuilder
                .setOngoing(false)
                .setProgress(0, 0, false)
                .setContentText("Migrated ${completed.get()}/$total entries")
                .clearActions()
            context.notify(Notifications.ID_MIGRATION_COMPLETE, notificationBuilder.build())

            Result.success()
        } catch (e: CancellationException) {
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Migration job failed" }
            notificationBuilder
                .setOngoing(false)
                .setProgress(0, 0, false)
                .setContentText(e.message ?: "Migration failed")
                .clearActions()
            context.notify(Notifications.ID_MIGRATION_COMPLETE, notificationBuilder.build())
            Result.failure()
        } finally {
            context.cancelNotification(Notifications.ID_MIGRATION_PROGRESS)
        }
    }

    private fun readIdPairs(file: File): Pair<LongArray, LongArray> {
        return DataInputStream(file.inputStream().buffered()).use { input ->
            val count = input.readInt()
            val current = LongArray(count)
            val target = LongArray(count)
            for (i in 0 until count) {
                current[i] = input.readLong()
                target[i] = input.readLong()
            }
            current to target
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_MIGRATION_PROGRESS,
            notificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        const val TAG = "MigrationJob"
        const val KEY_DATA_FILE = "data_file"
        const val KEY_REPLACE = "replace"
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"

        // WorkManager's Data payload is capped at 10240 bytes when serialized; a large migration
        // batch's id arrays can exceed that on their own, so the ids are written to a cache file
        // instead and only its path travels through Data.
        fun start(context: Context, pairs: List<Pair<Long, Long>>, replace: Boolean) {
            val dataFile = File(context.cacheDir, "migration_job_${System.nanoTime()}.dat")
            DataOutputStream(dataFile.outputStream().buffered()).use { out ->
                out.writeInt(pairs.size)
                pairs.forEach { (current, target) ->
                    out.writeLong(current)
                    out.writeLong(target)
                }
            }
            val data = workDataOf(
                KEY_DATA_FILE to dataFile.absolutePath,
                KEY_REPLACE to replace,
            )
            val request = OneTimeWorkRequestBuilder<MigrationJob>()
                .addTag(TAG)
                .setInputData(data)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }

        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG)
        }
    }
}
