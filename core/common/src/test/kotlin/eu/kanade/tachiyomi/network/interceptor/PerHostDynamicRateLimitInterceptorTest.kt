package eu.kanade.tachiyomi.network.interceptor

import android.os.SystemClock
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory

/**
 * Uses small real millisecond delays and measures actual wall-clock time rather than mocking
 * Thread.sleep - statically mocking java.lang.Thread is a JVM-wide, load-bearing class used by
 * the test runner itself, and doing so can hang or destabilize the whole test process.
 *
 * `delays` is a companion (JVM-wide) map, mutated in place rather than reassigned per test:
 * Injekt.addSingletonFactory caches the first-resolved instance for the process, so a later
 * test reassigning `delays` to a new map would be invisible to the already-cached policy
 * closure from an earlier test. Registering the factory once, against one persistent map,
 * sidesteps that entirely.
 */
class PerHostDynamicRateLimitInterceptorTest {

    companion object {
        private val delays = mutableMapOf<String, Long>()

        init {
            Injekt.addSingletonFactory<RequestRateLimitPolicy> { RequestRateLimitPolicy { host -> delays[host] ?: 0L } }
        }
    }

    @BeforeEach
    fun setUp() {
        delays.clear()
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    private fun fakeChain(url: String): Interceptor.Chain {
        val request = Request.Builder().url(url).build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()
        return mockk<Interceptor.Chain> {
            every { request() } returns request
            every { proceed(any()) } answers { response }
        }
    }

    @Test
    fun `does not delay when policy returns zero`() {
        delays["example.com"] = 0L
        val interceptor = PerHostDynamicRateLimitInterceptor()

        val elapsed = measureMillis {
            interceptor.intercept(fakeChain("https://example.com/a"))
            interceptor.intercept(fakeChain("https://example.com/b"))
        }

        (elapsed < 500L) shouldBe true
    }

    @Test
    fun `paces consecutive requests to the same host by the policy delay`() {
        val interceptor = PerHostDynamicRateLimitInterceptor()

        // The first ever call through this code path pays a one-time JIT/proxy warmup cost
        // unrelated to the interceptor itself (can be 100s of ms) - absorb it with a real
        // sleeping call here so the two timed calls below measure steady-state behavior only.
        delays["warmup.example.com"] = 1L
        interceptor.intercept(fakeChain("https://warmup.example.com/w"))

        delays["example.com"] = 100L
        val e1 = measureMillis { interceptor.intercept(fakeChain("https://example.com/a")) }
        val e2 = measureMillis { interceptor.intercept(fakeChain("https://example.com/b")) }

        // The fake clock never advances (elapsedRealtime is stubbed to a constant 0), so both
        // requests see "no time has passed" and each pays the full delay - a stronger check
        // than just "some delay happened", proving pacing applies per request, not per novel.
        // Threshold leaves slack below the configured 100ms for OS scheduling jitter.
        (e1 >= 60L) shouldBe true
        (e2 >= 60L) shouldBe true
    }

    @Test
    fun `different hosts are not serialized against each other`() {
        delays["a.example.com"] = 300L
        delays["b.example.com"] = 0L
        val interceptor = PerHostDynamicRateLimitInterceptor()

        interceptor.intercept(fakeChain("https://a.example.com/x"))
        // b.example.com has no configured delay and must not wait on a's gate, even though
        // a's request just paid a large delay.
        val bElapsed = measureMillis { interceptor.intercept(fakeChain("https://b.example.com/y")) }

        (bElapsed < 200L) shouldBe true
    }

    private inline fun measureMillis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }
}
