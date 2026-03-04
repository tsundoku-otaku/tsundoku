package tachiyomi.domain.translation.repository

import tachiyomi.domain.translation.model.TranslatedChapter

/**
 * Repository interface for managing translated chapters.
 * All storage is filesystem-based (no database table).
 */
interface TranslatedChapterRepository {

    /**
     * Get a translated chapter by chapter ID and target language.
     */
    suspend fun getTranslatedChapter(chapterId: Long, targetLanguage: String): TranslatedChapter?

    /**
     * Get all translations for a chapter.
     */
    suspend fun getAllTranslationsForChapter(chapterId: Long): List<TranslatedChapter>

    /**
     * Check if a chapter has a translation for a specific language.
     */
    suspend fun hasTranslation(chapterId: Long, targetLanguage: String): Boolean

    /**
     * Get set of chapter IDs that have any translation, from the given candidate IDs.
     */
    suspend fun getTranslatedChapterIds(chapterIds: Collection<Long>): Set<Long>

    /**
     * Get all distinct languages across the given chapter IDs.
     */
    suspend fun getTranslatedLanguagesForChapters(chapterIds: Collection<Long>): List<String>

    /**
     * Insert or update a translated chapter.
     */
    suspend fun upsertTranslation(translatedChapter: TranslatedChapter)

    /**
     * Delete a translation.
     */
    suspend fun deleteTranslation(chapterId: Long, targetLanguage: String)

    /**
     * Delete all translations.
     */
    suspend fun deleteAll()

    /**
     * Get all translations.
     */
    suspend fun getAll(): List<TranslatedChapter>

    /**
     * Delete all translations for a chapter.
     */
    suspend fun deleteAllForChapter(chapterId: Long)

    /**
     * Delete all translations for chapters belonging to a manga.
     */
    suspend fun deleteAllForChapters(chapterIds: Collection<Long>)

    /**
     * Get the total cache size in bytes.
     */
    suspend fun getCacheSize(): Long

    /**
     * Clear old cached translations.
     */
    suspend fun clearOldCache(olderThan: Long)

    /**
     * Clear temporary (.tmp) translation files.
     * Returns the number of bytes freed.
     */
    suspend fun clearTmpFiles(): Long

    /**
     * Insert or update a partial (.tmp) translation (for resume support).
     */
    suspend fun upsertTmpTranslation(translatedChapter: TranslatedChapter)

    /**
     * Get a partial (.tmp) translation if one exists.
     */
    suspend fun getTmpTranslation(chapterId: Long, targetLanguage: String): TranslatedChapter?

    /**
     * Check if a partial (.tmp) translation exists.
     */
    suspend fun hasTmpTranslation(chapterId: Long, targetLanguage: String): Boolean
}
