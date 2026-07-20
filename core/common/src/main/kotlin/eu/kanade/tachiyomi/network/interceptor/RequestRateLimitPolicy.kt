package eu.kanade.tachiyomi.network.interceptor

/**
 * Resolves the rate-limit spec to apply to requests for a given host. Kept in core/common (no
 * domain dependency) so [PerHostDynamicRateLimitInterceptor] can be wired into NetworkHelper's
 * client without core/common depending on the domain module; the real implementation is
 * registered via Injekt from the app module instead.
 */
fun interface RequestRateLimitPolicy {
    /** Rate-limit spec for [host]; [RateLimitSpec.NONE] means no throttling. */
    fun specFor(host: String): RateLimitSpec
}
