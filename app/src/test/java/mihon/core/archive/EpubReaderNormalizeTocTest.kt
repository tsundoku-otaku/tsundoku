package mihon.core.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubReaderNormalizeTocTest {

    private fun chapter(title: String, order: Int, depth: Int = 0) =
        EpubReader.EpubChapter(title = title, href = "s$order.xhtml", order = order, depth = depth)

    private fun normalizeFlat(vararg titles: String): List<String> =
        EpubReader.normalizeTableOfContents(
            titles.mapIndexed { index, title -> chapter(title, index) },
        ).map { it.title }

    private fun normalize(vararg entries: Pair<String, Int>): List<String> =
        EpubReader.normalizeTableOfContents(
            entries.mapIndexed { index, (title, depth) -> chapter(title, index, depth) },
        ).map { it.title }

    // Flat TOC (heuristic fallback)

    @Test
    fun `descriptive chapter titles are left untouched`() {
        // Regression: "Chapter N: Title" used to match the subsection regex and get prefixed with the
        // previous front-matter entry (e.g. "Table of Contents Page - Chapter 1: ...").
        val result = normalizeFlat(
            "Table of Contents Page",
            "Chapter 1: Operating Behind the Scenes",
            "Chapter 2: True Ability",
            "Postscript",
        )

        assertEquals(
            listOf(
                "Table of Contents Page",
                "Chapter 1: Operating Behind the Scenes",
                "Chapter 2: True Ability",
                "Postscript",
            ),
            result,
        )
    }

    @Test
    fun `bare subsection labels are still prefixed with the last primary title`() {
        val result = normalizeFlat("The Awakening", "Part 1", "Part 2")
        assertEquals(listOf("The Awakening", "The Awakening - Part 1", "The Awakening - Part 2"), result)
    }

    @Test
    fun `bare label with trailing punctuation still counts as a subsection`() {
        val result = normalizeFlat("Prologue", "Chapter 3.", "Chapter 4:")
        assertEquals(listOf("Prologue", "Prologue - Chapter 3.", "Prologue - Chapter 4:"), result)
    }

    @Test
    fun `roman numeral subsection labels are recognized`() {
        val result = normalizeFlat("The Return", "Act IV")
        assertEquals(listOf("The Return", "The Return - Act IV"), result)
    }

    @Test
    fun `subsection at the start with no primary is left as-is`() {
        val result = normalizeFlat("Part 1", "The Real Start")
        assertEquals(listOf("Part 1", "The Real Start"), result)
    }

    @Test
    fun `blank titles fall back to a positional chapter name`() {
        val result = normalizeFlat("Intro", "   ")
        assertEquals(listOf("Intro", "Chapter 2"), result)
    }

    @Test
    fun `empty toc returns empty`() {
        assertEquals(emptyList<String>(), EpubReader.normalizeTableOfContents(emptyList()))
    }

    // Nested TOC (structural, locale-agnostic)

    @Test
    fun `nested entries are prefixed with their parent title`() {
        val result = normalize(
            "Part One" to 0,
            "Chapter 1" to 1,
            "Chapter 2" to 1,
            "Part Two" to 0,
            "Chapter 3" to 1,
        )

        assertEquals(
            listOf(
                "Part One",
                "Part One - Chapter 1",
                "Part One - Chapter 2",
                "Part Two",
                "Part Two - Chapter 3",
            ),
            result,
        )
    }

    @Test
    fun `structural nesting works for non-english titles`() {
        // No keyword list involved, so German/Japanese labels normalize just like English ones.
        val result = normalize(
            "Erster Teil" to 0,
            "Kapitel 1" to 1,
            "第1章" to 1,
        )

        assertEquals(listOf("Erster Teil", "Erster Teil - Kapitel 1", "Erster Teil - 第1章"), result)
    }

    @Test
    fun `deep nesting joins the full ancestor chain`() {
        val result = normalize(
            "Book I" to 0,
            "Part A" to 1,
            "Chapter 1" to 2,
        )

        assertEquals(listOf("Book I", "Book I - Part A", "Book I - Part A - Chapter 1"), result)
    }

    @Test
    fun `descriptive titles under a parent keep the parent prefix instead of heuristic mangling`() {
        // A nested TOC forces the structural path even though a sibling looks like a heuristic subsection.
        val result = normalize(
            "Volume 1" to 0,
            "Chapter 1: The Beginning" to 1,
        )

        assertEquals(listOf("Volume 1", "Volume 1 - Chapter 1: The Beginning"), result)
    }
}
