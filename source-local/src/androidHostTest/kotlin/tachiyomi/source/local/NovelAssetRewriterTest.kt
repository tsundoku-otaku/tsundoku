package tachiyomi.source.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.net.URLEncoder

class NovelAssetRewriterTest {

    private fun scheme(path: String) = NovelAssetRewriter.SCHEME + URLEncoder.encode(path, "UTF-8")

    @Test
    fun `rewrites relative img src`() {
        val out = NovelAssetRewriter.rewrite(
            """<p>hi</p><img src="images/pic.jpg">""",
            "html",
            NovelAssetRewriter::relativeScheme,
        )
        assertEquals("""<p>hi</p><img src="${scheme("images/pic.jpg")}">""", out)
    }

    @Test
    fun `leaves absolute and data and scheme urls untouched`() {
        val html = """
            <img src="https://x/y.png">
            <img src="data:image/png;base64,AAAA">
            <img src="tsundoku-novel-image://already">
            <img src="//cdn/x.png">
        """.trimIndent()
        assertEquals(html, NovelAssetRewriter.rewrite(html, "html", NovelAssetRewriter::relativeScheme))
    }

    @Test
    fun `rewrites root-absolute refs relative to the chapter base`() {
        // Saved sites emit root-absolute paths (e.g. <img src="/forks/logo.webp">); for a local
        // novel the site root is the chapter's base dir, so the leading slash is dropped.
        val out = NovelAssetRewriter.rewrite(
            """<img src="/forks/logo-mihon.webp">""",
            "html",
            NovelAssetRewriter::relativeScheme,
        )
        assertEquals("""<img src="${scheme("forks/logo-mihon.webp")}">""", out)
    }

    @Test
    fun `archive rewrite resolves root-absolute from archive root`() {
        val out = NovelAssetRewriter.rewrite("""<img src="/img/x.png">""", "html") {
            NovelAssetRewriter.archiveScheme("OEBPS/text", it)
        }
        assertEquals("""<img src="${scheme("img/x.png")}">""", out)
    }

    @Test
    fun `isResolvableRef classification`() {
        assertTrue(NovelAssetRewriter.isResolvableRef("images/x.png"))
        assertTrue(NovelAssetRewriter.isResolvableRef("../x.png"))
        assertTrue(NovelAssetRewriter.isResolvableRef("/root/x"))
        assertFalse(NovelAssetRewriter.isResolvableRef("http://x/y"))
        assertFalse(NovelAssetRewriter.isResolvableRef("https://x/y"))
        assertFalse(NovelAssetRewriter.isResolvableRef("//cdn/x"))
        assertFalse(NovelAssetRewriter.isResolvableRef("data:x"))
        assertFalse(NovelAssetRewriter.isResolvableRef("#frag"))
        assertFalse(NovelAssetRewriter.isResolvableRef(""))
    }

    @Test
    fun `rewrites non-img resource tags but not anchors`() {
        val html = """<a href="chapter2.html">next</a><source src="v.mp4"><link href="style.css">"""
        val out = NovelAssetRewriter.rewrite(html, "html", NovelAssetRewriter::relativeScheme)
        assertTrue(out.contains("""<a href="chapter2.html">"""), "anchor href must be left alone")
        assertTrue(out.contains("""<source src="${scheme("v.mp4")}">"""))
        assertTrue(out.contains("""<link href="${scheme("style.css")}">"""))
    }

    @Test
    fun `does not touch data-src style attributes`() {
        val html = """<img data-src="lazy.jpg" src="real.jpg">"""
        val out = NovelAssetRewriter.rewrite(html, "html", NovelAssetRewriter::relativeScheme)
        assertTrue(out.contains("""data-src="lazy.jpg""""), "data-src must not be rewritten")
        assertTrue(out.contains("""src="${scheme("real.jpg")}""""))
    }

    @Test
    fun `rewrites srcset candidates preserving descriptors`() {
        val html = """<img srcset="a.jpg 1x, b.jpg 2x">"""
        val out = NovelAssetRewriter.rewrite(html, "html", NovelAssetRewriter::relativeScheme)
        assertEquals("""<img srcset="${scheme("a.jpg")} 1x, ${scheme("b.jpg")} 2x">""", out)
    }

    @Test
    fun `rewrites css url references`() {
        val html = """<div style="background-image:url('bg/tile.png')"></div>"""
        val out = NovelAssetRewriter.rewrite(html, "html", NovelAssetRewriter::relativeScheme)
        assertEquals("""<div style="background-image:url('${scheme("bg/tile.png")}')"></div>""", out)
    }

    @Test
    fun `rewrites markdown image syntax`() {
        val out = NovelAssetRewriter.rewrite("""![cover](art/cover.png)""", "md", NovelAssetRewriter::relativeScheme)
        assertEquals("""![cover](${scheme("art/cover.png")})""", out)
    }

    @Test
    fun `plain text extension is untouched`() {
        val text = """not html src="x.jpg" url(y.png)"""
        assertEquals(text, NovelAssetRewriter.rewrite(text, "txt", NovelAssetRewriter::relativeScheme))
    }

    @Test
    fun `archive scheme resolves relative paths against entry directory`() {
        assertEquals("OEBPS/img/x.png", NovelAssetRewriter.resolveArchivePath("OEBPS/text", "../img/x.png"))
        assertEquals("OEBPS/text/x.png", NovelAssetRewriter.resolveArchivePath("OEBPS/text", "x.png"))
        assertEquals("img/x.png", NovelAssetRewriter.resolveArchivePath("", "img/x.png"))
        assertEquals("x.png", NovelAssetRewriter.resolveArchivePath("a/b", "../../x.png"))
    }

    @Test
    fun `archive rewrite emits absolute in-archive path`() {
        val out = NovelAssetRewriter.rewrite("""<img src="../img/x.png">""", "html") {
            NovelAssetRewriter.archiveScheme("OEBPS/text", it)
        }
        assertEquals("""<img src="${scheme("OEBPS/img/x.png")}">""", out)
    }

    @Test
    fun `pre-encoded refs are decoded before re-encoding`() {
        // Browser "Save as complete" writes percent-encoded paths with spaces and parens.
        val html = """<img src="Saved%20Page%20(Complete)_files/x.png">"""
        val out = NovelAssetRewriter.rewrite(html, "html", NovelAssetRewriter::relativeScheme)
        // Scheme path must round-trip (via URLDecoder in the interceptor) back to the real on-disk name.
        val encoded = out.substringAfter(NovelAssetRewriter.SCHEME).substringBefore('"')
        assertEquals("Saved Page (Complete)_files/x.png", URLDecoder.decode(encoded, "UTF-8"))
    }

    @Test
    fun `isRelativeRef classification`() {
        assertTrue(NovelAssetRewriter.isRelativeRef("images/x.png"))
        assertTrue(NovelAssetRewriter.isRelativeRef("../x.png"))
        assertFalse(NovelAssetRewriter.isRelativeRef("http://x/y"))
        assertFalse(NovelAssetRewriter.isRelativeRef("https://x/y"))
        assertFalse(NovelAssetRewriter.isRelativeRef("//cdn/x"))
        assertFalse(NovelAssetRewriter.isRelativeRef("/root/x"))
        assertFalse(NovelAssetRewriter.isRelativeRef("data:x"))
        assertFalse(NovelAssetRewriter.isRelativeRef("#frag"))
        assertFalse(NovelAssetRewriter.isRelativeRef(""))
    }
}
