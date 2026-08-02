package eu.kanade.tachiyomi.network.interceptor

/**
 * Normalizes a host for rate-limit bookkeeping so "www.example.com" and "example.com" are
 * treated as the same target. A source's declared baseUrl and the hosts its actual requests
 * land on aren't always consistent about the "www." prefix, and treating them as distinct hosts
 * would split traffic across two untracked windows instead of pacing it together.
 */
fun String.normalizedRateLimitHost(): String = lowercase().removePrefix("www.")

/**
 * A best-effort approximation of this host's registrable domain - e.g. "api.example.com" and
 * "cdn.example.com" both resolve to "example.com" - or null if the host doesn't have enough
 * labels to derive one (a bare TLD, localhost, etc). Deliberately not OkHttp's
 * [okhttp3.HttpUrl.topPrivateDomain]: that needs its bundled PublicSuffixDatabase.list resource
 * on the classpath, which isn't reliably present in every module that ends up calling this (it
 * threw `IllegalStateException: Unable to load PublicSuffixDatabase.list resource.` in :domain's
 * plain-JUnit unit tests). A plain last-two-labels split is wrong for multi-part public suffixes
 * like "example.co.uk" (would compare as "co.uk"), but the only consequence of a false-positive
 * match here is pacing an unrelated host alongside a source that happens to share those two
 * labels - still throttled, just maybe grouped with the wrong source's window. Erring toward
 * "still gets paced" is an acceptable tradeoff for not depending on a resource file.
 */
fun String.topPrivateDomainOrNull(): String? {
    val labels = lowercase().removePrefix("www.").split('.')
    if (labels.size < 2) return null
    return labels.takeLast(2).joinToString(".")
}
