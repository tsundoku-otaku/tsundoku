package tachiyomi.domain.download.service

import eu.kanade.tachiyomi.network.interceptor.RateLimitSpec
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
    fun `no override resolves global delay, jitter and permits unrandomized`() {
        val (prefs, resolver) = resolver()
        prefs.requestDelay().set(2000)
        prefs.requestJitter().set(500)
        prefs.requestPermits().set(3)

        resolver.resolve(sourceId = 1L) shouldBe RateLimitSpec(delayMillis = 2000L, jitterMillis = 500L, permits = 3)
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
                permits = 5,
                enabled = false,
            ),
        )

        resolver.resolve(sourceId = 1L) shouldBe RateLimitSpec(delayMillis = 2000L, jitterMillis = 0L, permits = 1)
    }

    @Test
    fun `enabled override with only one field set mixes with global defaults`() {
        val (prefs, resolver) = resolver()
        prefs.requestDelay().set(2000)
        prefs.requestJitter().set(999)
        prefs.requestPermits().set(1)
        prefs.setSourceOverride(
            NovelDownloadPreferences.Companion.SourceOverride(
                sourceId = 1L,
                delayMillis = 5000,
                jitterMillis = null,
                permits = null,
                enabled = true,
            ),
        )

        resolver.resolve(sourceId = 1L) shouldBe RateLimitSpec(delayMillis = 5000L, jitterMillis = 999L, permits = 1)
    }

    @Test
    fun `override can raise permits independently of delay`() {
        val (prefs, resolver) = resolver()
        prefs.requestPermits().set(1)
        prefs.setSourceOverride(
            NovelDownloadPreferences.Companion.SourceOverride(sourceId = 1L, permits = 4, enabled = true),
        )

        resolver.resolve(sourceId = 1L).permits shouldBe 4
    }

    @Test
    fun `declared minimum floors the resolved delay when higher than config`() {
        val (prefs, resolver) = resolver()
        prefs.requestDelay().set(1000)
        prefs.requestJitter().set(0)

        resolver.resolve(sourceId = 1L, declaredMinimumMillis = 4000L).delayMillis shouldBe 4000L
    }

    @Test
    fun `declared minimum still applies when throttling is globally disabled`() {
        val (prefs, resolver) = resolver()
        prefs.enableRequestThrottling().set(false)
        prefs.requestDelay().set(1000)

        resolver.resolve(sourceId = 1L, declaredMinimumMillis = 1500L) shouldBe RateLimitSpec(delayMillis = 1500L)
    }

    @Test
    fun `throttling disabled with no declared minimum resolves to NONE`() {
        val (prefs, resolver) = resolver()
        prefs.enableRequestThrottling().set(false)
        prefs.requestDelay().set(1000)

        resolver.resolve(sourceId = 1L) shouldBe RateLimitSpec.NONE
    }
}
