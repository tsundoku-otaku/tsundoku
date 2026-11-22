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
}
