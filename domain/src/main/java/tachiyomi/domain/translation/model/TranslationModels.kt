package tachiyomi.domain.translation.model

/**
 * Represents a translated chapter stored on the filesystem.
 */
data class TranslatedChapter(
    val chapterId: Long,
    val mangaId: Long,
    val targetLanguage: String,
    val engineId: String,
    val translatedContent: String,
    val dateTranslated: Long = System.currentTimeMillis(),
)

/**
 * Represents a translation task in the queue.
 */
data class TranslationTask(
    val id: Long = 0,
    val chapterId: Long,
    val mangaId: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val engineId: Long,
    val priority: Int = 0, // Higher = more priority (manually read chapters get higher priority)
    val status: TranslationStatus = TranslationStatus.QUEUED,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val forceRetranslate: Boolean = false,
)

/**
 * Status of a translation task.
 */
enum class TranslationStatus {
    QUEUED,
    DOWNLOADING,
    TRANSLATING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * Progress information for translation operations.
 */
data class TranslationProgress(
    val totalChapters: Int,
    val completedChapters: Int,
    val currentChapterName: String?,
    val currentChapterProgress: Float, // 0.0 to 1.0
    val isRunning: Boolean,
    val isPaused: Boolean,
    val currentChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val isCancelling: Boolean = false,
) {
    val overallProgress: Float
        get() = if (totalChapters > 0) {
            (completedChapters + currentChapterProgress) / totalChapters
        } else {
            0f
        }
}

/**
 * Summary of a translation for display in chapter list.
 */
data class TranslationInfo(
    val chapterId: Long,
    val targetLanguage: String,
    val engineId: String,
    val dateTranslated: Long,
)

/**
 * Available languages for a manga's translations.
 */
data class TranslatedLanguages(
    val mangaId: Long,
    val languages: List<String>,
)

/**
 * Controls which content variant is used for EPUB export.
 */
enum class TranslationMode(val key: String) {
    /** Export only the original (source) content. */
    ORIGINAL("original"),
    /** Export only translated content; skip chapters without a translation. */
    TRANSLATED("translated"),
    /** Export two separate EPUB files — one original, one translated. */
    BOTH("both"),
    ;

    companion object {
        fun fromKey(key: String): TranslationMode =
            entries.firstOrNull { it.key == key } ?: ORIGINAL
    }
}
