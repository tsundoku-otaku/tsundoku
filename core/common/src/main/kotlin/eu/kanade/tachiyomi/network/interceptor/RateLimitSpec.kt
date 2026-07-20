package eu.kanade.tachiyomi.network.interceptor

/**
 * Resolved rate-limit configuration for a host: up to [permits] requests are allowed through
 * in a quick burst within a rolling [delayMillis] window before the caller must wait, with up
 * to [jitterMillis] of randomness added to that wait so it doesn't look like a bot waiting an
 * exact interval.
 */
data class RateLimitSpec(
    val delayMillis: Long,
    val jitterMillis: Long = 0L,
    val permits: Int = 1,
) {
    companion object {
        val NONE = RateLimitSpec(delayMillis = 0L, jitterMillis = 0L, permits = 1)
    }
}
