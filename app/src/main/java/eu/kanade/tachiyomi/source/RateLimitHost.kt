package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The base URL of this source, for the source types rate-limiting cares about - HttpSource and
 * JsSource (JS-plugin sources). A plain `as? HttpSource` cast silently misses JsSource, leaving
 * their interactive-bypass/background-guard/candidate-scanning state untracked wherever that
 * narrower cast was used instead - even though JsSource requests flow through the same
 * rate-limited OkHttp client. Shared by AndroidSourceManager.getRateLimitCandidates() and
 * [rateLimitHost] so the two can't drift on which source types are covered.
 */
fun Source?.rateLimitBaseUrl(): String? = when (this) {
    is HttpSource -> baseUrl
    is JsSource -> baseUrl
    else -> null
}

/**
 * The host used to key rate-limit pacing/bypass state for this source.
 */
fun Source?.rateLimitHost(): String? = rateLimitBaseUrl()?.toHttpUrlOrNull()?.host
