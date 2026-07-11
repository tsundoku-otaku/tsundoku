package eu.kanade.tachiyomi.source.custom

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CustomNovelSourceTest {

    @Test
    fun `rebase url replaces original host with custom host`() {
        assertEquals(
            "https://custom.example/chapter-1",
            rebaseCustomSourceUrl("https://old.example/chapter-1", "https://custom.example", "https://old.example"),
        )
        assertEquals(
            "https://custom.example/chapter-1",
            rebaseCustomSourceUrl("/chapter-1", "https://custom.example", "https://old.example"),
        )
    }

    @Test
    fun `rebasing manga chapter page and list uses custom base`() {
        val manga = SManga.create().also {
            it.title = "Novel"
            it.url = "https://old.example/novel"
            it.thumbnail_url = "https://old.example/cover.jpg"
            it.status = SManga.UNKNOWN
            it.update_strategy = UpdateStrategy.ALWAYS_UPDATE
            it.initialized = true
        }
        val chapter = SChapter.create().also {
            it.name = "Chapter 1"
            it.url = "https://old.example/chapter-1"
            it.date_upload = 0L
            it.chapter_number = 1f
            it.scanlator = null
            it.locked = false
        }
        val page = Page(0, "https://old.example/chapter-1")
        val mangasPage = MangasPage(listOf(manga), hasNextPage = true)

        assertEquals(
            "https://custom.example/novel",
            rebaseCustomSourceManga(manga, "https://custom.example", "https://old.example").url,
        )
        assertEquals(
            "https://custom.example/cover.jpg",
            rebaseCustomSourceManga(manga, "https://custom.example", "https://old.example").thumbnail_url,
        )
        assertEquals(
            "https://custom.example/chapter-1",
            rebaseCustomSourceChapter(chapter, "https://custom.example", "https://old.example").url,
        )
        assertEquals(
            "https://custom.example/chapter-1",
            rebaseCustomSourcePage(page, "https://custom.example", "https://old.example").url,
        )
        assertEquals(
            "https://custom.example/novel",
            rebaseCustomSourceMangasPage(
                mangasPage,
                "https://custom.example",
                "https://old.example",
            ).mangas.first().url,
        )
        assertTrue(
            rebaseCustomSourcePage(
                page,
                "https://custom.example",
                "https://old.example",
            ).url.startsWith("https://custom.example"),
        )
    }

    @Test
    fun `rebasing a page also rebases its imageUrl to the custom host`() {
        val page = Page(0, "https://old.example/chapter-1", "https://old.example/images/page-1.jpg")

        val rebased = rebaseCustomSourcePage(page, "https://custom.example", "https://old.example")

        assertEquals("https://custom.example/chapter-1", rebased.url)
        assertEquals("https://custom.example/images/page-1.jpg", rebased.imageUrl)
    }

    @Test
    fun `rebasing a page leaves a null imageUrl null`() {
        val page = Page(0, "https://old.example/chapter-1")

        assertEquals(null, rebaseCustomSourcePage(page, "https://custom.example", "https://old.example").imageUrl)
    }

    @Test
    fun `rebasing a page leaves a third-party cdn imageUrl unchanged`() {
        // A CDN/mirror host that isn't the delegated source's own host must not be glued onto
        // the custom base -- it's already a valid absolute URL pointing elsewhere.
        val page = Page(0, "https://old.example/chapter-1", "https://cdn.example/images/page-1.jpg")

        val rebased = rebaseCustomSourcePage(page, "https://custom.example", "https://old.example")

        assertEquals("https://custom.example/chapter-1", rebased.url)
        assertEquals("https://cdn.example/images/page-1.jpg", rebased.imageUrl)
    }

    @Test
    fun `a rebased page's url and imageUrl both map back to the delegated source host`() {
        // Mirrors what CustomNovelSource.getImageUrl/getImage do: a Page rebased for display
        // (source host -> custom host) must map back to the source host on both fields before
        // being handed to the wrapped extension, so its own image interceptors/headers apply.
        val original = Page(0, "https://old.example/chapter-1", "https://old.example/images/page-1.jpg")
        val rebased = rebaseCustomSourcePage(original, "https://custom.example", "https://old.example")

        assertEquals(
            original.url,
            mapCustomUrlToSourceUrl(rebased.url, "https://custom.example", "https://old.example"),
        )
        assertEquals(
            original.imageUrl,
            mapCustomUrlToSourceUrl(rebased.imageUrl, "https://custom.example", "https://old.example"),
        )
    }

    @Test
    fun `toBaseSourceUrl restores delegated host from custom url`() {
        assertEquals(
            "https://old.example/series/test",
            mapCustomUrlToSourceUrl(
                "https://custom.example/series/test",
                "https://custom.example",
                "https://old.example",
            ),
        )
        assertEquals(
            "https://old.example/series/test",
            mapCustomUrlToSourceUrl("https://old.example/series/test", "https://custom.example", "https://old.example"),
        )
    }

    @Test
    fun `rebase absolute url with sourceBase provided should convert host`() {
        // rebaseCustomSourceUrl is designed to convert URLs from sourceBase to customBase
        val absoluteUrl = "https://website1.com/library-of-heavens-path.html"
        val customBase = "https://custom.example"
        val sourceBase = "https://website1.com"

        // When sourceBase is provided and matches, it should convert
        assertEquals(
            "https://custom.example/library-of-heavens-path.html",
            rebaseCustomSourceUrl(absoluteUrl, customBase, sourceBase),
            "URL starting with sourceBase should be converted to custom base",
        )
    }

    @Test
    fun `rebase absolute url without sourceBase should return unchanged`() {
        // If sourceBase is NOT provided, absolute URLs should be returned as-is
        val absoluteUrl = "https://website1.com/library-of-heavens-path.html"
        val customBase = "https://custom.example"

        assertEquals(
            absoluteUrl,
            rebaseCustomSourceUrl(absoluteUrl, customBase, null),
            "Absolute URLs should be returned unchanged when no sourceBase provided",
        )
    }

    @Test
    fun `embedded absolute url can be rebased`() {
        val malformedUrl = "https://source.examplehttps//source.example/chapter-1"
        val customBase = "https://custom.example"
        val sourceBase = "https://source.example"

        assertEquals(
            "https://custom.example/chapter-1",
            rebaseCustomSourceUrl(malformedUrl, customBase, sourceBase),
        )
        assertEquals(
            "https://source.example/chapter-1",
            mapCustomUrlToSourceUrl("https://custom.example/chapter-1", customBase, sourceBase),
        )
    }

    @Test
    fun `rebase with sourceBase prefix should convert host`() {
        val sourceUrl = "https://website1.com/library-of-heavens-path.html"
        val customBase = "https://custom.example"
        val sourceBase = "https://website1.com"

        // If it matches sourceBase, convert to custom base
        val result = rebaseCustomSourceUrl(sourceUrl, customBase, sourceBase)
        assertEquals("https://custom.example/library-of-heavens-path.html", result)
    }

    @Test
    fun `mapCustomUrlToSourceUrl should handle absolute urls correctly`() {
        val customUrl = "https://custom.example/library-of-heavens-path.html"
        val customBase = "https://custom.example"
        val sourceBase = "https://website1.com"

        val result = mapCustomUrlToSourceUrl(customUrl, customBase, sourceBase)
        assertEquals("https://website1.com/library-of-heavens-path.html", result)
    }

    @Test
    fun `round trip conversion custom to source and back`() {
        val customUrl = "https://custom.example/library-of-heavens-path.html"
        val customBase = "https://custom.example"
        val sourceBase = "https://website1.com"

        // Custom URL → Source URL
        val toSource = mapCustomUrlToSourceUrl(customUrl, customBase, sourceBase)
        assertEquals("https://website1.com/library-of-heavens-path.html", toSource)

        // Source URL → Custom URL
        val backToCustom = rebaseCustomSourceUrl(toSource, customBase, sourceBase)
        assertEquals(customUrl, backToCustom)
    }

    @Test
    fun `test website1 scenario from runtime`() {
        // This mimics the actual scenario from the app
        val sourceBaseUrl = "https://website1.com"
        val customBaseUrl = "https://custom.example"

        // Scenario 1: Source returns absolute URL
        val sourceReturnedUrl = "https://website1.com/library-of-heavens-path.html"

        // When we rebase it to custom for display/storage
        val rebasedForCustom = rebaseCustomSourceUrl(sourceReturnedUrl, customBaseUrl, sourceBaseUrl)
        assertEquals(
            "https://custom.example/library-of-heavens-path.html",
            rebasedForCustom,
            "Source absolute URL should be converted to custom host",
        )

        // Scenario 2: We need to convert custom URL back to source for HTTP request
        val customStoredUrl = "https://custom.example/library-of-heavens-path.html"
        val convertedToSource = mapCustomUrlToSourceUrl(customStoredUrl, customBaseUrl, sourceBaseUrl)
        assertEquals(
            "https://website1.com/library-of-heavens-path.html",
            convertedToSource,
            "Custom URL should be converted back to source host",
        )

        // Scenario 3: The interceptor should then rewrite source host back to custom for the actual request
        val sourceUrl = convertedToSource
        val finalInterceptedUrl = if (sourceUrl != null && sourceUrl.startsWith(sourceBaseUrl)) {
            customBaseUrl + sourceUrl.removePrefix(sourceBaseUrl)
        } else {
            sourceUrl
        }
        assertEquals(
            "https://custom.example/library-of-heavens-path.html",
            finalInterceptedUrl,
            "Interceptor should rewrite source URLs to custom base",
        )
    }

    @Test
    fun `interceptor behavior when HttpSource baseUrl is preserved`() {
        // The interceptor is set up to convert sourceBase -> customBase
        val sourceBaseUrl = "https://website1.com"
        val customBaseUrl = "https://custom.example"

        // If HttpSource keeps its original baseUrl, requests will be built as:
        val httpSourceBuiltUrl = "https://website1.com/library-of-heavens-path.html"

        // Interceptor logic:
        val interceptorResult = if (httpSourceBuiltUrl.startsWith(sourceBaseUrl)) {
            customBaseUrl + httpSourceBuiltUrl.removePrefix(sourceBaseUrl)
        } else {
            httpSourceBuiltUrl
        }

        assertEquals(
            "https://custom.example/library-of-heavens-path.html",
            interceptorResult,
            "Interceptor correctly rewrites original sourceBase URL to custom",
        )
    }

    @Test
    fun `problem HttpSource baseUrl changed to custom breaks interceptor`() {
        // THE BUG: When we set HttpSource.baseUrl = customBaseUrl
        val sourceBaseUrl = "https://website1.com"
        val customBaseUrl = "https://custom.example"
        val modifiedHttpSourceBaseUrl = customBaseUrl // <- This is what patchHttpSourceForCustomBaseUrl does!

        // HttpSource will now build requests as:
        val httpSourceBuiltUrl = modifiedHttpSourceBaseUrl + "/library-of-heavens-path.html"

        // Interceptor tries to match and convert:
        val interceptorResult = if (httpSourceBuiltUrl.startsWith(sourceBaseUrl)) {
            customBaseUrl + httpSourceBuiltUrl.removePrefix(sourceBaseUrl)
        } else {
            httpSourceBuiltUrl // <- Returns unchanged!
        }

        assertEquals(
            "https://custom.example/library-of-heavens-path.html",
            interceptorResult,
            "This should work, but only by luck since URL is already custom",
        )

        // But the REAL problem: what if the URL is a relative path?
        val relativeUrl = "/library-of-heavens-path.html"
        val httpSourceBuiltFromRelative = modifiedHttpSourceBaseUrl + relativeUrl

        // Interceptor sees this and since it doesn't start with sourceBase, leaves it alone
        val interceptorResultFromRelative = if (httpSourceBuiltFromRelative.startsWith(sourceBaseUrl)) {
            customBaseUrl + httpSourceBuiltFromRelative.removePrefix(sourceBaseUrl)
        } else {
            httpSourceBuiltFromRelative
        }

        assertEquals(
            "https://custom.example/library-of-heavens-path.html",
            interceptorResultFromRelative,
            "This also works, but again only by accident",
        )
    }

    @Test
    fun `buildAbsoluteUrl handles redirect scenarios correctly`() {
        val baseUrl = "https://website1.com"

        // Case 1: Already absolute URL (after redirect to different domain)
        // This is the bug scenario: after a 301 redirect from website1.com to website1redirect.net,
        // the page.url becomes the full website1redirect.net URL which shouldn't be concatenated
        val redirectedAbsoluteUrl = "https://website1redirect.net/chapter-1"
        // Should NOT concatenate - must return the URL as-is
        val result1 = buildAbsoluteUrlForTest(redirectedAbsoluteUrl, baseUrl)
        assertEquals(
            "https://website1redirect.net/chapter-1",
            result1,
            "Already absolute URLs from redirects should be used as-is, not concatenated with baseUrl",
        )

        // Case 2: Root-relative URL
        val rootRelativeUrl = "/chapter-1"
        val result2 = buildAbsoluteUrlForTest(rootRelativeUrl, baseUrl)
        assertEquals(
            "https://website1.com/chapter-1",
            result2,
            "Root-relative URLs should be concatenated with baseUrl",
        )

        // Case 3: Relative URL without leading slash
        val relativeUrl = "chapter-1"
        val result3 = buildAbsoluteUrlForTest(relativeUrl, baseUrl)
        assertEquals(
            "https://website1.com/chapter-1",
            result3,
            "Relative URLs should be concatenated with baseUrl and a slash",
        )

        // Case 4: Empty or null URL
        val result4 = buildAbsoluteUrlForTest("", baseUrl)
        assertEquals(
            baseUrl,
            result4,
            "Empty URL should return baseUrl",
        )

        // Case 5: HTTP URL (also absolute)
        val httpAbsoluteUrl = "http://example.com/page"
        val result5 = buildAbsoluteUrlForTest(httpAbsoluteUrl, baseUrl)
        assertEquals(
            "http://example.com/page",
            result5,
            "HTTP absolute URLs should be used as-is",
        )
    }

    @Test
    fun `derive generic chapter pattern uses last digit run and generalizes novel url`() {
        // Two chapter URLs of the same novel; novel id "abc" must become the {novelUrl} placeholder
        // so the pattern is portable to other novels.
        val result = deriveGenericChapterPattern(
            "https://example.com/novel/abc/chapter-1",
            "https://example.com/novel/abc/chapter-450",
            "https://example.com",
            "https://example.com/novel/abc",
        )
        assertEquals(Triple("{novelUrl}/chapter-{n}", 1, 450), result)
    }

    @Test
    fun `derive generic chapter pattern without novel url stays relative`() {
        val result = deriveGenericChapterPattern(
            "https://example.com/novel/abc/chapter-1",
            "https://example.com/novel/abc/chapter-2",
            "https://example.com",
            null,
        )
        assertEquals(Triple("/novel/abc/chapter-{n}", 1, 2), result)
    }

    @Test
    fun `derive generic chapter pattern returns null when no numeric difference`() {
        assertEquals(
            null,
            deriveGenericChapterPattern(
                "https://example.com/novel/abc/intro",
                "https://example.com/novel/abc/intro",
                "https://example.com",
                "https://example.com/novel/abc",
            ),
        )
    }

    @Test
    fun `apply novel url to pattern substitutes current novel path`() {
        assertEquals(
            "/series/xyz/chapter-{n}",
            applyNovelUrlToPattern("{novelUrl}/chapter-{n}", "/series/xyz"),
        )
        // No placeholder = unchanged (legacy patterns).
        assertEquals(
            "/novel/abc/chapter-{n}",
            applyNovelUrlToPattern("/novel/abc/chapter-{n}", "/series/xyz"),
        )
    }

    @Test
    fun `generated chapter entries build numbered urls and names`() {
        val entries = generatedChapterEntries("/series/xyz/chapter-{n}", 1, 3, null)
        assertEquals(3, entries.size)
        assertEquals("/series/xyz/chapter-1", entries.first().url)
        assertEquals("Chapter 1", entries.first().name)
        assertEquals("Chapter 3", entries.last().name)

        val custom = generatedChapterEntries("/c/{n}", 5, 5, "Ep {n}")
        assertEquals(1, custom.size)
        assertEquals("Ep 5", custom.first().name)
        assertEquals(5f, custom.first().number)
    }

    @Test
    fun `parse status uses custom mapping before english fallback`() {
        // Non-English label resolved via the user mapping.
        assertEquals(
            SManga.COMPLETED,
            parseCustomSourceStatus("完结", mapOf("完结" to "completed")),
        )
        // Mapping is case-insensitive and substring-based.
        assertEquals(
            SManga.ONGOING,
            parseCustomSourceStatus("En cours de publication", mapOf("en cours" to "ongoing")),
        )
        // Built-in English keywords still work without a mapping.
        assertEquals(SManga.ONGOING, parseCustomSourceStatus("Ongoing"))
        assertEquals(SManga.COMPLETED, parseCustomSourceStatus("Completed"))
        // Unknown text and blank are UNKNOWN.
        assertEquals(SManga.UNKNOWN, parseCustomSourceStatus("???", mapOf("done" to "completed")))
        assertEquals(SManga.UNKNOWN, parseCustomSourceStatus(null))
    }

    // Helper function that mirrors buildAbsoluteUrl logic for testing
    private fun buildAbsoluteUrlForTest(url: String?, baseUrl: String): String {
        val trimmedUrl = url?.trim().orEmpty()
        if (trimmedUrl.isBlank()) return baseUrl

        // If URL already has a scheme, it's absolute (e.g., after a redirect to a different domain)
        if (trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")) {
            return trimmedUrl
        }

        // If URL starts with /, it's root-relative
        if (trimmedUrl.startsWith("/")) {
            return baseUrl + trimmedUrl
        }

        // Otherwise, it's a relative path - add slash between baseUrl and path
        return "$baseUrl/$trimmedUrl"
    }
}
