package tachiyomi.domain.download.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.InMemoryPreferenceStore

@Execution(ExecutionMode.CONCURRENT)
class RateLimitResolverTest {

    private fun resolver(): Pair<NovelDownloadPreferences, RateLimitResolver> {
        val prefs = NovelDownloadPreferences(InMemoryPreferenceStore())
        return prefs to RateLimitResolver(prefs)
    }

    @Test
    fun `no override uses global delay with zero jitter`() {
        val (prefs, resolver) = resolver()
        prefs.requestDelay().set(2000)
        prefs.requestJitter().set(0)

        resolver.resolveDelayMillis(sourceId = 1L) shouldBe 2000L
    }

    @Test
    fun `no override applies randomized jitter within range`() {
        val (prefs, resolver) = resolver()
        prefs.requestDelay().set(2000)
        prefs.requestJitter().set(500)

        repeat(50) {
            val result = resolver.resolveDelayMillis(sourceId = 1L)
            (result in 2000L..2500L) shouldBe true
        }
    }

    @Test
    fun `disabled override falls back to global config`() {
        val (prefs, resolver) = resolver()
        prefs.requestDelay().set(2000)
        prefs.requestJitter().set(0)
        prefs.setSourceOverride(
            NovelDownloadPreferences.Companion.SourceOverride(
                sourceId = 1L,
                delayMillis = 9000,
                jitterMillis = 0,
                enabled = false,
            ),
        )

        resolver.resolveDelayMillis(sourceId = 1L) shouldBe 2000L
    }

    @Test
    fun `enabled override with only one field set mixes with global default`() {
        val (prefs, resolver) = resolver()
        prefs.requestDelay().set(2000)
        prefs.requestJitter().set(999)
        prefs.setSourceOverride(
            NovelDownloadPreferences.Companion.SourceOverride(
                sourceId = 1L,
                delayMillis = 5000,
                jitterMillis = null,
                enabled = true,
            ),
        )

        val result = resolver.resolveDelayMillis(sourceId = 1L)
        (result in 5000L..5999L) shouldBe true
    }

    @Test
    fun `declared minimum floors the resolved delay when higher than config`() {
        val (prefs, resolver) = resolver()
        prefs.requestDelay().set(1000)
        prefs.requestJitter().set(0)

        resolver.resolveDelayMillis(sourceId = 1L, declaredMinimumMillis = 4000L) shouldBe 4000L
    }

    @Test
    fun `declared minimum still applies when throttling is globally disabled`() {
        val (prefs, resolver) = resolver()
        prefs.enableRequestThrottling().set(false)
        prefs.requestDelay().set(1000)

        resolver.resolveDelayMillis(sourceId = 1L, declaredMinimumMillis = 1500L) shouldBe 1500L
    }

    @Test
    fun `throttling disabled with no declared minimum resolves to zero`() {
        val (prefs, resolver) = resolver()
        prefs.enableRequestThrottling().set(false)
        prefs.requestDelay().set(1000)

        resolver.resolveDelayMillis(sourceId = 1L) shouldBe 0L
    }
}
