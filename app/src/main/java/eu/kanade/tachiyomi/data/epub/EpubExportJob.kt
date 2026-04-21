package eu.kanade.tachiyomi.data.epub

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.fetchNovelPageText
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.util.epub.EpubExportNaming
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.archive.EpubWriter
import mihon.core.archive.epubReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import tachiyomi.source.local.io.LocalNovelSourceFileSystem
import tachiyomi.source.local.LocalNovelSource
import tachiyomi.source.local.isLocal
import tachiyomi.source.local.isLocalNovel
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URLDecoder
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
    private val localNovelFileSystem: LocalNovelSourceFileSystem = Injekt.get()

    private val notificationBuilder = context.notificationBuilder(Notifications.CHANNEL_EPUB_EXPORT) {
        setSmallIcon(android.R.drawable.ic_menu_save)
        setContentTitle(context.stringResource(TDMR.strings.epub_export_job_title))
        setContentText(context.stringResource(TDMR.strings.epub_export_job_starting))
        setProgress(0, 0, true)
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
        val joinVolumes = inputData.getBoolean(KEY_JOIN_VOLUMES, true)
        val includeVolumeNumber = inputData.getBoolean(KEY_INCLUDE_VOLUME_NUMBER, false)

        logcat(LogPriority.INFO) {
            "EPUB Export starting: ${mangaIds.size} novels, downloadedOnly=$downloadedOnly, translationMode=$translationMode, joinVolumes=$joinVolumes, includeVolumeNumber=$includeVolumeNumber"
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
                    joinVolumes = joinVolumes,
                    includeVolumeNumber = includeVolumeNumber,
                )
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    Result.success()
                } else {
                    logcat(LogPriority.ERROR, e) { "EPUB export failed" }
                    showErrorNotification(
                        e.message ?: context.stringResource(TDMR.strings.epub_export_job_error_unknown),
                    )
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
        joinVolumes: Boolean,
        includeVolumeNumber: Boolean,
    ) {
        logcat(LogPriority.INFO) {
            "performExport called with ${mangaIds.size} manga IDs, outputUri=$outputUri, translationMode=$translationMode, joinVolumes=$joinVolumes"
        }

        val mangaList = mangaIds.mapNotNull { mangaRepository.getMangaById(it) }
        if (mangaList.isEmpty()) {
            logcat(LogPriority.ERROR) { "No manga found for IDs: $mangaIds" }
            showErrorNotification(context.stringResource(TDMR.strings.epub_export_job_error_no_novels))
            return
        }

        logcat(LogPriority.INFO) { "Found ${mangaList.size} manga to export" }

        val tempDir = File(context.cacheDir, "epub_export_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        var successCount = 0
        var skippedCount = 0
        val totalCount = mangaList.size
        val deflateLevel = downloadPreferences.epubCompressionLevel.get()

        try {
            for ((index, manga) in mangaList.withIndex()) {
                updateProgress(index + 1, totalCount, manga.title)

                try {
                    val source = resolveExportSource(manga)
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

                    // Identify which chapters have translations
                    val translatedChapterIds = if (translationMode != TranslationMode.ORIGINAL) {
                        translatedChapterRepository.getTranslatedChapterIds(chapters.map { it.id })
                    } else {
                        emptySet()
                    }

                    val chapterContents = mutableListOf<ChapterContent>()
                    val isLocalSource = source.isLocal() || manga.isLocalNovel()
                    val localEpubContexts = mutableMapOf<String, LocalEpubContext>()
                    val localVolumePositions = mutableMapOf<String, Int>()

                    try {
                        for ((chapterIndex, chapter) in chapters.withIndex()) {
                            val isDownloaded = if (isLocalSource) {
                                true
                            } else {
                                downloadManager.isChapterDownloaded(
                                    chapter.name,
                                    chapter.scanlator,
                                    chapter.url,
                                    manga.title,
                                    manga.source,
                                )
                            }

                            val hasTranslation = chapter.id in translatedChapterIds
                            val localReference = if (isLocalSource) parseLocalEpubReference(chapter.url) else null
                            val localContext = localReference?.let {
                                getOrCreateLocalEpubContext(it, localEpubContexts)
                            }
                            val localChapterHref = localReference?.chapterHref
                            val localOrderInVolume = localContext?.let { context ->
                                val current = localVolumePositions[context.key] ?: 0
                                localVolumePositions[context.key] = current + 1
                                current
                            }
                            val resolvedLocalChapterHref = if (localContext != null) {
                                findBestTocEntry(localContext.toc, localChapterHref, localOrderInVolume)?.href
                                    ?: localChapterHref
                            } else {
                                null
                            }

                            if (chapterIndex == 0) {
                                logcat(LogPriority.DEBUG) {
                                    "${manga.title} ch ${chapter.name}: isDownloaded=$isDownloaded, hasTranslation=$hasTranslation, localRef=${localReference?.key}"
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

                            // Original content
                            var originalContent: String? = null
                            if (translationMode != TranslationMode.TRANSLATED && isDownloaded) {
                                originalContent = if (localContext != null) {
                                    readLocalEpubContentForExport(
                                        localContext = localContext,
                                        chapterHref = resolvedLocalChapterHref,
                                        fallbackOrder = localOrderInVolume,
                                    )
                                        ?: readOriginalContent(manga, chapter, source)
                                } else {
                                    readOriginalContent(manga, chapter, source)
                                }
                            }

                            // Translated content
                            var translatedContent: String? = null
                            if (translationMode != TranslationMode.ORIGINAL && hasTranslation) {
                                try {
                                    val translations = translatedChapterRepository.getAllTranslationsForChapter(chapter.id)
                                    translatedContent = translations.firstOrNull()?.translatedContent
                                } catch (e: Exception) {
                                    logcat(LogPriority.WARN, e) {
                                        "Failed to get translation for chapter: ${chapter.name}"
                                    }
                                }
                            }

                            val keepLocalOriginalSlot = isLocalSource && isDownloaded

                            val hasUsableContent = when (translationMode) {
                                TranslationMode.ORIGINAL ->
                                    (originalContent != null && originalContent.isNotBlank()) || keepLocalOriginalSlot
                                TranslationMode.TRANSLATED -> translatedContent != null && translatedContent.isNotBlank()
                                TranslationMode.BOTH ->
                                    (originalContent != null && originalContent.isNotBlank()) ||
                                        keepLocalOriginalSlot ||
                                        (translatedContent != null && translatedContent.isNotBlank())
                            }

                            if (hasUsableContent) {
                                chapterContents.add(
                                    ChapterContent(
                                        name = resolveExportChapterTitle(
                                            chapterName = chapter.name,
                                            localContext = localContext,
                                            chapterHref = resolvedLocalChapterHref,
                                            fallbackOrder = localOrderInVolume,
                                        ),
                                        chapterNumber = chapter.chapterNumber,
                                        order = chapterIndex,
                                        originalContent = originalContent?.takeIf { it.isNotBlank() },
                                        translatedContent = translatedContent?.takeIf { it.isNotBlank() },
                                        volumeKey = localContext?.key,
                                        volumeLabel = localContext?.volumeLabel,
                                        volumeNumber = localContext?.volumeNumber,
                                        chapterHref = resolvedLocalChapterHref,
                                    ),
                                )
                            }
                        }

                        if (isLocalSource) {
                            rebuildLocalChapterContentsFromToc(chapterContents, localEpubContexts.values)
                        }
                    } finally {
                        localEpubContexts.values.forEach { localContext ->
                            runCatching { localContext.reader.close() }
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

                    val volumeUnits = buildVolumeUnits(chapterContents, joinVolumes)
                    if (volumeUnits.isEmpty()) {
                        skippedCount++
                        continue
                    }
                    val splitByVolume = !joinVolumes && volumeUnits.size > 1
                    val joinedVolumeSuffix = if (!splitByVolume) {
                        buildJoinedVolumeSuffix(chapterContents, includeVolumeNumber)
                    } else {
                        null
                    }

                    val statusLabel = EpubExportNaming.mangaStatusLabel(manga.status)

                    // Get cover image
                    val coverImage = readCoverImage(manga.thumbnailUrl)

                    // Create EPUB metadata
                    val metadata = EpubWriter.Metadata(
                        title = manga.title.trim().ifBlank { manga.url },
                        author = manga.author?.trim()?.takeIf { it.isNotBlank() }
                            ?: manga.artist?.trim()?.takeIf { it.isNotBlank() },
                        description = buildMetadataDescription(
                            baseDescription = manga.description,
                            statusLabel = statusLabel,
                            includeStatus = includeStatus,
                        ),
                        language = source.lang.ifBlank { "en" },
                        genres = normalizeGenres(manga.genre),
                        publisher = source.name.takeIf { it.isNotBlank() },
                    )

                    fun writeEpub(
                        filename: String,
                        epubMetadata: EpubWriter.Metadata,
                        outputChapters: List<EpubWriter.Chapter>,
                        cover: ByteArray?,
                    ) {
                        val tempFile = File(tempDir, filename)
                        tempFile.outputStream().use { outputStream ->
                            EpubWriter(deflateLevel).write(
                                outputStream = outputStream,
                                metadata = epubMetadata,
                                chapters = outputChapters,
                                coverImage = cover,
                            )
                        }
                    }

                    var writtenFilesForManga = 0

                    for ((unitIndex, volumeUnit) in volumeUnits.withIndex()) {
                        val volumeSuffix = if (splitByVolume) {
                            buildVolumeSuffix(
                                unit = volumeUnit,
                                includeVolumeNumber = includeVolumeNumber,
                                fallbackIndex = unitIndex + 1,
                            )
                        } else {
                            joinedVolumeSuffix
                        }

                        val metadataForUnit = if (!volumeUnit.label.isNullOrBlank() && splitByVolume) {
                            metadata.copy(title = "${metadata.title} - ${volumeUnit.label}")
                        } else {
                            metadata
                        }

                        when (translationMode) {
                            TranslationMode.ORIGINAL -> {
                                val originalSourceChapters = if (isLocalSource) {
                                    volumeUnit.chapters
                                } else {
                                    volumeUnit.chapters.filter { !it.originalContent.isNullOrBlank() }
                                }
                                val originalEpubChapters = originalSourceChapters.map { ch ->
                                    EpubWriter.Chapter(
                                        title = ch.name,
                                        content = ch.originalContent.orEmpty(),
                                        order = ch.order,
                                    )
                                }

                                if (originalEpubChapters.isNotEmpty()) {
                                    writeEpub(
                                        filename = buildExportFilename(
                                            mangaTitle = manga.title,
                                            chapterContents = originalSourceChapters,
                                            includeChapterCount = includeChapterCount,
                                            includeChapterRange = includeChapterRange,
                                            includeStatus = includeStatus,
                                            statusLabel = statusLabel,
                                            volumeSuffix = volumeSuffix,
                                        ),
                                        epubMetadata = metadataForUnit,
                                        outputChapters = originalEpubChapters,
                                        cover = coverImage,
                                    )
                                    writtenFilesForManga++
                                }
                            }

                            TranslationMode.TRANSLATED -> {
                                val translatedSourceChapters = volumeUnit.chapters
                                    .filter { !it.translatedContent.isNullOrBlank() }
                                val translatedEpubChapters = translatedSourceChapters.map { ch ->
                                    EpubWriter.Chapter(
                                        title = ch.name,
                                        content = ch.translatedContent.orEmpty(),
                                        order = ch.order,
                                    )
                                }

                                if (translatedEpubChapters.isNotEmpty()) {
                                    writeEpub(
                                        filename = buildExportFilename(
                                            mangaTitle = manga.title,
                                            chapterContents = translatedSourceChapters,
                                            includeChapterCount = includeChapterCount,
                                            includeChapterRange = includeChapterRange,
                                            includeStatus = includeStatus,
                                            statusLabel = statusLabel,
                                            volumeSuffix = volumeSuffix,
                                        ),
                                        epubMetadata = metadataForUnit,
                                        outputChapters = translatedEpubChapters,
                                        cover = coverImage,
                                    )
                                    writtenFilesForManga++
                                }
                            }

                            TranslationMode.BOTH -> {
                                val originalSourceChapters = if (isLocalSource) {
                                    volumeUnit.chapters
                                } else {
                                    volumeUnit.chapters.filter { !it.originalContent.isNullOrBlank() }
                                }
                                val originalEpubChapters = originalSourceChapters.map { ch ->
                                    EpubWriter.Chapter(
                                        title = ch.name,
                                        content = ch.originalContent.orEmpty(),
                                        order = ch.order,
                                    )
                                }
                                if (originalEpubChapters.isNotEmpty()) {
                                    writeEpub(
                                        filename = buildExportFilename(
                                            mangaTitle = manga.title,
                                            chapterContents = originalSourceChapters,
                                            includeChapterCount = includeChapterCount,
                                            includeChapterRange = includeChapterRange,
                                            includeStatus = includeStatus,
                                            statusLabel = statusLabel,
                                            volumeSuffix = volumeSuffix,
                                            translationSuffix = "Original",
                                        ),
                                        epubMetadata = metadataForUnit,
                                        outputChapters = originalEpubChapters,
                                        cover = coverImage,
                                    )
                                    writtenFilesForManga++
                                }

                                val translatedSourceChapters = volumeUnit.chapters
                                    .filter { !it.translatedContent.isNullOrBlank() }
                                val translatedEpubChapters = translatedSourceChapters.map { ch ->
                                    EpubWriter.Chapter(
                                        title = ch.name,
                                        content = ch.translatedContent.orEmpty(),
                                        order = ch.order,
                                    )
                                }
                                if (translatedEpubChapters.isNotEmpty()) {
                                    val translatedMetadata = metadataForUnit.copy(
                                        title = "${metadataForUnit.title} [Translated]",
                                    )
                                    writeEpub(
                                        filename = buildExportFilename(
                                            mangaTitle = manga.title,
                                            chapterContents = translatedSourceChapters,
                                            includeChapterCount = includeChapterCount,
                                            includeChapterRange = includeChapterRange,
                                            includeStatus = includeStatus,
                                            statusLabel = statusLabel,
                                            volumeSuffix = volumeSuffix,
                                            translationSuffix = "Translated",
                                        ),
                                        epubMetadata = translatedMetadata,
                                        outputChapters = translatedEpubChapters,
                                        cover = coverImage,
                                    )
                                    writtenFilesForManga++
                                }
                            }
                        }
                    }

                    if (writtenFilesForManga > 0) {
                        logcat(LogPriority.INFO) {
                            "Exported ${manga.title}: ${chapterContents.size} chapters into $writtenFilesForManga file(s)"
                        }
                        successCount++
                    } else {
                        logcat(LogPriority.WARN) {
                            "${manga.title}: No EPUB output generated after filtering"
                        }
                        skippedCount++
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to export ${manga.title}" }
                    skippedCount++
                }
            }

            // Write to output
            val tempFiles = tempDir.listFiles()
                ?.filter { it.name.endsWith(".epub") }
                ?.sortedBy { it.name }
                ?: emptyList()

            logcat(LogPriority.INFO) {
                "Export complete: ${tempFiles.size} EPUB files in temp dir, successCount=$successCount, skippedCount=$skippedCount"
            }

            if (tempFiles.isEmpty()) {
                logcat(LogPriority.ERROR) { "No EPUB files were created in temp dir" }
                showErrorNotification(context.stringResource(TDMR.strings.epub_export_job_error_no_files))
                return
            }

            val shouldZipOutput = tempFiles.size > 1 || totalCount > 1 || !joinVolumes
            if (shouldZipOutput) {
                // Create ZIP for multiple files (multiple novels OR both-mode producing two files)
                logcat(LogPriority.INFO) { "Writing ${tempFiles.size} EPUBs to ZIP at $outputUri" }
                context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        zipOut.setLevel(deflateLevel)
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
                    showErrorNotification(context.stringResource(TDMR.strings.epub_export_job_error_write_output))
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
                    showErrorNotification(context.stringResource(TDMR.strings.epub_export_job_error_write_output))
                    return
                }
            }

            showCompleteNotification(successCount, skippedCount)
        } finally {
            // Cleanup
            tempDir.deleteRecursively()
        }
    }

    private suspend fun resolveExportSource(manga: Manga): Source? {
        sourceManager.get(manga.source)?.let { return it }

        withTimeoutOrNull(10_000) {
            sourceManager.isInitialized.first { it }
        }

        sourceManager.get(manga.source)?.let { return it }

        if (manga.isLocalNovel()) {
            sourceManager.get(LocalNovelSource.ID)?.let { return it }
        }

        return null
    }

    private data class ChapterContent(
        val name: String,
        val chapterNumber: Double,
        val order: Int,
        val originalContent: String?,
        val translatedContent: String?,
        val volumeKey: String?,
        val volumeLabel: String?,
        val volumeNumber: Int?,
        val chapterHref: String?,
    )

    private data class LocalEpubReference(
        val key: String,
        val chapterHref: String?,
    )

    private data class LocalEpubContext(
        val key: String,
        val volumeLabel: String,
        val volumeNumber: Int?,
        val reader: mihon.core.archive.EpubReader,
        val toc: List<mihon.core.archive.EpubReader.EpubChapter>,
    )

    private data class VolumeUnit(
        val key: String,
        val label: String?,
        val number: Int?,
        val chapters: List<ChapterContent>,
    )

    private fun parseLocalEpubReference(chapterUrl: String): LocalEpubReference? {
        val filePath = chapterUrl.substringBefore("#").trim()
        val chapterHref = chapterUrl.substringAfter("#", "").trim().takeIf { it.isNotBlank() }

        val pathParts = filePath.split('/', limit = 2)
        if (pathParts.size != 2) return null

        val novelDirName = pathParts[0].trim()
        val epubFileName = pathParts[1].trim()
        if (
            novelDirName.isBlank() ||
            epubFileName.isBlank() ||
            !epubFileName.endsWith(".epub", ignoreCase = true)
        ) {
            return null
        }

        return LocalEpubReference(
            key = "$novelDirName/$epubFileName",
            chapterHref = chapterHref,
        )
    }

    private fun getOrCreateLocalEpubContext(
        reference: LocalEpubReference,
        cache: MutableMap<String, LocalEpubContext>,
    ): LocalEpubContext? {
        cache[reference.key]?.let { return it }

        val pathParts = reference.key.split('/', limit = 2)
        if (pathParts.size != 2) return null
        val novelDirName = pathParts[0]
        val epubFileName = pathParts[1]

        val epubFile = localNovelFileSystem.getBaseDirectory()
            ?.findFile(novelDirName)
            ?.findFile(epubFileName)
            ?: return null

        val reader = runCatching { epubFile.epubReader(context) }
            .onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to open local EPUB for export: ${reference.key}"
                }
            }
            .getOrNull() ?: return null

        val toc = runCatching { reader.getNormalizedTableOfContents() }
            .getOrElse {
                logcat(LogPriority.WARN, it) {
                    "Failed to parse TOC for local EPUB: ${reference.key}"
                }
                emptyList()
            }

        val volumeLabel = epubFileName.substringBeforeLast('.', epubFileName)
        val context = LocalEpubContext(
            key = reference.key,
            volumeLabel = volumeLabel,
            volumeNumber = extractVolumeNumber(volumeLabel),
            reader = reader,
            toc = toc,
        )

        cache[reference.key] = context
        return context
    }

    private fun readLocalEpubContentForExport(
        localContext: LocalEpubContext,
        chapterHref: String?,
        fallbackOrder: Int?,
    ): String? {
        val reader = localContext.reader
        val tocEntry = findBestTocEntry(localContext.toc, chapterHref, fallbackOrder)

        val hrefAttempts = buildList {
            chapterHref?.takeIf { it.isNotBlank() }?.let(::add)
            tocEntry?.href?.takeIf { it.isNotBlank() }?.let(::add)
            chapterHref?.substringBefore("#")?.takeIf { it.isNotBlank() }?.let(::add)
            tocEntry?.href?.substringBefore("#")?.takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()

        hrefAttempts.forEach { href ->
            val content = runCatching { reader.getChapterContentForExport(href) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            if (content != null) return content
        }

        if (chapterHref.isNullOrBlank()) {
            val fallbackContent = runCatching {
                val packagePath = reader.getPackageHref()
                val pages = reader.getPagesFromDocument(reader.getPackageDocument(packagePath))
                pages.mapNotNull { pageHref ->
                    reader.getChapterContentForExport(pageHref).takeIf { it.isNotBlank() }
                }.joinToString("\n\n")
            }.getOrDefault("")

            if (fallbackContent.isNotBlank()) {
                return fallbackContent
            }
        }

        return null
    }

    private fun resolveExportChapterTitle(
        chapterName: String,
        localContext: LocalEpubContext?,
        chapterHref: String?,
        fallbackOrder: Int?,
    ): String {
        val tocTitle = localContext
            ?.let { findBestTocEntry(it.toc, chapterHref, fallbackOrder)?.title }
            ?.trim()
            .orEmpty()

        if (tocTitle.isNotBlank()) {
            return tocTitle
        }

        if (localContext != null) {
            val strippedName = stripVolumePrefix(chapterName, localContext.volumeLabel)
            if (strippedName.isNotBlank()) {
                return strippedName
            }
        }

        return chapterName.trim().ifBlank { "Chapter" }
    }

    private fun findBestTocEntry(
        toc: List<mihon.core.archive.EpubReader.EpubChapter>,
        chapterHref: String?,
        fallbackOrder: Int?,
    ): mihon.core.archive.EpubReader.EpubChapter? {
        return findTocEntryForHref(toc, chapterHref)
            ?: fallbackOrder?.let { order -> toc.getOrNull(order) }
    }

    private fun stripVolumePrefix(chapterName: String, volumeLabel: String): String {
        val trimmedName = chapterName.trim()
        if (trimmedName.isBlank()) return trimmedName

        val prefixPattern = Regex("^${Regex.escape(volumeLabel)}\\s*-\\s*", RegexOption.IGNORE_CASE)
        val stripped = trimmedName.replace(prefixPattern, "")
        return stripped.ifBlank { trimmedName }
    }

    private fun findTocEntryForHref(
        toc: List<mihon.core.archive.EpubReader.EpubChapter>,
        chapterHref: String?,
    ): mihon.core.archive.EpubReader.EpubChapter? {
        if (chapterHref.isNullOrBlank() || toc.isEmpty()) return null

        val targetPath = normalizeHrefPath(chapterHref.substringBefore("#"))
        val targetFragment = normalizeHrefFragment(chapterHref.substringAfter("#", ""))

        return toc.firstOrNull { tocEntry ->
            val tocPath = normalizeHrefPath(tocEntry.href.substringBefore("#"))
            val tocFragment = normalizeHrefFragment(tocEntry.href.substringAfter("#", ""))
            tocPath == targetPath &&
                (targetFragment.isBlank() || tocFragment == targetFragment)
        } ?: toc.firstOrNull { tocEntry ->
            normalizeHrefPath(tocEntry.href.substringBefore("#")) == targetPath
        }
    }

    private fun normalizeHrefPath(path: String): String {
        val cleaned = decodeUrlComponent(path.trim())
            .replace('\\', '/')
            .removePrefix("./")
        return cleaned.lowercase()
    }

    private fun normalizeHrefFragment(fragment: String): String {
        return decodeUrlComponent(fragment.trim().removePrefix("#")).lowercase()
    }

    private fun decodeUrlComponent(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun extractVolumeNumber(volumeLabel: String): Int? {
        val patterns = listOf(
            Regex("(?i)\\bvol(?:ume)?\\.?\\s*(\\d{1,4})\\b"),
            Regex("(?i)\\bv\\.?\\s*(\\d{1,4})\\b"),
        )

        for (pattern in patterns) {
            val number = pattern.find(volumeLabel)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            if (number != null) {
                return number
            }
        }

        return null
    }

    private fun buildVolumeUnits(
        chapterContents: List<ChapterContent>,
        joinVolumes: Boolean,
    ): List<VolumeUnit> {
        val sortedContents = chapterContents.sortedBy { it.order }
        if (sortedContents.isEmpty()) return emptyList()

        if (joinVolumes) {
            val first = sortedContents.first()
            return listOf(
                VolumeUnit(
                    key = first.volumeKey ?: "all",
                    label = first.volumeLabel,
                    number = first.volumeNumber,
                    chapters = sortedContents,
                ),
            )
        }

        val groupedByVolume = linkedMapOf<String, MutableList<ChapterContent>>()
        sortedContents.forEach { chapter ->
            val key = chapter.volumeKey ?: "__default__"
            groupedByVolume.getOrPut(key) { mutableListOf() }.add(chapter)
        }

        if (groupedByVolume.size <= 1) {
            val first = sortedContents.first()
            return listOf(
                VolumeUnit(
                    key = first.volumeKey ?: "all",
                    label = first.volumeLabel,
                    number = first.volumeNumber,
                    chapters = sortedContents,
                ),
            )
        }

        return groupedByVolume.map { (key, groupedChapters) ->
            val first = groupedChapters.first()
            VolumeUnit(
                key = key,
                label = first.volumeLabel,
                number = first.volumeNumber,
                chapters = groupedChapters,
            )
        }
    }

    private fun buildVolumeSuffix(
        unit: VolumeUnit,
        includeVolumeNumber: Boolean,
        fallbackIndex: Int,
    ): String? {
        if (includeVolumeNumber && unit.number != null) {
            return "v${unit.number}"
        }

        return unit.label?.trim()?.takeIf { it.isNotBlank() } ?: "vol$fallbackIndex"
    }

    private fun buildJoinedVolumeSuffix(
        chapterContents: List<ChapterContent>,
        includeVolumeNumber: Boolean,
    ): String? {
        if (!includeVolumeNumber) {
            return null
        }

        val volumeCount = chapterContents
            .mapNotNull { it.volumeKey }
            .distinct()
            .size

        if (volumeCount > 1) {
            return "${volumeCount}vol"
        }

        val volumeNumbers = chapterContents
            .mapNotNull { it.volumeNumber }
            .distinct()
            .sorted()

        if (volumeNumbers.isNotEmpty()) {
            return if (volumeNumbers.size == 1) {
                "v${volumeNumbers.first()}"
            } else {
                "v${volumeNumbers.first()}-${volumeNumbers.last()}"
            }
        }

        return null
    }

    private fun rebuildLocalChapterContentsFromToc(
        chapterContents: MutableList<ChapterContent>,
        localContexts: Collection<LocalEpubContext>,
    ) {
        if (chapterContents.isEmpty() || localContexts.isEmpty()) return

        val localContextByKey = localContexts.associateBy { it.key }
        val localDbChapters = chapterContents
            .filter { chapter ->
                chapter.volumeKey != null &&
                    localContextByKey.containsKey(chapter.volumeKey)
            }
            .sortedBy { it.order }

        if (localDbChapters.isEmpty()) return

        val chaptersByHref = linkedMapOf<String, ChapterContent>()
        localDbChapters.forEach { chapter ->
            val volumeKey = chapter.volumeKey ?: return@forEach
            val chapterHref = chapter.chapterHref?.takeIf { it.isNotBlank() } ?: return@forEach
            val key = buildLocalChapterLookupKey(volumeKey, chapterHref)
            chaptersByHref.putIfAbsent(key, chapter)
        }

        val chaptersByVolumeOrder = localDbChapters
            .groupBy { it.volumeKey }
            .mapValues { (_, chapters) -> chapters.sortedBy { it.order } }

        val rebuilt = mutableListOf<ChapterContent>()
        var nextOrder = 0

        localContexts.forEach { localContext ->
            val toc = localContext.toc.sortedBy { it.order }

            if (toc.isEmpty()) {
                chaptersByVolumeOrder[localContext.key]
                    .orEmpty()
                    .forEach { chapter ->
                        rebuilt.add(chapter.copy(order = nextOrder++))
                    }
                return@forEach
            }

            toc.forEachIndexed { tocIndex, tocEntry ->
                val key = buildLocalChapterLookupKey(localContext.key, tocEntry.href)
                val existing = chaptersByHref[key]
                    ?: chaptersByVolumeOrder[localContext.key]?.getOrNull(tocIndex)

                val title = existing?.name
                    ?.takeIf { it.isNotBlank() }
                    ?: tocEntry.title.trim().ifBlank { "Chapter ${nextOrder + 1}" }

                val originalContent = existing?.originalContent
                    ?.takeIf { it.isNotBlank() }
                    ?: readLocalEpubContentForExport(localContext, tocEntry.href, tocEntry.order)
                        ?.takeIf { it.isNotBlank() }

                rebuilt.add(
                    ChapterContent(
                        name = title,
                        chapterNumber = existing?.chapterNumber ?: 0.0,
                        order = nextOrder++,
                        originalContent = originalContent,
                        translatedContent = existing?.translatedContent?.takeIf { it.isNotBlank() },
                        volumeKey = localContext.key,
                        volumeLabel = localContext.volumeLabel,
                        volumeNumber = localContext.volumeNumber,
                        chapterHref = tocEntry.href,
                    ),
                )
            }
        }

        if (rebuilt.isEmpty()) return

        val nonLocalChapters = chapterContents
            .filterNot { chapter ->
                chapter.volumeKey != null &&
                    localContextByKey.containsKey(chapter.volumeKey)
            }
            .sortedBy { it.order }
            .mapIndexed { index, chapter ->
                chapter.copy(order = rebuilt.size + index)
            }

        chapterContents.clear()
        chapterContents.addAll(rebuilt)
        chapterContents.addAll(nonLocalChapters)
    }

    private fun buildLocalChapterLookupKey(volumeKey: String, href: String): String {
        val path = normalizeHrefPath(href.substringBefore("#"))
        val fragment = normalizeHrefFragment(href.substringAfter("#", ""))
        return "$volumeKey|$path#$fragment"
    }

    private fun buildExportFilename(
        mangaTitle: String,
        chapterContents: List<ChapterContent>,
        includeChapterCount: Boolean,
        includeChapterRange: Boolean,
        includeStatus: Boolean,
        statusLabel: String?,
        volumeSuffix: String? = null,
        translationSuffix: String? = null,
    ): String {
        val filenameBuilder = StringBuilder(EpubExportNaming.sanitizeFilename(mangaTitle))
        EpubExportNaming.appendChapterCount(
            filenameBuilder = filenameBuilder,
            chapterCount = chapterContents.size,
            includeChapterCount = includeChapterCount,
        )
        EpubExportNaming.appendChapterRange(
            filenameBuilder = filenameBuilder,
            chapterNumbers = chapterContents.map { it.chapterNumber },
            includeChapterRange = includeChapterRange,
        )
        EpubExportNaming.appendStatusLabel(
            filenameBuilder = filenameBuilder,
            statusLabel = statusLabel,
            includeStatus = includeStatus,
        )

        volumeSuffix?.takeIf { it.isNotBlank() }?.let {
            filenameBuilder.append(" [${EpubExportNaming.sanitizeFilename(it)}]")
        }

        translationSuffix?.takeIf { it.isNotBlank() }?.let {
            filenameBuilder.append(" [$it]")
        }

        filenameBuilder.append(".epub")
        return filenameBuilder.toString()
    }

    /**
     * Read original (source) content for a chapter from the download directory or CBZ archive.
     * Delegates to [ChapterContentReader] (DRY fix 2.2).
     */
    private suspend fun readOriginalContent(
        manga: Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.Source,
    ): String? {
        return try {
            if (source.isLocal() || manga.isLocalNovel()) {
                return source.fetchNovelPageText(Page(0, chapter.url))
            }

            val reader = eu.kanade.tachiyomi.data.download.ChapterContentReader(context, downloadProvider)
            reader.readDownloadedContent(manga, chapter, source)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read downloaded chapter: ${chapter.name}" }
            null
        }
    }

    private fun normalizeGenres(genres: List<String>?): List<String> {
        val mergedGenres = linkedMapOf<String, String>()
        genres.orEmpty().forEach { genre ->
            val normalizedGenre = genre.trim()
            if (normalizedGenre.isNotBlank()) {
                mergedGenres.putIfAbsent(normalizedGenre.lowercase(), normalizedGenre)
            }
        }
        return mergedGenres.values.toList()
    }

    private fun buildMetadataDescription(
        baseDescription: String?,
        statusLabel: String?,
        includeStatus: Boolean,
    ): String? {
        val sections = mutableListOf<String>()

        baseDescription
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(sections::add)

        if (includeStatus && !statusLabel.isNullOrBlank()) {
            sections += "Status: $statusLabel"
        }

        return sections.joinToString("\n\n").takeIf { it.isNotBlank() }
    }


    private fun readCoverImage(thumbnailUrl: String?): ByteArray? {
        val url = thumbnailUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return runCatching {
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                val request = okhttp3.Request.Builder().url(url).build()
                networkHelper.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body.bytes()
                }
            } else {
                context.contentResolver.openInputStream(Uri.parse(url))?.use { stream -> stream.readBytes() }
            }
        }.getOrNull()
    }

    private fun updateProgress(current: Int, total: Int, title: String) {
        context.notify(
            Notifications.ID_EPUB_EXPORT_PROGRESS,
            notificationBuilder
                .setContentTitle(context.stringResource(TDMR.strings.epub_export_job_title))
                .setContentText(
                    context.stringResource(
                        TDMR.strings.epub_export_job_progress,
                        current,
                        total,
                        title,
                    ),
                )
                .setProgress(total, current, false)
                .build(),
        )
    }

    private fun showCompleteNotification(success: Int, skipped: Int) {
        context.cancelNotification(Notifications.ID_EPUB_EXPORT_PROGRESS)
        val message = if (skipped > 0) {
            context.stringResource(TDMR.strings.epub_export_job_complete_with_skipped, success, skipped)
        } else {
            context.stringResource(TDMR.strings.epub_export_job_complete, success)
        }
        context.notify(
            Notifications.ID_EPUB_EXPORT_COMPLETE,
            context.notificationBuilder(Notifications.CHANNEL_EPUB_EXPORT) {
                setSmallIcon(android.R.drawable.ic_menu_save)
                setContentTitle(context.stringResource(TDMR.strings.epub_export_job_complete_title))
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
                setContentTitle(context.stringResource(TDMR.strings.epub_export_job_failed_title))
                setContentText(error)
                setAutoCancel(true)
            }.build(),
        )
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
        private const val KEY_JOIN_VOLUMES = "join_volumes"
        private const val KEY_INCLUDE_VOLUME_NUMBER = "include_volume_number"

        fun start(
            context: Context,
            mangaIds: List<Long>,
            outputUri: Uri,
            downloadedOnly: Boolean = false,
            translationMode: TranslationMode = TranslationMode.ORIGINAL,
            includeChapterCount: Boolean = false,
            includeChapterRange: Boolean = false,
            includeStatus: Boolean = false,
            joinVolumes: Boolean = true,
            includeVolumeNumber: Boolean = false,
        ) {
            val data = workDataOf(
                KEY_MANGA_IDS to mangaIds.toLongArray(),
                KEY_OUTPUT_URI to outputUri.toString(),
                KEY_DOWNLOADED_ONLY to downloadedOnly,
                KEY_TRANSLATION_MODE to translationMode.key,
                KEY_INCLUDE_CHAPTER_COUNT to includeChapterCount,
                KEY_INCLUDE_CHAPTER_RANGE to includeChapterRange,
                KEY_INCLUDE_STATUS to includeStatus,
                KEY_JOIN_VOLUMES to joinVolumes,
                KEY_INCLUDE_VOLUME_NUMBER to includeVolumeNumber,
            )

            context.notify(
                Notifications.ID_EPUB_EXPORT_PROGRESS,
                context.notificationBuilder(Notifications.CHANNEL_EPUB_EXPORT) {
                    setSmallIcon(android.R.drawable.ic_menu_save)
                    setContentTitle(context.stringResource(TDMR.strings.epub_export_job_title))
                    setContentText(context.stringResource(TDMR.strings.epub_export_job_starting))
                    setProgress(0, 0, true)
                    setOngoing(true)
                    setOnlyAlertOnce(true)
                }.build(),
            )

            val request = OneTimeWorkRequestBuilder<EpubExportJob>()
                .addTag(TAG)
                .setInputData(data)
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
