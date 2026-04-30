package tachiyomi.source.local

import mihon.core.archive.EpubReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubChapterListBuilderTest {

    @Test
    fun `buildEpubChaptersFromToc preserves fragment-based chapters`() {
        val tocChapters = listOf(
            EpubReader.EpubChapter(
                title = "Capitolo 1",
                href = "chapter.xhtml#one",
                order = 0,
            ),
            EpubReader.EpubChapter(
                title = "Capitolo 1",
                href = "chapter.xhtml#one",
                order = 1,
            ),
            EpubReader.EpubChapter(
                title = "Capitolo 2",
                href = "chapter.xhtml#two",
                order = 2,
            ),
        )

        val chapters = buildEpubChaptersFromToc(
            mangaUrl = "local-novels/book",
            chapterFileName = "volume.epub",
            chapterFileNameWithoutExtension = "volume",
            chapterLastModified = 123456789L,
            tocChapters = tocChapters,
            hasMultipleEpubFiles = false,
        )

        assertEquals(2, chapters.size)
        assertEquals("local-novels/book/volume.epub#chapter.xhtml#one", chapters[0].url)
        assertEquals("local-novels/book/volume.epub#chapter.xhtml#two", chapters[1].url)
        assertEquals("Capitolo 1", chapters[0].name)
        assertEquals("Capitolo 2", chapters[1].name)
        assertEquals(1f, chapters[0].chapter_number)
        assertEquals(2f, chapters[1].chapter_number)
        assertEquals(123456789L, chapters[0].date_upload)
    }

    @Test
    fun `buildEpubChaptersFromToc includes spine-only pages not in toc`() {
        val tocChapters = listOf(
            EpubReader.EpubChapter(
                title = "PART ONE",
                href = "Fahrenheit_451_split_001.html",
                order = 0,
            ),
            EpubReader.EpubChapter(
                title = "PART TWO",
                href = "Fahrenheit_451_split_002.html",
                order = 1,
            ),
            EpubReader.EpubChapter(
                title = "PART THREE",
                href = "Fahrenheit_451_split_003.html",
                order = 2,
            ),
        )

        val spinePages = listOf(
            "titlepage.xhtml",
            "Fahrenheit_451_split_000.html",
            "Fahrenheit_451_split_001.html",
            "Fahrenheit_451_split_002.html",
            "Fahrenheit_451_split_003.html",
            "Fahrenheit_451_split_004.html",
        )

        val chapters = buildEpubChaptersFromToc(
            mangaUrl = "local-novels/f451",
            chapterFileName = "f451.epub",
            chapterFileNameWithoutExtension = "f451",
            chapterLastModified = 123L,
            tocChapters = tocChapters,
            spinePageHrefs = spinePages,
            hasMultipleEpubFiles = false,
        )

        assertEquals(6, chapters.size)
        assertEquals("local-novels/f451/f451.epub#titlepage.xhtml", chapters[0].url)
        assertEquals("local-novels/f451/f451.epub#Fahrenheit_451_split_000.html", chapters[1].url)
        assertEquals("local-novels/f451/f451.epub#Fahrenheit_451_split_001.html", chapters[2].url)
        assertEquals("local-novels/f451/f451.epub#Fahrenheit_451_split_002.html", chapters[3].url)
        assertEquals("local-novels/f451/f451.epub#Fahrenheit_451_split_003.html", chapters[4].url)
        assertEquals("local-novels/f451/f451.epub#Fahrenheit_451_split_004.html", chapters[5].url)

        assertEquals("titlepage", chapters[0].name)
        assertEquals("Fahrenheit 451 split 000", chapters[1].name)
        assertEquals("PART ONE", chapters[2].name)
        assertEquals("PART TWO", chapters[3].name)
        assertEquals("PART THREE", chapters[4].name)
        assertEquals("Fahrenheit 451 split 004", chapters[5].name)
    }

    @Test
    fun `buildEpubChaptersFromToc includes navigation docs in spine when not in toc`() {
        val chapters = buildEpubChaptersFromToc(
            mangaUrl = "local-novels/book",
            chapterFileName = "volume.epub",
            chapterFileNameWithoutExtension = "volume",
            chapterLastModified = 1L,
            tocChapters = listOf(
                EpubReader.EpubChapter(
                    title = "Chapter 1",
                    href = "text/chapter1.xhtml",
                    order = 0,
                ),
            ),
            spinePageHrefs = listOf(
                "nav.xhtml",
                "toc.xhtml",
                "text/chapter1.xhtml",
            ),
            hasMultipleEpubFiles = false,
        )

        assertEquals(3, chapters.size)
        assertEquals("local-novels/book/volume.epub#nav.xhtml", chapters[0].url)
        assertEquals("local-novels/book/volume.epub#toc.xhtml", chapters[1].url)
        assertEquals("local-novels/book/volume.epub#text/chapter1.xhtml", chapters[2].url)
        assertEquals("nav", chapters[0].name)
        assertEquals("toc", chapters[1].name)
        assertEquals("Chapter 1", chapters[2].name)
    }

    @Test
    fun `buildEpubChaptersFromToc handles multi-book EPUBs with repeating chapter titles`() {
        // Validates that all TOC entries appear as chapters (no language-specific filtering)
        // Multi-volume interleaving is prevented by the offset system, not by filtering
        val tocChapters = listOf(
            EpubReader.EpubChapter(title = "IL TRONO DI VETRO", href = "p009_half-title-01.xhtml", order = 0),
            EpubReader.EpubChapter(title = "CAPITOLO 1", href = "p011_capitolo-01.xhtml", order = 1),
            EpubReader.EpubChapter(title = "CAPITOLO 2", href = "p012_capitolo-02.xhtml", order = 2),
            EpubReader.EpubChapter(title = "CAPITOLO 3", href = "p013_capitolo-03.xhtml", order = 3),
            EpubReader.EpubChapter(title = "LA CORONA DI MEZZANOTTE", href = "p068_half-title-02.xhtml", order = 4),
            EpubReader.EpubChapter(title = "PARTE PRIMA. La Campionessa del Re", href = "p070_parte-01.xhtml", order = 5),
            EpubReader.EpubChapter(title = "CAPITOLO 1", href = "p071_capitolo-56.xhtml", order = 6),
            EpubReader.EpubChapter(title = "CAPITOLO 2", href = "p072_capitolo-57.xhtml", order = 7),
            EpubReader.EpubChapter(title = "CAPITOLO 3", href = "p073_capitolo-58.xhtml", order = 8),
        )

        val chapters = buildEpubChaptersFromToc(
            mangaUrl = "local-novels/throne-of-glass",
            chapterFileName = "volume1.epub",
            chapterFileNameWithoutExtension = "volume1",
            chapterLastModified = 123L,
            tocChapters = tocChapters,
            hasMultipleEpubFiles = false,
        )

        assertEquals(9, chapters.size)
        assertEquals("IL TRONO DI VETRO", chapters[0].name)
        assertEquals("CAPITOLO 1", chapters[1].name)
        assertEquals("CAPITOLO 3", chapters[3].name)
        assertEquals("LA CORONA DI MEZZANOTTE", chapters[4].name)
        assertEquals("PARTE PRIMA. La Campionessa del Re", chapters[5].name)
        assertEquals("CAPITOLO 1", chapters[6].name)

        for (i in chapters.indices) {
            assertEquals((i + 1).toFloat(), chapters[i].chapter_number)
        }
    }

    @Test
    fun `buildEpubChaptersFromToc supports volume offsets for multi epub sorting`() {
        val volume1 = buildEpubChaptersFromToc(
            mangaUrl = "local-novels/throne-of-glass",
            chapterFileName = "volume1.epub",
            chapterFileNameWithoutExtension = "volume1",
            chapterLastModified = 123L,
            tocChapters = listOf(
                EpubReader.EpubChapter(title = "CAPITOLO 1", href = "v1-1.xhtml", order = 0),
                EpubReader.EpubChapter(title = "CAPITOLO 2", href = "v1-2.xhtml", order = 1),
            ),
            hasMultipleEpubFiles = true,
            chapterNumberOffset = 0f,
        )

        val volume2 = buildEpubChaptersFromToc(
            mangaUrl = "local-novels/throne-of-glass",
            chapterFileName = "volume2.epub",
            chapterFileNameWithoutExtension = "volume2",
            chapterLastModified = 124L,
            tocChapters = listOf(
                EpubReader.EpubChapter(title = "CAPITOLO 1", href = "v2-1.xhtml", order = 0),
                EpubReader.EpubChapter(title = "CAPITOLO 2", href = "v2-2.xhtml", order = 1),
            ),
            hasMultipleEpubFiles = true,
            chapterNumberOffset = 100_000f,
        )

        assertEquals(1f, volume1[0].chapter_number)
        assertEquals(2f, volume1[1].chapter_number)
        assertEquals(100_001f, volume2[0].chapter_number)
        assertEquals(100_002f, volume2[1].chapter_number)
        assertEquals("volume1 - CAPITOLO 1", volume1[0].name)
        assertEquals("volume2 - CAPITOLO 1", volume2[0].name)
    }
}
