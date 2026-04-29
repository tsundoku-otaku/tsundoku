
package tachiyomi.source.local

import mihon.core.archive.EpubReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `buildEpubChaptersFromToc skips navigation docs in spine when not in toc`() {
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

        assertEquals(1, chapters.size)
        assertEquals("local-novels/book/volume.epub#text/chapter1.xhtml", chapters[0].url)
        assertEquals("Chapter 1", chapters[0].name)
    }

    @Test
    fun `buildEpubChaptersFromToc handles multi-book EPUBs with repeating chapter titles`() {
        // This reproduces and FIXES the issue with volumes mixing chapters
        // Book 1: "IL TRONO DI VETRO" with CAPITOLO 1-3
        // Book 2: "LA CORONA DI MEZZANOTTE" > "PARTE PRIMA" with CAPITOLO 1-3 (actually chapters 56-58 in files)
        val tocChapters = listOf(
            // Book 1: IL TRONO DI VETRO (chapters 1-3 for brevity)
            EpubReader.EpubChapter(title = "IL TRONO DI VETRO", href = "p009_half-title-01.xhtml", order = 0),
            EpubReader.EpubChapter(title = "CAPITOLO 1", href = "p011_capitolo-01.xhtml", order = 1),
            EpubReader.EpubChapter(title = "CAPITOLO 2", href = "p012_capitolo-02.xhtml", order = 2),
            EpubReader.EpubChapter(title = "CAPITOLO 3", href = "p013_capitolo-03.xhtml", order = 3),
            // Book 2: LA CORONA DI MEZZANOTTE > PARTE PRIMA
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

        // EXPECTED: 6 chapters (headers not counted)
        // Book 1: 3 chapters with context prefix
        // Book 2: 3 chapters with book + part context prefix
        assertEquals(6, chapters.size)
        
        // Book 1 chapters - context is accumulated book title
        assertEquals("local-novels/throne-of-glass/volume1.epub#p011_capitolo-01.xhtml", chapters[0].url)
        assertEquals("IL TRONO DI VETRO - CAPITOLO 1", chapters[0].name)
        assertEquals(1f, chapters[0].chapter_number)

        assertEquals("local-novels/throne-of-glass/volume1.epub#p012_capitolo-02.xhtml", chapters[1].url)
        assertEquals("IL TRONO DI VETRO - CAPITOLO 2", chapters[1].name)
        assertEquals(2f, chapters[1].chapter_number)

        assertEquals("local-novels/throne-of-glass/volume1.epub#p013_capitolo-03.xhtml", chapters[2].url)
        assertEquals("IL TRONO DI VETRO - CAPITOLO 3", chapters[2].name)
        assertEquals(3f, chapters[2].chapter_number)

        // Book 2 chapters - NOW WITH FULL CONTEXT! No more confusion!
        // Should show book + part context: "LA CORONA DI MEZZANOTTE - PARTE PRIMA - CAPITOLO 1"
        assertEquals("local-novels/throne-of-glass/volume1.epub#p071_capitolo-56.xhtml", chapters[3].url)
        assertEquals("LA CORONA DI MEZZANOTTE - PARTE PRIMA. La Campionessa del Re - CAPITOLO 1", chapters[3].name)
        assertEquals(4f, chapters[3].chapter_number)

        assertEquals("local-novels/throne-of-glass/volume1.epub#p072_capitolo-57.xhtml", chapters[4].url)
        assertEquals("LA CORONA DI MEZZANOTTE - PARTE PRIMA. La Campionessa del Re - CAPITOLO 2", chapters[4].name)
        assertEquals(5f, chapters[4].chapter_number)

        assertEquals("local-novels/throne-of-glass/volume1.epub#p073_capitolo-58.xhtml", chapters[5].url)
        assertEquals("LA CORONA DI MEZZANOTTE - PARTE PRIMA. La Campionessa del Re - CAPITOLO 3", chapters[5].name)
        assertEquals(6f, chapters[5].chapter_number)
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
            chapterLastModified = 456L,
            tocChapters = listOf(
                EpubReader.EpubChapter(title = "CAPITOLO 1", href = "v2-1.xhtml", order = 0),
                EpubReader.EpubChapter(title = "CAPITOLO 2", href = "v2-2.xhtml", order = 1),
            ),
            hasMultipleEpubFiles = true,
            chapterNumberOffset = 100_000f,
        )

        val combined = (volume1 + volume2).sortedWith { first, second ->
            when {
                first.chapter_number != second.chapter_number -> second.chapter_number.compareTo(first.chapter_number)
                else -> second.name.compareTo(first.name, ignoreCase = true)
            }
        }

        assertEquals(listOf(
            "volume2 - CAPITOLO 2",
            "volume2 - CAPITOLO 1",
            "volume1 - CAPITOLO 2",
            "volume1 - CAPITOLO 1",
        ), combined.map { it.name })

        assertTrue(combined.take(2).all { it.name.startsWith("volume2") })
        assertTrue(combined.drop(2).all { it.name.startsWith("volume1") })
    }
}
