package tachiyomi.domain.download.service

/**
 * Minimal source info needed to resolve a request's rate-limit spec, gathered from any source
 * type that exposes a base URL - not just [eu.kanade.tachiyomi.source.online.HttpSource]. Lets
 * [SourceRateLimitPolicy] pace JS-plugin and other non-HttpSource sources the same way it paces
 * HttpSource-backed ones.
 */
data class RateLimitCandidate(
    val sourceId: Long,
    val baseUrl: String,
    val isNovel: Boolean,
    val isUnmetered: Boolean,
    val declaredMinimumMillis: Long,
)
