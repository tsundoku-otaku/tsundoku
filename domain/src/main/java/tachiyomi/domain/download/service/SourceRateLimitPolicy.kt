package tachiyomi.domain.download.service

import eu.kanade.tachiyomi.network.interceptor.RequestRateLimitPolicy
import eu.kanade.tachiyomi.source.RateLimited
import eu.kanade.tachiyomi.source.isNovelSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.source.service.SourceManager

/**
 * Concrete [RequestRateLimitPolicy] backed by the app's sources and preferences. Resolves a
 * request's target host back to the source that owns it, then asks [RateLimitResolver] for the
 * actual delay, floored by whatever minimum that source declares via [RateLimited].
 */
class SourceRateLimitPolicy(
    private val sourceManager: SourceManager,
    private val resolver: RateLimitResolver,
) : RequestRateLimitPolicy {

    override fun delayMillisFor(host: String): Long {
        val source = sourceManager.getOnlineSources()
            .firstOrNull { it.baseUrl.toHttpUrlOrNull()?.host == host }
            ?: return 0L

        val declaredMinimum = (source as? RateLimited)?.minimumDelayMillis ?: 0L

        // Preserve today's scope: throttling targets novel sources by default. A manga
        // source is only paced if it opts in by declaring its own RateLimited minimum.
        if (!source.isNovelSource() && declaredMinimum == 0L) return 0L

        return resolver.resolveDelayMillis(source.id, declaredMinimum)
    }
}
