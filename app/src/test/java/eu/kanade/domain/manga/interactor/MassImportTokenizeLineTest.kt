package eu.kanade.domain.manga.interactor

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MassImportTokenizeLineTest {

    @Test
    fun `keeps commas inside a url`() {
        val url = "https://example.com/wiki?title=Hello,_Hello_and_Hello"
        assertEquals(listOf(url), MassImport.tokenizeLine(url))
    }

    @Test
    fun `keeps trailing parenthesis`() {
        val url = "https://example.com/wiki?title=Some_Title_(Indonesia)"
        assertEquals(listOf(url), MassImport.tokenizeLine(url))
    }

    @Test
    fun `strips unbalanced trailing parenthesis and period`() {
        assertEquals(
            listOf("https://example.com/x", "https://example.com/wiki/A_(B)"),
            MassImport.urlTokens("(see https://example.com/x). Also (https://example.com/wiki/A_(B))."),
        )
    }

    @Test
    fun `strips angle brackets`() {
        assertEquals(listOf("https://example.com/x"), MassImport.urlTokens("<https://example.com/x>"))
    }

    @Test
    fun `splits comma and semicolon separated urls`() {
        assertEquals(
            listOf("https://a.example.com/x", "https://b.example.com/y", "https://c.example.com/z"),
            MassImport.tokenizeLine("https://a.example.com/x,https://b.example.com/y; https://c.example.com/z"),
        )
    }

    @Test
    fun `de-glues urls without separators`() {
        assertEquals(
            listOf("https://a.example.com/x", "https://b.example.com/y"),
            MassImport.tokenizeLine("https://a.example.com/xhttps://b.example.com/y"),
        )
    }

    @Test
    fun `strips bom from first line`() {
        assertEquals(
            listOf("https://a.example.com/x"),
            MassImport.tokenizeLine("\uFEFFhttps://a.example.com/x"),
        )
    }

    @Test
    fun `quoted csv row yields only the url`() {
        assertEquals(
            listOf("https://a.example.com/x"),
            MassImport.urlTokens("\"https://a.example.com/x\",\"Some, Title\",3"),
        )
    }

    @Test
    fun `csv row with title before url`() {
        assertEquals(
            listOf("https://a.example.com/x"),
            MassImport.urlTokens("Some Title,https://a.example.com/x"),
        )
    }

    @Test
    fun `csv header and non url columns are dropped`() {
        assertEquals(emptyList<String>(), MassImport.urlTokens("url,title,author"))
    }

    @Test
    fun `tokenizing already tokenized output is stable`() {
        val first = MassImport.urlTokens("\"https://a.example.com/x\",\"Title\"; https://b.example.com/y,")
        assertEquals(first, first.flatMap { MassImport.urlTokens(it) })
    }

    @Test
    fun `tab separated row yields only the url`() {
        assertEquals(
            listOf("https://a.example.com/x"),
            MassImport.urlTokens("Title	https://a.example.com/x	3"),
        )
    }

    @Test
    fun `uppercase scheme is lowercased`() {
        assertEquals(
            listOf("https://a.example.com/x", "http://b.example.com/y"),
            MassImport.urlTokens("HTTPS://a.example.com/x Http://b.example.com/y"),
        )
    }

    @Test
    fun `quoted url after another column is kept`() {
        assertEquals(
            listOf("https://a.example.com/x"),
            MassImport.urlTokens("\"Some Title\",\"https://a.example.com/x\""),
        )
    }

    @Test
    fun `unquoted csv row with url first drops later columns`() {
        assertEquals(
            listOf("https://a.example.com/x"),
            MassImport.urlTokens("https://a.example.com/x,Some Title,3"),
        )
    }

    @Test
    fun `json list keeps every url`() {
        assertEquals(
            listOf("https://a.example.com/x", "https://b.example.com/y"),
            MassImport.urlTokens("[\"https://a.example.com/x\", \"https://b.example.com/y\"]"),
        )
    }

    @Test
    fun `url glued to a quote or bracket is kept`() {
        assertEquals(
            listOf("https://a.example.com/x", "https://b.example.com/y"),
            MassImport.urlTokens("url:\"https://a.example.com/x\" see:<https://b.example.com/y>"),
        )
    }

    @Test
    fun `malformed scheme stays a non url token`() {
        assertEquals(listOf("htps://a.example.com/x"), MassImport.tokenizeLine("htps://a.example.com/x"))
    }

    @Test
    fun `single quoted url is unquoted`() {
        assertEquals(
            listOf("https://a.example.com/x"),
            MassImport.urlTokens("'https://a.example.com/x'"),
        )
    }

    @Test
    fun `apostrophe inside or ending an unquoted url is kept`() {
        assertEquals(
            listOf("https://a.example.com/wiki/Rock_'n'"),
            MassImport.urlTokens("https://a.example.com/wiki/Rock_'n'"),
        )
    }

    @Test
    fun `python style list drops trailing quote and bracket`() {
        assertEquals(
            listOf("https://a.example.com/1", "https://a.example.com/2"),
            MassImport.urlTokens("['https://a.example.com/1', 'https://a.example.com/2']"),
        )
    }

    @Test
    fun `trailing brace and bracket are stripped when unbalanced`() {
        assertEquals(listOf("https://a.example.com/x"), MassImport.urlTokens("{https://a.example.com/x}"))
        assertEquals(
            listOf("https://a.example.com/x?f[a]=1"),
            MassImport.urlTokens("https://a.example.com/x?f[a]=1"),
        )
    }

    @Test
    fun `comma inside query is kept`() {
        assertEquals(
            listOf("https://a.example.com/x?ids=12,34"),
            MassImport.urlTokens("https://a.example.com/x?ids=12,34"),
        )
        assertEquals(
            listOf("https://a.example.com/x?ids=12,34", "https://a.example.com/y"),
            MassImport.urlTokens("https://a.example.com/x?ids=12,34, https://a.example.com/y"),
        )
    }

    @Test
    fun `comma before space or scheme splits urls`() {
        assertEquals(
            listOf("https://a.example.com/1", "https://a.example.com/2"),
            MassImport.urlTokens("https://a.example.com/1,https://a.example.com/2"),
        )
        assertEquals(
            listOf("https://a.example.com/1?p=1", "https://a.example.com/2?p=2"),
            MassImport.urlTokens("https://a.example.com/1?p=1, https://a.example.com/2?p=2"),
        )
        assertEquals(
            listOf("https://a.example.com/1?p=1", "https://a.example.com/2"),
            MassImport.urlTokens("https://a.example.com/1?p=1,https://a.example.com/2"),
        )
    }

    @Test
    fun `sentence punctuation is kept on a url alone`() {
        assertEquals(listOf("https://a.example.com/what?!"), MassImport.urlTokens("https://a.example.com/what?!"))
        assertEquals(listOf("https://a.example.com/x."), MassImport.urlTokens("  https://a.example.com/x.  "))
        assertEquals(
            listOf("https://a.example.com/a!", "https://a.example.com/b:"),
            MassImport.urlTokens("https://a.example.com/a! https://a.example.com/b:"),
        )
    }

    @Test
    fun `sentence punctuation is stripped in prose`() {
        assertEquals(
            listOf("https://a.example.com/x", "https://a.example.com/y"),
            MassImport.urlTokens("Read https://a.example.com/x! Or https://a.example.com/y?"),
        )
    }

    @Test
    fun `trailing comma is always stripped`() {
        assertEquals(listOf("https://a.example.com/x"), MassImport.urlTokens("https://a.example.com/x,"))
    }

    @Test
    fun `unicode spaces end the url`() {
        for (space in listOf('\u00A0', '\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF', '\u3000', '\u2009')) {
            assertEquals(
                listOf("https://a.example.com/x", "Title"),
                MassImport.tokenizeLine("https://a.example.com/x${space}Title"),
                "U+%04X".format(space.code),
            )
        }
    }

    @Test
    fun `scheme check is case insensitive and needs a host`() {
        assertEquals(true, MassImport.isHttpUrl("HTTP://a.example.com/x"))
        assertEquals(true, MassImport.isHttpUrl("Https://a.example.com"))
        assertEquals(false, MassImport.isHttpUrl("http://"))
        assertEquals(false, MassImport.isHttpUrl("HTTPS://"))
        assertEquals(false, MassImport.isHttpUrl("http:///x"))
        assertEquals(false, MassImport.isHttpUrl("ftp://a.example.com"))
    }

    @Test
    fun `bare scheme is not a url`() {
        assertEquals(emptyList<String>(), MassImport.urlTokens("http:// https://"))
        assertEquals(listOf("http://"), MassImport.tokenizeLine("http://"))
    }

    @Test
    fun `archive and redirect links stay whole`() {
        val archive = "https://web.archive.org/web/2020/https://a.example.com/x"
        assertEquals(listOf(archive), MassImport.urlTokens(archive))
        val redirect = "https://a.example.com/out?to=https://b.example.com/y&x=1"
        assertEquals(listOf(redirect), MassImport.urlTokens(redirect))
    }

    @Test
    fun `invalid token reasons`() {
        assertEquals("Missing http:// or https://", MassImport.invalidTokenReason("a.example.com/x"))
        assertEquals("Missing http:// or https://", MassImport.invalidTokenReason("www.example.com"))
        assertEquals("Not a valid URL", MassImport.invalidTokenReason("htps://a.example.com/x"))
        assertEquals("Not a valid URL", MassImport.invalidTokenReason("http://"))
        assertEquals(null, MassImport.invalidTokenReason("Title"))
        assertEquals(null, MassImport.invalidTokenReason("Vol.II"))
        assertEquals(null, MassImport.invalidTokenReason("3"))
    }

    @Test
    fun `schemeless entry is kept as a field for the invalid list`() {
        assertEquals(
            listOf("a.example.com/x", "https://a.example.com/y"),
            MassImport.tokenizeLine("a.example.com/x, https://a.example.com/y"),
        )
    }

    @Test
    fun `parseUrls matches urlTokens and dedups`() {
        val massImport = MassImport(
            sourceManager = mockk(relaxed = true),
            networkToLocalManga = mockk(relaxed = true),
            mangaRepository = mockk(relaxed = true),
            sourcePreferences = mockk(relaxed = true),
        )
        val text = "Title,https://a.example.com/x\nhttps://a.example.com/x/\nurl,title"
        assertEquals(listOf("https://a.example.com/x"), massImport.parseUrls(text))
    }
}
