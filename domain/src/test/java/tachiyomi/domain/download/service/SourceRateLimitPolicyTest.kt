package tachiyomi.domain.download.service

import eu.kanade.tachiyomi.network.interceptor.RateLimitSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.source.service.SourceManager

@Execution(ExecutionMode.CONCURRENT)
class SourceRateLimitPolicyTest {

    private fun policy(vararg candidates: RateLimitCandidate, isInitialized: Boolean = true): SourceRateLimitPolicy {
        val sourceManager = mockk<SourceManager> {
            every { getRateLimitCandidates() } returns candidates.toList()
            every { this@mockk.isInitialized } returns MutableStateFlow(isInitialized)
        }
        val resolver = RateLimitResolver(NovelDownloadPreferences(InMemoryPreferenceStore()))
        return SourceRateLimitPolicy(sourceManager, resolver)
    }

    private fun novelCandidate(
        host: String,
        id: Long = 1L,
        isUnmetered: Boolean = false,
        declaredMinimumMillis: Long = 0L,
    ) = RateLimitCandidate(
        sourceId = id,
        baseUrl = "https://$host",
        isNovel = true,
        isUnmetered = isUnmetered,
        declaredMinimumMillis = declaredMinimumMillis,
    )

    @Test
    fun `unknown host falls back to the global default spec`() {
        val result = policy().specFor("unknown.example.com")
        result shouldBe RateLimitResolver(NovelDownloadPreferences(InMemoryPreferenceStore())).resolveDefault()
    }

    @Test
    fun `unknown host resolves to NONE when throttling is disabled`() {
        val prefs = NovelDownloadPreferences(InMemoryPreferenceStore())
        prefs.enableRequestThrottling().set(false)
        val sourceManager = mockk<SourceManager> {
            every { getRateLimitCandidates() } returns emptyList()
            every { isInitialized } returns MutableStateFlow(true)
        }
        val policy = SourceRateLimitPolicy(sourceManager, RateLimitResolver(prefs))

        policy.specFor("unknown.example.com") shouldBe RateLimitSpec.NONE
    }

    @Test
    fun `subdomain of a known source's baseUrl resolves that source's spec`() {
        val candidate = novelCandidate("example.com")
        val result = policy(candidate).specFor("api.example.com")
        (result.delayMillis > 0) shouldBe true
    }

    @Test
    fun `sibling subdomain of a known source's baseUrl resolves that source's spec`() {
        val candidate = novelCandidate("cdn.example.com")
        val result = policy(candidate).specFor("api.example.com")
        (result.delayMillis > 0) shouldBe true
    }

    @Test
    fun `unrelated domain is not matched by an unrelated source's baseUrl`() {
        val candidate = novelCandidate("example.com")
        val result = policy(candidate).specFor("totally-unrelated.org")
        result shouldBe RateLimitResolver(NovelDownloadPreferences(InMemoryPreferenceStore())).resolveDefault()
    }

    @Test
    fun `unrelated tenant of a wildcard hosting suffix is not matched to another tenant's source`() {
        // Regression test: "alice.github.io" and "bob.github.io" share a naive last-two-labels
        // split ("github.io") but are unrelated sites - matching them would let an unmetered
        // tenant's spec (e.g. NONE) silently exempt a completely unrelated host.
        val candidate = novelCandidate("alice.github.io", isUnmetered = true)
        val result = policy(candidate).specFor("bob.github.io")
        result shouldBe RateLimitResolver(NovelDownloadPreferences(InMemoryPreferenceStore())).resolveDefault()
    }

    @Test
    fun `subdomain of the same wildcard hosting tenant still resolves that tenant's source`() {
        val candidate = novelCandidate("alice.github.io")
        val result = policy(candidate).specFor("api.alice.github.io")
        (result.delayMillis > 0) shouldBe true
    }

    @Test
    fun `unrelated host sharing IP octets with a source's IP baseUrl is not matched to that source`() {
        // Regression test: a naive last-two-labels split treats a dotted-quad IP's last two
        // octets as if they were a registrable domain, so "192.168.1.50" and "10.0.1.50" (two
        // unrelated self-hosted servers that happen to share their last two octets - plausible
        // on any home LAN) would otherwise be wrongly grouped together.
        val candidate = novelCandidate("192.168.1.50", isUnmetered = true)
        val result = policy(candidate).specFor("10.0.1.50")
        result shouldBe RateLimitResolver(NovelDownloadPreferences(InMemoryPreferenceStore())).resolveDefault()
    }

