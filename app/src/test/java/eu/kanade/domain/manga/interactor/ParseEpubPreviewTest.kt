package eu.kanade.domain.manga.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ParseEpubPreviewTest {

    private val parser = ParseEpubPreview()

    @Test
    fun `defaultCustomTitle returns single title for one file`() {
        val files = listOf(
            ParseEpubPreview.TitleCandidate(
                fileName = "one.epub",
                title = "One",
                collection = null,
            ),
        )

        assertEquals("One", parser.defaultCustomTitleFromCandidates(files))
    }

    @Test
    fun `defaultCustomTitle prefers shared collection`() {
        val files = listOf(
            ParseEpubPreview.TitleCandidate(
                fileName = "a.epub",
                title = "A",
                collection = "Saga",
            ),
            ParseEpubPreview.TitleCandidate(
                fileName = "b.epub",
                title = "B",
                collection = "Saga",
            ),
        )

        assertEquals("Saga", parser.defaultCustomTitleFromCandidates(files))
    }

    @Test
    fun `defaultCustomTitle falls back to first filename without extension`() {
        val files = listOf(
            ParseEpubPreview.TitleCandidate(
                fileName = "first_book.epub",
                title = "A",
                collection = null,
            ),
            ParseEpubPreview.TitleCandidate(
                fileName = "second_book.epub",
                title = "B",
                collection = null,
            ),
        )

        assertEquals("first_book", parser.defaultCustomTitleFromCandidates(files))
    }
}
