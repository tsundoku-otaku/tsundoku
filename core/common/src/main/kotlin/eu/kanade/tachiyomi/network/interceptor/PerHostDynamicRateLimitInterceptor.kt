package eu.kanade.tachiyomi.network.interceptor

import android.os.SystemClock
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException
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
        val call = chain.call()
        if (call.isCanceled()) throw IOException("Canceled")

        val request = chain.request()
        val host = request.url.host.normalizedRateLimitHost()

        if (InteractiveRateLimitBypass.isBypassed(host) && !BackgroundRateLimitGuard.isActive(host)) {
            return chain.proceed(request)
        }

        val spec = policy.specFor(host)

        if (spec.delayMillis > 0) {
            val lock = hostLocks.computeIfAbsent(host) { Any() }
            // Compute the required wait under the lock, then sleep with the lock released so other
            // threads can pace against their own window instead of blocking on the monitor. A slot
            // is only ever taken under the lock while the window has room, so the permit bound holds
            // even though several threads may be waiting concurrently.
            var hasWaited = false
            while (true) {
                var wait = 0L
                synchronized(lock) {
                    val window = dispatchWindows.computeIfAbsent(host) { ArrayDeque() }
                    val now = SystemClock.elapsedRealtime()

                    // Drop dispatches that have aged out of the window.
                    while (window.isNotEmpty() && now - window.peekFirst() >= spec.delayMillis) {
                        window.removeFirst()
                    }

                    if (window.size < spec.permits) {
                        window.addLast(now)
                    } else if (hasWaited) {
                        // This request already served its wait, so claim a slot by evicting the
                        // oldest rather than looping until it ages out. The wait we paid is the
                        // pacing; relying on aging would spin forever whenever the clock hasn't
                        // advanced past the oldest entry (e.g. a frozen clock under test).
                        window.removeFirst()
                        window.addLast(now)
                    } else {
                        // Clamp jitter to the delay so a large/misconfigured jitterMillis can't
                        // balloon a single wait to several multiples of delayMillis.
                        val jitterBound = minOf(spec.jitterMillis, spec.delayMillis)
                        val jitter = if (jitterBound > 0) Random.nextLong(0, jitterBound) else 0L
                        wait = spec.delayMillis - (now - window.peekFirst()) + jitter
                    }
                }
                if (wait <= 0) break

                RateLimitWaitTracker.startWaiting(host, SystemClock.elapsedRealtime() + wait)
                try {
                    waitCancellably(wait, call)
                } finally {
                    RateLimitWaitTracker.stopWaiting(host)
                }
                hasWaited = true
            }
        }

        return chain.proceed(request)
    }

    /**
     * Waits out [waitMillis] in short chunks rather than one [Thread.sleep] call, checking
     * [call] for cancellation between chunks. A single blind sleep can't be interrupted by
     * cancelling the underlying OkHttp call - cancel() closes sockets/connections, but a wait
     * that hasn't reached the network yet has nothing to close, so it would otherwise run to
     * completion even after the caller has given up (e.g. leaving the reader, cancelling a
     * download). Uses [System.nanoTime] rather than [SystemClock.elapsedRealtime] for the
     * countdown so this remains real wall-clock time even in tests that freeze SystemClock to
     * exercise the window bookkeeping above.
     */
    private fun waitCancellably(waitMillis: Long, call: Call) {
        var remaining = waitMillis
        while (remaining > 0) {
            if (call.isCanceled()) throw IOException("Canceled")
            val chunk = minOf(remaining, POLL_INTERVAL_MILLIS)
            val start = System.nanoTime()
            try {
                Thread.sleep(chunk)
            } catch (e: InterruptedException) {
                // OkHttp expects interceptor failures as IOException; keep the interrupt flag set.
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for rate limit", e)
            }
            remaining -= maxOf((System.nanoTime() - start) / 1_000_000, 1L)
        }
    }

    companion object {
        private const val POLL_INTERVAL_MILLIS = 200L
    }
}
