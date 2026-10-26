package eu.kanade.tachiyomi.data.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslatedChapter

class TranslationCachePolicyTest {

    private fun cached(content: String = "<p>hola</p>") = TranslatedChapter(
        chapterId = 1L,
        mangaId = 1L,
        targetLanguage = "en",
        engineId = "test",
        translatedContent = content,
    )

    @Test
    fun `shouldServeCached returns true when a cached translation exists`() {
        assertTrue(TranslationCachePolicy.shouldServeCached(cached()))
    }

    @Test
    fun `shouldServeCached returns false when no cached translation`() {
        assertFalse(TranslationCachePolicy.shouldServeCached(null))
    }

    @Test
    fun `cached translations served regardless of source hash drift`() {
        // The policy intentionally ignores hash compatibility — once a chapter has been
        // translated, opening or re-opening it must NOT hit the translation API. Even if
        // the source HTML changes between sessions (e.g. site re-templated, downloaded
        // chapter re-fetched), the cached translation wins until the user triggers an
        // explicit re-translate.
        val cachedAgainstDifferentSource = cached(content = "<!-- old-source-hash:abc -->\n<p>bonjour</p>")
        assertTrue(TranslationCachePolicy.shouldServeCached(cachedAgainstDifferentSource))
    }

    @Test
    fun `auto-enqueue skipped when cache present`() {
        assertTrue(TranslationCachePolicy.shouldSkipAutoEnqueue(cached()))
    }

    @Test
    fun `auto-enqueue not skipped when no cache`() {
        assertFalse(TranslationCachePolicy.shouldSkipAutoEnqueue(null))
    }

    private fun partial(runId: Long?, sourceParagraphs: Int? = null, paragraphs: Int = 0) =
        PartialTranslation(null, List(paragraphs) { "p$it" }, sourceParagraphs, runId)

    @Test
    fun `unforced run reuses any partial`() {
        TranslationCachePolicy.canReuse(partial(runId = 5L), forceRetranslate = false, taskCreatedAt = 100L) shouldBe
            true
        TranslationCachePolicy.canReuse(partial(runId = null), forceRetranslate = false, taskCreatedAt = 100L) shouldBe
            true
    }

    @Test
    fun `forced run reuses only its own task's partial`() {
        TranslationCachePolicy.canReuse(partial(runId = 100L), forceRetranslate = true, taskCreatedAt = 100L) shouldBe
            true
        TranslationCachePolicy.canReuse(partial(runId = 200L), forceRetranslate = true, taskCreatedAt = 100L) shouldBe
            false
        TranslationCachePolicy.canReuse(partial(runId = null), forceRetranslate = true, taskCreatedAt = 100L) shouldBe
            false
    }

    @Test
    fun `a recorded source paragraph count wins over the translated paragraph count`() {
        val counts = listOf(3, 3, 2)
        TranslationCachePolicy.resumeChunkCount(counts, partial(1L, sourceParagraphs = 3, paragraphs = 7)) shouldBe 1
        TranslationCachePolicy.resumeChunkCount(counts, partial(1L, sourceParagraphs = 8)) shouldBe 3
        TranslationCachePolicy.resumeChunkCount(counts, partial(1L, sourceParagraphs = 0)) shouldBe 0
        TranslationCachePolicy.resumeChunkCount(counts, partial(null, paragraphs = 6)) shouldBe 2
    }

    @Test
    fun `a partial from another chunk size that ends mid chunk starts over`() {
        val counts = listOf(3, 3, 2)
        TranslationCachePolicy.resumeChunkCount(counts, partial(1L, sourceParagraphs = 4, paragraphs = 4)) shouldBe 0
        TranslationCachePolicy.resumeChunkCount(counts, partial(1L, sourceParagraphs = 20, paragraphs = 20)) shouldBe 0
    }

    @Test
    fun `partial html round trips without counting the title as a paragraph`() {
        val paragraphs = listOf("one", "two\nlines", "three")
        val html = TranslationHtmlUtils.buildPartialHtml(42L, 2, "Title", paragraphs)

        val parsed = TranslationHtmlUtils.parsePartialHtml(html)

        parsed.title shouldBe "Title"
        parsed.paragraphs shouldBe paragraphs
        parsed.sourceParagraphs shouldBe 2
        parsed.runId shouldBe 42L
    }

    @Test
    fun `an older partial drops its title before paragraphs are counted`() {
        val parsed = TranslationHtmlUtils.parsePartialHtml(
            TranslationHtmlUtils.buildTranslatedHtml("Title", listOf("one", "two")),
        )

        parsed.title shouldBe "Title"
        parsed.paragraphs shouldBe listOf("one", "two")
        parsed.sourceParagraphs shouldBe null
        parsed.runId shouldBe null
    }

    @Test
    fun `resume skips only chunks fully covered by the partial result`() {
        val counts = listOf(3, 3, 2)
        TranslationCachePolicy.resumeChunkCount(counts, 0) shouldBe 0
        TranslationCachePolicy.resumeChunkCount(counts, 2) shouldBe 0
        TranslationCachePolicy.resumeChunkCount(counts, 3) shouldBe 1
        TranslationCachePolicy.resumeChunkCount(counts, 5) shouldBe 1
        TranslationCachePolicy.resumeChunkCount(counts, 6) shouldBe 2
        TranslationCachePolicy.resumeChunkCount(counts, 8) shouldBe 3
        TranslationCachePolicy.resumeChunkCount(counts, 20) shouldBe 3
        TranslationCachePolicy.resumeChunkCount(emptyList(), 4) shouldBe 0
    }
}
