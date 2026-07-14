package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The host used to key rate-limit pacing/bypass state for this source - mirrors
 * AndroidSourceManager.getRateLimitCandidates()'s notion of "a source with a base URL," which
 * isn't just HttpSource. A plain `as? HttpSource` cast (the pattern this replaces) silently
 * misses JsSource (JS-plugin sources), leaving their interactive-bypass/background-guard state
 * untracked wherever that narrower cast was used instead - even though JsSource requests flow
 * through the same rate-limited OkHttp client.
 */
fun Source?.rateLimitHost(): String? {
    val baseUrl = when (this) {
        is HttpSource -> baseUrl
        is JsSource -> baseUrl
        else -> return null
    }
    return baseUrl.toHttpUrlOrNull()?.host
}
