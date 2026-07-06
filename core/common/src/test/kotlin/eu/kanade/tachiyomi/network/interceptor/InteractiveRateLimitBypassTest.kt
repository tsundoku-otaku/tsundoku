package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class InteractiveRateLimitBypassTest {

    @Test
    fun `host is not bypassed before or after the block runs`() = runBlocking {
        val host = "example-${System.nanoTime()}.com"
        InteractiveRateLimitBypass.isBypassed(host) shouldBe false

        InteractiveRateLimitBypass.bypassing(host) {
            InteractiveRateLimitBypass.isBypassed(host) shouldBe true
        }

        InteractiveRateLimitBypass.isBypassed(host) shouldBe false
    }

    @Test
    fun `null host is a no-op and never reports bypassed`() = runBlocking {
        InteractiveRateLimitBypass.bypassing<Unit>(null) {
            InteractiveRateLimitBypass.isBypassed("anything.com") shouldBe false
        }
    }

    @Test
    fun `nested bypass calls for the same host don't clear early`() = runBlocking {
        val host = "example-${System.nanoTime()}.com"

        InteractiveRateLimitBypass.bypassing(host) {
            InteractiveRateLimitBypass.bypassing(host) {
                InteractiveRateLimitBypass.isBypassed(host) shouldBe true
            }
            // The inner call finished and decremented, but the outer's own reference should
            // still hold the bypass open - this is what makes concurrent fetches to the same
            // source (e.g. details + chapters in parallel) safe.
            InteractiveRateLimitBypass.isBypassed(host) shouldBe true
        }

        InteractiveRateLimitBypass.isBypassed(host) shouldBe false
    }
}
