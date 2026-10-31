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
    fun `sanitizeForRender WEB_VIEW forces eager loading on lazy images`() {
        val out = HtmlUtils.sanitizeForRender(
            "<figure><img src=\"/uploads/x.jpg\" loading=\"lazy\" width=\"1024\" height=\"1024\"></figure>",
            target = RenderTarget.WEB_VIEW,
            keepEmbeddedCss = true,
            keepEmbeddedJs = true,
            blockMedia = false,
        )
        assertFalse(out.contains("loading=\"lazy\""))
        assertTrue(out.contains("loading=\"eager\""))
    }

    @Test
    fun `sanitizeForRender lazy rewrite only touches img and iframe tags`() {
        val out = HtmlUtils.sanitizeForRender(
            "<pre>loading=\"lazy\"</pre><p data-loading=\"lazy\">x</p><iframe src=\"/f\" loading='lazy'></iframe>",
            target = RenderTarget.WEB_VIEW,
            keepEmbeddedCss = true,
            keepEmbeddedJs = true,
            blockMedia = false,
        )
        assertTrue(out.contains("<pre>loading=\"lazy\"</pre>"))
        assertTrue(out.contains("data-loading=\"lazy\""))
        assertTrue(out.contains("<iframe src=\"/f\" loading=\"eager\">"))
    }

    @Test
    fun `sanitizeForRender TEXT_VIEW leaves lazy loading attribute untouched`() {
        val out = HtmlUtils.sanitizeForRender(
            "<img src=\"/uploads/x.jpg\" loading=\"lazy\">",
            target = RenderTarget.TEXT_VIEW,
            keepEmbeddedCss = true,
            keepEmbeddedJs = true,
            blockMedia = false,
        )
        assertTrue(out.contains("loading=\"lazy\""))
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

    @Test
    fun `sanitizeForRender WEB_VIEW also rewrites unquoted and single-quoted lazy loading`() {
        val out = HtmlUtils.sanitizeForRender(
            "<img src=\"/a.jpg\" loading=lazy><img src='/b.jpg' loading='lazy'>",
            target = RenderTarget.WEB_VIEW,
            keepEmbeddedCss = true,
            keepEmbeddedJs = true,
            blockMedia = false,
        )
        assertFalse(out.contains("lazy"))
    }

    private fun webView(html: String, keepJs: Boolean) = HtmlUtils.sanitizeForRender(
        html,
        target = RenderTarget.WEB_VIEW,
        keepEmbeddedCss = true,
        keepEmbeddedJs = keepJs,
        blockMedia = false,
    )

    @Test
    fun `sanitizeForRender strips inline event handlers when js is off`() {
        val out = webView(
            "<img src=\"/x.jpg\" onerror=\"alert(1)\"><p onclick='a()' class=\"c\">t</p><b ONLOAD=a()>b</b>",
            keepJs = false,
        )
        assertFalse(out.contains("onerror", ignoreCase = true))
        assertFalse(out.contains("onclick", ignoreCase = true))
        assertFalse(out.contains("onload", ignoreCase = true))
        assertTrue(out.contains("src=\"/x.jpg\""))
        assertTrue(out.contains("class=\"c\""))
    }

    @Test
    fun `sanitizeForRender strips javascript urls when js is off`() {
        val out = webView(
            "<a href=\"javascript:alert(1)\">a</a><a href=' JavaScript:x()'>b</a><a href=\"https://example.com\">c</a>",
            keepJs = false,
        )
        assertFalse(out.contains("javascript:", ignoreCase = true))
        assertTrue(out.contains("href=\"https://example.com\""))
    }

    @Test
    fun `sanitizeForRender keeps handlers and javascript urls when js is on`() {
        val html = "<img src=\"/x.jpg\" onerror=\"a()\"><a href=\"javascript:b()\">a</a>"
        val out = webView(html, keepJs = true)
        assertTrue(out.contains("onerror=\"a()\""))
        assertTrue(out.contains("javascript:b()"))
    }

    @Test
    fun `sanitizeForRender leaves text that looks like a handler untouched`() {
        val out = webView("<p>use onclick=\"x\" in html</p>", keepJs = false)
        assertTrue(out.contains("use onclick=\"x\" in html"))
    }

    @Test
    fun `sanitizeForRender lazy rewrite does not touch pre or script text`() {
        val out = webView("<pre>loading=\"lazy\"</pre><img src=\"/x.jpg\" data-loading=\"lazy\" loading=lazy>", true)
        assertTrue(out.contains("<pre>loading=\"lazy\"</pre>"))
        assertTrue(out.contains("data-loading=\"lazy\""))
        assertTrue(out.contains("loading=\"eager\""))
    }

    @Test
    fun `sanitizeForRender lazy rewrite covers iframes`() {
        val out = webView("<iframe src=\"https://example.com/v\" loading=\"lazy\"></iframe>", true)
        assertTrue(out.contains("loading=\"eager\""))
    }

    @Test
    fun `sanitizeForRender strips slash separated and entity encoded vectors when js is off`() {
        val out = webView(
            "<svg/onload=a()><img src=x onerror=alert(1)><a href=\"jav&#x61;script:x()\">a</a>" +
                "<a href=\"java&#9;script:y()\">b</a>",
            keepJs = false,
        )
        assertFalse(out.contains("onerror", ignoreCase = true))
        assertFalse(out.contains("onload", ignoreCase = true))
        assertFalse(out.contains("script:", ignoreCase = true))
    }

    @Test
    fun `sanitizeForRender keeps handler-like text inside attribute values when js is off`() {
        val out = webView("<img src=\"/a.jpg\" alt=\"press onclick=x to go\">", keepJs = false)
        assertTrue(out.contains("alt=\"press onclick=x to go\""))
    }

    @Test
    fun `sanitizeForRender lazy rewrite cannot break out of an attribute`() {
        val out = webView("<img src=\"/a.jpg\" alt=\"loading=lazy onerror=Android.foo() x\">", keepJs = false)
        val img = org.jsoup.Jsoup.parseBodyFragment(out).selectFirst("img")!!
        assertEquals(setOf("src", "alt"), img.attributes().asList().map { it.key }.toSet())
    }

    @Test
    fun `sanitizeForRender noscript content cannot become live markup`() {
        val out = webView("<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></p></noscript>", keepJs = false)
        assertFalse(out.contains("onerror", ignoreCase = true))
    }

    @Test
    fun `sanitizeForRender comments cannot join attribute fragments into a tag`() {
        val out = webView("<p title=\"<!--\">a</p><p title=\"--><img src=x onmouseover=alert(1)>\">b</p>", keepJs = false)
        val doc = org.jsoup.Jsoup.parseBodyFragment(out)
        assertTrue(doc.select("[onmouseover]").isEmpty())
        assertTrue(doc.select("img").isEmpty())
    }

    @Test
    fun `sanitizeForRender strips srcdoc and data html urls when js is off`() {
        val out = webView(
            "<iframe srcdoc=\"<script>Android.x()</script>\"></iframe><iframe src=\"data:text/html,<script>x()</script>\"></iframe>" +
                "<img src=\"data:image/png;base64,AAAA\">",
            keepJs = false,
        )
        assertFalse(out.contains("srcdoc"))
        assertFalse(out.contains("data:text/html"))
        assertTrue(out.contains("data:image/png;base64,AAAA"))
    }

    @Test
    fun `sanitizeForRender script variants are removed`() {
        val out = webView("<script >a()</script ><scr<script></script>ipt>b()</script>", keepJs = false)
        assertTrue(org.jsoup.Jsoup.parseBodyFragment(out).select("script").isEmpty())
    }

    @Test
    fun `sanitizeForRender keeps javascript text in non url attributes and non handler on attributes`() {
        val out = webView("<img src=\"/a.jpg\" alt=\"JavaScript: The Good Parts cover\" data-onboarding-step=\"1\">", keepJs = false)
        assertTrue(out.contains("alt=\"JavaScript: The Good Parts cover\""))
        assertTrue(out.contains("data-onboarding-step=\"1\""))
    }

    @Test
    fun `sanitizeForRender returns clean chapters verbatim`() {
        val html = "<p class=\"a\">Hello <b>world</b></p>"
        assertEquals(html, webView(html, keepJs = false))
    }

    @Test
    fun `sanitizeForRender removes svg animations that can set javascript links when js is off`() {
        val out = webView(
            "<svg><a><animate attributeName=\"href\" values=\"javascript:alert(1)\"/><text>x</text></a>" +
                "<set attributeName=\"xlink:href\" to=\"javascript:a()\"/>" +
                "<animateMotion dur=\"1s\"/><animateTransform attributeName=\"transform\" from=\"0\"/></svg>",
            keepJs = false,
        )
        val doc = org.jsoup.Jsoup.parseBodyFragment(out)
        assertTrue(doc.select("animate, set, animatemotion, animatetransform").isEmpty())
        assertFalse(out.contains("javascript:", ignoreCase = true))
        assertTrue(out.contains("<text>x</text>"))
    }

    @Test
    fun `sanitizeForRender keeps svg animations when js is on`() {
        val out = webView("<svg><animate attributeName=\"opacity\" values=\"0;1\"/></svg>", keepJs = true)
        assertTrue(out.contains("<animate"))
    }

    @Test
    fun `sanitizeForRender removes embedded documents when js is off`() {
        val out = webView(
            "<iframe src=\"https://example.com/x.html\"></iframe><object data=\"https://example.com/x.swf\"></object>" +
                "<embed src=\"https://example.com/x.html\"><frameset><frame src=\"https://example.com/f.html\"></frameset>" +
                "<p>text</p>",
            keepJs = false,
        )
        val doc = org.jsoup.Jsoup.parseBodyFragment(out)
        assertTrue(doc.select("iframe, frame, frameset, object, embed").isEmpty())
        assertFalse(out.contains("example.com"))
        assertTrue(out.contains("<p>text</p>"))
    }

    @Test
    fun `sanitizeForRender keeps iframes when js is on`() {
        val out = webView("<iframe src=\"https://example.com/v\"></iframe>", keepJs = true)
        assertTrue(out.contains("<iframe"))
    }

    @Test
    fun `sanitizeForRender always removes base`() {
        val html = "<base href=\"https://example.com/\"><img src=\"/a.jpg\">"
        assertFalse(webView(html, keepJs = false).contains("<base"))
        assertFalse(webView(html, keepJs = true).contains("<base"))
    }

    @Test
    fun `sanitizeForRender output stays clean when reparsed after foreign content mutation`() {
        val payloads = listOf(
            "<img src=\"/a.jpg\" loading=\"lazy\"><math><mtext><table><mglyph><style><img src=x onerror=alert(1)>",
            "<!-- x --><form><math><mtext></form><form><mglyph><style></math><img src onerror=alert(1)>",
        )
        for (payload in payloads) {
            val out = webView(payload, keepJs = false)
            val reparsed = org.jsoup.Jsoup.parseBodyFragment(out).apply { outputSettings().prettyPrint(false) }
            assertTrue(reparsed.select("[onerror]").isEmpty(), out)
            assertEquals(out, reparsed.body().html())
        }
    }

    @Test
    fun `sanitizeForRender drops an unterminated tag when js is off`() {
        val out = webView("<img src=x onerror=Android.x() ", keepJs = false)
        assertFalse(out.contains("onerror"))
    }
}
