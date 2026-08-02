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
        begin(normalized)
        try {
            return block()
        } finally {
            end(normalized)
        }
    }

    /**
     * Non-suspend twin of [track] for callers on a plain thread that can't call a suspend
     * function - namely an OkHttp [okhttp3.Interceptor], which always runs synchronously on
     * OkHttp's own dispatcher thread, never the calling coroutine's.
     */
    fun <T> trackBlocking(host: String?, block: () -> T): T {
        val normalized = host?.normalizedRateLimitHost() ?: return block()
        begin(normalized)
        try {
            return block()
        } finally {
            end(normalized)
        }
    }

    private fun begin(normalizedHost: String) {
        activeHosts.merge(normalizedHost, 1, Int::plus)
    }

    private fun end(normalizedHost: String) {
        activeHosts.computeIfPresent(normalizedHost) { _, count -> (count - 1).takeIf { it > 0 } }
    }
}
