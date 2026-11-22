package eu.kanade.tachiyomi.ui.manga

import eu.kanade.domain.manga.model.WordDensity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Collections

class WordCountScannerTest {

    private class FakeReader(
        private val contents: Map<Int, String?>,
        private val failing: Set<Int> = emptySet(),
        val log: MutableList<List<Int>>,
    ) : WordCountChapterReader<Int> {
        private val read = mutableListOf<Int>()
        var closed = false

        override suspend fun read(chapter: Int): String? {
            yield()
            read += chapter
            if (chapter in failing) error("broken chapter $chapter")
            return contents[chapter]
        }

        override fun close() {
            closed = true
            log += read.toList()
        }
    }

    private fun scan(
        contents: Map<Int, String?>,
        chapters: List<Int> = contents.keys.sorted(),
        failing: Set<Int> = emptySet(),
        parallelism: Int,
        totalChapters: Int = chapters.size,
        progress: MutableList<Int> = Collections.synchronizedList(mutableListOf()),
        readers: MutableList<FakeReader> = Collections.synchronizedList(mutableListOf()),
        log: MutableList<List<Int>> = Collections.synchronizedList(mutableListOf()),
    ): WordDensity = runBlocking {
        WordCountScanner.scan(
            chapters = chapters,
            totalChapters = totalChapters,
            parallelism = parallelism,
            openReader = { FakeReader(contents, failing, log).also { readers += it } },
            onProgress = { progress += it },
        )
    }

    @Test
    fun `totals match a sequential count for every parallelism`() {
        val contents = (1..23).associateWith { n -> List(n) { "word" }.joinToString(" ") }
        val expectedWords = (1..23).sum().toLong()

        for (parallelism in listOf(1, 2, 4, 50)) {
            val result = scan(contents, parallelism = parallelism, totalChapters = 30)
            assertEquals(WordDensity(expectedWords, 23, 30, 0), result, "parallelism $parallelism")
        }
    }

    @Test
    fun `missing and failing chapters are counted as unreadable`() {
        val contents = mapOf(1 to "a b", 2 to null, 3 to "c", 4 to "d e f", 5 to "g")

        val result = scan(contents, failing = setOf(4), parallelism = 2, totalChapters = 8)

        assertEquals(
            WordDensity(totalWords = 4, countedChapters = 3, totalChapters = 8, unreadableChapters = 2),
            result,
        )
        assertEquals(3, result.notDownloadedChapters)
    }

    @Test
    fun `each reader walks one contiguous run in order and is closed`() {
        val contents = (1..10).associateWith { "x" }
        val readers = Collections.synchronizedList(mutableListOf<FakeReader>())
        val log = Collections.synchronizedList(mutableListOf<List<Int>>())

        scan(contents, parallelism = 3, readers = readers, log = log)

        assertEquals(3, readers.size)
        assertTrue(readers.all { it.closed })
        assertEquals(listOf(listOf(1, 2, 3, 4), listOf(5, 6, 7, 8), listOf(9, 10)), log.sortedBy { it.first() })
    }

    @Test
    fun `progress reports every chapter once and ends at the chapter count`() {
        val contents = (1..17).associateWith { "x" }
        val progress = Collections.synchronizedList(mutableListOf<Int>())

        scan(contents, parallelism = 4, progress = progress)

        assertEquals((1..17).toList(), progress.sorted())
    }

    @Test
    fun `an empty chapter list gives an empty result`() {
        assertEquals(WordDensity(0, 0, 5, 0), scan(emptyMap(), parallelism = 4, totalChapters = 5))
    }

    @Test
    fun `cancelling the scan stops it and closes its readers`() = runBlocking {
        val readers = Collections.synchronizedList(mutableListOf<BlockingReader>())
        val job = async {
            WordCountScanner.scan(
                chapters = (1..8).toList(),
                totalChapters = 8,
                parallelism = 2,
                openReader = { BlockingReader().also { readers += it } },
                onProgress = {},
            )
        }
        while (readers.size < 2) yield()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(readers.all { it.closed })
    }

    private class BlockingReader : WordCountChapterReader<Int> {
        var closed = false
        override suspend fun read(chapter: Int): String? = awaitCancellation()
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `scan totals over the fixture chapters match the golden counts`() {
        val dir = File(javaClass.classLoader!!.getResource("wordcount")!!.toURI())
        val golden = File(dir, "golden-wordcounts.tsv").readLines()
            .filter { it.isNotBlank() }
            .associate { line -> line.substringBefore('\t') to line.substringAfter('\t').toLong() }
        val names = golden.keys.sorted()
        val contents = names.indices.associateWith { File(dir, names[it]).readText() }

        val result = scan(contents, parallelism = 4)

        assertEquals(golden.values.sum(), result.totalWords)
        assertEquals(names.size, result.countedChapters)
    }

    @Test
    fun `cancellation from a reader is not swallowed as an unreadable chapter`() {
        val failure = runCatching {
            runBlocking {
                WordCountScanner.scan(
                    chapters = listOf(1, 2),
                    totalChapters = 2,
                    parallelism = 1,
                    openReader = {
                        object : WordCountChapterReader<Int> {
                            override suspend fun read(chapter: Int): String? = throw CancellationException("stop")
                            override fun close() {}
                        }
                    },
                    onProgress = {},
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }
}
