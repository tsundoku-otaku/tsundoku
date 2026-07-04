package eu.kanade.tachiyomi.ui.customsource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SelectorUrlAlgorithmsTest {

    @Test
    fun `derivePageTemplate handles query param form`() {
        assertEquals(
            "https://site.com/list?page={page}",
            derivePageTemplate("https://site.com/list?page=1", "https://site.com/list?page=2"),
        )
    }

    @Test
    fun `derivePageTemplate handles path segment form`() {
        assertEquals(
            "https://site.com/page/{page}",
            derivePageTemplate("https://site.com/page/1", "https://site.com/page/2"),
        )
    }

    @Test
    fun `derivePageTemplate returns null when urls are identical or non-numeric`() {
        assertNull(derivePageTemplate("https://site.com/list", "https://site.com/list"))
        assertNull(derivePageTemplate("https://site.com/a", "https://site.com/b"))
        assertNull(derivePageTemplate("", "https://site.com/page/2"))
    }

    @Test
    fun `applyPagePattern reuses query token when page-1 had no page param`() {
        // The inserted token must contain the full "page=2" for the query form to trigger; this
        // happens when page 1 has no param and page 2 adds one.
        assertEquals(
            "https://site.com/latest?page=2",
            applyPagePattern("https://site.com/list", "https://site.com/list?page=2", "https://site.com/latest"),
        )
    }

    @Test
    fun `applyPagePattern reuses path token on the latest url`() {
        assertEquals(
            "https://site.com/latest/2",
            applyPagePattern("https://site.com/list/1", "https://site.com/list/2", "https://site.com/latest"),
        )
    }

    @Test
    fun `applyPagePattern appends with ampersand when target already has a query`() {
        assertEquals(
            "https://site.com/latest?lang=en&page=2",
            applyPagePattern(
                "https://site.com/list",
                "https://site.com/list?page=2",
                "https://site.com/latest?lang=en",
            ),
        )
    }

    @Test
    fun `deriveSearchUrl replaces raw query occurrence with placeholder`() {
        assertEquals(
            "https://site.com/?s={query}",
            deriveSearchUrl("https://site.com/?s=sword", "https://site.com", "sword"),
        )
    }

    @Test
    fun `deriveSearchUrl matches percent-encoded multi-word query`() {
        assertEquals(
            "https://site.com/search/{query}",
            deriveSearchUrl("https://site.com/search/sword%20art", "https://site.com", "sword art"),
        )
    }

    @Test
    fun `deriveSearchUrl returns null when host differs or query absent`() {
        assertNull(deriveSearchUrl("https://other.com/?s=sword", "https://site.com", "sword"))
        assertNull(deriveSearchUrl("https://site.com/?s=other", "https://site.com", "sword"))
        assertNull(deriveSearchUrl("https://site.com/?s=sword", "https://site.com", ""))
    }

    @Test
    fun `deriveSearchUrl matches across www and non-www hosts`() {
        assertEquals(
            "https://www.site.com/search?keyword={query}",
            deriveSearchUrl("https://www.site.com/search?keyword=World", "https://site.com", "World"),
        )
    }
}
