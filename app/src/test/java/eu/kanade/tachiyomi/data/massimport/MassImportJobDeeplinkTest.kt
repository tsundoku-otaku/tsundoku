package eu.kanade.tachiyomi.data.massimport

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private class FakeDeeplinkResolver(private val supported: Boolean) : DeeplinkResolver {
    var callCount = 0
        private set

    override fun isDeeplinkUrl(source: CatalogueSource, url: String): Boolean {
        callCount++
        return supported
    }
}

private class FakeSource(
    override val id: Long = 1L,
    override val name: String = "Fake",
    override val lang: String = "en",
    private val searchResult: SManga? = null,
) : CatalogueSource {
    var searchMangaCalls = 0
        private set

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        searchMangaCalls++
        return MangasPage(listOfNotNull(searchResult), false)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException()
}

private fun manga(url: String) = SManga.create().apply {
    this.url = url
    title = "Title"
}

class MassImportJobDeeplinkTest {

    @Test
    fun `non-deeplink url is not resolved and search is never called`() = runBlocking {
        val resolver = FakeDeeplinkResolver(supported = false)
        val source = FakeSource(searchResult = manga("/manga/canonical-id"))

        val result = resolveDeeplinkManga(source, "https://example.com/manga/123", resolver, 5_000L)

        assertNull(result)
        assertEquals(0, source.searchMangaCalls)
    }

    @Test
    fun `deeplink url resolves via getSearchManga once`() = runBlocking {
        val resolver = FakeDeeplinkResolver(supported = true)
        val source = FakeSource(searchResult = manga("/manga/canonical-id"))

        val result = resolveDeeplinkManga(source, "https://example.com/title/uuid-1234/some-slug", resolver, 5_000L)

        assertEquals("/manga/canonical-id", result?.url)
        assertEquals(1, source.searchMangaCalls)
    }

    @Test
    fun `deeplink url with empty search result falls back to null`() = runBlocking {
        val resolver = FakeDeeplinkResolver(supported = true)
        val source = FakeSource(searchResult = null)

        val result = resolveDeeplinkManga(source, "https://example.com/title/uuid-1234/some-slug", resolver, 5_000L)

        assertNull(result)
        assertEquals(1, source.searchMangaCalls)
    }

    @Test
    fun `source with no installed package falls back to non-deeplink path`() {
        val resolver = PackageManagerDeeplinkResolver(
            lookupPkgName = { null },
            queryHostSupported = { _, _ -> throw AssertionError("should never query without a package") },
        )
        val source = FakeSource()

        assertEquals(false, resolver.isDeeplinkUrl(source, "https://example.com/manga/123"))
    }

    @Test
    fun `same url is only queried once per batch`() {
        var queries = 0
        val resolver = PackageManagerDeeplinkResolver(
            lookupPkgName = { "com.example.extension" },
            queryHostSupported = { _, _ -> queries++; true },
        )
        val source = FakeSource()

        repeat(5) { resolver.isDeeplinkUrl(source, "https://example.com/title/same-slug") }

        assertEquals(1, queries)
    }

    @Test
    fun `same host but different paths are queried independently`() {
        var queries = 0
        val resolver = PackageManagerDeeplinkResolver(
            lookupPkgName = { "com.example.extension" },
            queryHostSupported = { _, url -> queries++; url.endsWith("/supported-path") },
        )
        val source = FakeSource()

        val supported = resolver.isDeeplinkUrl(source, "https://example.com/supported-path")
        val unsupported = resolver.isDeeplinkUrl(source, "https://example.com/unsupported-path")

        assertEquals(true, supported)
        assertEquals(false, unsupported)
        assertEquals(2, queries)
    }

    @Test
    fun `same manga reached via two mirror hosts resolves to the same canonical url`() = runBlocking {
        val resolver = FakeDeeplinkResolver(supported = true)
        val source = FakeSource(searchResult = manga("/manga/canonical-id"))

        val viaMirrorA = resolveDeeplinkManga(source, "https://mirror-a.test/title/123", resolver, 5_000L)
        val viaMirrorB = resolveDeeplinkManga(source, "https://mirror-b.test/title/123", resolver, 5_000L)

        assertEquals(viaMirrorA?.url, viaMirrorB?.url)
        assertEquals("/manga/canonical-id", viaMirrorA?.url)
    }

    @Test
    fun `different hosts for the same mirror-aware source resolve independently`() {
        var queries = 0
        val resolver = PackageManagerDeeplinkResolver(
            lookupPkgName = { "com.example.extension" },
            queryHostSupported = { _, url -> queries++; url.contains("mirror-a") || url.contains("mirror-b") },
        )
        val source = FakeSource()

        val a = resolver.isDeeplinkUrl(source, "https://mirror-a.test/manga/123")
        val b = resolver.isDeeplinkUrl(source, "https://mirror-b.test/manga/123")

        assertEquals(true, a)
        assertEquals(true, b)
        assertEquals(2, queries)
    }
}
