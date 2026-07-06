package eu.kanade.tachiyomi.network.interceptor

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs [block], polling [RateLimitWaitTracker] for [host] every [pollMillis] and invoking
 * [onWaitChanged] with the remaining wait (ms), or null when there's no active wait - including
 * once more, with null, right before returning. Lets a caller (e.g. a job's progress
 * notification) surface an otherwise-silent interceptor-level wait.
 *
 * No-op passthrough when [host] is null (e.g. a non-HTTP source), since there's nothing to poll.
 */
suspend fun <T> withRateLimitWaitUpdates(
    host: String?,
    pollMillis: Long = 1000,
    onWaitChanged: (remainingMillis: Long?) -> Unit,
    block: suspend () -> T,
): T {
    if (host == null) return block()

    return coroutineScope {
        val ticker = launch {
            while (isActive) {
                onWaitChanged(RateLimitWaitTracker.remainingMillisFor(host))
                delay(pollMillis)
            }
        }
        try {
            block()
        } finally {
            ticker.cancel()
            onWaitChanged(null)
        }
    }
}
