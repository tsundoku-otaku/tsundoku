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

    @Test
    fun `unforced run reuses any stored segment`() {
        TranslationCachePolicy.canReuse(5L, forceRetranslate = false, taskCreatedAt = 100L) shouldBe true
        TranslationCachePolicy.canReuse(null, forceRetranslate = false, taskCreatedAt = 100L) shouldBe false
    }

    @Test
    fun `forced run reuses only what it wrote itself`() {
        TranslationCachePolicy.canReuse(99L, forceRetranslate = true, taskCreatedAt = 100L) shouldBe false
        TranslationCachePolicy.canReuse(100L, forceRetranslate = true, taskCreatedAt = 100L) shouldBe true
        TranslationCachePolicy.canReuse(null, forceRetranslate = true, taskCreatedAt = 100L) shouldBe false
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
