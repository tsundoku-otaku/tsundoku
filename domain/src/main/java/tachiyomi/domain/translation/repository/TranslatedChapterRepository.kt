package tachiyomi.domain.translation.repository

import tachiyomi.domain.translation.model.ChapterRef
import tachiyomi.domain.translation.model.TranslatedChapter
import tachiyomi.domain.translation.model.TranslationLocator

/**
 * Repository interface for managing translated chapters.
 *
 * All storage is filesystem-based (no database table), keyed by a portable
 * [TranslationLocator] (source name / novel title / chapter) plus a language code,
 * so translation files can be moved between installs.
 */
interface TranslatedChapterRepository {

    /**
     * Get a translated chapter for a locator and target language.
     */
    suspend fun getTranslatedChapter(locator: TranslationLocator, targetLanguage: String): TranslatedChapter?

    /**
     * Get all translations (every language) for a chapter locator.
     */
    suspend fun getAllTranslationsForChapter(locator: TranslationLocator): List<TranslatedChapter>

    /**
     * Batched form of [getAllTranslationsForChapter] for a whole novel: resolves the novel
     * directory and lists each language dir once, then maps every candidate chapter to its
     * translations (all languages) keyed by [ChapterRef.id]. Avoids the per-chapter directory
     * scan of calling [getAllTranslationsForChapter] in a loop.
     */
    suspend fun getAllTranslationsForNovel(
        sourceName: String,
        novelTitle: String,
        chapters: Collection<ChapterRef>,
    ): Map<Long, List<TranslatedChapter>>

    /**
     * Check if a chapter has a translation for a specific language.
     */
    suspend fun hasTranslation(locator: TranslationLocator, targetLanguage: String): Boolean

    /**
     * From the given candidate chapters (of one manga), return the ids that have a
     * translation for [targetLanguage].
     */
    suspend fun filterTranslatedChapters(
        sourceName: String,
        novelTitle: String,
        targetLanguage: String,
        chapters: Collection<ChapterRef>,
    ): Set<Long>

    /**
     * Insert or update a translated chapter.
     */
    suspend fun upsertTranslation(locator: TranslationLocator, translatedChapter: TranslatedChapter)

    /**
     * Delete a translation for a specific language.
     */
    suspend fun deleteTranslation(locator: TranslationLocator, targetLanguage: String)

    /**
     * Delete all translations (every language) for a single chapter.
     */
    suspend fun deleteAllForChapter(locator: TranslationLocator)

    /**
     * Delete all translations for the given chapters of a manga.
     */
    suspend fun deleteAllForChapters(
        sourceName: String,
        novelTitle: String,
        chapters: Collection<ChapterRef>,
    )

    /**
     * Delete every translation for a whole manga (all chapters, all languages).
     */
    suspend fun deleteAllForManga(sourceName: String, novelTitle: String)

    /**
     * Move a novel's translations when its title changes (translations are keyed by title on disk).
     */
    suspend fun renameNovel(sourceName: String, oldTitle: String, newTitle: String)

    /**
     * Move a chapter's translation files (every language) when a source renames the chapter,
     * mirroring DownloadManager.renameChapter for downloads.
     */
    suspend fun renameChapter(
        sourceName: String,
        novelTitle: String,
        oldChapterName: String,
        newChapterName: String,
        chapterUrl: String,
    )

    /**
     * Move a novel's translations to a different source and/or title (e.g. on migration).
     * Denies the move if a non-empty destination already exists.
     */
    suspend fun moveNovel(oldSourceName: String, oldTitle: String, newSourceName: String, newTitle: String)

    /**
     * Delete all translations.
     */
    suspend fun deleteAll()

    /**
     * Clear temporary (.tmp) translation files. Returns the number of bytes freed.
     */
    suspend fun clearTmpFiles(): Long

    /**
     * Insert or update a partial (.tmp) translation (for resume support).
     */
    suspend fun upsertTmpTranslation(locator: TranslationLocator, translatedChapter: TranslatedChapter)

    /**
     * Get a partial (.tmp) translation if one exists.
     */
    suspend fun getTmpTranslation(locator: TranslationLocator, targetLanguage: String): TranslatedChapter?
}
