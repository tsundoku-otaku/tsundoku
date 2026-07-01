package mihon.core.archive

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubReaderTocParseTest {

    // resolvePath mirrors resolveZipPath with an empty base dir: identity for these flat fixtures.
    private val identityResolve: (String) -> String = { it }

    private fun ncx(xml: String) =
        Jsoup.parse(xml, "", Parser.xmlParser()).selectFirst("navMap")!!

    private fun navList(xml: String) =
        Jsoup.parse(xml, "", Parser.xmlParser())
            .selectFirst("nav")!!
            .selectFirst("ol")!!

    // ── EPUB 2 NCX ──────────────────────────────────────────────────

    @Test
    fun `flat ncx keeps document order and depth zero and preserves fragment hrefs`() {
        // Mirrors the Classroom of the Elite NCX: flat navMap, each entry a distinct file with a fragment.
        val navMap = ncx(
            """
            <ncx><navMap>
              <navPoint><navLabel><text>Table of Contents Page</text></navLabel>
                <content src="Text/section-0004.html#auto_bookmark_toc_top"/></navPoint>
              <navPoint><navLabel><text>Chapter 1: Operating Behind the Scenes</text></navLabel>
                <content src="Text/section-0005.html#auto_bookmark_toc_top"/></navPoint>
            </navMap></ncx>
            """.trimIndent(),
        )

        val toc = EpubReader.buildTocFromNcxNavMap(navMap, "toc.ncx", identityResolve)

        assertEquals(2, toc.size)
        assertEquals(listOf(0, 0), toc.map { it.depth })
        assertEquals(listOf(0, 1), toc.map { it.order })
        assertEquals("Text/section-0005.html#auto_bookmark_toc_top", toc[1].href)

        // Full pipeline: descriptive title must survive normalization unchanged.
        val names = EpubReader.normalizeTableOfContents(toc).map { it.title }
        assertEquals(
            listOf("Table of Contents Page", "Chapter 1: Operating Behind the Scenes"),
            names,
        )
    }

    @Test
    fun `nested navPoints carry depth and normalize into parent-child labels`() {
        val navMap = ncx(
            """
            <ncx><navMap>
              <navPoint><navLabel><text>Part One</text></navLabel><content src="p1.xhtml"/>
                <navPoint><navLabel><text>Chapter 1</text></navLabel><content src="c1.xhtml"/></navPoint>
                <navPoint><navLabel><text>Chapter 2</text></navLabel><content src="c2.xhtml"/></navPoint>
              </navPoint>
              <navPoint><navLabel><text>Part Two</text></navLabel><content src="p2.xhtml"/>
                <navPoint><navLabel><text>Chapter 3</text></navLabel><content src="c3.xhtml"/></navPoint>
              </navPoint>
            </navMap></ncx>
            """.trimIndent(),
        )

        val toc = EpubReader.buildTocFromNcxNavMap(navMap, "toc.ncx", identityResolve)

        assertEquals(listOf("Part One", "Chapter 1", "Chapter 2", "Part Two", "Chapter 3"), toc.map { it.title })
        assertEquals(listOf(0, 1, 1, 0, 1), toc.map { it.depth })
        assertEquals(listOf(0, 1, 2, 3, 4), toc.map { it.order })
        assertEquals(listOf("p1.xhtml", "c1.xhtml", "c2.xhtml", "p2.xhtml", "c3.xhtml"), toc.map { it.href })

        val names = EpubReader.normalizeTableOfContents(toc).map { it.title }
        assertEquals(
            listOf("Part One", "Part One - Chapter 1", "Part One - Chapter 2", "Part Two", "Part Two - Chapter 3"),
            names,
        )
    }

    @Test
    fun `fragment-only ncx entry reuses the previous file path`() {
        val navMap = ncx(
            """
            <ncx><navMap>
              <navPoint><navLabel><text>A</text></navLabel><content src="chapter.xhtml#top"/></navPoint>
              <navPoint><navLabel><text>B</text></navLabel><content src="#middle"/></navPoint>
            </navMap></ncx>
            """.trimIndent(),
        )

        val toc = EpubReader.buildTocFromNcxNavMap(navMap, "toc.ncx", identityResolve)

        assertEquals("chapter.xhtml#top", toc[0].href)
        assertEquals("chapter.xhtml#middle", toc[1].href)
    }

    // ── EPUB 3 nav ──────────────────────────────────────────────────

    @Test
    fun `nested nav ol carries depth and normalizes across locales`() {
        val root = navList(
            """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <nav epub:type="toc"><ol>
                <li><a href="p1.xhtml">Erster Teil</a>
                  <ol>
                    <li><a href="c1.xhtml">Kapitel 1</a></li>
                    <li><a href="c2.xhtml">Kapitel 2</a></li>
                  </ol>
                </li>
                <li><a href="back.xhtml">Nachwort</a></li>
              </ol></nav>
            </body></html>
            """.trimIndent(),
        )

        val toc = EpubReader.buildTocFromNavList(root, "nav.xhtml", identityResolve)

        assertEquals(listOf("Erster Teil", "Kapitel 1", "Kapitel 2", "Nachwort"), toc.map { it.title })
        assertEquals(listOf(0, 1, 1, 0), toc.map { it.depth })
        assertEquals(listOf(0, 1, 2, 3), toc.map { it.order })

        val names = EpubReader.normalizeTableOfContents(toc).map { it.title }
        assertEquals(
            listOf("Erster Teil", "Erster Teil - Kapitel 1", "Erster Teil - Kapitel 2", "Nachwort"),
            names,
        )
    }

    @Test
    fun `nav parent without a link still nests its children under nothing`() {
        // A <li> that is a bare section header (no <a>) must not crash and children keep their own depth.
        val root = navList(
            """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <nav epub:type="toc"><ol>
                <li><span>Section</span>
                  <ol><li><a href="c1.xhtml">Chapter 1</a></li></ol>
                </li>
              </ol></nav>
            </body></html>
            """.trimIndent(),
        )

        val toc = EpubReader.buildTocFromNavList(root, "nav.xhtml", identityResolve)

        assertEquals(listOf("Chapter 1"), toc.map { it.title })
        assertEquals(listOf(1), toc.map { it.depth })
        assertEquals("c1.xhtml", toc[0].href)
    }
}
