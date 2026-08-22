package eu.kanade.domain.manga.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.massimport.DeeplinkResolver
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

private class FakeHttpSource(
    override val name: String,
    override val lang: String,
    override val baseUrl: String,
) : HttpSource()

private class FakeDeeplinkResolver(private val deeplinkHosts: Set<String>) : DeeplinkResolver {
    override fun isDeeplinkUrl(source: CatalogueSource, url: String): Boolean {
        val host = java.net.URI(url).host?.lowercase() ?: return false
        return host in deeplinkHosts
    }
}

class MassImportDeeplinkTest {

    private val source = FakeHttpSource(name = "Fake", lang = "en", baseUrl = "https://example.com")

    private fun massImport(
        favoriteSourceAndUrl: List<Pair<Long, String>> = emptyList(),
        deeplinkHosts: Set<String> = emptySet(),
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
            io.mockk.coEvery { mangaRepository.getFavoriteSourceAndUrl() } returns favoriteSourceAndUrl
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
}
