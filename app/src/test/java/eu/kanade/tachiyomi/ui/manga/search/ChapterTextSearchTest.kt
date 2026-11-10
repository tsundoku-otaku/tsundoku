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
    fun `snippets remember which match they show`() {
        val result = ChapterTextSearch.findMatches(
            "a b a b a",
            ChapterTextSearch.buildRegex("a", ChapterSearchOptions()),
            maxSnippets = 2,
        )
        assertEquals(listOf(0, 1), result.snippets.map { it.matchIndex })
    }

    @Test
    fun `locate picks the same match when the reader text has the same matches`() {
        val regex = ChapterTextSearch.buildRegex("rogue", ChapterSearchOptions())
        // Reader text keeps paragraph breaks where the search text had spaces.
        val readerText = "The Rogue ran.\n\nA rogue hid.\n\nNo ROGUE here."
        assertEquals(
            18 until 23,
            ChapterTextSearch.locateMatch(readerText, regex, matchIndex = 1, matchCount = 3, position = 0.4f),
        )
        assertEquals(
            4 until 9,
            ChapterTextSearch.locateMatch(readerText, regex, matchIndex = 0, matchCount = 3, position = 0.9f),
        )
    }

    @Test
    fun `locate falls back to the match nearest the search position when counts differ`() {
        val regex = ChapterTextSearch.buildRegex("x", ChapterSearchOptions())
        val readerText = "x" + ".".repeat(98) + "x"
        assertEquals(
            99 until 100,
            ChapterTextSearch.locateMatch(readerText, regex, matchIndex = 0, matchCount = 5, position = 0.8f),
        )
        assertEquals(
            0 until 1,
            ChapterTextSearch.locateMatch(readerText, regex, matchIndex = 4, matchCount = 5, position = 0.2f),
        )
    }

    @Test
    fun `locate finds nothing when the reader text lacks the match`() {
        val regex = ChapterTextSearch.buildRegex("absent", ChapterSearchOptions())
        assertEquals(
            null,
            ChapterTextSearch.locateMatch("some text", regex, matchIndex = 0, matchCount = 1, position = 0f),
        )
    }

    @Test
    fun `search target rebuilds the regex it was searched with`() {
        val target = ChapterSearchTarget(
            query = "play",
            options = ChapterSearchOptions(wholeWord = true, caseSensitive = true),
            matchIndex = 0,
            matchCount = 1,
            position = 0f,
        )
        assertEquals(1, ChapterTextSearch.findMatches("Play the player, play", target.buildRegex()).count)
        assertEquals(target, ChapterSearchTarget.decode(target.encode()))
    }
}
