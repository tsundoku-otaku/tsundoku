package eu.kanade.tachiyomi.data.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TranslationHtmlUtilsTest {

    @Test
    fun `chunk groups keep a paragraph with a line break whole`() {
        val paragraphs = listOf("one\nline two", "three")

        val groups = TranslationHtmlUtils.chunkGroups(paragraphs, 1)

        assertEquals(listOf(1, 1), groups.map { it.size })
        assertEquals(TranslationHtmlUtils.buildChunks(paragraphs, 1), groups.map { it.joinToString("\n\n") })
    }

    @Test
    fun `nested media is extracted once with its parent`() {
        val (html, images) = TranslationHtmlUtils.extractImages(
            "<p>a</p><figure><picture><source srcset=\"a.webp\"><img src=\"a.jpg\"></picture></figure><img src=\"b.jpg\">",
        )

        assertEquals(2, images.size)
        assertEquals(1, images.values.count { "a.jpg" in it })
        assertEquals(html, html.replace("a.jpg", ""))
        assertEquals(
            "<p>a</p><figure><picture><source srcset=\"a.webp\"><img src=\"a.jpg\"></picture></figure><img src=\"b.jpg\">",
            TranslationHtmlUtils.reinsertImages(html, images).replace("\n", ""),
        )
    }

    @Test
    fun `an image whose placeholder the engine dropped goes at the end`() {
        val images = mapOf("[IMG_PLACEHOLDER_0]" to "<img src=\"a.jpg\">")
        assertEquals("<p>text</p><img src=\"a.jpg\">", TranslationHtmlUtils.reinsertImages("<p>text</p>", images))
    }

    @Test
    fun `partial keeps the hash of the source it covers`() {
        val hash = TranslationHtmlUtils.paragraphsHash(listOf("a", "b"))
        val parsed = TranslationHtmlUtils.parsePartialHtml(
            TranslationHtmlUtils.buildPartialHtml(1L, 2, null, listOf("x", "y"), hash),
        )
        assertEquals(hash, parsed.sourceHash)
        assertEquals(false, hash == TranslationHtmlUtils.paragraphsHash(listOf("a", "c")))
    }

    @Test
    fun `a partial cut short while written has no source boundary`() {
        val html = TranslationHtmlUtils.buildPartialHtml(1L, 2, null, listOf("x", "y"))
        val truncated = html.substring(0, html.indexOf("y"))

        assertEquals(2, TranslationHtmlUtils.parsePartialHtml(html).sourceParagraphs)
        assertEquals(null, TranslationHtmlUtils.parsePartialHtml(truncated).sourceParagraphs)
    }
}
