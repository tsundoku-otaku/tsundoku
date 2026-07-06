package tachiyomi.domain.download.service

import kotlin.random.Random

/**
 * Resolves the actual delay to apply before a request to a given source, combining global
 * defaults, any per-source override, and a source's own declared minimum. This is the single
 * place that logic lives, replacing what used to be duplicated across the download, library
 * update, and mass import jobs.
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
    fun resolveDelayMillis(sourceId: Long, declaredMinimumMillis: Long = 0L): Long {
        if (!prefs.enableRequestThrottling().get()) return declaredMinimumMillis

        val override = prefs.getSourceOverride(sourceId)
        val (delay, jitter) = if (override?.enabled == true) {
            (override.delayMillis ?: prefs.requestDelay().get()) to
                (override.jitterMillis ?: prefs.requestJitter().get())
        } else {
            prefs.requestDelay().get() to prefs.requestJitter().get()
        }

        // Jitter is randomized on purpose: the goal is to look like a natural user's
        // varying pace, not a bot waiting an exact, easily fingerprinted interval.
        val base = delay.toLong() + if (jitter > 0) Random.nextLong(0, jitter.toLong()) else 0L
        return maxOf(base, declaredMinimumMillis)
    }
}
