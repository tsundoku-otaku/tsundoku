package mihon.domain.migration

import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.util.system.notify

/**
 * Throttles progress-notification updates for a durable migration WorkManager job so a fast loop
 * (e.g. hundreds of small entries) doesn't spam NotificationManager on every single item. Always
 * pushes the final update, even inside the throttle window, so the notification never gets stuck
 * short of 100%.
 *
 * MigrationJob drives this from several concurrently-running coroutines (bounded by a semaphore),
 * so both the throttle check and the builder mutation are synchronized - otherwise two callers can
 * race past the throttle check together and mutate the shared builder concurrently, garbling the
 * notification text and reordering the WorkManager progress updates.
 */
class MigrationProgressNotifier(
    private val context: Context,
    private val notificationId: Int,
    private val builder: NotificationCompat.Builder,
    private val intervalMs: Long = 500L,
) {
    private var lastNotifyAt = 0L

    @Synchronized
    fun update(done: Int, total: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < intervalMs && done < total) return
        lastNotifyAt = now
        builder
            .setContentText("$done/$total")
            .setProgress(total, done, false)
        context.notify(notificationId, builder.build())
    }
}
