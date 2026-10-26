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
