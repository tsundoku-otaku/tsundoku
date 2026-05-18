package eu.kanade.tachiyomi.ui.reader.viewer.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelTextNormalizationTest {

    @Test
    fun `toHtml escapes unsafe html in plain text`() {
        // Pass .txt extension to force plain text detection
        val content = "Hi\nLine break\n<quote> para <quote>\n\n<bold> should not be bold"

        val html = NovelViewerTextUtils.normalizeContentForHtml(content, "chapter.txt")
        assertTrue(html.startsWith("<pre "))
        assertTrue(html.contains("data-tsundoku-plain-text=\"1\""))
        assertTrue(html.contains("Hi\nLine break"))
        assertTrue(html.contains("&lt;quote&gt; para &lt;quote&gt;"))
        assertTrue(html.contains("&lt;bold&gt; should not be bold"))
    }

    @Test
    fun `normalizeContentForHtml keeps txt content as escaped plain text`() {
        val content = "Line 1\n\n<punch>\n  indented line"

        val html = NovelViewerTextUtils.normalizeContentForHtml(content, "chapter.txt")

        assertTrue(html.contains("data-tsundoku-plain-text=\"1\""))
        assertTrue(html.contains("white-space: pre-wrap"))
        assertTrue(html.contains("&lt;punch&gt;"))
        assertTrue(html.contains("indented line"))
    }

    @Test
    fun `normalizeContentForHtml treats angle bracket words as plain text when not html`() {
        val content = "<punch> is not an html tag"

        val html = NovelViewerTextUtils.normalizeContentForHtml(content, "chapter")

        assertTrue(html.contains("data-tsundoku-plain-text=\"1\""))
        assertTrue(html.contains("&lt;punch&gt; is not an html tag"))
    }

    @Test
    fun `normalizeContentForHtml keeps html unchanged`() {
        val content = "<p>Hello</p><div>World</div>"

        val html = NovelViewerTextUtils.normalizeContentForHtml(content, "chapter.html")

        assertTrue(html == content)
    }

    // ── Entity encoding: plain text path ──────────────────────────────────

    @Test
    fun `plain text with pre-encoded lt-D-gt encodes correctly for display`() {
        // Content from HTTP sources or plain text files may contain &lt;D&gt; literally.
        // After normalizeContentForHtml the <pre> must contain &lt;D&gt; (not &amp;lt;D&amp;gt;)
        // so Html.fromHtml / WebView renders <D> on screen, not &lt;D&gt;.
        val content = "Amanda defeats &lt;D&gt; rank villain"
        val html = NovelViewerTextUtils.normalizeContentForHtml(content, "chapter")

        assertTrue(html.contains("data-tsundoku-plain-text=\"1\""))
        // Single-encoded &lt;D&gt; present — will render as <D>
        assertTrue(html.contains("&lt;D&gt;"))
        // Double-encoded &amp;lt; must NOT be present — that renders as &lt;D&gt; on screen
        assertFalse(html.contains("&amp;lt;"))
        assertFalse(html.contains("&amp;gt;"))
    }

    @Test
    fun `plain text with named entities encodes correctly`() {
        val content = "he&rsquo;s a hero&mdash;well known"
        val html = NovelViewerTextUtils.normalizeContentForHtml(content, "chapter")

        assertTrue(html.contains("data-tsundoku-plain-text=\"1\""))
        // Entities decoded then re-escaped: rsquo → ' → &#39; / escaped char; mdash → — → literal
        assertFalse(html.contains("&amp;rsquo;"))
        assertFalse(html.contains("&amp;mdash;"))
    }

    @Test
    fun `plain text with raw angle brackets gets escaped`() {
        val content = "Amanda defeats <D> rank villain"
        val html = NovelViewerTextUtils.normalizeContentForHtml(content, "chapter")

        assertTrue(html.contains("data-tsundoku-plain-text=\"1\""))
        assertTrue(html.contains("&lt;D&gt;"))
        assertFalse(html.contains("<D>"))
    }

    // ── Entity encoding: HTML path ─────────────────────────────────────────

    @Test
    fun `HTTP source HTML with correct single-encoded entities passes through unchanged`() {
        // Standard HTML source: &lt;D&gt; is correct, browser renders as <D>. No modification needed.
        val content = "<p>Amanda defeats &lt;D&gt; rank villain</p>"
        val html = NovelViewerTextUtils.normalizeContentForHtml(content, "chapter")

        assertEquals(content, html)
        assertFalse(html.contains("&amp;lt;"))
    }

    @Test
    fun `HTML with literal amp Tom and Jerry preserved`() {
        val content = "<p>Tom &amp; Jerry</p>"
        assertEquals(content, NovelViewerTextUtils.normalizeContentForHtml(content, "chapter"))
    }
}
