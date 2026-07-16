package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtmlUtilsTest {

    @Test
    fun `toHtml escapes unsafe html in plain text`() {
        // Pass .txt extension to force plain text detection
        val content = "Hi\nLine break\n<quote> para <quote>\n\n<bold> should not be bold"

        val html = HtmlUtils.normalizeContentForHtml(content, "chapter.txt")
        assertTrue(html.startsWith("<pre "))
        assertTrue(html.contains("data-tsundoku-plain-text=\"1\""))
        assertTrue(html.contains("Hi\nLine break"))
        assertTrue(html.contains("&lt;quote&gt; para &lt;quote&gt;"))
        assertTrue(html.contains("&lt;bold&gt; should not be bold"))
    }

    @Test
    fun `normalizeContentForHtml keeps txt content as escaped plain text`() {
        val content = "Line 1\n\n<punch>\n  indented line"

        val html = HtmlUtils.normalizeContentForHtml(content, "chapter.txt")

        assertTrue(html.contains("data-tsundoku-plain-text=\"1\""))
        assertTrue(html.contains("white-space: pre-wrap"))
        assertTrue(html.contains("&lt;punch&gt;"))
        assertTrue(html.contains("indented line"))
    }

    @Test
    fun `normalizeContentForHtml treats angle bracket words as plain text when not html`() {
        val content = "<punch> is not an html tag"

        val html = HtmlUtils.normalizeContentForHtml(content, "chapter")

        assertTrue(html.contains("data-tsundoku-plain-text=\"1\""))
        assertTrue(html.contains("&lt;punch&gt; is not an html tag"))
    }

    @Test
    fun `normalizeContentForHtml keeps html unchanged`() {
        val content = "<p>Hello</p><div>World</div>"

        val html = HtmlUtils.normalizeContentForHtml(content, "chapter.html")

        assertTrue(html == content)
    }

    // ── Entity encoding: plain text path ──────────────────────────────────

    @Test
    fun `plain text with pre-encoded lt-D-gt encodes correctly for display`() {
        // Content from HTTP sources or plain text files may contain &lt;D&gt; literally.
        // After normalizeContentForHtml the <pre> must contain &lt;D&gt; (not &amp;lt;D&amp;gt;)
        // so Html.fromHtml / WebView renders <D> on screen, not &lt;D&gt;.
        val content = "Amanda defeats &lt;D&gt; rank villain"
        val html = HtmlUtils.normalizeContentForHtml(content, "chapter")

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
        val html = HtmlUtils.normalizeContentForHtml(content, "chapter")

        assertTrue(html.contains("data-tsundoku-plain-text=\"1\""))
        // Entities decoded then re-escaped: rsquo → ' → &#39; / escaped char; mdash → — → literal
        assertFalse(html.contains("&amp;rsquo;"))
        assertFalse(html.contains("&amp;mdash;"))
    }

    @Test
    fun `plain text with raw angle brackets gets escaped`() {
        val content = "Amanda defeats <D> rank villain"
        val html = HtmlUtils.normalizeContentForHtml(content, "chapter")

        assertTrue(html.contains("data-tsundoku-plain-text=\"1\""))
        assertTrue(html.contains("&lt;D&gt;"))
        assertFalse(html.contains("<D>"))
    }

    // ── Entity encoding: HTML path ─────────────────────────────────────────

    @Test
    fun `HTTP source HTML with correct single-encoded entities passes through unchanged`() {
        // Standard HTML source: &lt;D&gt; is correct, browser renders as <D>. No modification needed.
        val content = "<p>Amanda defeats &lt;D&gt; rank villain</p>"
        val html = HtmlUtils.normalizeContentForHtml(content, "chapter")

        assertEquals(content, html)
        assertFalse(html.contains("&amp;lt;"))
    }

    @Test
    fun `HTML with literal amp Tom and Jerry preserved`() {
        val content = "<p>Tom &amp; Jerry</p>"
        assertEquals(content, HtmlUtils.normalizeContentForHtml(content, "chapter"))
    }

    // ── sanitizeForRender: TEXT_VIEW strips scripts and styles unconditionally ───

    @Test
    fun `sanitizeForRender TEXT_VIEW strips scripts even when keepEmbeddedJs is true`() {
        val out = HtmlUtils.sanitizeForRender(
            "<p>Body</p><script>alert(1)</script>",
            target = RenderTarget.TEXT_VIEW,
            keepEmbeddedCss = true,
            keepEmbeddedJs = true,
            blockMedia = false,
        )
        assertFalse(out.contains("<script"))
        assertTrue(out.contains("Body"))
    }

    @Test
    fun `sanitizeForRender TEXT_VIEW strips styles even when keepEmbeddedCss is true`() {
        val out = HtmlUtils.sanitizeForRender(
            "<p>Body</p><style>p{color:red}</style>",
            target = RenderTarget.TEXT_VIEW,
            keepEmbeddedCss = true,
            keepEmbeddedJs = true,
            blockMedia = false,
        )
        assertFalse(out.contains("<style"))
    }

    // ── sanitizeForRender: WEB_VIEW respects keep flags ────────────────────────────

    @Test
    fun `sanitizeForRender WEB_VIEW preserves scripts when keepEmbeddedJs true`() {
        val out = HtmlUtils.sanitizeForRender(
            "<p>Body</p><script>alert(1)</script>",
            target = RenderTarget.WEB_VIEW,
            keepEmbeddedCss = true,
            keepEmbeddedJs = true,
            blockMedia = false,
        )
        assertTrue(out.contains("<script"))
    }

    @Test
    fun `sanitizeForRender WEB_VIEW strips scripts when keepEmbeddedJs false`() {
        val out = HtmlUtils.sanitizeForRender(
            "<p>Body</p><script>alert(1)</script>",
            target = RenderTarget.WEB_VIEW,
            keepEmbeddedCss = true,
            keepEmbeddedJs = false,
            blockMedia = false,
        )
        assertFalse(out.contains("<script"))
    }

    @Test
    fun `sanitizeForRender strips HTML comments and entity-encoded ad markers`() {
        val out = HtmlUtils.sanitizeForRender(
            "<p>Body <!-- ad --> &lt;!--ad2--&gt;</p>",
            target = RenderTarget.WEB_VIEW,
            keepEmbeddedCss = true,
            keepEmbeddedJs = true,
            blockMedia = false,
        )
        assertFalse(out.contains("<!--"))
        assertFalse(out.contains("&lt;!--"))
    }

    @Test
    fun `sanitizeForRender blockMedia strips img video audio`() {
        val out = HtmlUtils.sanitizeForRender(
            "<p>Body</p><img src=\"x\"><video><source src=\"y\"></video><audio></audio>",
            target = RenderTarget.WEB_VIEW,
            keepEmbeddedCss = true,
            keepEmbeddedJs = false,
            blockMedia = true,
        )
        assertFalse(out.contains("<img"))
        assertFalse(out.contains("<video"))
        assertFalse(out.contains("<audio"))
        assertFalse(out.contains("<source"))
    }

    // ── isPlainTextChapter: URL parsing edges ────────────────────────────────────

    @Test
    fun `isPlainTextChapter matches txt without slash in URL`() {
        assertTrue(HtmlUtils.isPlainTextChapter("foo.txt"))
        assertTrue(HtmlUtils.isPlainTextChapter("foo.TXT"))
        assertTrue(HtmlUtils.isPlainTextChapter("foo.text"))
    }

    @Test
    fun `isPlainTextChapter matches txt with path prefix`() {
        assertTrue(HtmlUtils.isPlainTextChapter("/some/path/chapter.txt"))
        assertTrue(HtmlUtils.isPlainTextChapter("https://example.com/chapter.txt"))
    }

    @Test
    fun `isPlainTextChapter strips fragment and query`() {
        assertTrue(HtmlUtils.isPlainTextChapter("foo.txt#frag"))
        assertTrue(HtmlUtils.isPlainTextChapter("foo.txt?qs=1"))
        assertTrue(HtmlUtils.isPlainTextChapter("/path/foo.txt?qs=1#frag"))
    }

    @Test
    fun `isPlainTextChapter rejects non-plaintext extensions`() {
        assertFalse(HtmlUtils.isPlainTextChapter("foo.html"))
        assertFalse(HtmlUtils.isPlainTextChapter("foo.md"))
        assertFalse(HtmlUtils.isPlainTextChapter("foo"))
        assertFalse(HtmlUtils.isPlainTextChapter(null))
        assertFalse(HtmlUtils.isPlainTextChapter(""))
    }

    // ── normalizeUrl: schema repair ──────────────────────────────────────────────

    @Test
    fun `normalizeUrl fixes missing colon in https`() {
        assertEquals("https://foo.com/a", HtmlUtils.normalizeUrl("https//foo.com/a"))
    }

    @Test
    fun `normalizeUrl fixes missing colon in http`() {
        assertEquals("http://foo.com/a", HtmlUtils.normalizeUrl("http//foo.com/a"))
    }

    @Test
    fun `normalizeUrl trims whitespace and leaves well-formed urls alone`() {
        assertEquals("https://foo.com/a", HtmlUtils.normalizeUrl("  https://foo.com/a  "))
    }

    @Test
    fun `normalizeUrl returns null for null, empty, or whitespace-only input`() {
        assertEquals(null, HtmlUtils.normalizeUrl(null))
        assertEquals(null, HtmlUtils.normalizeUrl(""))
        assertEquals(null, HtmlUtils.normalizeUrl("   "))
        assertEquals(null, HtmlUtils.normalizeUrl("\t\n"))
    }

    // ── stripChapterTitle: various heading shapes ────────────────────────────────

    @Test
    fun `stripChapterTitle removes first H1`() {
        val out = HtmlUtils.stripChapterTitle("<h1>Chapter 1</h1><p>Body</p>", "Chapter 1")
        assertFalse(out.contains("<h1>"))
        assertTrue(out.contains("Body"))
    }

    @Test
    fun `stripChapterTitle removes first H2 H3 etc`() {
        val out = HtmlUtils.stripChapterTitle("<h3>Chapter 1</h3><p>Body</p>", "Chapter 1")
        assertFalse(out.contains("<h3>"))
    }

    @Test
    fun `stripChapterTitle unconditionally strips first heading even if name differs`() {
        // Heading is always stripped (existing behavior: extractPatterns first entry is unconditional)
        val out = HtmlUtils.stripChapterTitle("<h1>Some Other Title</h1><p>Body</p>", "Chapter 1")
        assertFalse(out.contains("<h1>"))
    }

    @Test
    fun `stripChapterTitle leaves content alone when nothing matches`() {
        val content = "<p>Body</p><p>More body</p>"
        val out = HtmlUtils.stripChapterTitle(content, "Chapter 1")
        assertEquals(content, out)
    }

    @Test
    fun `stripChapterTitle removes matching plain first line`() {
        val out = HtmlUtils.stripChapterTitle("Chapter 1\n\nBody text here", "Chapter 1")
        assertFalse(out.startsWith("Chapter 1"))
        assertTrue(out.contains("Body text here"))
    }

    // ── isTitleMatch ─────────────────────────────────────────────────────────────

    @Test
    fun `isTitleMatch exact match`() {
        assertTrue(HtmlUtils.isTitleMatch("chapter 1", "chapter 1"))
    }

    @Test
    fun `isTitleMatch substring match`() {
        assertTrue(HtmlUtils.isTitleMatch("chapter 1: prologue", "chapter 1"))
    }

    @Test
    fun `isTitleMatch chapter pattern match`() {
        assertTrue(HtmlUtils.isTitleMatch("chapter 5", "chapter 5: the awakening"))
    }

    @Test
    fun `isTitleMatch empty inputs return false`() {
        assertFalse(HtmlUtils.isTitleMatch("", "chapter 1"))
        assertFalse(HtmlUtils.isTitleMatch("chapter 1", ""))
    }

    // ── stripMediaTags ───────────────────────────────────────────────────────

    @Test
    fun `stripMediaTags removes img tags`() {
        val result = HtmlUtils.stripMediaTags("<p>Text</p><img src=\"x.jpg\"><p>More</p>")
        assertFalse(result.contains("<img"))
        assertTrue(result.contains("Text"))
        assertTrue(result.contains("More"))
    }

    @Test
    fun `stripMediaTags removes self-closing img tags`() {
        val result = HtmlUtils.stripMediaTags("<img src=\"a.png\" alt=\"b\" />")
        assertFalse(result.contains("<img"))
    }

    @Test
    fun `stripMediaTags removes video blocks with content`() {
        val result = HtmlUtils.stripMediaTags("<video controls><source src=\"v.mp4\"><p>fallback</p></video>")
        assertFalse(result.contains("<video"))
        assertFalse(result.contains("<source"))
    }

    @Test
    fun `stripMediaTags removes audio blocks`() {
        val result = HtmlUtils.stripMediaTags("<audio src=\"a.mp3\"></audio>")
        assertFalse(result.contains("<audio"))
    }

    @Test
    fun `stripMediaTags removes source tags`() {
        val result = HtmlUtils.stripMediaTags("<source src=\"x.webm\" type=\"video/webm\">")
        assertFalse(result.contains("<source"))
    }

    @Test
    fun `stripMediaTags leaves non-media html unchanged`() {
        val html = "<p>Hello <strong>world</strong></p>"
        assertEquals(html, HtmlUtils.stripMediaTags(html))
    }

    @Test
    fun `stripMediaTags is case insensitive`() {
        val result = HtmlUtils.stripMediaTags("<IMG SRC=\"x\"><VIDEO></VIDEO><AUDIO></AUDIO>")
        assertFalse(result.contains("<IMG", ignoreCase = true))
        assertFalse(result.contains("<VIDEO", ignoreCase = true))
        assertFalse(result.contains("<AUDIO", ignoreCase = true))
    }

    // ── stripChapterTitle beyond search limit ─────────────────────────────────

    @Test
    fun `stripChapterTitle does not strip heading beyond search limit`() {
        val preamble = "a".repeat(4000)
        val content = "<p>$preamble</p><h1>Chapter 1</h1><p>Body</p>"
        val out = HtmlUtils.stripChapterTitle(content, "Chapter 1")
        assertTrue(out.contains("<h1>Chapter 1</h1>"), "heading beyond search limit must be preserved")
    }

    // ── extractTables ─────────────────────────────────────────────────────────

    @Test
    fun `extractTables pulls table into a model and leaves a sentinel`() {
        val html = "<p>Intro</p><table><tr><th>Name</th><th>Value</th></tr>" +
            "<tr><td>STR</td><td>51</td></tr></table>"
        val (out, models) = HtmlUtils.extractTables(html)
        assertFalse(out.contains("<table", ignoreCase = true), "table tag should be gone")
        assertTrue(out.contains("Intro"), "surrounding content kept")
        assertEquals(1, models.size)
        val m = models[0]
        assertTrue(m.hasHeader, "first row used <th>")
        assertEquals(listOf("Name", "Value"), m.rows[0])
        assertEquals(listOf("STR", "51"), m.rows[1])
        // Sentinel present and parseable back to the model index.
        val match = HtmlUtils.tableSentinelRegex.find(out)
        assertTrue(match != null, "sentinel left in html")
        assertEquals(0, match!!.groupValues[1].toInt())
    }

    @Test
    fun `extractTables is a no-op without a table`() {
        val html = "<p>Just text.</p>"
        val (out, models) = HtmlUtils.extractTables(html)
        assertEquals(html, out)
        assertTrue(models.isEmpty())
    }

    @Test
    fun `extractTables honors br and p as line breaks inside a cell`() {
        val html = "<table><tr><td>Line1<br>Line2</td><td><p>A</p><p>B</p></td></tr></table>"
        val (_, models) = HtmlUtils.extractTables(html)
        assertEquals(listOf("Line1\nLine2", "A\nB"), models[0].rows[0])
    }

    @Test
    fun `extractTables collapses inner whitespace but keeps intended breaks`() {
        val html = "<table><tr><td>  big   gap <br>  next  </td></tr></table>"
        val (_, models) = HtmlUtils.extractTables(html)
        assertEquals(listOf("big gap\nnext"), models[0].rows[0])
    }

    @Test
    fun `extractTables indexes multiple tables in order`() {
        val html = "<table><tr><td>A</td></tr></table><p>mid</p><table><tr><td>B</td></tr></table>"
        val (out, models) = HtmlUtils.extractTables(html)
        assertEquals(2, models.size)
        assertEquals(listOf("A"), models[0].rows[0])
        assertEquals(listOf("B"), models[1].rows[0])
        val indices = HtmlUtils.tableSentinelRegex.findAll(out).map { it.groupValues[1].toInt() }.toList()
        assertEquals(listOf(0, 1), indices)
    }
}
