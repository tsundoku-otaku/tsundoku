package eu.kanade.domain.manga.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins [WordDensity.countWords] to the counts the original Jsoup-based implementation produced for
 * the fixture chapters in `resources/wordcount`. `golden-wordcounts.tsv` was generated once from that
 * implementation and must not be edited to make a faster counter pass.
 */
class WordCountGoldenTest {

    @Test
    fun `countWords matches the golden counts for every fixture chapter`() {
        val dir = File(javaClass.classLoader!!.getResource("wordcount")!!.toURI())
        val golden = File(dir, "golden-wordcounts.tsv").readLines()
            .filter { it.isNotBlank() }
            .associate { line -> line.substringBefore('\t') to line.substringAfter('\t').toInt() }
        val fixtures = dir.listFiles()!!.filter { it.extension == "html" || it.extension == "txt" }

        assertEquals(golden.keys, fixtures.map { it.name }.toSet())
        fixtures.forEach { fixture ->
            assertEquals(golden.getValue(fixture.name), WordDensity.countWords(fixture.readText()), fixture.name)
        }
    }
}
