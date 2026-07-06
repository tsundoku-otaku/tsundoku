package eu.kanade.tachiyomi.network.interceptor

import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Paces outgoing requests per host using a policy that's re-queried on every request, rather
 * than a fixed permits/period baked in at construction like [RateLimitInterceptor]. This is
 * what gives the app real per-request throttling: a novel needing 50 requests naturally takes
 * proportionally longer than one needing 1, since every request goes through this gate, not
 * just once per job item.
 *
 * Within each host's [RateLimitSpec], up to `permits` requests are allowed through in a burst
 * (a sliding window over the last `delayMillis`) before a request has to wait - so a source
 * that's fine with, say, 3 requests in quick succession doesn't get penalized for it, but a
 * long unbroken stream still gets paced.
 *
 * The policy is injected lazily rather than through the constructor because this interceptor
 * is built as part of NetworkHelper's eager client construction, which happens very early in
 * app startup - long before the domain-level policy implementation's own dependencies
 * (SourceManager, etc.) are necessarily ready. Deferring the Injekt lookup to the first actual
 * request avoids that construction-order issue entirely.
 *
 * Actual waits are published to [RateLimitWaitTracker] so foreground UI (job notifications) can
 * show that a wait is happening, rather than it looking like the job stalled.
 */
class PerHostDynamicRateLimitInterceptor : Interceptor {

    private val policy: RequestRateLimitPolicy by injectLazy()
    private val dispatchWindows = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val hostLocks = ConcurrentHashMap<String, Any>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        if (InteractiveRateLimitBypass.isBypassed(host)) {
            return chain.proceed(request)
        }

        val spec = policy.specFor(host)

        if (spec.delayMillis > 0) {
            val lock = hostLocks.computeIfAbsent(host) { Any() }
            synchronized(lock) {
                val window = dispatchWindows.computeIfAbsent(host) { ArrayDeque() }
                var now = SystemClock.elapsedRealtime()

                // Drop dispatches that have aged out of the window.
                while (window.isNotEmpty() && now - window.peekFirst() >= spec.delayMillis) {
                    window.removeFirst()
                }

                if (window.size >= spec.permits) {
                    val jitter = if (spec.jitterMillis > 0) Random.nextLong(0, spec.jitterMillis) else 0L
                    val wait = spec.delayMillis - (now - window.peekFirst()) + jitter
                    if (wait > 0) {
                        RateLimitWaitTracker.startWaiting(host, now + wait)
                        try {
                            Thread.sleep(wait)
                        } finally {
                            RateLimitWaitTracker.stopWaiting(host)
                        }
                    }
                    window.removeFirst()
                    now = SystemClock.elapsedRealtime()
                }

                window.addLast(now)
            }
        }

        return chain.proceed(request)
    }
}
