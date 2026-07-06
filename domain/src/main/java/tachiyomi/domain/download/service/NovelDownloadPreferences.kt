package tachiyomi.domain.download.service

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Preferences for novel download throttling and rate limiting.
 * These settings help avoid getting rate-limited by novel sources.
 */
class NovelDownloadPreferences(
    private val preferenceStore: PreferenceStore,
) {
    /**
     * Enable per-request delay throttling for novel sources.
     * Applies once per outgoing HTTP request (via the app's shared network client),
     * regardless of which job (download, library update, mass import) triggered it.
     */
    fun enableRequestThrottling() = preferenceStore.getBoolean(
        "novel_request_throttling_enabled",
        true,
    )

    /**
     * Base delay between requests to the same source (in milliseconds)
     */
    fun requestDelay() = preferenceStore.getInt(
        "novel_request_delay_ms",
        3000, // Default 3 seconds
    )

    /**
     * Random jitter added on top of the base delay (in milliseconds).
     * Actual delay = requestDelay + random(0, requestJitter). This exists so requests
     * look like a human's natural pacing rather than a bot waiting an exact interval.
     */
    fun requestJitter() = preferenceStore.getInt(
        "novel_request_jitter_ms",
        1000, // Default 0-1 second random
    )

    /**
     * Skip source if details fetch failed X times consecutively
     * 0 implies disabled
     */
    fun skipSourceIfFailedXTimes() = preferenceStore.getInt(
        "novel_mass_import_skip_source_failures",
        0,
    )

    /**
     * Enable additional delay every 5 library updates for novel sources
     */
    fun enableUpdateStaggering() = preferenceStore.getBoolean(
        "novel_update_staggering_enabled",
        false,
    )

    /**
     * Maximum parallel library updates for novel sources (per extension)
     */
    fun parallelNovelUpdates() = preferenceStore.getInt(
        "novel_parallel_updates",
        2, // Default to 2 concurrent novel sources
    )

    /**
     * Number of concurrent mass imports
     */
    fun parallelMassImport() = preferenceStore.getInt(
        "novel_parallel_mass_import",
        1, // Default 1
    )

    /**
     * When multiple files are picked for mass import, import each file as its own queue/batch
     * instead of concatenating them into a single batch.
     */
    fun massImportSeparateFilePerBatch() = preferenceStore.getBoolean(
        "novel_mass_import_separate_file_per_batch",
        false,
    )

    /**
     * Split the imported URLs by host so each domain becomes its own queue/batch. Streamed to
     * per-host temp files with a bounded number of open writers, so large files don't OOM.
     */
    fun massImportSplitByDomain() = preferenceStore.getBoolean(
        "novel_mass_import_split_by_domain",
        false,
    )

    /**
     * Stored source-specific overrides as JSON string
     * Format: Map<sourceId: Long, SourceOverride>
     *
     * Uses a new key (v2) because the shape of [SourceOverride] changed from four
     * per-job delay fields to a single delay+jitter pair, and decoding isn't configured
     * to tolerate unknown/missing fields from the old format.
     */
    fun sourceOverrides() = preferenceStore.getString(
        "novel_source_overrides_v2",
        "{}",
    )

    /**
     * Maximum parallel downloads for novels (separate from manga)
     */
    fun parallelNovelDownloads() = preferenceStore.getInt(
        "novel_parallel_downloads",
        1, // Default to 1 for rate limiting
    )

    /**
     * Download images from chapter HTML and embed them as base64
     */
    fun downloadChapterImages() = preferenceStore.getBoolean(
        "novel_download_chapter_images",
        true,
    )

    /**
     * Maximum image size in KB before compression (0 = no limit)
     */
    fun maxImageSizeKb() = preferenceStore.getInt(
        "novel_max_image_size_kb",
        500, // Default 500KB
    )

    /**
     * Image compression quality (1-100)
     */
    fun imageCompressionQuality() = preferenceStore.getInt(
        "novel_image_compression_quality",
        80,
    )

    /**
     * Resume download queue when new chapters are added while paused
     */
    fun resumeQueueOnNewChapters() = preferenceStore.getBoolean(
        "novel_resume_queue_on_new_chapters",
        false,
    )

    /**
     * ZIP compression level for novel chapter archives (.zip format).
     * Novels are saved as .zip files; older downloads may be in .cbz format which is still supported.
     * 0 = store (no compression), 1-9 = deflate levels.
     */
    fun zipCompressionLevel() = preferenceStore.getInt(
        "novel_zip_compression_level",
        0, // Default to store for backwards compatibility
    )

    /**
     * Get source override for a specific source ID
     */
    fun getSourceOverride(sourceId: Long): SourceOverride? {
        return try {
            val json = sourceOverrides().get()
            if (json.isEmpty() || json == "{}") return null

            val overrides = kotlinx.serialization.json.Json.decodeFromString<Map<String, SourceOverride>>(json)
            overrides[sourceId.toString()]
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Set source override for a specific source
     */
    fun setSourceOverride(override: SourceOverride) {
        try {
            val currentJson = sourceOverrides().get()
            val overrides = if (currentJson.isEmpty() || currentJson == "{}") {
                mutableMapOf()
            } else {
                kotlinx.serialization.json.Json.decodeFromString<MutableMap<String, SourceOverride>>(currentJson)
            }

            overrides[override.sourceId.toString()] = override
            val newJson = kotlinx.serialization.json.Json.encodeToString(overrides)
            sourceOverrides().set(newJson)
        } catch (_: Exception) {
            // Log error
        }
    }

    /**
     * Remove source override
     */
    fun removeSourceOverride(sourceId: Long) {
        try {
            val currentJson = sourceOverrides().get()
            if (currentJson.isEmpty() || currentJson == "{}") return

            val overrides = kotlinx.serialization.json.Json.decodeFromString<MutableMap<String, SourceOverride>>(
                currentJson,
            )
            overrides.remove(sourceId.toString())

            val newJson = if (overrides.isEmpty()) {
                "{}"
            } else {
                kotlinx.serialization.json.Json.encodeToString(overrides)
            }
            sourceOverrides().set(newJson)
        } catch (_: Exception) {
            // Log error
        }
    }

    /**
     * Get all source overrides
     */
    fun getAllSourceOverrides(): List<SourceOverride> {
        return try {
            val json = sourceOverrides().get()
            if (json.isEmpty() || json == "{}") return emptyList()

            val overrides = kotlinx.serialization.json.Json.decodeFromString<Map<String, SourceOverride>>(json)
            overrides.values.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        /**
         * Represents a source-specific override for throttling settings
         */
        @kotlinx.serialization.Serializable
        data class SourceOverride(
            val sourceId: Long,
            val delayMillis: Int? = null,
            val jitterMillis: Int? = null,
            val enabled: Boolean = true,
        )
    }
}
