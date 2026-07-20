package tachiyomi.domain.download.service

import eu.kanade.tachiyomi.network.interceptor.RateLimitSpec
import eu.kanade.tachiyomi.network.interceptor.RequestRateLimitPolicy
import eu.kanade.tachiyomi.network.interceptor.normalizedRateLimitHost
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.source.service.SourceManager

/**
 * Concrete [RequestRateLimitPolicy] backed by the app's sources and preferences. Resolves a
 * request's target host back to the source that owns it, then asks [RateLimitResolver] for the
 * actual spec, floored by whatever minimum delay that source declares via
 * [eu.kanade.tachiyomi.source.RateLimited].
 */
class SourceRateLimitPolicy(
    private val sourceManager: SourceManager,
    private val resolver: RateLimitResolver,
) : RequestRateLimitPolicy {

    @Volatile
    private var cachedIndex: Map<String, RateLimitCandidate> = emptyMap()

    @Volatile
    private var cachedAtNanos: Long? = null

    override fun specFor(host: String): RateLimitSpec {
        val candidate = hostIndex()[host.normalizedRateLimitHost()] ?: return RateLimitSpec.NONE

        // A source explicitly declaring it doesn't need traffic considerations (e.g. a
        // self-hosted server) is honored regardless of novel/RateLimited status.
        if (candidate.isUnmetered) return RateLimitSpec.NONE

        // Preserve today's scope: throttling targets novel sources by default. A manga
        // source is only paced if it opts in by declaring its own RateLimited minimum.
        if (!candidate.isNovel && candidate.declaredMinimumMillis == 0L) return RateLimitSpec.NONE

        return resolver.resolve(candidate.sourceId, candidate.declaredMinimumMillis)
    }

    /**
     * [SourceManager.getRateLimitCandidates] re-derives its list from the full source map on
     * every call, and resolving a host requires parsing every source's baseUrl - fine once,
     * wasteful when redone on every single HTTP request a job fires off. The installed source
     * set only actually changes when an extension is installed/uninstalled, so a short cache is
     * safe: at worst a just-installed source goes unthrottled for up to [CACHE_TTL_NANOS].
     *
     * [SourceManager.isInitialized] guards against a narrower race: this interceptor is wired up
     * very early in app startup, before the source manager has necessarily finished loading
     * extensions. Caching whatever (possibly empty) list it returns before then for the full TTL
     * would leave a real source's requests unthrottled for that whole window - so until the
     * source manager reports itself initialized, every call recomputes instead of caching.
     */
    private fun hostIndex(): Map<String, RateLimitCandidate> {
        val now = System.nanoTime()
        cachedAtNanos?.let { cachedAt -> if (now - cachedAt < CACHE_TTL_NANOS) return cachedIndex }

        synchronized(this) {
            val recheckNow = System.nanoTime()
            cachedAtNanos?.let { cachedAt -> if (recheckNow - cachedAt < CACHE_TTL_NANOS) return cachedIndex }

            cachedIndex = sourceManager.getRateLimitCandidates()
                .mapNotNull { candidate ->
                    val host = candidate.baseUrl.toHttpUrlOrNull()?.host?.normalizedRateLimitHost()
                        ?: return@mapNotNull null
                    host to candidate
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, candidates) -> candidates.mostRestrictive() }
            cachedAtNanos = if (sourceManager.isInitialized.value) recheckNow else null
            return cachedIndex
        }
    }

    /**
     * Two installed sources sharing a baseUrl host (a multi-language extension variant, a custom
     * source pointed at another extension's host, etc.) can't both occupy the same index slot.
     * Rather than let map-building order silently pick a winner, prefer whichever candidate would
     * apply the most conservative pacing - erring toward throttling the shared host protects the
     * actual server even if one colliding source is misconfigured or opts out.
     */
    private fun List<RateLimitCandidate>.mostRestrictive(): RateLimitCandidate {
        val metered = filterNot { it.isUnmetered }
        val pool = metered.ifEmpty { this }
        return pool.sortedWith(
            compareByDescending<RateLimitCandidate> { it.declaredMinimumMillis }
                .thenByDescending { it.isNovel }
                .thenBy { it.sourceId },
        ).first()
    }

    private companion object {
        const val CACHE_TTL_NANOS = 5_000_000_000L // 5 seconds
    }
}
