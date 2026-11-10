package eu.kanade.tachiyomi.ui.manga.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.util.regex.PatternSyntaxException

/**
 * Pins what chapter search finds against output recorded from the original implementation
 * (before the speed work), for every option combination over a fixture corpus.
 *
 * The golden file must never be edited by hand: when this test fails, the search changed what it
 * finds. To record it again from a given implementation, run this test with the environment
 * variable `CHAPTER_SEARCH_WRITE_GOLDEN` set to the golden file's path.
 */
class ChapterSearchGoldenTest {

    private val queries = listOf(
        "rogue",
        "Rogue",
        "the",
        "gate",
        "silver gate",
        "play",
        "a.b",
        "élan",
        "&",
        "Player \\d+",
        "\\bgate\\b",
        "r.g",
        "(?i)ROGUE",
        "b*",
        "cat|dog",
        "剣",
        "strasse",
        "A{10,}",
        "(unclosed",
        "Seraphine opened the silver gate",
    )

    private val optionSets = buildList {
        for (isRegex in listOf(false, true)) {
            for (caseSensitive in listOf(false, true)) {
                for (wholeWord in listOf(false, true)) {
                    add(ChapterSearchOptions(isRegex = isRegex, caseSensitive = caseSensitive, wholeWord = wholeWord))
                }
            }
        }
    }

    /** Searches one chapter's raw content the way chapter search does. */
    private fun search(content: String, regex: Regex): ChapterTextMatches =
        ChapterTextSearch.findMatches(ChapterTextSearch.toPlainText(content), regex, maxSnippets = MAX_SNIPPETS)

    private fun render(): String = buildString {
        val fixtures = fixtureDir().listFiles()!!.sortedBy { it.name }
        for (query in queries) {
            for (options in optionSets) {
                val label = "query=${quote(query)} regex=${options.isRegex} " +
                    "case=${options.caseSensitive} word=${options.wholeWord}"
                val regex = try {
                    ChapterTextSearch.buildRegex(query, options)
                } catch (e: PatternSyntaxException) {
                    appendLine("$label invalid")
                    continue
                }
                appendLine(label)
                for (fixture in fixtures) {
                    val matches = search(fixture.readText(), regex)
                    if (matches.count == 0) continue
                    appendLine("  ${fixture.name} count=${matches.count}")
                    matches.snippets.forEach { snippet ->
                        appendLine(
                            "    [${snippet.matchStart},${snippet.matchEnd}) pos=${snippet.position} " +
                                quote(snippet.text),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `search results match the original implementation`() {
        val actual = render()
        val writeTo = System.getenv("CHAPTER_SEARCH_WRITE_GOLDEN")
        if (writeTo != null) {
            File(writeTo).writeText(actual)
            return
        }
        val golden = javaClass.getResource("/chapter-search/golden.txt")!!.readText()
        assertEquals(golden, actual)
    }

    private fun fixtureDir(): File = File(javaClass.getResource("/chapter-search/fixtures")!!.toURI())

    private fun quote(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\"", "\\\"") + "\""

    private companion object {
        const val MAX_SNIPPETS = 500
    }
}
