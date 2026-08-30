package eu.kanade.domain.manga.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.massimport.DeeplinkResolver
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

private class FakeHttpSource(
    override val name: String,
    override val lang: String,
    override val baseUrl: String,
    private val searchResultUrl: String? = null,
) : HttpSource() {

    // Path the last getMangaUpdate details fetch was asked for; lets a test assert which URL
    // (guessed-from-path vs deeplink-canonical) resolution settled on.
    var requestedUrl: String? = null
        private set

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val results = searchResultUrl
            ?.let { listOf(SManga.create().apply { url = it; title = "Canonical" }) }
            .orEmpty()
        return MangasPage(results, false)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        requestedUrl = manga.url
        return SMangaUpdate(SManga.create().apply { url = manga.url; title = "Title" }, emptyList())
    }
}

private class FakeDeeplinkResolver(private val deeplinkHosts: Set<String>) : DeeplinkResolver {
    override fun isDeeplinkUrl(source: CatalogueSource, url: String): Boolean {
        val host = java.net.URI(url).host?.lowercase() ?: return false
        return host in deeplinkHosts
    }
}

class MassImportDeeplinkTest {

    private val source = FakeHttpSource(name = "Fake", lang = "en", baseUrl = "https://example.com")
    private val resolvedManga = mockk<Manga>(relaxed = true)

    private fun massImport(
        favoriteSourceAndUrl: List<Pair<Long, String>> = emptyList(),
        deeplinkHosts: Set<String> = emptySet(),
        source: FakeHttpSource = this.source,
    ): MassImport {
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            every { getAll() } returns listOf(source)
        }
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val networkToLocalManga = mockk<NetworkToLocalManga>(relaxed = true)
        val sourcePreferences = mockk<SourcePreferences>(relaxed = true)
        return MassImport(
            sourceManager = sourceManager,
            networkToLocalManga = networkToLocalManga,
            mangaRepository = mangaRepository,
            sourcePreferences = sourcePreferences,
            deeplinkResolverFactory = { FakeDeeplinkResolver(deeplinkHosts) },
        ).also {
            coEvery { mangaRepository.getFavoriteSourceAndUrl() } returns favoriteSourceAndUrl
            coEvery { networkToLocalManga(any<Manga>()) } returns resolvedManga
        }
    }

    @Test
    fun `regular url already in library is flagged as already-in-library`() = runBlocking {
        // extractPathFromUrl("https://example.com/manga/123") -> "/manga/123"
        val massImport = massImport(favoriteSourceAndUrl = listOf(source.id to "/manga/123"))

        val result = massImport.analyzeUrls("https://example.com/manga/123")

        assertEquals(listOf("https://example.com/manga/123"), result.alreadyInLibrary)
        assertTrue(result.validUrls.isEmpty())
    }

    @Test
    fun `deeplink-shaped url is valid even when its guessed path collides with a library entry`() = runBlocking {
        val deeplinkUrl = "https://example.com/title/uuid-1234/some-slug"
        // The guessed path for this URL would collide with an unrelated library entry; a
        // deeplink-aware preview must not use that guess to flag it as already-in-library.
        val massImport = massImport(
            favoriteSourceAndUrl = listOf(source.id to "/title/uuid-1234/some-slug"),
            deeplinkHosts = setOf("example.com"),
        )

        val result = massImport.analyzeUrls(deeplinkUrl)

        assertEquals(listOf(deeplinkUrl), result.validUrls)
        assertTrue(result.alreadyInLibrary.isEmpty())
    }

    @Test
    fun `deeplink-shaped url with no source match is still reported as no matching source`() = runBlocking {
        val massImport = massImport(deeplinkHosts = setOf("example.com"))

        val result = massImport.analyzeUrls("https://unrelated-host.test/title/uuid-1234/some-slug")

        assertEquals(1, result.invalidUrls.size)
        assertEquals("No matching source", result.invalidUrls.first().second)
    }

    @Test
    fun `resolveUrlToManga returns null when no installed source matches the host`() = runBlocking {
        val massImport = massImport()

        assertNull(massImport.resolveUrlToManga("https://unrelated-host.test/novel/abc"))
    }

    @Test
    fun `resolveUrlToManga fetches the URL's own path when it is not a deeplink`() = runBlocking {
        val massImport = massImport()

        val result = massImport.resolveUrlToManga("https://example.com/novel/abc")

        assertSame(resolvedManga, result?.first)
        assertSame(source, result?.second)
        assertEquals("/novel/abc", source.requestedUrl)
    }

    @Test
    fun `resolveUrlToManga fetches the canonical path when the URL is a deeplink`() = runBlocking {
        val deeplinkSource = FakeHttpSource(
            name = "Fake",
            lang = "en",
            baseUrl = "https://example.com",
            searchResultUrl = "/novel/real-slug",
        )
        val massImport = massImport(deeplinkHosts = setOf("example.com"), source = deeplinkSource)

        val result = massImport.resolveUrlToManga("https://example.com/title/uuid-1234/shareable")

        assertSame(resolvedManga, result?.first)
        assertEquals("/novel/real-slug", deeplinkSource.requestedUrl)
    }
}
