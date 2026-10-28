package eu.kanade.tachiyomi.source.custom

import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CustomSourceLogicTest {

    private val base = "https://site.example"

    @Test
    fun `page placeholder is substituted on every page including page 1`() {
        // Path-numbered sites: list/{page} -> list/1, list/2 (page 1 numbered in the path).
        assertEquals("$base/novels/page/1", buildPagedUrlTemplate("$base/novels/page/{page}", base, 1))
        assertEquals("$base/novels/page/3", buildPagedUrlTemplate("$base/novels/page/{page}", base, 3))
        assertEquals("$base/popular/1", buildPagedUrlTemplate("$base/popular/{page}", base, 1))
        assertEquals("$base/popular/2", buildPagedUrlTemplate("$base/popular/{page}", base, 2))
    }

    @Test
    fun `query-style page param is numbered`() {
        assertEquals("$base/novels?page=1", buildPagedUrlTemplate("$base/novels?page={page}", base, 1))
        assertEquals("$base/novels?page=2", buildPagedUrlTemplate("$base/novels?page={page}", base, 2))
    }

    @Test
    fun `template without page token is returned verbatim (used for page 1)`() {
        assertEquals("$base/popular", buildPagedUrlTemplate("$base/popular", base, 1))
    }

    @Test
    fun `baseUrl token is expanded`() {
        assertEquals("$base/popular", buildPagedUrlTemplate("{baseUrl}/popular", base, 1))
    }

    @Test
    fun `search url encodes the query and numbers the page`() {
        assertEquals(
            "$base/?s=hello+world&page=1",
            buildPagedSearchUrlTemplate("$base/?s={query}&page={page}", base, "hello world", 1),
        )
        assertEquals(
            "$base/search/hello/1",
            buildPagedSearchUrlTemplate("$base/search/{query}/{page}", base, "hello", 1),
        )
    }

    @Test
    fun `status mapping overrides built-in keywords`() {
        val mapping = mapOf("连载" to "ongoing", "完结" to "completed")
        assertEquals(SManga.ONGOING, parseCustomSourceStatus("连载中", mapping))
        assertEquals(SManga.COMPLETED, parseCustomSourceStatus("已完结", mapping))
    }

    @Test
    fun `status falls back to english keywords without mapping`() {
        assertEquals(SManga.ONGOING, parseCustomSourceStatus("Ongoing", null))
        assertEquals(SManga.COMPLETED, parseCustomSourceStatus("Completed", null))
        assertEquals(SManga.CANCELLED, parseCustomSourceStatus("Canceled", null))
        assertEquals(SManga.UNKNOWN, parseCustomSourceStatus("???", null))
        assertEquals(SManga.UNKNOWN, parseCustomSourceStatus(null, null))
    }

    @Test
    fun `generated chapter entries cover the inclusive range`() {
        val entries = generatedChapterEntries("/novel/chapter-{n}", 1, 3, null)
        assertEquals(
            listOf("/novel/chapter-1", "/novel/chapter-2", "/novel/chapter-3"),
            entries.map { it.url },
        )
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), entries.map { it.name })
        assertEquals(listOf(1f, 2f, 3f), entries.map { it.number })
    }

    @Test
    fun `generated chapter entries use the custom name template`() {
        val entries = generatedChapterEntries("/c/{n}", 5, 5, "Ch. {n} of the saga")
        assertEquals("Ch. 5 of the saga", entries.single().name)
    }

    @Test
    fun `generated chapter entries are empty when end precedes start`() {
        assertTrue(generatedChapterEntries("/c/{n}", 5, 1, null).isEmpty())
    }

    @Test
    fun `friendly parse error flags missing fields`() {
        val msg = customSourceFriendlyParseError(
            IllegalArgumentException("Field 'popularUrl' is required but it was missing"),
        )
        assertTrue(msg.startsWith("Missing required field"))
    }

    @Test
    fun `friendly parse error flags malformed json`() {
        val msg = customSourceFriendlyParseError(IllegalArgumentException("Unexpected JSON token at offset 4"))
        assertTrue(msg.startsWith("Malformed JSON"))
    }

    @Test
    fun `friendly parse error falls back to class name when message blank`() {
        val msg = customSourceFriendlyParseError(IllegalStateException())
        assertFalse(msg.isBlank())
        assertTrue(msg.contains("IllegalStateException"))
    }

    @Test
    fun `cover resolver prefers absolute lazy-load attributes`() {
        val doc = org.jsoup.Jsoup.parse(
            """<img src="/img/cover.jpg" data-src="/img/real.jpg">""",
            "https://site.example/novel",
        )
        // data-src wins over src, and the relative path is absolutized.
        assertEquals("https://site.example/img/real.jpg", resolveImageUrl(doc.selectFirst("img")!!))
    }

    @Test
    fun `cover resolver unwraps srcset to first url`() {
        val doc = org.jsoup.Jsoup.parse(
            """<img srcset="https://cdn.example/a.jpg 1x, https://cdn.example/b.jpg 2x">""",
            "https://site.example",
        )
        assertEquals("https://cdn.example/a.jpg", resolveImageUrl(doc.selectFirst("img")!!))
    }

    @Test
    fun `cover resolver reads css background image`() {
        val doc = org.jsoup.Jsoup.parse(
            """<div style="background-image: url('https://cdn.example/bg.png');"></div>""",
            "https://site.example",
        )
        assertEquals("https://cdn.example/bg.png", resolveImageUrl(doc.selectFirst("div")!!))
    }

    @Test
    fun `cover resolver returns null when no image present`() {
        val doc = org.jsoup.Jsoup.parse("""<div class="x"></div>""", "https://site.example")
        assertNull(resolveImageUrl(doc.selectFirst("div")!!))
    }

    @Test
    fun `html to text preserves paragraph and line breaks`() {
        val out = htmlToFormattedText("<p>First para.</p><p>Second line.<br>Third line.</p>")
        assertEquals("First para.\n\nSecond line.\nThird line.", out)
    }

    @Test
    fun `html to text collapses excess blank lines and trims`() {
        val out = htmlToFormattedText("<p>One</p><p></p><p></p><p>Two</p>")
        assertEquals("One\n\nTwo", out)
    }

    @Test
    fun `rewrite moves only the base host and keeps path and query`() {
        val rewritten = rewriteToCustomBase(
            "https://source.example/novel/1?page=2".toHttpUrl(),
            "https://source.example",
            "https://mirror.example",
        )
        assertEquals("https://mirror.example/novel/1?page=2", rewritten.toString())
    }

    @Test
    fun `rewrite leaves a url-valued query parameter alone`() {
        val rewritten = rewriteToCustomBase(
            "https://source.example/proxy?url=https://cdn.example/a.jpg".toHttpUrl(),
            "https://source.example",
            "https://mirror.example",
        )
        assertEquals("mirror.example", rewritten?.host)
        assertEquals("https://cdn.example/a.jpg", rewritten?.queryParameter("url"))
    }

    @Test
    fun `rewrite ignores requests to other hosts`() {
        assertNull(
            rewriteToCustomBase(
                "https://cdn.example/a.jpg?ref=https://source.example".toHttpUrl(),
                "https://source.example",
                "https://mirror.example",
            ),
        )
    }
}
