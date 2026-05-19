package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the pure, Android-free CSS helpers in [NovelWebViewStyler].
 *
 * [NovelWebViewStyler.buildPayload] requires Activity / WebView and is not
 * exercisable in JVM unit tests. [NovelWebViewStyler.fontOverrideCss] is the
 * extracted testable piece: it owns the logic that forces font-size and
 * font-family from reader settings to win over inline styles baked into the
 * source HTML (e.g. `<div style="font-size:18px">`).
 */
class NovelWebViewStylerTest {

    // ── fontOverrideCss ───────────────────────────────────────────────────────

    @Test
    fun `reader-priority mode emits font-size inherit in star rule`() {
        val (star, _) = NovelWebViewStyler.fontOverrideCss(sourceCssPriority = false, useOriginalFonts = false)
        assertTrue("font-size: inherit !important" in star) {
            "Expected star-rule override to contain font-size inherit, got: $star"
        }
    }

    @Test
    fun `reader-priority mode emits font-family inherit in star rule when not using original fonts`() {
        val (star, _) = NovelWebViewStyler.fontOverrideCss(sourceCssPriority = false, useOriginalFonts = false)
        assertTrue("font-family: inherit !important" in star) {
            "Expected star-rule override to contain font-family inherit, got: $star"
        }
    }

    @Test
    fun `reader-priority with useOriginalFonts omits font-family inherit`() {
        val (star, _) = NovelWebViewStyler.fontOverrideCss(sourceCssPriority = false, useOriginalFonts = true)
        assertTrue("font-size: inherit !important" in star)
        assertFalse("font-family: inherit !important" in star) {
            "Should not override font-family when useOriginalFonts=true, got: $star"
        }
    }

    @Test
    fun `reader-priority mode emits heading size rules`() {
        val (_, headings) = NovelWebViewStyler.fontOverrideCss(sourceCssPriority = false, useOriginalFonts = false)
        assertTrue("h1" in headings && "2em" in headings) { "Missing h1 rule in: $headings" }
        assertTrue("h2" in headings && "1.5em" in headings) { "Missing h2 rule in: $headings" }
        assertTrue("h3" in headings) { "Missing h3 rule in: $headings" }
        assertTrue("h4" in headings) { "Missing h4 rule in: $headings" }
        assertTrue("h5" in headings) { "Missing h5 rule in: $headings" }
        assertTrue("h6" in headings) { "Missing h6 rule in: $headings" }
    }

    @Test
    fun `heading rules use !important so they beat star-rule specificity`() {
        val (_, headings) = NovelWebViewStyler.fontOverrideCss(sourceCssPriority = false, useOriginalFonts = false)
        assertTrue("!important" in headings) {
            "Heading rules must use !important to override * { font-size: inherit !important }, got: $headings"
        }
    }

    @Test
    fun `source-priority mode produces empty overrides`() {
        val (star, headings) = NovelWebViewStyler.fontOverrideCss(sourceCssPriority = true, useOriginalFonts = false)
        assertTrue(star.isEmpty()) { "Expected empty star override when source has priority, got: $star" }
        assertTrue(headings.isEmpty()) { "Expected empty heading rules when source has priority, got: $headings" }
    }

    @Test
    fun `source-priority with useOriginalFonts also produces empty overrides`() {
        val (star, headings) = NovelWebViewStyler.fontOverrideCss(sourceCssPriority = true, useOriginalFonts = true)
        assertTrue(star.isEmpty())
        assertTrue(headings.isEmpty())
    }
}
