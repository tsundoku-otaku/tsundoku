package tachiyomi.domain.download.service

import eu.kanade.tachiyomi.network.interceptor.RateLimitSpec

/**
 * Resolves the actual rate-limit spec to apply to a given source, combining global defaults,
 * any per-source override, and a source's own declared minimum delay. This is the single place
 * that logic lives, replacing what used to be duplicated across the download, library update,
 * and mass import jobs.
 *
 * Jitter randomization happens in the interceptor at the point a wait is actually enforced, not
 * here - with request bursts (permits > 1), most calls resolve a spec without ever waiting, so
 * randomizing per-resolve would be meaningless for those and wrong for the ones that do wait.
 */
class RateLimitResolver(
    private val prefs: NovelDownloadPreferences,
) {
    /**
     * @param declaredMinimumMillis the source's own [eu.kanade.tachiyomi.source.RateLimited]
     * minimum, if any. The resolved delay never goes below this, even if the user has
     * throttling disabled or configured a lower override - an extension author's stated
     * minimum is a floor, not a suggestion.
     */
    fun resolve(sourceId: Long, declaredMinimumMillis: Long = 0L): RateLimitSpec {
        if (!prefs.enableRequestThrottling().get()) {
            return if (declaredMinimumMillis > 0) {
                RateLimitSpec(delayMillis = declaredMinimumMillis)
            } else {
                RateLimitSpec.NONE
            }
        }

        val override = prefs.getSourceOverride(sourceId)
        val (delay, jitter, permits) = if (override?.enabled == true) {
            Triple(
                override.delayMillis ?: prefs.requestDelay().get(),
                override.jitterMillis ?: prefs.requestJitter().get(),
                override.permits ?: prefs.requestPermits().get(),
            )
        } else {
            Triple(prefs.requestDelay().get(), prefs.requestJitter().get(), prefs.requestPermits().get())
        }

        return RateLimitSpec(
            delayMillis = maxOf(delay.toLong(), declaredMinimumMillis),
            jitterMillis = jitter.toLong(),
            permits = permits.coerceAtLeast(1),
        )
    }
}
