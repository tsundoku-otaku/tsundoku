package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Permanently exempts every request made through a client carrying this interceptor from
 * [PerHostDynamicRateLimitInterceptor]'s pacing - and, now that an unrecognized host falls back
 * to the global default spec instead of [RateLimitSpec.NONE], from that default too.
 *
 * For network consumers that share the app's rate-limited [eu.kanade.tachiyomi.network.NetworkHelper.client]
 * but aren't novel/manga sources - trackers, translation engines, the extension/app updater,
 * font downloads - and so were never meant to be paced by novel-source throttling settings.
 * Marks the request's host as [InteractiveRateLimitBypass]-active for the duration of the call,
 * the same mechanism [MangaCoverFetcher][eu.kanade.tachiyomi.data.coil.MangaCoverFetcher] and
 * [MangaScreenModel][eu.kanade.tachiyomi.ui.manga.MangaScreenModel] already use for interactive
 * fetches, just applied once at client construction instead of wrapped around every call site -
 * these consumers are unconditionally exempt, not just for the duration of one foreground fetch.
 *
 * Not meant to be added via [OkHttpClient.Builder.addInterceptor] directly - use
 * [rateLimitExempt] instead, which installs it *before* [PerHostDynamicRateLimitInterceptor]
 * in the chain. `addInterceptor` always appends, and [PerHostDynamicRateLimitInterceptor] is
 * already baked into the base [eu.kanade.tachiyomi.network.NetworkHelper.client] every one of
 * these consumers builds on top of via `newBuilder()` - appending this interceptor would run it
 * *after* the rate limiter has already paced (or wrongly conservative-defaulted) the request,
 * too late to matter.
 */
private class RateLimitExemptInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        return InteractiveRateLimitBypass.bypassingBlocking(host) { chain.proceed(chain.request()) }
    }
}

/**
 * Returns a client identical to this one but exempt from source rate-limiting, for non-source
 * network consumers (trackers, translation engines, app/extension updaters, font downloads).
 * See [RateLimitExemptInterceptor] for why this must insert at the front of the chain rather
 * than via a plain `addInterceptor` call.
 */
fun OkHttpClient.rateLimitExempt(): OkHttpClient = newBuilder()
    .apply { interceptors().add(0, RateLimitExemptInterceptor()) }
    .build()
