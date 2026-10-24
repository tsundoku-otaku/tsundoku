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
     * Whether a queued run may reuse a stored translation or partial (.tmp) result
     * dated [dateTranslated]. A forced run only reuses what it wrote itself, so a retry after
     * a failure resumes instead of starting the whole book over.
     */
    fun canReuse(dateTranslated: Long?, forceRetranslate: Boolean, taskCreatedAt: Long): Boolean =
        dateTranslated != null && (!forceRetranslate || dateTranslated >= taskCreatedAt)

    /**
     * Number of leading chunks already covered by [resumedParagraphs] paragraphs of a partial
     * translation, given each chunk's paragraph count.
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
