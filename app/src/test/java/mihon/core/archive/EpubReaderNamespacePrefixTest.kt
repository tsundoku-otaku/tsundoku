package mihon.core.archive

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubReaderNamespacePrefixTest {

    private fun opf(xml: String) = Jsoup.parse(xml, "", Parser.xmlParser())

    private val prefixedOpf = """
        <ns0:package xmlns:ns0="http://www.idpf.org/2007/opf" version="2.0">
        <ns0:metadata>
            <ns0:meta name="cover" content="cover-image" />
        </ns0:metadata>
        <ns0:manifest>
            <ns0:item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml" />
            <ns0:item id="chapter0001" href="Text/chapter0001.xhtml" media-type="application/xhtml+xml" />
            <ns0:item id="chapter0002" href="Text/chapter0002.xhtml" media-type="application/xhtml+xml" />
            <ns0:item id="cover-image" href="Images/cover.webp" media-type="image/webp" />
        </ns0:manifest>
        <ns0:spine toc="ncx">
            <ns0:itemref idref="chapter0001" />
            <ns0:itemref idref="chapter0002" />
        </ns0:spine>
        </ns0:package>
    """.trimIndent()

    @Test
    fun `unprefixed opf selectors match nothing before stripping`() {
        val doc = opf(prefixedOpf)

        assertTrue(doc.select("manifest > item").isEmpty())
        assertTrue(doc.select("spine > itemref").isEmpty())
    }

    @Test
    fun `stripNamespacePrefixes renames tags in place and returns the same document`() {
        val doc = opf(prefixedOpf)

        val stripped = EpubReader.stripNamespacePrefixes(doc)

        assertTrue(stripped === doc)
        assertEquals(4, doc.select("manifest > item").size)
        assertEquals(2, doc.select("spine > itemref").size)
    }

    @Test
    fun `extractSpineHrefs resolves ordered xhtml pages after stripping a prefixed opf`() {
        val doc = EpubReader.stripNamespacePrefixes(opf(prefixedOpf))

        val hrefs = EpubReader.extractSpineHrefs(doc)

        assertEquals(listOf("Text/chapter0001.xhtml", "Text/chapter0002.xhtml"), hrefs)
    }

    @Test
    fun `extractSpineHrefs returns nothing for an unstripped prefixed opf`() {
        val doc = opf(prefixedOpf)

        assertTrue(EpubReader.extractSpineHrefs(doc).isEmpty())
    }

    @Test
    fun `findNcxHref resolves the ncx manifest item after stripping`() {
        val doc = EpubReader.stripNamespacePrefixes(opf(prefixedOpf))

        assertEquals("toc.ncx", EpubReader.findNcxHref(doc))
    }

    @Test
    fun `findNavHref is empty when the manifest has no epub3 nav item`() {
        val doc = EpubReader.stripNamespacePrefixes(opf(prefixedOpf))

        assertEquals("", EpubReader.findNavHref(doc))
    }

    @Test
    fun `findNavHref resolves a prefixed epub3 nav manifest item after stripping`() {
        val navOpf = """
            <ns0:package xmlns:ns0="http://www.idpf.org/2007/opf" version="3.0">
            <ns0:manifest>
                <ns0:item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav" />
                <ns0:item id="chapter0001" href="Text/chapter0001.xhtml" media-type="application/xhtml+xml" />
            </ns0:manifest>
            <ns0:spine>
                <ns0:itemref idref="chapter0001" />
            </ns0:spine>
            </ns0:package>
        """.trimIndent()
        val doc = EpubReader.stripNamespacePrefixes(opf(navOpf))

        assertEquals("nav.xhtml", EpubReader.findNavHref(doc))
    }

    @Test
    fun `stripNamespacePrefixes preserves dc prefix so metadata stays queryable`() {
        val dcOpf = """
            <package xmlns="http://www.idpf.org/2007/opf"
                     xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0">
            <metadata>
                <dc:title>My Novel</dc:title>
                <dc:creator>Some Author</dc:creator>
            </metadata>
            <manifest>
                <item id="chapter0001" href="Text/chapter0001.xhtml" media-type="application/xhtml+xml" />
            </manifest>
            <spine>
                <itemref idref="chapter0001" />
            </spine>
            </package>
        """.trimIndent()
        val doc = EpubReader.stripNamespacePrefixes(opf(dcOpf))

        assertEquals("My Novel", doc.getElementsByTag("dc:title").firstOrNull()?.text())
        assertEquals("Some Author", doc.getElementsByTag("dc:creator").firstOrNull()?.text())
        assertEquals(1, doc.select("manifest > item").size)
    }

    @Test
    fun `stripNamespacePrefixes leaves plain metadata reachable when dc is auto-prefixed`() {
        val prefixedDcOpf = """
            <ns0:package xmlns:ns0="http://www.idpf.org/2007/opf" version="2.0">
            <ns0:metadata>
                <ns1:title xmlns:ns1="http://purl.org/dc/elements/1.1/">Prefixed Novel</ns1:title>
            </ns0:metadata>
            <ns0:manifest>
                <ns0:item id="chapter0001" href="Text/chapter0001.xhtml" media-type="application/xhtml+xml" />
            </ns0:manifest>
            <ns0:spine>
                <ns0:itemref idref="chapter0001" />
            </ns0:spine>
            </ns0:package>
        """.trimIndent()
        val doc = EpubReader.stripNamespacePrefixes(opf(prefixedDcOpf))

        assertEquals("Prefixed Novel", doc.select("metadata > title").firstOrNull()?.text())
        assertEquals(1, doc.select("manifest > item").size)
    }

    @Test
    fun `unprefixed opf is unaffected by stripping`() {
        val plainXml = """
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
            <manifest>
                <item id="chapter0001" href="Text/chapter0001.xhtml" media-type="application/xhtml+xml" />
            </manifest>
            <spine>
                <itemref idref="chapter0001" />
            </spine>
            </package>
        """.trimIndent()
        val doc = EpubReader.stripNamespacePrefixes(opf(plainXml))

        assertEquals(listOf("Text/chapter0001.xhtml"), EpubReader.extractSpineHrefs(doc))
    }
}
