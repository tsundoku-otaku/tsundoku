package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelTextRendererTest {

    @Test
    fun `drops source candidates and unwraps the picture element around the fallback img`() {
        val html = """
            <section><picture>
                <source srcset="https://cdn.example.com/cover.jxl" type="image/jxl">
                <source srcset="https://cdn.example.com/cover.avif" type="image/avif">
                <img src="https://cdn.example.com/cover.jpg" alt="Cover">
            </picture></section>
        """.trimIndent()
        val doc = org.jsoup.Jsoup.parse(html)
        NovelTextRenderer.unwrapPictureSources(doc)

        assertTrue(doc.select("picture").isEmpty(), "picture wrapper should be removed")
        assertTrue(doc.select("source").isEmpty(), "source candidates should be removed")
        val img = doc.selectFirst("img")
        assertEquals("https://cdn.example.com/cover.jpg", img?.attr("src"))
        assertEquals("section", img?.parent()?.tagName(), "img should be reparented to picture's own parent")
    }

    @Test
    fun `leaves html without a picture element untouched`() {
        val html = """<div><img src="https://cdn.example.com/plain.jpg"></div>"""
        val doc = org.jsoup.Jsoup.parse(html)
        NovelTextRenderer.unwrapPictureSources(doc)

        assertEquals("https://cdn.example.com/plain.jpg", doc.selectFirst("img")?.attr("src"))
    }

    @Test
    fun `handles multiple picture elements in the same document`() {
        val html = """
            <picture><source srcset="a.avif"><img src="a.jpg"></picture>
            <picture><source srcset="b.avif"><img src="b.jpg"></picture>
        """.trimIndent()
        val doc = org.jsoup.Jsoup.parse(html)
        NovelTextRenderer.unwrapPictureSources(doc)

        assertTrue(doc.select("picture, source").isEmpty())
        assertEquals(listOf("a.jpg", "b.jpg"), doc.select("img").map { it.attr("src") })
    }
}
