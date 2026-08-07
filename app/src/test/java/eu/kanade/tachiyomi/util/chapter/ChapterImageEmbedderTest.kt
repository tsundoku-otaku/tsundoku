package eu.kanade.tachiyomi.util.chapter

import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.download.service.NovelDownloadPreferences

class ChapterImageEmbedderTest {

    private val embedder = ChapterImageEmbedder(
        networkHelper = mockk<NetworkHelper>(relaxed = true),
        novelDownloadPreferences = mockk<NovelDownloadPreferences>(relaxed = true),
    )

    @Test
    fun `absolute urls are returned unchanged`() {
        assertEquals("https://cdn.com/a.jpg", embedder.resolveUrl("https://cdn.com/a.jpg", "https://x.com/ch1"))
        assertEquals("http://cdn.com/a.jpg", embedder.resolveUrl("http://cdn.com/a.jpg", null))
    }

    @Test
    fun `protocol-relative urls inherit https`() {
        assertEquals("https://cdn.com/a.jpg", embedder.resolveUrl("//cdn.com/a.jpg", "https://x.com/ch1"))
    }

    @Test
    fun `root-relative urls resolve against the base host`() {
        assertEquals(
            "https://x.com/images/a.jpg",
            embedder.resolveUrl("/images/a.jpg", "https://x.com/novel/ch1"),
        )
    }

    @Test
    fun `relative urls resolve against the base path`() {
        assertEquals(
            "https://x.com/novel/images/a.jpg",
            embedder.resolveUrl("images/a.jpg", "https://x.com/novel/ch1"),
        )
    }

    @Test
    fun `relative url with no base url is returned as-is`() {
        assertEquals("images/a.jpg", embedder.resolveUrl("images/a.jpg", null))
    }

    @Test
    fun `root-relative url with no base url is returned as-is`() {
        assertEquals("/images/a.jpg", embedder.resolveUrl("/images/a.jpg", null))
    }

    @Test
    fun `malformed base url falls back to the original relative url`() {
        assertEquals("images/a.jpg", embedder.resolveUrl("images/a.jpg", "not a url"))
    }
}
