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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.domain.migration.usecases.MigrateMangaUseCase
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
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
        setContentText(context.stringResource(TDMR.strings.migrate_notification_starting))
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
            clearActiveIds(context)
            return Result.failure()
        }

        val (currentIds, targetIds) = try {
            readIdPairs(dataFile)
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, e) { "Failed to read migration job data" }
            dataFile.delete()
            clearActiveIds(context)
            return Result.failure()
        }

        setForegroundSafely()

        val total = currentIds.size
        val completed = AtomicInteger(0)
        val progressNotifier = MigrationProgressNotifier(context, Notifications.ID_MIGRATION_PROGRESS, notificationBuilder)
        // Serializes increment+publish together so concurrent workers can't publish progress
        // out of order (the increment alone is atomic, but publishing it isn't tied to that
        // order without a lock spanning both).
        val progressMutex = Mutex()

        suspend fun updateProgress() {
            progressMutex.withLock {
                val done = completed.incrementAndGet()
                setProgress(workDataOf(KEY_PROGRESS_CURRENT to done, KEY_PROGRESS_TOTAL to total))
                progressNotifier.update(done, total)
            }
        }

        return try {
            coroutineScope {
                // Matches the concurrency the old in-ViewModel loop used - each migration
                // involves network calls that benefit from limited parallelism.
                val semaphore = Semaphore(3)
                currentIds.indices.map { i ->
                    async {
                        semaphore.withPermit {
                            try {
                                val current = getManga.await(currentIds[i])
                                val target = getManga.await(targetIds[i])
                                if (current != null && target != null) {
                                    migrateManga(current = current, target = target, replace = replace)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR, e) { "Failed to migrate manga at index $i" }
                            } finally {
                                updateProgress()
                            }
                        }
                    }
                }.awaitAll()
            }

            notificationBuilder
                .setOngoing(false)
                .setProgress(0, 0, false)
                .setContentText(
                    context.stringResource(TDMR.strings.migrate_notification_result, completed.get(), total),
                )
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
                .setContentText(e.message ?: context.stringResource(TDMR.strings.migrate_notification_failed))
                .clearActions()
            context.notify(Notifications.ID_MIGRATION_COMPLETE, notificationBuilder.build())
            Result.failure()
        } finally {
            context.cancelNotification(Notifications.ID_MIGRATION_PROGRESS)
            clearActiveIds(context)
            // Only deleted once the run has actually finished (success, definitive failure, or
            // cancellation) - not right after being read - so a WorkManager retry after process
            // death (this finally block never runs if the process is killed mid-migration) can
            // still find the file and resume instead of failing outright with no data to work from.
            dataFile.delete()
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

        private const val PREFS_NAME = "migration_job_state"
        private const val KEY_ACTIVE_CURRENT_IDS = "active_current_ids"

        // WorkManager's Data payload is capped at 10240 bytes when serialized; a large migration
        // batch's id arrays can exceed that on their own, so the ids are written to a cache file
        // instead and only its path travels through Data.
        //
        // Returns false if a job was already running: with ExistingWorkPolicy.KEEP, an existing
        // unique job silently drops the new request instead of queuing it, so the request's own
        // WorkInfo is checked to tell that apart from a genuine enqueue - otherwise the caller has
        // no way to know its request was a no-op, and the active-ids below would clobber the
        // running job's own set out from under isRunningFor().
        //
        // activeIds defaults to pairs' current-side ids but should be passed explicitly as the
        // screen's full original manga selection: pairs only contains entries that had a
        // successful search match, while isRunningFor() is compared against a freshly (re)created
        // ViewModel's full ctor manga set, matches included or not.
        fun start(
            context: Context,
            pairs: List<Pair<Long, Long>>,
            replace: Boolean,
            activeIds: Collection<Long> = pairs.map { it.first },
        ): Boolean {
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
            val workManager = context.workManager
            workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request).result.get()
            if (workManager.getWorkInfoById(request.id).get() == null) {
                dataFile.delete()
                return false
            }
            // Persisted separately from the WorkManager Data above (which is deleted off the
            // cache file once doWork() reads it) so a freshly (re)created ViewModel can tell
            // whether an already-running job is for its own manga set - see isRunningFor().
            //
            // Written with commit() rather than apply(): a concurrently (re)created ViewModel's
            // isRunningFor() can already see isRunning() == true right after the enqueue above,
            // so an async apply() would leave a window where active ids aren't visible yet and
            // isRunningFor() wrongly returns false for this job's own manga set.
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_ACTIVE_CURRENT_IDS, activeIds.mapTo(mutableSetOf()) { it.toString() })
                .commit()
            return true
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
            clearActiveIds(context)
        }

        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG, includeEnqueued = true)
        }

        // MigrationJob is unique work, so at most one batch can be running at a time, but a
        // screen reattaching in init() still needs to know whether that running job is *its*
        // batch or an unrelated one left over from a different manga selection - otherwise it
        // would skip its own search phase and report false success once the unrelated job ends.
        fun isRunningFor(context: Context, mangaIds: Collection<Long>): Boolean {
            if (!isRunning(context)) return false
            val active = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_ACTIVE_CURRENT_IDS, null)
                ?: return false
            // Full-set match, not partial overlap: two unrelated selections that merely share
            // one manga id must not be treated as the same running job.
            return mangaIds.mapTo(mutableSetOf()) { it.toString() } == active
        }

        private fun clearActiveIds(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_ACTIVE_CURRENT_IDS)
                .apply()
        }
    }
}
