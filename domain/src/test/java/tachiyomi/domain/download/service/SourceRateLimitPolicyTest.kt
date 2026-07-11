package tachiyomi.domain.download.service

import eu.kanade.tachiyomi.network.interceptor.RateLimitSpec
import eu.kanade.tachiyomi.source.RateLimited
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.service.SourceManager

@Execution(ExecutionMode.CONCURRENT)
class SourceRateLimitPolicyTest {

    private fun policy(vararg sources: HttpSource): SourceRateLimitPolicy {
        val sourceManager = mockk<SourceManager> {
            every { getOnlineSources() } returns sources.toList()
        }
        val resolver = RateLimitResolver(NovelDownloadPreferences(InMemoryPreferenceStore()))
        return SourceRateLimitPolicy(sourceManager, resolver)
    }

    private fun novelSource(
        host: String,
        id: Long = 1L,
        moreInterfaces: Array<kotlin.reflect.KClass<*>> = emptyArray(),
    ) = mockk<HttpSource>(moreInterfaces = moreInterfaces) {
        every { baseUrl } returns "https://$host"
        every { this@mockk.id } returns id
        every { isNovelSource } returns true
    }

    @Test
    fun `unknown host resolves to NONE`() {
        val result = policy().specFor("unknown.example.com")
        result shouldBe RateLimitSpec.NONE
    }

    @Test
    fun `unmetered source is never throttled regardless of novel or RateLimited status`() {
        val source = novelSource("example.com", moreInterfaces = arrayOf(UnmeteredSource::class))
        val result = policy(source).specFor("example.com")
        result shouldBe RateLimitSpec.NONE
    }

    @Test
    fun `non-novel source with no declared minimum is not throttled`() {
        val source = mockk<HttpSource> {
            every { baseUrl } returns "https://example.com"
            every { id } returns 1L
            every { isNovelSource } returns false
        }
        val result = policy(source).specFor("example.com")
        result shouldBe RateLimitSpec.NONE
    }

    @Test
    fun `novel source resolves a real spec`() {
        val source = novelSource("example.com")
        val result = policy(source).specFor("example.com")
        (result.delayMillis > 0) shouldBe true
    }

    @Test
    fun `RateLimited minimum floors the resolved delay even for a non-novel source`() {
        val source = mockk<HttpSource>(moreInterfaces = arrayOf(RateLimited::class)) {
            every { baseUrl } returns "https://example.com"
            every { id } returns 1L
            every { isNovelSource } returns false
            every { (this@mockk as RateLimited).minimumDelayMillis } returns 5000L
        }
        val result = policy(source).specFor("example.com")
        result.delayMillis shouldBe 5000L
    }

    @Test
    fun `www-prefixed host resolves against a bare-host baseUrl`() {
        val source = novelSource("example.com")
        val result = policy(source).specFor("www.example.com")
        (result.delayMillis > 0) shouldBe true
    }
}
