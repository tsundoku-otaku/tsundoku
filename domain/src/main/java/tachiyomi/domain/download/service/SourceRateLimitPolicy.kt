package tachiyomi.domain.download.service

import eu.kanade.tachiyomi.network.interceptor.RateLimitSpec
import eu.kanade.tachiyomi.network.interceptor.RequestRateLimitPolicy
import eu.kanade.tachiyomi.network.interceptor.normalizedRateLimitHost
import eu.kanade.tachiyomi.source.RateLimited
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.isNovelSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.source.service.SourceManager

/**
 * Concrete [RequestRateLimitPolicy] backed by the app's sources and preferences. Resolves a
 * request's target host back to the source that owns it, then asks [RateLimitResolver] for the
 * actual spec, floored by whatever minimum delay that source declares via [RateLimited].
 */
class SourceRateLimitPolicy(
    private val sourceManager: SourceManager,
    private val resolver: RateLimitResolver,
) : RequestRateLimitPolicy {

    override fun specFor(host: String): RateLimitSpec {
        val normalizedHost = host.normalizedRateLimitHost()
        val source = sourceManager.getOnlineSources()
            .firstOrNull { it.baseUrl.toHttpUrlOrNull()?.host?.normalizedRateLimitHost() == normalizedHost }
            ?: return RateLimitSpec.NONE

        // A source explicitly declaring it doesn't need traffic considerations (e.g. a
        // self-hosted server) is honored regardless of novel/RateLimited status.
        if (source is UnmeteredSource) return RateLimitSpec.NONE

        val declaredMinimum = (source as? RateLimited)?.minimumDelayMillis ?: 0L

        // Preserve today's scope: throttling targets novel sources by default. A manga
        // source is only paced if it opts in by declaring its own RateLimited minimum.
        if (!source.isNovelSource() && declaredMinimum == 0L) return RateLimitSpec.NONE

        return resolver.resolve(source.id, declaredMinimum)
    }
}
