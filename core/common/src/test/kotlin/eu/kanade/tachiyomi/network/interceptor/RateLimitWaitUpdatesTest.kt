package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class RateLimitWaitUpdatesTest {

    @Test
    fun `null host runs the block without polling at all`() = runBlocking {
        var calls = 0

        val result = withRateLimitWaitUpdates(host = null, onWaitChanged = { calls++ }) { "done" }

        result shouldBe "done"
        calls shouldBe 0
    }

    @Test
    fun `polls while the block runs and always finishes with a null update`() = runBlocking {
        val host = "example-${System.nanoTime()}.com"
        val updates = mutableListOf<Long?>()

        val result = withRateLimitWaitUpdates(host = host, pollMillis = 20, onWaitChanged = { updates.add(it) }) {
            delay(60)
            "done"
        }

        result shouldBe "done"
        updates.isNotEmpty() shouldBe true
        // Guaranteed final call reports no wait, regardless of how many polls happened.
        updates.last() shouldBe null
    }
}
