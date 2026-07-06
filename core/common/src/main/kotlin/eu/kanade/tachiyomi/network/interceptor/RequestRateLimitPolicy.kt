package eu.kanade.tachiyomi.network.interceptor

/**
 * Resolves how long to wait before dispatching a request to a given host. Kept in
 * core/common (no domain dependency) so [PerHostDynamicRateLimitInterceptor] can be wired
 * into NetworkHelper's client without core/common depending on the domain module; the real
 * implementation is registered via Injekt from the app module instead.
 */
fun interface RequestRateLimitPolicy {
    /** Delay (ms) to wait before dispatching a request to [host]; 0 means no throttling. */
    fun delayMillisFor(host: String): Long
}
