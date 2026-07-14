package eu.kanade.tachiyomi.network.interceptor

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which hosts a background job (library update, mass import, chapter download) is
 * currently doing work against, so [PerHostDynamicRateLimitInterceptor] can refuse to honor an
 * [InteractiveRateLimitBypass] for that host while a background job is active there. Without
 * this, a background job's own requests could silently ride along on an unrelated foreground
 * bypass (e.g. the user browsing the same source while a library update runs) and burst past
 * the host's declared safe rate - the exact bursting this whole mechanism exists to prevent.
 *
 * Host-keyed refcount for the same reason [InteractiveRateLimitBypass] is: OkHttp dispatches
 * calls on its own Dispatcher executor threads, not the calling coroutine's, so per-request
 * scoping isn't available without tagging every Request built by extension code.
 *
 * This is coarser than per-call scoping: while a background job is active on a host, an
 * interactive bypass for that same host is suppressed too, not just the background job's own
 * requests. That's an acceptable tradeoff - a brief UX-lag window during a rare overlap - in
 * exchange for never letting a background job's traffic escape pacing.
 */
object BackgroundRateLimitGuard {
    private val activeHosts = ConcurrentHashMap<String, Int>()

    fun isActive(host: String): Boolean = activeHosts.containsKey(host.normalizedRateLimitHost())

    suspend fun <T> active(host: String?, block: suspend () -> T): T {
        val normalized = host?.normalizedRateLimitHost() ?: return block()
        activeHosts.merge(normalized, 1, Int::plus)
        try {
            return block()
        } finally {
            activeHosts.computeIfPresent(normalized) { _, count -> (count - 1).takeIf { it > 0 } }
        }
    }
}
