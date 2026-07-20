package eu.kanade.tachiyomi.network.interceptor

import android.os.SystemClock
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RateLimitWaitTrackerTest {

    @BeforeEach
    fun setUp() {
        mockkStatic(SystemClock::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    @Test
    fun `reports remaining time while waiting, null once elapsed or stopped`() {
        val host = "example-${System.nanoTime()}.com"
        every { SystemClock.elapsedRealtime() } returns 1000L

        RateLimitWaitTracker.remainingMillisFor(host) shouldBe null

        RateLimitWaitTracker.startWaiting(host, untilElapsedRealtime = 1500L)
        RateLimitWaitTracker.remainingMillisFor(host) shouldBe 500L

        every { SystemClock.elapsedRealtime() } returns 1600L
        // Already past the "until" timestamp - reports as no longer waiting even though
        // nothing explicitly stopped it, since the wait has naturally elapsed.
        RateLimitWaitTracker.remainingMillisFor(host) shouldBe null

        RateLimitWaitTracker.startWaiting(host, untilElapsedRealtime = 2000L)
        every { SystemClock.elapsedRealtime() } returns 1900L
        RateLimitWaitTracker.remainingMillisFor(host) shouldBe 100L

        RateLimitWaitTracker.stopWaiting(host)
        RateLimitWaitTracker.remainingMillisFor(host) shouldBe null
    }
}
