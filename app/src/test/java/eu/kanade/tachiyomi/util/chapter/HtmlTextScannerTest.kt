package eu.kanade.tachiyomi.util.chapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HtmlTextScannerTest {

    private fun scan(content: String): String {
        val out = StringBuilder()
        HtmlTextScanner.scan(content, onChar = { out.append(it) }, onBreak = { out.append('|') })
        return out.toString()
    }

    @Test
    fun `block tags break and inline tags do not`() {
        assertEquals("one|two", scan("one<br>two"))
        assertEquals("word", scan("wo<b>rd</b>"))
        assertEquals("wo|rd", scan("wo<p>rd"))
    }

    @Test
    fun `skips comments scripts and styles`() {
        assertEquals("a |b", scan("a <!-- hidden -->b"))
        assertEquals("|shown||", scan("<p>shown</p><script>x y</script>"))
        assertEquals("|ok", scan("<style>p{}</style>ok"))
    }

    @Test
    fun `decodes entities and keeps unknown ones literal`() {
        assertEquals("Tom & <Jerry>", scan("Tom &amp; &lt;Jerry&gt;"))
        assertEquals("A B", scan("A&#160;B"))
        assertEquals("a &zzz b", scan("a &zzz b"))
    }

    @Test
    fun `ampersand without a semicolon stays text`() {
        assertEquals("a & b", scan("a & b"))
    }

    @Test
    fun `unterminated tag start stays text`() {
        assertEquals("a<p", scan("a<p"))
        assertEquals("x<y |z", scan("x<y <p>z"))
    }

    @Test
    fun `quoted greater-than does not end a tag`() {
        assertEquals("text", scan("<a title=\"x > y\">text</a>"))
    }

    @Test
    fun `unclosed skipped element only skips its tag`() {
        assertEquals("||||one", scan("<html><head><title>t</title><body>one"))
    }

    @Test
    fun `decodes every entity and drops soft hyphens`() {
        assertEquals("caf\u00E9 \uD83D\uDE00 cafe", scan("caf&eacute; &#128512; ca&shy;f\u00ADe"))
    }
}
