package eu.kanade.tachiyomi.network.interceptor

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Exposes which hosts [PerHostDynamicRateLimitInterceptor] is currently sleeping on, and until
 * when, so foreground UI (job progress notifications) can surface an otherwise fully invisible
 * wait instead of it just looking stalled.
 */
object RateLimitWaitTracker {
    private val _waitingUntil = MutableStateFlow<Map<String, Long>>(emptyMap())
    val waitingUntil: StateFlow<Map<String, Long>> = _waitingUntil

    internal fun startWaiting(host: String, untilElapsedRealtime: Long) {
        _waitingUntil.update { it + (host.normalizedRateLimitHost() to untilElapsedRealtime) }
    }

    internal fun stopWaiting(host: String) {
        _waitingUntil.update { it - host.normalizedRateLimitHost() }
    }

    /** Remaining wait (ms) for [host], or null if it isn't currently being waited on. */
    fun remainingMillisFor(host: String): Long? {
        val until = _waitingUntil.value[host.normalizedRateLimitHost()] ?: return null
        val remaining = until - SystemClock.elapsedRealtime()
        return remaining.takeIf { it > 0 }
    }
}