    @Test
    fun `unmetered source is never throttled regardless of novel or RateLimited status`() {
        val candidate = novelCandidate("example.com", isUnmetered = true)
        val result = policy(candidate).specFor("example.com")
        result shouldBe RateLimitSpec.NONE
    }

    @Test
    fun `non-novel source with no declared minimum is not throttled`() {
        val candidate = RateLimitCandidate(
            sourceId = 1L,
            baseUrl = "https://example.com",
            isNovel = false,
            isUnmetered = false,
            declaredMinimumMillis = 0L,
        )
        val result = policy(candidate).specFor("example.com")
        result shouldBe RateLimitSpec.NONE
    }

    @Test
    fun `novel source resolves a real spec`() {
        val candidate = novelCandidate("example.com")
        val result = policy(candidate).specFor("example.com")
        (result.delayMillis > 0) shouldBe true
    }

    @Test
    fun `non-HttpSource candidate is throttled the same as an HttpSource one`() {
        // Regression test: JS-plugin sources (and any other non-HttpSource type) must be
        // pace-able too, since SourceRateLimitPolicy only ever sees the type-agnostic
        // RateLimitCandidate, not the concrete source.
        val candidate = novelCandidate("example.com")
        val result = policy(candidate).specFor("example.com")
        (result.delayMillis > 0) shouldBe true
    }

    @Test
    fun `RateLimited minimum floors the resolved delay even for a non-novel source`() {
        val candidate = RateLimitCandidate(
            sourceId = 1L,
            baseUrl = "https://example.com",
            isNovel = false,
            isUnmetered = false,
            declaredMinimumMillis = 5000L,
        )
        val result = policy(candidate).specFor("example.com")
        result.delayMillis shouldBe 5000L
    }

    @Test
    fun `www-prefixed host resolves against a bare-host baseUrl`() {
        val candidate = novelCandidate("example.com")
        val result = policy(candidate).specFor("www.example.com")
        (result.delayMillis > 0) shouldBe true
    }

    @Test
    fun `two sources sharing a host resolve to the more conservative declared minimum`() {
        val loose = novelCandidate("example.com", id = 1L, declaredMinimumMillis = 1000L)
        val strict = novelCandidate("example.com", id = 2L, declaredMinimumMillis = 9000L)
        val result = policy(loose, strict).specFor("example.com")
        result.delayMillis shouldBe 9000L
    }

    @Test
    fun `a metered source sharing a host with an unmetered one is still throttled`() {
        val unmetered = novelCandidate("example.com", id = 1L, isUnmetered = true)
        val metered = novelCandidate("example.com", id = 2L, declaredMinimumMillis = 4000L)
        val result = policy(unmetered, metered).specFor("example.com")
        result.delayMillis shouldBe 4000L
    }

    @Test
    fun `host collision resolution is deterministic regardless of source order`() {
        val a = novelCandidate("example.com", id = 5L, declaredMinimumMillis = 2000L)
        val b = novelCandidate("example.com", id = 3L, declaredMinimumMillis = 2000L)
        val forward = policy(a, b).specFor("example.com")
        val reversed = policy(b, a).specFor("example.com")
        forward shouldBe reversed
    }

    @Test
    fun `getRateLimitCandidates is not re-queried on every call`() {
        val candidate = novelCandidate("example.com")
        val sourceManager = mockk<SourceManager> {
            every { getRateLimitCandidates() } returns listOf(candidate)
            every { isInitialized } returns MutableStateFlow(true)
        }
        val resolver = RateLimitResolver(NovelDownloadPreferences(InMemoryPreferenceStore()))
        val policy = SourceRateLimitPolicy(sourceManager, resolver)

        policy.specFor("example.com")
        policy.specFor("example.com")
        policy.specFor("unknown.example.com")

        verify(exactly = 1) { sourceManager.getRateLimitCandidates() }
    }

    @Test
    fun `an uninitialized source manager is re-queried on every call instead of caching a cold result`() {
        // Regression test: caching an empty/partial list from before extensions finish loading
        // would leave a real source's requests unthrottled for the whole cache TTL.
        val candidate = novelCandidate("example.com")
        val sourceManager = mockk<SourceManager> {
            every { getRateLimitCandidates() } returns listOf(candidate)
            every { isInitialized } returns MutableStateFlow(false)
        }
        val resolver = RateLimitResolver(NovelDownloadPreferences(InMemoryPreferenceStore()))
        val policy = SourceRateLimitPolicy(sourceManager, resolver)

        policy.specFor("example.com")
        policy.specFor("example.com")
        policy.specFor("example.com")

        verify(exactly = 3) { sourceManager.getRateLimitCandidates() }
    }
}
