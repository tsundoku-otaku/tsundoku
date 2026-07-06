package eu.kanade.tachiyomi.network.interceptor

import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap

/**
 * Paces outgoing requests per host using a policy that's re-queried on every request, rather
 * than a fixed permits/period baked in at construction like [RateLimitInterceptor]. This is
 * what gives the app real per-request throttling: a novel needing 50 requests naturally takes
 * proportionally longer than one needing 1, since every request goes through this gate, not
 * just once per job item.
 *
 * The policy is injected lazily rather than through the constructor because this interceptor
 * is built as part of NetworkHelper's eager client construction, which happens very early in
 * app startup - long before the domain-level policy implementation's own dependencies
 * (SourceManager, etc.) are necessarily ready. Deferring the Injekt lookup to the first actual
 * request avoids that construction-order issue entirely.
 */
class PerHostDynamicRateLimitInterceptor : Interceptor {

    private val policy: RequestRateLimitPolicy by injectLazy()
    private val lastDispatch = ConcurrentHashMap<String, Long>()
    private val hostLocks = ConcurrentHashMap<String, Any>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val minInterval = policy.delayMillisFor(host)

        if (minInterval > 0) {
            val lock = hostLocks.computeIfAbsent(host) { Any() }
            synchronized(lock) {
                val wait = minInterval - (SystemClock.elapsedRealtime() - (lastDispatch[host] ?: 0L))
                if (wait > 0) Thread.sleep(wait)
                lastDispatch[host] = SystemClock.elapsedRealtime()
            }
        }

        return chain.proceed(request)
    }
}
