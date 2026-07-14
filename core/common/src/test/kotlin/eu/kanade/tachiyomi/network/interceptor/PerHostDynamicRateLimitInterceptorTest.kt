package eu.kanade.tachiyomi.network.interceptor

import android.os.SystemClock
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uses small real millisecond delays and measures actual wall-clock time rather than mocking
 * Thread.sleep - statically mocking java.lang.Thread is a JVM-wide, load-bearing class used by
 * the test runner itself, and doing so can hang or destabilize the whole test process.
 *
 * `specs` is a companion (JVM-wide) map, mutated in place rather than reassigned per test:
 * Injekt.addSingletonFactory caches the first-resolved instance for the process, so a later
 * test reassigning `specs` to a new map would be invisible to the already-cached policy
 * closure from an earlier test. Registering the factory once, against one persistent map,
 * sidesteps that entirely.
 */
class PerHostDynamicRateLimitInterceptorTest {

    companion object {
        private val specs = mutableMapOf<String, RateLimitSpec>()

        init {
            Injekt.addSingletonFactory<RequestRateLimitPolicy> {
                RequestRateLimitPolicy { host -> specs[host] ?: RateLimitSpec.NONE }
            }
        }
    }

    @BeforeEach
    fun setUp() {
        specs.clear()
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    private fun fakeChain(url: String, isCanceled: () -> Boolean = { false }): Interceptor.Chain {
        val request = Request.Builder().url(url).build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()
        val call = mockk<Call> {
            every { this@mockk.isCanceled() } answers { isCanceled() }
        }
        return mockk<Interceptor.Chain> {
            every { request() } returns request
            every { call() } returns call
            every { proceed(any()) } answers { response }
        }
    }

    @Test
    fun `does not delay when policy returns NONE`() {
        specs["example.com"] = RateLimitSpec.NONE
        val interceptor = PerHostDynamicRateLimitInterceptor()

        val elapsed = measureMillis {
            interceptor.intercept(fakeChain("https://example.com/a"))
            interceptor.intercept(fakeChain("https://example.com/b"))
        }

        (elapsed < 500L) shouldBe true
    }

    @Test
    fun `paces the second request to a host once the single default permit is used`() {
        val interceptor = PerHostDynamicRateLimitInterceptor()

        specs["example.com"] = RateLimitSpec(delayMillis = 100L)
        // With the default permits=1, the very first request to a host has an empty window
        // and goes through immediately - there's nothing to wait on yet.
        val e1 = measureMillis { interceptor.intercept(fakeChain("https://example.com/a")) }
        // The second request finds the window's single slot already taken and must wait out
        // the delay. The fake clock never advances (elapsedRealtime is stubbed to a constant
        // 0), so it sees "no time has passed" and pays the full delay.
        val e2 = measureMillis { interceptor.intercept(fakeChain("https://example.com/b")) }

        (e1 < 60L) shouldBe true
        // Threshold leaves slack below the configured 100ms for OS scheduling jitter.
        (e2 >= 60L) shouldBe true
    }

    @Test
    fun `an interactively bypassed host skips throttling even with a full window`() = runBlocking<Unit> {
        val host = "example.com"
        specs[host] = RateLimitSpec(delayMillis = 200L)
        val interceptor = PerHostDynamicRateLimitInterceptor()

        // Fill the single default permit's window so a normal call here would have to wait.
        interceptor.intercept(fakeChain("https://$host/a"))

        val elapsed = InteractiveRateLimitBypass.bypassing(host) {
            measureMillis { interceptor.intercept(fakeChain("https://$host/b")) }
        }

        (elapsed < 100L) shouldBe true
    }

    @Test
    fun `an active background job suppresses an unrelated interactive bypass for the same host`() =
        runBlocking<Unit> {
            val host = "example.com"
            specs[host] = RateLimitSpec(delayMillis = 200L)
            val interceptor = PerHostDynamicRateLimitInterceptor()

            // Fill the single default permit's window so a normal call here would have to wait.
            interceptor.intercept(fakeChain("https://$host/a"))

            val elapsed = InteractiveRateLimitBypass.bypassing(host) {
                BackgroundRateLimitGuard.active(host) {
                    measureMillis { interceptor.intercept(fakeChain("https://$host/b")) }
                }
            }

            // A background job is active on this host, so the interactive bypass is suppressed
            // and the request pays the full configured delay instead of skipping it.
            (elapsed >= 100L) shouldBe true
        }

    @Test
    fun `allows a burst of permits before enforcing the delay`() {
        val interceptor = PerHostDynamicRateLimitInterceptor()

        specs["warmup.example.com"] = RateLimitSpec(delayMillis = 1L)
        interceptor.intercept(fakeChain("https://warmup.example.com/w"))

        specs["example.com"] = RateLimitSpec(delayMillis = 200L, permits = 3)
        val burst = measureMillis {
            repeat(3) { interceptor.intercept(fakeChain("https://example.com/$it")) }
        }
        val fourth = measureMillis { interceptor.intercept(fakeChain("https://example.com/3")) }

        // The first 3 requests fit within the burst capacity and shouldn't wait meaningfully.
        (burst < 100L) shouldBe true
        // The 4th exceeds the burst capacity and must wait out the window (with slack below
        // the configured 200ms for OS scheduling jitter).
        (fourth >= 150L) shouldBe true
    }

    @Test
    fun `different hosts are not serialized against each other`() {
        specs["a.example.com"] = RateLimitSpec(delayMillis = 300L)
        specs["b.example.com"] = RateLimitSpec.NONE
        val interceptor = PerHostDynamicRateLimitInterceptor()

        interceptor.intercept(fakeChain("https://a.example.com/x"))
        // b.example.com has no configured delay and must not wait on a's gate, even though
        // a's request just paid a large delay.
        val bElapsed = measureMillis { interceptor.intercept(fakeChain("https://b.example.com/y")) }

        (bElapsed < 200L) shouldBe true
    }

    @Test
    fun `www-prefixed and bare host share the same throttling window`() {
        specs["example.com"] = RateLimitSpec(delayMillis = 100L)
        val interceptor = PerHostDynamicRateLimitInterceptor()

        interceptor.intercept(fakeChain("https://www.example.com/a"))
        val elapsed = measureMillis { interceptor.intercept(fakeChain("https://example.com/b")) }

        (elapsed >= 60L) shouldBe true
    }

    @Test
    fun `cancelling the call interrupts an in-progress wait instead of running the full delay`() {
        specs["example.com"] = RateLimitSpec(delayMillis = 2000L)
        val interceptor = PerHostDynamicRateLimitInterceptor()
        val canceled = AtomicBoolean(false)

        interceptor.intercept(fakeChain("https://example.com/a", isCanceled = { canceled.get() }))

        Thread {
            Thread.sleep(150)
            canceled.set(true)
        }.start()

        var thrown: Throwable? = null
        val elapsed = measureMillis {
            try {
                interceptor.intercept(fakeChain("https://example.com/b", isCanceled = { canceled.get() }))
            } catch (e: IOException) {
                thrown = e
            }
        }

        (thrown != null) shouldBe true
        (elapsed < 2000L) shouldBe true
    }

    private inline fun measureMillis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }
}
