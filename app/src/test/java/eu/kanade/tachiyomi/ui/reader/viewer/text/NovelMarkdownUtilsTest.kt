package eu.kanade.tachiyomi.ui.reader.viewer.text

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelTextNormalizationTest {

    @Test
    fun `toHtml escapes unsafe html in plain text`() {
        // Pass .txt extension to force plain text detection
        val content = "Use <script>alert(1)</script> and <tag>."

        val html = NovelViewerTextUtils.normalizeContentForHtml(content, "chapter.txt")

        assertTrue(html.contains("data-tsundoku-plain-text=\"1\""))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&lt;tag&gt;"))
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
}
