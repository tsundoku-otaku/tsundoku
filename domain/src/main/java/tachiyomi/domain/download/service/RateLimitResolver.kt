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

        return resolveIgnoringToggle(sourceId, declaredMinimumMillis)
    }

    /**
     * The spec applied to a host that isn't recognized as any installed source's baseUrl or a
     * domain-suffix match of one - e.g. a host an extension calls that [SourceRateLimitPolicy]
     * can't attribute to a specific source. Falls back to the same global defaults a source
     * without an override would get, rather than [RateLimitSpec.NONE] - an unrecognized host is
     * an unknown risk, not a known-safe one, so it shouldn't be exempt from throttling by default.
     */
    fun resolveDefault(): RateLimitSpec {
        if (!prefs.enableRequestThrottling().get()) return RateLimitSpec.NONE

        return resolveDefaultIgnoringToggle()
    }

    /**
     * Same as [resolve], but ignoring [NovelDownloadPreferences.enableRequestThrottling] - the
     * spec [sourceId] would get if throttling were on. Exists purely for
     * [SourceRateLimitPolicy]'s diagnostic logging: the toggle being off makes every real request
     * come back as [RateLimitSpec.NONE], which would otherwise hide a host that's going through
     * the paced client (i.e. wasn't exempted via `rateLimitExempt()`) but happens to only be
     * getting caught right now because someone has throttling disabled for testing.
     */
    fun resolveIgnoringToggle(sourceId: Long, declaredMinimumMillis: Long = 0L): RateLimitSpec {
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

    /** Toggle-ignoring twin of [resolveDefault] - see [resolveIgnoringToggle]. */
    fun resolveDefaultIgnoringToggle(): RateLimitSpec = RateLimitSpec(
        delayMillis = prefs.requestDelay().get().toLong(),
        jitterMillis = prefs.requestJitter().get().toLong(),
        permits = prefs.requestPermits().get().coerceAtLeast(1),
    )

    /** Whether the user currently has request throttling enabled at all. */
    fun isThrottlingEnabled(): Boolean = prefs.enableRequestThrottling().get()
}
