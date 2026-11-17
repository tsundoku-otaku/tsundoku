package eu.kanade.domain.manga.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WordDensityTest {

    @Test
    fun `countWords ignores markup and decodes entities`() {
        val html = """
            <html><head><title>Ignored title</title><style>p { color: red; }</style></head>
            <body><h1>Chapter&nbsp;1</h1><p>Tom &amp; Jerry said &lt;hi&gt;.</p>
            <script>var notWords = 1;</script><p>Last<br/>line</p></body></html>
        """.trimIndent()

        // Chapter, 1, Tom, &, Jerry, said, <hi>., Last, line
        assertEquals(9, WordDensity.countWords(html))
    }

    @Test
    fun `countWords handles plain text and blank content`() {
        assertEquals(4, WordDensity.countWords("one two\nthree\t four"))
        assertEquals(0, WordDensity.countWords("   \n "))
        assertEquals(0, WordDensity.countWords("<p> </p>"))
    }

    @Test
    fun `averageWords divides by counted chapters`() {
        assertEquals(450, WordDensity(totalWords = 900, countedChapters = 2, totalChapters = 5).averageWords)
        assertEquals(0, WordDensity(totalWords = 0, countedChapters = 0, totalChapters = 5).averageWords)
    }

    @Test
    fun `unreadable chapters are kept apart from chapters that are not downloaded`() {
        val density = WordDensity(totalWords = 1500, countedChapters = 3, totalChapters = 10, unreadableChapters = 2)

        assertEquals(500, density.averageWords)
        assertEquals(5, density.notDownloadedChapters)
        val allAvailable = WordDensity(totalWords = 900, countedChapters = 3, totalChapters = 5, unreadableChapters = 2)
        assertEquals(0, allAvailable.notDownloadedChapters)
    }

    @Test
    fun `densityTier steps every 500 words`() {
        assertEquals(1, WordDensity.densityTier(0))
        assertEquals(1, WordDensity.densityTier(1))
        assertEquals(1, WordDensity.densityTier(500))
        assertEquals(2, WordDensity.densityTier(501))
        assertEquals(2, WordDensity.densityTier(1000))
        assertEquals(3, WordDensity.densityTier(1001))
        assertEquals(21, WordDensity.densityTier(10_001))
    }

    @Test
    fun `tier bounds match densityTier`() {
        assertEquals(1, WordDensity.tierMinWords(1))
        assertEquals(500, WordDensity.tierMaxWords(1))
        assertEquals(501, WordDensity.tierMinWords(2))
        assertEquals(1000, WordDensity.tierMaxWords(2))

        for (tier in 1..30) {
            assertEquals(tier, WordDensity.densityTier(WordDensity.tierMinWords(tier)))
            assertEquals(tier, WordDensity.densityTier(WordDensity.tierMaxWords(tier)))
        }
    }

    @Test
    fun `countWords separates block tags and keeps inline tags inside a word`() {
        assertEquals(2, WordDensity.countWords("<p>one</p><p>two</p>"))
        assertEquals(1, WordDensity.countWords("<p>wo<b>rd</b></p>"))
        assertEquals(2, WordDensity.countWords("one<br>two"))
        assertEquals(4, WordDensity.countWords("a < b <!-- hidden words here --> c"))
        assertEquals(1, WordDensity.countWords("<p>shown</p><script>a b c</script>"))
    }

    @Test
    fun `countWords keeps an unclosed tag start as text`() {
        assertEquals(5, WordDensity.countWords("if x<y then the rest"))
        assertEquals(4, WordDensity.countWords("a<b c <i>d</i> e"))
        assertEquals(1, WordDensity.countWords("<a title=\"x > y\">text</a>"))
    }

    @Test
    fun `countWords keeps counting after an unclosed head or script`() {
        assertEquals(2, WordDensity.countWords("<html><head><title>t</title><body><p>one two</p>"))
        assertEquals(2, WordDensity.countWords("<p>one</p><style>two"))
    }

    @Test
    fun `countWords keeps more inline tags inside a word`() {
        assertEquals(1, WordDensity.countWords("foo<wbr>bar"))
        assertEquals(1, WordDensity.countWords("a<code>b</code><del>c</del><abbr>d</abbr>"))
    }
}
