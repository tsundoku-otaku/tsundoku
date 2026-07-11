package eu.kanade.tachiyomi.network.interceptor

import java.util.concurrent.ConcurrentHashMap

/**
 * Lets an interactive, user-initiated fetch (e.g. opening a novel that's never been loaded, or
 * tapping refresh) skip [PerHostDynamicRateLimitInterceptor]'s pacing for the duration of that
 * one fetch, while background jobs (library update, mass import, chapter downloads) stay fully
 * throttled. Without this, a foreground screen the user is actively staring at can block for the
 * same multi-second-per-request pacing that's appropriate for a large unattended batch job.
 *
 * Keyed by host rather than thread/coroutine context on purpose: OkHttp dispatches calls (and
 * therefore runs interceptors) on its own Dispatcher executor threads, not the calling
 * coroutine's thread, so a ThreadLocal or CoroutineContext element set by the caller would never
 * be visible inside intercept(). A host-keyed refcount works regardless of which thread actually
 * ends up executing the request.
 */
object InteractiveRateLimitBypass {
    private val bypassedHosts = ConcurrentHashMap<String, Int>()

    fun isBypassed(host: String): Boolean = bypassedHosts.containsKey(host.normalizedRateLimitHost())

    suspend fun <T> bypassing(host: String?, block: suspend () -> T): T {
        val normalized = host?.normalizedRateLimitHost() ?: return block()
        bypassedHosts.merge(normalized, 1, Int::plus)
        try {
            return block()
        } finally {
            bypassedHosts.computeIfPresent(normalized) { _, count -> (count - 1).takeIf { it > 0 } }
        }
    }
}
