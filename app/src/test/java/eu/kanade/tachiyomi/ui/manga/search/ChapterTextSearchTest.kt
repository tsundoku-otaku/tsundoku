package eu.kanade.tachiyomi.ui.manga.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.regex.PatternSyntaxException
import kotlin.coroutines.cancellation.CancellationException

class ChapterTextSearchTest {

    private fun count(text: String, query: String, options: ChapterSearchOptions = ChapterSearchOptions()) =
        ChapterTextSearch.findMatches(text, ChapterTextSearch.buildRegex(query, options)).count

    @Test
    fun `plain query matches literally and ignores case by default`() {
        assertEquals(2, count("Rogue said: rogue? (a.b)", "rogue"))
        assertEquals(1, count("a.b axb", "a.b"))
        assertEquals(0, count("Rogue", "rogue", ChapterSearchOptions(caseSensitive = true)))
    }

    @Test
    fun `case insensitive matching handles non-ascii letters`() {
        assertEquals(1, count("ÉLAN", "élan"))
    }

    @Test
    fun `regex option enables patterns`() {
        val options = ChapterSearchOptions(isRegex = true)
        assertEquals(3, count("Player 1, Player 22, Player 3", "Player \\d+", options))
        assertEquals(0, count("Player 1", "Player \\d+"))
    }

    @Test
    fun `whole word skips matches inside words`() {
        val options = ChapterSearchOptions(wholeWord = true)
        assertEquals(1, count("Play the player, play", "play", options.copy(caseSensitive = true)))
        assertEquals(2, count("Play the player, play", "play", options))
        assertEquals(1, count("cat|catalog", "cat|dog", options.copy(isRegex = true)))
    }

    @Test
    fun `invalid regex throws`() {
        assertThrows<PatternSyntaxException> {
            ChapterTextSearch.buildRegex("(unclosed", ChapterSearchOptions(isRegex = true))
        }
    }

    @Test
    fun `empty regex matches are not counted`() {
        assertEquals(1, count("aab", "b*", ChapterSearchOptions(isRegex = true)))
    }

    @Test
    fun `snippets carry match bounds and position`() {
        val text = "x".repeat(100) + "needle" + "y".repeat(100)
        val result = ChapterTextSearch.findMatches(text, ChapterTextSearch.buildRegex("needle", ChapterSearchOptions()))
        val snippet = result.snippets.single()
        assertEquals("needle", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
        assertTrue(snippet.text.startsWith("…"))
        assertTrue(snippet.text.endsWith("…"))
        assertEquals(100f / text.length, snippet.position)
    }

    @Test
    fun `snippet count is capped while every match is counted`() {
        val result = ChapterTextSearch.findMatches(
            "a ".repeat(10),
            ChapterTextSearch.buildRegex("a", ChapterSearchOptions()),
            maxSnippets = 2,
        )
        assertEquals(10, result.count)
        assertEquals(2, result.snippets.size)
    }

    @Test
    fun `html is reduced to visible text`() {
        val text = ChapterTextSearch.toPlainText(
            "<html><head><style>.rogue{}</style><script>var rogue;</script></head>" +
                "<body><h1>Title</h1><p>The <b>Rogue</b> ran.</p></body></html>",
        )
        assertEquals("Title The Rogue ran.", text)
        assertEquals(1, count(text, "rogue"))
    }

    @Test
    fun `cancelled scan aborts`() {
        assertThrows<CancellationException> {
            ChapterTextSearch.findMatches(
                "a".repeat(100_000),
                ChapterTextSearch.buildRegex("b", ChapterSearchOptions()),
                isActive = { false },
            )
        }
    }

    @Test
    fun `plain text decodes entities and collapses whitespace`() {
        assertEquals("Tom & Jerry said <hi>…", ChapterTextSearch.toPlainText("<p>Tom &amp; Jerry\n\n said &lt;hi&gt;&hellip;</p>"))
        assertEquals("one two", ChapterTextSearch.toPlainText("one<br/>two"))
        assertEquals("wo rd", ChapterTextSearch.toPlainText("<p>wo</p><p>rd</p>"))
        assertEquals("word", ChapterTextSearch.toPlainText("<p>wo<b>rd</b></p>"))
    }

    @Test
    fun `plain text decodes every html entity and drops soft hyphens`() {
        assertEquals("café caé 😀", ChapterTextSearch.toPlainText("caf&eacute; ca&#233; &#128512;"))
        assertEquals("cafe", ChapterTextSearch.toPlainText("ca&shy;fe"))
        assertEquals("&bogus; stays", ChapterTextSearch.toPlainText("&bogus; stays"))
    }

    @Test
    fun `an unclosed tag start stays text`() {
        assertEquals("a<b and the rest", ChapterTextSearch.toPlainText("a<b and the rest"))
        assertEquals("a<b c d", ChapterTextSearch.toPlainText("a<b c <i>d</i>"))
    }

    @Test
    fun `a quoted greater-than does not end a tag`() {
        assertEquals("text", ChapterTextSearch.toPlainText("<a title=\"x > y\">text</a>"))
    }

    @Test
    fun `range bounds accept comma decimals and flag bad input`() {
        assertEquals(12.5, ChapterSearchScreen.Model.parseRangeBound(" 12,5 "))
        assertTrue(ChapterSearchScreen.Model.parseRangeBound("  ")!!.isNaN())
        assertEquals(null, ChapterSearchScreen.Model.parseRangeBound("abc"))
    }

    @Test
    fun `literal search matches the regex search`() {
        val text = "The Rogue met the rogue; ROGUES everywhere."
        for (ignoreCase in listOf(true, false)) {
            val options = ChapterSearchOptions(caseSensitive = !ignoreCase)
            val viaRegex = ChapterTextSearch.findMatches(text, ChapterTextSearch.buildRegex("rogue", options))
            val literal = ChapterTextSearch.findLiteralMatches(text, "rogue", ignoreCase)
            assertEquals(viaRegex.count, literal.count)
            assertEquals(viaRegex.snippets.map { it.matchStart }, literal.snippets.map { it.matchStart })
        }
    }
}
