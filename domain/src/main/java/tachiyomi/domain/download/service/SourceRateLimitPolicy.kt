package tachiyomi.domain.download.service

import eu.kanade.tachiyomi.network.interceptor.RateLimitSpec
import eu.kanade.tachiyomi.network.interceptor.RequestRateLimitPolicy
import eu.kanade.tachiyomi.network.interceptor.normalizedRateLimitHost
import eu.kanade.tachiyomi.source.RateLimited
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.online.HttpSource
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

    @Volatile
    private var cachedIndex: Map<String, HttpSource> = emptyMap()

    @Volatile
    private var cachedAtNanos: Long? = null

    override fun specFor(host: String): RateLimitSpec {
        val source = hostIndex()[host.normalizedRateLimitHost()] ?: return RateLimitSpec.NONE

        // A source explicitly declaring it doesn't need traffic considerations (e.g. a
        // self-hosted server) is honored regardless of novel/RateLimited status.
        if (source is UnmeteredSource) return RateLimitSpec.NONE

        val declaredMinimum = (source as? RateLimited)?.minimumDelayMillis ?: 0L

        // Preserve today's scope: throttling targets novel sources by default. A manga
        // source is only paced if it opts in by declaring its own RateLimited minimum.
        if (!source.isNovelSource() && declaredMinimum == 0L) return RateLimitSpec.NONE

        return resolver.resolve(source.id, declaredMinimum)
    }

    /**
     * [SourceManager.getOnlineSources] re-derives its list from the full source map on every
     * call, and resolving a host requires parsing every source's baseUrl - fine once, wasteful
     * when redone on every single HTTP request a job fires off. The installed source set only
     * actually changes when an extension is installed/uninstalled, so a short cache is safe: at
     * worst a just-installed source goes unthrottled for up to [CACHE_TTL_NANOS].
     */
    private fun hostIndex(): Map<String, HttpSource> {
        val now = System.nanoTime()
        cachedAtNanos?.let { cachedAt -> if (now - cachedAt < CACHE_TTL_NANOS) return cachedIndex }

        synchronized(this) {
            val recheckNow = System.nanoTime()
            cachedAtNanos?.let { cachedAt -> if (recheckNow - cachedAt < CACHE_TTL_NANOS) return cachedIndex }

            cachedIndex = sourceManager.getOnlineSources().mapNotNull { source ->
                val host = source.baseUrl.toHttpUrlOrNull()?.host?.normalizedRateLimitHost()
                    ?: return@mapNotNull null
                host to source
            }.toMap()
            cachedAtNanos = recheckNow
            return cachedIndex
        }
    }

    private companion object {
        const val CACHE_TTL_NANOS = 5_000_000_000L // 5 seconds
    }
}
