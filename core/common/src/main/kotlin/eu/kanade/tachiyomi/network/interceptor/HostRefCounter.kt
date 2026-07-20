package eu.kanade.tachiyomi.network.interceptor

import java.util.concurrent.ConcurrentHashMap

/**
 * Host-keyed refcount shared by [InteractiveRateLimitBypass] and [BackgroundRateLimitGuard]: both
 * need to know "is at least one in-flight call currently active for this host" without per-call
 * scoping, since OkHttp dispatches calls (and therefore runs interceptors) on its own Dispatcher
 * executor threads, not the calling coroutine's thread.
 */
internal class HostRefCounter {
    private val activeHosts = ConcurrentHashMap<String, Int>()

    fun isActive(host: String): Boolean = activeHosts.containsKey(host.normalizedRateLimitHost())

    suspend fun <T> track(host: String?, block: suspend () -> T): T {
        val normalized = host?.normalizedRateLimitHost() ?: return block()
        activeHosts.merge(normalized, 1, Int::plus)
        try {
            return block()
        } finally {
            activeHosts.computeIfPresent(normalized) { _, count -> (count - 1).takeIf { it > 0 } }
        }
    }
}
