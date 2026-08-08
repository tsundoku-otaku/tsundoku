package mihon.core.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtmlAssetRewriterTest {

    private fun toLocal(url: String) = "local://${url.substringBefore('?')}"

    @Test
    fun `rewrites plain src attribute`() {
        val out = HtmlAssetRewriter.rewriteHtml("""<img src="a.jpg">""", ::toLocal)
        assertEquals("""<img src="local://a.jpg">""", out)
    }

    @Test
    fun `leaves url untouched when mapping has no entry`() {
        val out = HtmlAssetRewriter.rewriteHtml("""<img src="a.jpg">""") { null }
        assertEquals("""<img src="a.jpg">""", out)
    }

    @Test
    fun `rewrites every srcset candidate independently`() {
        val out = HtmlAssetRewriter.rewriteHtml(
            """<img srcset="a.jpg 1x, b.jpg 2x, c.jpg 3x">""",
            ::toLocal,
        )
        assertEquals("""<img srcset="local://a.jpg 1x, local://b.jpg 2x, local://c.jpg 3x">""", out)
    }

    @Test
    fun `srcset candidates sharing a url prefix that only differ by query are not corrupted`() {
        val map = mapOf(
            "image_20.jpg" to "tsundoku-novel-image://image_20.jpg",
            "image_20.jpg?w=106&h=150" to "tsundoku-novel-image://image_20_thumb.jpg",
        )
        val out = HtmlAssetRewriter.rewriteHtml(
            """<img srcset="image_20.jpg 1443w, image_20.jpg?w=106&h=150 106w">""",
        ) { map[it] }
        assertEquals(
            """<img srcset="tsundoku-novel-image://image_20.jpg 1443w, """ +
                """tsundoku-novel-image://image_20_thumb.jpg 106w">""",
            out,
        )
    }

    @Test
    fun `srcset candidate with no mapping falls back to its original url unchanged`() {
        val out = HtmlAssetRewriter.rewriteHtml(
            """<img srcset="known.jpg 1x, unknown.jpg 2x">""",
        ) { url -> "local://$url".takeIf { url == "known.jpg" } }
        assertEquals("""<img srcset="local://known.jpg 1x, unknown.jpg 2x">""", out)
    }

    @Test
    fun `blank srcset candidates from stray commas are preserved as-is`() {
        val out = HtmlAssetRewriter.rewriteHtml("""<img srcset="a.jpg 1x, , b.jpg 2x">""", ::toLocal)
        assertEquals("""<img srcset="local://a.jpg 1x,  , local://b.jpg 2x">""", out)
    }

    @Test
    fun `rewrites background-image url inside a style attribute`() {
        val out = HtmlAssetRewriter.rewriteHtml(
            """<div style="background-image:url('bg.png')"></div>""",
            ::toLocal,
        )
        assertEquals("""<div style="background-image:url('local://bg.png')"></div>""", out)
    }

    @Test
    fun `rewrites url references inside a style block`() {
        val out = HtmlAssetRewriter.rewriteHtml(
            """<style>.a { background: url(bg.png); }</style>""",
            ::toLocal,
        )
        assertEquals("""<style>.a { background: url(local://bg.png); }</style>""", out)
    }

    @Test
    fun `does not touch url-like text inside a script tag`() {
        val html = """<script>var x = "url(a.jpg)";</script>"""
        assertEquals(html, HtmlAssetRewriter.rewriteHtml(html, ::toLocal))
    }

    @Test
    fun `extractImageUrls only collects img src and srcset, not link href or script src`() {
        val urls = HtmlAssetRewriter.extractImageUrls(
            """<link href="style.css"><script src="app.js"></script>""" +
                """<img src="a.jpg" srcset="b.jpg 1x, c.jpg 2x">""" +
                """<video src="clip.mp4"><source src="clip.webm"></video>""",
        )
        assertEquals(setOf("a.jpg", "b.jpg", "c.jpg"), urls)
    }

    @Test
    fun `extractImageUrls collects every srcset candidate`() {
        val urls = HtmlAssetRewriter.extractImageUrls(
            """<img src="a.jpg" srcset="b.jpg 1x, c.jpg?w=100 2x, d.jpg 3x">""",
        )
        assertEquals(setOf("a.jpg", "b.jpg", "c.jpg?w=100", "d.jpg"), urls)
    }

    @Test
    fun `extractImageUrls collects background-image but not other css url declarations`() {
        val urls = HtmlAssetRewriter.extractImageUrls(
            """<div style="background-image:url('bg.png')"></div>""" +
                """<style>@font-face { src: url(font.woff2); } .a { cursor: url(cur.png), auto; }</style>""",
        )
        assertEquals(setOf("bg.png"), urls)
    }

    @Test
    fun `rewriteImageUrls rewrites img src and srcset but leaves link and script untouched`() {
        val out = HtmlAssetRewriter.rewriteImageUrls(
            """<link href="style.css"><script src="app.js"></script><img src="a.jpg" srcset="a.jpg 1x, b.jpg 2x">""",
        ) { url -> "local/$url".takeIf { url == "a.jpg" || url == "b.jpg" } }
        assertEquals(
            """<link href="style.css"><script src="app.js"></script>""" +
                """<img src="local/a.jpg" srcset="local/a.jpg 1x, local/b.jpg 2x">""",
            out,
        )
    }

    @Test
    fun `rewriteImageUrls rewrites background-image and preserves everything else in the style block`() {
        val out = HtmlAssetRewriter.rewriteImageUrls(
            """<style>.a { background-image: url(bg.png); } @font-face { src: url(font.woff2); }</style>""",
        ) { url -> "local/$url".takeIf { url == "bg.png" } }
        assertEquals(
            """<style>.a { background-image:url(local/bg.png); } @font-face { src: url(font.woff2); }</style>""",
            out,
        )
    }

    @Test
    fun `extractImageUrls prefers a lazy-load data-src over its placeholder src`() {
        val urls = HtmlAssetRewriter.extractImageUrls(
            """<img data-src="real.jpg" src="placeholder.gif">""",
        )
        assertEquals(setOf("real.jpg"), urls)
    }

    @Test
    fun `rewriteImageUrls points src at the resolved data-src, not the placeholder`() {
        val out = HtmlAssetRewriter.rewriteImageUrls(
            """<img data-src="real.jpg" src="placeholder.gif">""",
        ) { url -> "local/$url".takeIf { url == "real.jpg" } }
        assertEquals("""<img data-src="real.jpg" src="local/real.jpg">""", out)
    }

    @Test
    fun `extractImageUrls collects picture source srcset candidates`() {
        val urls = HtmlAssetRewriter.extractImageUrls(
            """<picture><source srcset="a.webp"><source srcset="b.avif"><img src="fallback.jpg"></picture>""",
        )
        assertEquals(setOf("a.webp", "b.avif", "fallback.jpg"), urls)
    }

    @Test
    fun `rewriteImageUrls rewrites picture source srcset alongside the fallback img`() {
        val out = HtmlAssetRewriter.rewriteImageUrls(
            """<picture><source srcset="a.webp"><img src="fallback.jpg"></picture>""",
        ) { url -> "local/$url" }
        assertEquals("""<picture><source srcset="local/a.webp"><img src="local/fallback.jpg"></picture>""", out)
    }

    @Test
    fun `extractImageUrls does not treat a standalone video or audio source as an image`() {
        val urls = HtmlAssetRewriter.extractImageUrls(
            """<video src="clip.mp4"><source src="clip.webm"></video>""",
        )
        assertTrue(urls.isEmpty())
    }
}
