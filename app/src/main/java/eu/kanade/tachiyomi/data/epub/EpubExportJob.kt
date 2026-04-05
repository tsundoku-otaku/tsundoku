package eu.kanade.tachiyomi.data.epub

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.core.archive.EpubWriter
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubExportJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val sourceManager: SourceManager = Injekt.get()
    private val mangaRepository: MangaRepository = Injekt.get()
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get()
    private val downloadManager: DownloadManager = Injekt.get()
    private val downloadProvider: DownloadProvider = Injekt.get()
    private val networkHelper: NetworkHelper = Injekt.get()
    private val translatedChapterRepository: TranslatedChapterRepository = Injekt.get()
    private val downloadPreferences: DownloadPreferences = Injekt.get()

    private val notificationBuilder = context.notificationBuilder(Notifications.CHANNEL_EPUB_EXPORT) {
        setSmallIcon(android.R.drawable.ic_menu_save)
        setContentTitle("EPUB Export")
        setContentText("Starting...")
        setOngoing(true)
        setOnlyAlertOnce(true)
    }

    override suspend fun doWork(): Result {
        val mangaIds = inputData.getLongArray(KEY_MANGA_IDS)?.toList() ?: return Result.failure()
        val uriString = inputData.getString(KEY_OUTPUT_URI) ?: return Result.failure()
        val downloadedOnly = inputData.getBoolean(KEY_DOWNLOADED_ONLY, false)
        val translationMode = TranslationMode.fromKey(
            inputData.getString(KEY_TRANSLATION_MODE) ?: TranslationMode.ORIGINAL.key,
        )
        val includeChapterCount = inputData.getBoolean(KEY_INCLUDE_CHAPTER_COUNT, false)
        val includeChapterRange = inputData.getBoolean(KEY_INCLUDE_CHAPTER_RANGE, false)
        val includeStatus = inputData.getBoolean(KEY_INCLUDE_STATUS, false)

        logcat(LogPriority.INFO) {
            "EPUB Export starting: ${mangaIds.size} novels, downloadedOnly=$downloadedOnly, translationMode=$translationMode"
        }

        try {
            setForegroundSafely()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to set foreground service" }
        }

        return withIOContext {
            try {
                performExport(
                    mangaIds = mangaIds,
                    outputUri = Uri.parse(uriString),
                    downloadedOnly = downloadedOnly,
                    translationMode = translationMode,
                    includeChapterCount = includeChapterCount,
                    includeChapterRange = includeChapterRange,
                    includeStatus = includeStatus,
                )
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    Result.success()
                } else {
                    logcat(LogPriority.ERROR, e) { "EPUB export failed" }
                    showErrorNotification(e.message ?: "Unknown error")
                    Result.failure()
                }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_EPUB_EXPORT_PROGRESS,
            notificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private suspend fun performExport(
        mangaIds: List<Long>,
        outputUri: Uri,
        downloadedOnly: Boolean,
        translationMode: TranslationMode,
        includeChapterCount: Boolean,
        includeChapterRange: Boolean,
        includeStatus: Boolean,
    ) {
        logcat(LogPriority.INFO) {
            "performExport called with ${mangaIds.size} manga IDs, outputUri=$outputUri, translationMode=$translationMode"
        }

        val mangaList = mangaIds.mapNotNull { mangaRepository.getMangaById(it) }
        if (mangaList.isEmpty()) {
            logcat(LogPriority.ERROR) { "No manga found for IDs: $mangaIds" }
            showErrorNotification("No novels found to export")
            return
        }

        logcat(LogPriority.INFO) { "Found ${mangaList.size} manga to export" }

        val tempDir = File(context.cacheDir, "epub_export_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        var successCount = 0
        var skippedCount = 0
        val totalCount = mangaList.size

        try {
            for ((index, manga) in mangaList.withIndex()) {
                updateProgress(index + 1, totalCount, manga.title)

                try {
                    val source = sourceManager.get(manga.source)
                    if (source == null || !source.isNovelSource()) {
                        logcat(LogPriority.WARN) { "${manga.title}: Not a novel source" }
                        skippedCount++
                        continue
                    }

                    val chapters = getChaptersByMangaId.await(manga.id)
                        .sortedBy { it.chapterNumber }

                    if (chapters.isEmpty()) {
                        logcat(LogPriority.WARN) { "${manga.title}: No chapters found" }
                        skippedCount++
                        continue
                    }

                    // ── Collect content per chapter ──────────────────────
                    data class ChapterContent(
                        val name: String,
                        val chapterNumber: Double,
                        val order: Int,
                        val originalContent: String?,
                        val translatedContent: String?,
                    )

                    // Identify which chapters have translations
                    val translatedChapterIds = if (translationMode != TranslationMode.ORIGINAL) {
                        translatedChapterRepository.getTranslatedChapterIds(chapters.map { it.id })
                    } else {
                        emptySet()
                    }

                    val chapterContents = mutableListOf<ChapterContent>()

                    for ((chapterIndex, chapter) in chapters.withIndex()) {
                        val isDownloaded = downloadManager.isChapterDownloaded(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                            manga.title,
                            manga.source,
                        )

                        val hasTranslation = chapter.id in translatedChapterIds

                        if (chapterIndex == 0) {
                            logcat(LogPriority.DEBUG) {
                                "${manga.title} ch ${chapter.name}: isDownloaded=$isDownloaded, hasTranslation=$hasTranslation"
                            }
                        }

                        // Skip undownloaded chapters if downloadedOnly and no translation available
                        if (downloadedOnly && !isDownloaded && !hasTranslation) {
                            if (chapterIndex < 3) {
                                logcat(LogPriority.DEBUG) {
                                    "${manga.title} ch ${chapter.name}: skipping - not downloaded and downloadedOnly=true"
                                }
                            }
                            continue
                        }

                        // ── Original content ──
                        var originalContent: String? = null
                        if (translationMode != TranslationMode.TRANSLATED && isDownloaded) {
                            originalContent = readOriginalContent(manga, chapter, source)
                        }

                        // ── Translated content ──
                        var translatedContent: String? = null
                        if (translationMode != TranslationMode.ORIGINAL && hasTranslation) {
                            try {
                                val translations = translatedChapterRepository.getAllTranslationsForChapter(chapter.id)
                                translatedContent = translations.firstOrNull()?.translatedContent
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) { "Failed to get translation for chapter: ${chapter.name}" }
                            }
                        }

                        // For ORIGINAL mode -> need original; for TRANSLATED mode -> need translated;
                        // for BOTH -> gather whatever is available
                        val hasUsableContent = when (translationMode) {
                            TranslationMode.ORIGINAL -> originalContent != null && originalContent.isNotBlank()
                            TranslationMode.TRANSLATED -> translatedContent != null && translatedContent.isNotBlank()
                            TranslationMode.BOTH -> (originalContent != null && originalContent.isNotBlank()) ||
                                (translatedContent != null && translatedContent.isNotBlank())
                        }

                        if (hasUsableContent) {
                            chapterContents.add(
                                ChapterContent(
                                    name = chapter.name,
                                    chapterNumber = chapter.chapterNumber,
                                    order = chapterIndex,
                                    originalContent = originalContent?.takeIf { it.isNotBlank() },
                                    translatedContent = translatedContent?.takeIf { it.isNotBlank() },
                                ),
                            )
                        }
                    }

                    if (chapterContents.isEmpty()) {
                        logcat(LogPriority.WARN) {
                            "${manga.title}: No chapters could be exported (chapters=${chapters.size}, downloadedOnly=$downloadedOnly, translationMode=$translationMode)"
                        }
                        skippedCount++
                        continue
                    }

                    logcat(LogPriority.INFO) {
                        "${manga.title}: Exporting ${chapterContents.size} chapters (mode=$translationMode)"
                    }

                    // Get cover image
                    val coverImage = try {
                        manga.thumbnailUrl?.let { url ->
                            val request = okhttp3.Request.Builder().url(url).build()
                            networkHelper.client.newCall(request).execute().use { it.body.bytes() }
                        }
                    } catch (e: Exception) {
                        null
                    }

                    // Create EPUB metadata
                    val metadata = EpubWriter.Metadata(
                        title = manga.title,
                        author = manga.author,
                        description = manga.description,
                        language = source.lang.ifBlank { "en" },
                        genres = manga.genre ?: emptyList(),
                        publisher = source.name,
                    )

                    // ── Build EPUB file(s) based on translation mode ──
                    fun buildFilename(suffix: String? = null): String {
                        val filenameBuilder = StringBuilder(sanitizeFilename(manga.title))
                        if (includeChapterCount) {
                            filenameBuilder.append(" [${chapterContents.size}ch]")
                        }
                        if (includeChapterRange) {
                            val firstChapterNum = chapterContents.minOf { it.chapterNumber }
                            val lastChapterNum = chapterContents.maxOf { it.chapterNumber }
                            val firstCh = if (firstChapterNum == firstChapterNum.toLong().toDouble()) {
                                firstChapterNum.toLong().toString()
                            } else {
                                firstChapterNum.toString()
                            }
                            val lastCh = if (lastChapterNum == lastChapterNum.toLong().toDouble()) {
                                lastChapterNum.toLong().toString()
                            } else {
                                lastChapterNum.toString()
                            }
                            if (firstCh != lastCh) {
                                filenameBuilder.append(" [ch$firstCh-$lastCh]")
                            } else {
                                filenameBuilder.append(" [ch$firstCh]")
                            }
                        }
                        if (includeStatus) {
                            val statusStr = when (manga.status) {
                                SManga.ONGOING.toLong() -> "Ongoing"
                                SManga.COMPLETED.toLong() -> "Completed"
                                SManga.LICENSED.toLong() -> "Licensed"
                                SManga.PUBLISHING_FINISHED.toLong() -> "Finished"
                                SManga.CANCELLED.toLong() -> "Cancelled"
                                SManga.ON_HIATUS.toLong() -> "Hiatus"
                                else -> null
                            }
                            statusStr?.let { filenameBuilder.append(" [$it]") }
                        }
                        suffix?.let { filenameBuilder.append(" [$it]") }
                        filenameBuilder.append(".epub")
                        return filenameBuilder.toString()
                    }

                    fun writeEpub(
                        filename: String,
                        epubMetadata: EpubWriter.Metadata,
                        chapters: List<EpubWriter.Chapter>,
                        cover: ByteArray?,
                    ) {
                        val tempFile = File(tempDir, filename)
                        val deflateLevel = downloadPreferences.epubCompressionLevel.get()
                        tempFile.outputStream().use { outputStream ->
                            EpubWriter(deflateLevel).write(
                                outputStream = outputStream,
                                metadata = epubMetadata,
                                chapters = chapters,
                                coverImage = cover,
                            )
                        }
                    }

                    when (translationMode) {
                        TranslationMode.ORIGINAL -> {
                            val epubChapters = chapterContents.mapNotNull { ch ->
                                ch.originalContent?.let {
                                    EpubWriter.Chapter(title = ch.name, content = it, order = ch.order)
                                }
                            }
                            if (epubChapters.isNotEmpty()) {
                                writeEpub(buildFilename(), metadata, epubChapters, coverImage)
                            }
                        }
                        TranslationMode.TRANSLATED -> {
                            val epubChapters = chapterContents.mapNotNull { ch ->
                                ch.translatedContent?.let {
                                    EpubWriter.Chapter(title = ch.name, content = it, order = ch.order)
                                }
                            }
                            if (epubChapters.isNotEmpty()) {
                                writeEpub(buildFilename(), metadata, epubChapters, coverImage)
                            }
                        }
                        TranslationMode.BOTH -> {
                            // Original EPUB
                            val originalChapters = chapterContents.mapNotNull { ch ->
                                ch.originalContent?.let {
                                    EpubWriter.Chapter(title = ch.name, content = it, order = ch.order)
                                }
                            }
                            if (originalChapters.isNotEmpty()) {
                                writeEpub(buildFilename("Original"), metadata, originalChapters, coverImage)
                            }

                            // Translated EPUB
                            val translatedChapters = chapterContents.mapNotNull { ch ->
                                ch.translatedContent?.let {
                                    EpubWriter.Chapter(title = ch.name, content = it, order = ch.order)
                                }
                            }
                            if (translatedChapters.isNotEmpty()) {
                                val translatedMetadata = metadata.copy(
                                    title = "${manga.title} [Translated]",
                                )
                                writeEpub(
                                    buildFilename("Translated"),
                                    translatedMetadata,
                                    translatedChapters,
                                    coverImage,
                                )
                            }
                        }
                    }

                    logcat(LogPriority.INFO) { "Exported ${manga.title}: ${chapterContents.size} chapters" }
                    successCount++
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to export ${manga.title}" }
                    skippedCount++
                }
            }

            // Write to output
            val tempFiles = tempDir.listFiles()?.filter { it.name.endsWith(".epub") } ?: emptyList()

            logcat(LogPriority.INFO) {
                "Export complete: ${tempFiles.size} EPUB files in temp dir, successCount=$successCount, skippedCount=$skippedCount"
            }

            if (tempFiles.isEmpty()) {
                logcat(LogPriority.ERROR) { "No EPUB files were created in temp dir" }
                showErrorNotification("No novels could be exported. Check that chapters are downloaded.")
                return
            }

            if (tempFiles.size > 1 || totalCount > 1) {
                // Create ZIP for multiple files (multiple novels OR both-mode producing two files)
                logcat(LogPriority.INFO) { "Writing ${tempFiles.size} EPUBs to ZIP at $outputUri" }
                context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        tempFiles.forEach { file ->
                            val entry = ZipEntry(file.name)
                            zipOut.putNextEntry(entry)
                            file.inputStream().use { input ->
                                input.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }
                    }
                } ?: run {
                    logcat(LogPriority.ERROR) { "Failed to open output stream for URI: $outputUri" }
                    showErrorNotification("Failed to write to output file")
                    return
                }
            } else if (tempFiles.isNotEmpty()) {
                // Single file, copy directly
                logcat(LogPriority.INFO) {
                    "Writing single EPUB to $outputUri (size=${tempFiles.first().length()} bytes)"
                }
                context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                    tempFiles.first().inputStream().use { input ->
                        input.copyTo(outputStream)
                    }
                } ?: run {
                    logcat(LogPriority.ERROR) { "Failed to open output stream for URI: $outputUri" }
                    showErrorNotification("Failed to write to output file")
                    return
                }
            }

            showCompleteNotification(successCount, skippedCount)
        } finally {
            // Cleanup
            tempDir.deleteRecursively()
        }
    }

    /**
     * Read original (source) content for a chapter from the download directory or CBZ archive.
     * Delegates to [ChapterContentReader] (DRY fix 2.2).
     */
    private fun readOriginalContent(
        manga: Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.Source,
    ): String? {
        return try {
            val reader = eu.kanade.tachiyomi.data.download.ChapterContentReader(context, downloadProvider)
            reader.readDownloadedContent(manga, chapter, source)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read downloaded chapter: ${chapter.name}" }
            null
        }
    }

    private fun updateProgress(current: Int, total: Int, title: String) {
        context.notify(
            Notifications.ID_EPUB_EXPORT_PROGRESS,
            notificationBuilder
                .setContentTitle("EPUB Export")
                .setContentText("Exporting $current/$total: $title")
                .setProgress(total, current, false)
                .build(),
        )
    }

    private fun showCompleteNotification(success: Int, skipped: Int) {
        context.cancelNotification(Notifications.ID_EPUB_EXPORT_PROGRESS)
        val message = buildString {
            append("Exported $success novels")
            if (skipped > 0) append(", $skipped skipped")
        }
        context.notify(
            Notifications.ID_EPUB_EXPORT_COMPLETE,
            context.notificationBuilder(Notifications.CHANNEL_EPUB_EXPORT) {
                setSmallIcon(android.R.drawable.ic_menu_save)
                setContentTitle("EPUB Export Complete")
                setContentText(message)
                setAutoCancel(true)
            }.build(),
        )
    }

    private fun showErrorNotification(error: String) {
        context.cancelNotification(Notifications.ID_EPUB_EXPORT_PROGRESS)
        context.notify(
            Notifications.ID_EPUB_EXPORT_COMPLETE,
            context.notificationBuilder(Notifications.CHANNEL_EPUB_EXPORT) {
                setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                setContentTitle("EPUB Export Failed")
                setContentText(error)
                setAutoCancel(true)
            }.build(),
        )
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(200)
    }

    companion object {
        private const val TAG = "EpubExportJob"
        private const val KEY_MANGA_IDS = "manga_ids"
        private const val KEY_OUTPUT_URI = "output_uri"
        private const val KEY_DOWNLOADED_ONLY = "downloaded_only"
        private const val KEY_TRANSLATION_MODE = "translation_mode"
        private const val KEY_INCLUDE_CHAPTER_COUNT = "include_chapter_count"
        private const val KEY_INCLUDE_CHAPTER_RANGE = "include_chapter_range"
        private const val KEY_INCLUDE_STATUS = "include_status"

        fun start(
            context: Context,
            mangaIds: List<Long>,
            outputUri: Uri,
            downloadedOnly: Boolean = false,
            translationMode: TranslationMode = TranslationMode.ORIGINAL,
            includeChapterCount: Boolean = false,
            includeChapterRange: Boolean = false,
            includeStatus: Boolean = false,
        ) {
            val data = workDataOf(
                KEY_MANGA_IDS to mangaIds.toLongArray(),
                KEY_OUTPUT_URI to outputUri.toString(),
                KEY_DOWNLOADED_ONLY to downloadedOnly,
                KEY_TRANSLATION_MODE to translationMode.key,
                KEY_INCLUDE_CHAPTER_COUNT to includeChapterCount,
                KEY_INCLUDE_CHAPTER_RANGE to includeChapterRange,
                KEY_INCLUDE_STATUS to includeStatus,
            )

            val request = OneTimeWorkRequestBuilder<EpubExportJob>()
                .addTag(TAG)
                .setInputData(data)
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
