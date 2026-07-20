package eu.kanade.tachiyomi.network.interceptor

/**
 * Normalizes a host for rate-limit bookkeeping so "www.example.com" and "example.com" are
 * treated as the same target. A source's declared baseUrl and the hosts its actual requests
 * land on aren't always consistent about the "www." prefix, and treating them as distinct hosts
 * would split traffic across two untracked windows instead of pacing it together.
 */
fun String.normalizedRateLimitHost(): String = lowercase().removePrefix("www.")
