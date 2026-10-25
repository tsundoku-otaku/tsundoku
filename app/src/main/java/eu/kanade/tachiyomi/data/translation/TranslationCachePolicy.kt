package eu.kanade.tachiyomi.data.translation

import tachiyomi.domain.translation.model.TranslatedChapter

/**
 * Pure decision helpers for the translation cache. Extracted out of [TranslationService]
 * so the policy is unit-testable without standing up the full service + its dependencies.
 *
 * Contract: once a chapter has any cached translation for a target language, neither the
 * auto-enqueue path nor the inline translate path may hit the translation API. Re-translation
 * is only allowed via the explicit `forceRetranslate=true` flow on [TranslationService.enqueue].
 */
object TranslationCachePolicy {

    /**
     * @return true when the inline [TranslationService.translateChapterContent] call must
     * return the cached translation instead of calling the API.
     */
    fun shouldServeCached(cached: TranslatedChapter?): Boolean = cached != null

    /**
     * @return true when the reader's auto-enqueue path should skip enqueuing a translation
     * job because one is already cached for the requested target language.
     */
    fun shouldSkipAutoEnqueue(cached: TranslatedChapter?): Boolean = cached != null

    /**
     * Whether a queued run may resume from [partial]. A forced run only resumes a partial its
     * own task wrote, so a chunk retry of the same task resumes instead of starting over, while
     * another run's output is never mixed in.
     */
    fun canReuse(partial: PartialTranslation, forceRetranslate: Boolean, taskCreatedAt: Long): Boolean =
        !forceRetranslate || partial.runId == taskCreatedAt

    /** Number of leading chunks [partial] already covers, given each chunk's paragraph count. */
    fun resumeChunkCount(chunkParagraphCounts: List<Int>, partial: PartialTranslation): Int =
        partial.chunksDone?.coerceIn(0, chunkParagraphCounts.size)
            ?: resumeChunkCount(chunkParagraphCounts, partial.paragraphs.size)

    /**
     * Number of leading chunks covered by [resumedParagraphs] paragraphs, for partials saved
     * before the chunk count was recorded.
     */
    fun resumeChunkCount(chunkParagraphCounts: List<Int>, resumedParagraphs: Int): Int {
        if (resumedParagraphs <= 0) return 0
        var covered = 0
        chunkParagraphCounts.forEachIndexed { i, count ->
            covered += count
            if (covered > resumedParagraphs) return i
        }
        return chunkParagraphCounts.size
    }
}
