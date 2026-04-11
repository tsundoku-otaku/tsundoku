package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.repository.StubSourceRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipInputStream
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Imports LNReader backup files (.zip) into Tsundoku.
 *
 * LNReader backup format:
 * ```
 * backup.zip
 * ├── Version.json
 * ├── Category.json
 * ├── NovelAndChapters/
 * │   ├── {novelId}.json  (each contains novel info + chapters array)
 * │   └── ...
 * └── Setting.json
 * ```
 */
class LNReaderBackupImporter(
    private val context: Context,
    private val notifier: BackupNotifier? = null,
    private val jsPluginManager: JsPluginManager = Injekt.get(),
    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(),
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
    private val stubSourceRepository: StubSourceRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
    private val errors = ConcurrentLinkedQueue<Pair<Date, String>>()

    @Serializable
    data class LNNovel(
        val id: Int = 0,
        val path: String = "",
        val pluginId: String = "",
        val name: String = "",
        val cover: String? = null,
        val summary: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val status: String? = null,
        val genres: String? = null,
        val inLibrary: Int = 0,
        val isLocal: Int = 0,
        val totalPages: Int = 0,
        val chapters: List<LNChapter> = emptyList(),
    )

    @Serializable
    data class LNChapter(
        val id: Int = 0,
        val novelId: Int = 0,
        val path: String = "",
        val name: String = "",
        val releaseTime: String? = null,
        val readTime: String? = null,
        val bookmark: Int = 0,
        val unread: Int = 1,
        val isDownloaded: Int = 0,
        val updatedTime: String? = null,
        val chapterNumber: Float? = null,
        val page: String = "",
        val progress: Int? = null,
        val position: Int? = null,
    )

    @Serializable
    data class LNCategory(
        val id: Int = 0,
        val name: String = "",
        val sort: Int = 0,
        val novelIds: List<Int> = emptyList(),
    )

    data class ImportResult(
        val novelCount: Int,
        val categoryCount: Int,
        val errorCount: Int,
        val logFile: File,
        val missingPlugins: List<String> = emptyList(),
        val skippedCount: Int = 0,
        val installedPluginCount: Int = 0,
        val restoredDownloadCount: Int = 0,
        val restoredCoverCount: Int = 0,
    )

    data class ImportOptions(
        val restoreNovels: Boolean = true,
        val restoreChapters: Boolean = true,
        val restoreCategories: Boolean = true,
        val restoreHistory: Boolean = true,
        val restorePlugins: Boolean = true,
        val restoreMissingPlugins: Boolean = false,
        val restoreDownloadedChapters: Boolean = true,
        val restoreCovers: Boolean = true,
    )

    /**
     * Import an LNReader backup from the given URI.
     */
    suspend fun import(uri: Uri, options: ImportOptions = ImportOptions()): ImportResult {
        errors.clear()
        var novelCount = 0
        var categoryCount = 0
        var skippedCount = 0
        var installedPluginCount = 0
        var restoredDownloadCount = 0
        var restoredCoverCount = 0
        val missingPlugins = mutableSetOf<String>()

        try {
            // Step 1: Extract data only
            val (novels, categories, pluginZipFile) = extractBackupData(uri)

            try {
                logcat(LogPriority.INFO) {
                    "LNReaderImport: Found ${novels.size} novels, ${categories.size} categories (options: $options)"
                }

                // Step 2: Restore categories FIRST
                val backupCategories = categories.map { lnCat ->
                    BackupCategory(
                        name = lnCat.name,
                        order = lnCat.sort.toLong(),
                        flags = 0,
                        contentType = Category.CONTENT_TYPE_NOVEL,
                    )
                }
                if (options.restoreCategories) {
                    categoriesRestorer(backupCategories)
                    categoryCount = categories.size
                    logcat(LogPriority.INFO) { "LNReaderImport: Restored $categoryCount categories" }
                }

                // Step 3: Install plugins
                if (options.restorePlugins && pluginZipFile != null) {
                    installedPluginCount = installPluginsFromDownloadZip(pluginZipFile)
                    // Wait for plugins to be processed by subscribing to the hot flow or just a short delay
                    // Since it's blocking in the plugin manager ideally, we can remove the delay
                }

                // Step 4: Build plugin mapping
                val pluginIdToSourceId = buildPluginMapping().toMutableMap()

                // Detect missing plugins and create stub sources if requested
                val requiredPlugins = novels.map { it.pluginId }.toSet()
                val actualMissingPlugins = requiredPlugins - pluginIdToSourceId.keys
                missingPlugins.addAll(actualMissingPlugins)

                if (actualMissingPlugins.isNotEmpty()) {
                    if (options.restoreMissingPlugins) {
                        // Create stub sources for missing plugins
                        actualMissingPlugins.forEach { pluginId ->
                            val stubSourceId = generateStubSourceId(pluginId)
                            try {
                                stubSourceRepository.upsertStubSource(
                                    id = stubSourceId,
                                    lang = "unknown",
                                    name = "$pluginId (Missing)",
                                    isNovel = true,
                                )
                                pluginIdToSourceId[pluginId] = stubSourceId
                                logcat(LogPriority.INFO) {
                                    "LNReaderImport: Created stub source for missing plugin '$pluginId' with ID $stubSourceId"
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR, e) {
                                    "LNReaderImport: Failed to create stub source for '$pluginId'"
                                }
                                errors.add(Date() to "Failed to create stub source for '$pluginId': ${e.message}")
                            }
                        }
                    } else {
                        logcat(LogPriority.WARN) { "LNReaderImport: Missing plugins: ${missingPlugins.joinToString()}" }
                        errors.add(
                            Date() to
                                "Missing plugins (install these extensions first or enable 'Restore with missing plugins'): ${missingPlugins.joinToString()}",
                        )
                    }
                }

                // Build category name -> novel IDs mapping for assignment
            // Build category name -> novel IDs mapping for assignment
            val novelIdToCategoryNames = mutableMapOf<Int, MutableList<String>>()
            categories.forEach { cat ->
                cat.novelIds.forEach { novelId ->
                    novelIdToCategoryNames.getOrPut(novelId) { mutableListOf() }.add(cat.name)
                }
            }

            // Pre-fetch existing manga mappings to avoid 2N DB lookups
            val mangaCache = mutableMapOf<Pair<String, Long>, tachiyomi.domain.manga.model.Manga>()
            novels.forEach { novel ->
                val sourceId = if (novel.isLocal != 0) 1L else pluginIdToSourceId[novel.pluginId]
                if (sourceId != null) {
                    val dbManga = getMangaByUrlAndSourceId.await(novel.path, sourceId)
                    if (dbManga != null) {
                        mangaCache[novel.path to sourceId] = dbManga
                    }
                }
            }

            // Convert and restore novels
            if (options.restoreNovels) {
                coroutineScope {
                    novels.forEachIndexed { index, novel ->
                        ensureActive()
                        try {
                            // Local novels get LocalNovelSource regardless of pluginId
                            val sourceId = if (novel.isLocal != 0) {
                                1L // LocalNovelSource.ID
                            } else {
                                pluginIdToSourceId[novel.pluginId]
                            }
                            if (sourceId == null) {
                                skippedCount++
                                errors.add(
                                    Date() to
                                        "${novel.name}: Unknown plugin '${novel.pluginId}' - skipping (enable 'Restore with missing plugins' to import as stub)",
                                )
                                return@forEachIndexed
                            }

                            notifier?.showRestoreProgress(
                                novel.name,
                                index + 1,
                                novels.size,
                            )

                            val backupManga = convertNovel(
                                novel,
                                sourceId,
                                novelIdToCategoryNames,
                                backupCategories,
                                includeChapters = options.restoreChapters,
                                includeHistory = options.restoreHistory,
                                includeCategories = options.restoreCategories,
                            )

                            // Check if this novel already exists in the database
                            val existingManga = mangaCache[novel.path to sourceId] ?: getMangaByUrlAndSourceId.await(novel.path, sourceId)
                            if (existingManga != null && novel.isLocal == 0) {
                                // Existing JS novel — skip metadata overwrite, only update chapters/history
                                logcat(LogPriority.INFO) {
                                    "LNReaderImport: Novel '${novel.name}' already exists (id=${existingManga.id}), updating chapters only"
                                }
                                mangaRestorer.restoreExistingChapters(existingManga, backupManga, backupCategories)
                                skippedCount++
                            } else {
                                mangaRestorer.restore(backupManga, backupCategories)
                            }
                            novelCount++
                            logcat(LogPriority.DEBUG) {
                                "LNReaderImport: Restored novel '${novel.name}' (${index + 1}/${novels.size})"
                            }
                        } catch (e: Exception) {
                            errors.add(Date() to "${novel.name} [${novel.pluginId}]: ${e.message}")
                        }
                    }
                }
            }
            // Step 5: Restore downloaded chapter HTML and cached covers from LNReader download.zip
            if ((options.restoreDownloadedChapters || options.restoreCovers) && pluginZipFile != null) {
                val restored = restoreDownloadedAssetsFromDownloadZip(
                    pluginZipFile,
                    novels,
                    pluginIdToSourceId,
                    options.restoreDownloadedChapters,
                    options.restoreCovers,
                    mangaCache
                )
                restoredDownloadCount = restored.first
                restoredCoverCount = restored.second
            }
            } finally {
                pluginZipFile?.delete()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "LNReaderImport: Failed to import backup" }
            errors.add(Date() to "Fatal error: ${e.message}")
        }

        val logFile = writeErrorLog()
        return ImportResult(
            novelCount = novelCount,
            categoryCount = categoryCount,
            errorCount = errors.size,
            logFile = logFile,
            missingPlugins = missingPlugins.toList(),
            skippedCount = skippedCount,
            installedPluginCount = installedPluginCount,
            restoredDownloadCount = restoredDownloadCount,
            restoredCoverCount = restoredCoverCount,
        )
    }

    private fun buildPluginMapping(): Map<String, Long> {
        val plugins = jsPluginManager.installedPlugins.value
        val mapping = plugins.associate { installed ->
            installed.plugin.id to installed.plugin.sourceId()
        }.toMutableMap()
        // Map LNReader's "local" pluginId to LocalNovelSource
        mapping["local"] = 1L // LocalNovelSource.ID
        return mapping
    }

    /**
     * Represents extracted backup data: novels, categories, and optional plugin zip bytes.
     */
    data class ExtractedBackup(
        val novels: List<LNNovel>,
        val categories: List<LNCategory>,
        val pluginZipFile: File?,
    )

    private fun extractBackupData(uri: Uri): ExtractedBackup {
        val novels = mutableListOf<LNNovel>()
        var categories = emptyList<LNCategory>()
        var downloadZipFile: File? = null
        var lastNotifyTime = 0L

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                var processedCount = 0

                ZipInputStream(inputStream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastNotifyTime > 500) {
                            notifier?.showRestoreProgress("Extracting: $name", processedCount, processedCount + 100)
                            lastNotifyTime = currentTime
                        }

                        when {
                            name == "Category.json" -> {
                                try {
                                    categories = json.decodeFromStream<List<LNCategory>>(zip)
                                } catch (e: Exception) {
                                    logcat(LogPriority.WARN, e) { "LNReaderImport: Failed to parse Category.json" }
                                }
                            }
                            name.startsWith("NovelAndChapters/") && name.endsWith(".json") -> {
                            try {
                                val novel = json.decodeFromStream<LNNovel>(zip)
                                if (novel.name.isNotBlank()) {
                                    novels.add(novel)
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) { "LNReaderImport: Failed to parse $name" }
                                errors.add(Date() to "Parse error for $name: ${e.message}")
                            }
                        }
                        name == "download.zip" -> {
                            val tempFile = context.createFileInCacheDir("lnreader_download.zip")
                            tempFile.outputStream().use { fos ->
                                zip.copyTo(fos)
                            }
                            downloadZipFile = tempFile
                        }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                        processedCount++
                    }
                }
            }
        } catch (e: Exception) {
            downloadZipFile?.delete()
            throw e
        }

        return ExtractedBackup(novels, categories, downloadZipFile)
    }

    private suspend fun installPluginsFromDownloadZip(zipFile: File): Int {
        var installedCount = 0
        try {
            ZipInputStream(zipFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // Process plugin JS files
                    val nameLower = name.lowercase()
                    if (nameLower.startsWith("plugins/") && nameLower.endsWith("/index.js") && !entry.isDirectory) {
                        val parts = name.removePrefix(name.substringBefore("/") + "/").split("/")
                        if (parts.size == 2) {
                            val pluginId = parts[0]
                            try {
                                val code = zip.bufferedReader(StandardCharsets.UTF_8).readText()
                                if (code.isNotBlank()) {
                                    val installed = jsPluginManager.installPluginFromCode(pluginId, code)
                                    if (installed) {
                                        installedCount++
                                        logcat(LogPriority.INFO) {
                                            "LNReaderImport: Installed plugin '$pluginId' from backup"
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) {
                                    "LNReaderImport: Failed to install plugin '$pluginId' from backup"
                                }
                                errors.add(Date() to "Failed to install plugin '$pluginId': ${e.message}")
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "LNReaderImport: Failed to process download.zip" }
            errors.add(Date() to "Failed to process download.zip: ${e.message}")
        }
        return installedCount
    }

    private suspend fun restoreDownloadedAssetsFromDownloadZip(
        zipFile: File,
        novels: List<LNNovel>,
        pluginIdToSourceId: Map<String, Long>,
        restoreDownloadedChapters: Boolean,
        restoreCovers: Boolean,
        mangaCache: Map<Pair<String, Long>, tachiyomi.domain.manga.model.Manga>,
    ): Pair<Int, Int> {
        val novelByPluginAndId = novels.associateBy { normalizePluginId(it.pluginId) to it.id }
        val downloadedChapterByKey = buildMap {
            novels.forEach { novel ->
                novel.chapters
                    .filter { it.isDownloaded != 0 }
                    .forEach { chapter ->
                        put(Triple(normalizePluginId(novel.pluginId), novel.id, chapter.id), chapter)
                    }
            }
        }

        val restoredChapterFiles = java.util.concurrent.atomic.AtomicInteger(0)
        val restoredCovers = java.util.concurrent.atomic.AtomicInteger(0)

        try {
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entries = zip.entries().toList()
                val totalEntries = entries.size
                val processedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val lastNotifyTime = java.util.concurrent.atomic.AtomicLong(0L)

                val entriesToProcess = mutableListOf<Triple<Int, String, java.util.zip.ZipEntry>>()

                for (entry in entries) {
                    if (entry.isDirectory) {
                        processedCount.incrementAndGet()
                        continue
                    }
                    val name = entry.name

                    if (name.endsWith(".nomedia", ignoreCase = true)) {
                        processedCount.incrementAndGet()
                        continue
                    }

                    val parts = name.split('/')
                    if (parts.size >= 4 && parts[0].equals("Novels", ignoreCase = true)) {
                        val pluginId = parts[1]
                        val novelId = parts[2].toIntOrNull()

                        if (novelId != null) {
                            entriesToProcess.add(Triple(novelId, pluginId, entry))
                            continue
                        }
                    }
                    processedCount.incrementAndGet()
                }

                val groupedByNovel = entriesToProcess.groupBy { it.first }
                val concurrencyLimit = Semaphore(10)
                val dirCreationMutex = kotlinx.coroutines.sync.Mutex()

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    coroutineScope {
                        val deferreds = groupedByNovel.map { (novelId, novelEntries) ->
                            async {
                                concurrencyLimit.withPermit {
                                    if (novelEntries.isEmpty()) return@withPermit

                                val firstEntry = novelEntries.first()
                                val pluginId = firstEntry.second
                                val normalizedPluginId = normalizePluginId(pluginId)
                                val novel = novelByPluginAndId[normalizedPluginId to novelId] ?: return@withPermit
                                val sourceId = resolveSourceId(pluginIdToSourceId, novel.pluginId) ?: return@withPermit

                                var mangaDirFetched = false
                                var mangaDir: UniFile? = null
                                var dbMangaFetched = false
                                var dbManga: tachiyomi.domain.manga.model.Manga? = null

                                for ((_, _, entry) in novelEntries) {
                                    val name = entry.name
                                    val parts = name.split('/')

                                    val isChapter = parts.size == 5 && parts[4].startsWith("index.", ignoreCase = true)
                                    val isCover = parts.size == 4 && parts[3].startsWith("cover.", ignoreCase = true)

                                    if (isChapter && restoreDownloadedChapters) {
                                        val chapterId = parts[3].toIntOrNull()
                                        if (chapterId != null) {
                                            val chapter = downloadedChapterByKey[Triple(normalizedPluginId, novelId, chapterId)]

                                            if (chapter != null) {
                                                if (!mangaDirFetched) {
                                                    val source = sourceManager.getOrStub(sourceId)
                                                    dirCreationMutex.withLock {
                                                        mangaDir = downloadProvider.getMangaDir(novel.name, source).getOrNull()
                                                    }
                                                    mangaDirFetched = true
                                                }

                                                if (mangaDir != null) {
                                                    zip.getInputStream(entry).use { inputStream ->
                                                        if (writeDownloadedChapterHtml(novel, chapter, mangaDir, inputStream)) {
                                                            restoredChapterFiles.incrementAndGet()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else if (isCover && restoreCovers) {
                                        if (!dbMangaFetched) {
                                            dbManga = mangaCache[novel.path to sourceId] ?: getMangaByUrlAndSourceId.await(novel.path, sourceId)
                                            dbMangaFetched = true
                                        }

                                        if (dbManga != null) {
                                            zip.getInputStream(entry).use { inputStream ->
                                                if (restoreCoverToCache(novel, dbManga, inputStream)) {
                                                    restoredCovers.incrementAndGet()
                                                }
                                            }
                                        }
                                    }

                                    val current = processedCount.incrementAndGet()
                                    val currentTime = System.currentTimeMillis()
                                    val lastTime = lastNotifyTime.get()
                                    if (currentTime - lastTime > 500) {
                                        if (lastNotifyTime.compareAndSet(lastTime, currentTime)) {
                                            notifier?.showRestoreProgress("Restoring assets", current, totalEntries)
                                        }
                                    }
                                }
                                }
                            }
                        }
                        awaitAll(*deferreds.toTypedArray())
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "LNReaderImport: Failed to restore downloaded assets from $zipFile" }
            errors.add(Date() to "Failed to restore downloaded assets from $zipFile: ${e.message}")
        }

        return Pair(restoredChapterFiles.get(), restoredCovers.get())
    }

    private fun writeDownloadedChapterHtml(
        novel: LNNovel,
        chapter: LNChapter,
        mangaDir: UniFile,
        inputStream: InputStream,
    ): Boolean {
        return try {
            val chapterDirName = downloadProvider.getChapterDirName(
                chapter.name,
                chapterScanlator = null,
                chapterUrl = chapter.path,
            )

            val chapterDir = mangaDir.findFile(chapterDirName) ?: mangaDir.createDirectory(chapterDirName)
            if (chapterDir == null || !chapterDir.exists()) {
                return false
            }

            writeStreamToFile(chapterDir, "001.html", inputStream)
            true
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "LNReaderImport: Failed to restore downloaded chapter '${chapter.name}' for '${novel.name}'"
            }
            false
        }
    }

    private fun restoreCoverToCache(novel: LNNovel, manga: tachiyomi.domain.manga.model.Manga, inputStream: InputStream): Boolean {
        return try {
            coverCache.setCustomCoverToCache(manga, inputStream)
            true
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "LNReaderImport: Failed to restore cached cover for '${novel.name}'" }
            false
        }
    }

    private fun writeStreamToFile(parent: UniFile, fileName: String = "001.html", inputStream: InputStream) {
        val existing = parent.findFile(fileName)
        if (existing != null) {
            existing.delete()
        }
        val file = parent.createFile(fileName) ?: throw IllegalStateException("Failed to create '$fileName'")
        file.openOutputStream().use { out ->
            inputStream.copyTo(out)
        }
    }

    private fun normalizePluginId(pluginId: String): String {
        return pluginId.lowercase(Locale.ROOT).replace('-', '_')
    }

    private fun resolveSourceId(pluginIdToSourceId: Map<String, Long>, pluginId: String): Long? {
        val normalized = normalizePluginId(pluginId)
        return pluginIdToSourceId[normalized] ?: pluginIdToSourceId[normalized.replace('_', '-')]
    }

    /**
     * Generate a deterministic source ID for a missing plugin based on its plugin ID.
     */
    private fun generateStubSourceId(pluginId: String): Long {
        val hash = pluginId.hashCode()
        return 5_000_000_000L + (hash.toLong() and 0x7FFFFFFF)
    }

    private fun writeErrorLog(): File {
        val logFile = File(context.cacheDir, "lnreader_import_errors.log")
        logFile.printWriter().use { writer ->
            if (errors.isEmpty()) {
                writer.println("No errors encountered during import.")
            } else {
                writer.println("Errors encountered during import:")
                errors.forEach { (date, message) ->
                    val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)
                    writer.println("[$formattedDate] $message")
                }
            }
        }
        return logFile
    }

    private fun convertNovel(
        novel: LNNovel,
        sourceId: Long,
        novelIdToCategoryNames: Map<Int, List<String>>,
        backupCategories: List<BackupCategory>,
        includeChapters: Boolean = true,
        includeHistory: Boolean = true,
        includeCategories: Boolean = true,
    ): BackupManga {
        val backupChapters = if (includeChapters) {
            novel.chapters.mapIndexed { index, ch ->
                BackupChapter(
                    url = ch.path,
                    name = ch.name,
                    scanlator = null,
                    read = ch.unread == 0,
                    bookmark = ch.bookmark != 0,
                    lastPageRead = ch.progress?.toLong() ?: 0L,
                    dateFetch = 0L,
                    dateUpload = parseDate(ch.releaseTime) ?: 0L,
                    chapterNumber = ch.chapterNumber ?: (index + 1).toFloat(),
                    sourceOrder = index.toLong(),
                )
            }
        } else {
            emptyList()
        }

        val backupHistory = if (includeHistory) {
            novel.chapters
                .filter { it.readTime != null }
                .map { ch ->
                    BackupHistory(
                        url = ch.path,
                        lastRead = parseDate(ch.readTime) ?: 0L,
                        readDuration = 0L,
                    )
                }
        } else {
            emptyList()
        }

        // Map novel ID to category orders (use the BackupCategory.order, not list index)
        val categoryOrders = if (includeCategories) {
            val categoryNames = novelIdToCategoryNames[novel.id].orEmpty()
            categoryNames.mapNotNull { name ->
                backupCategories.firstOrNull { it.name == name }?.order
            }
        } else {
            emptyList()
        }

        val status = when (novel.status?.lowercase()) {
            "ongoing" -> 1
            "completed" -> 2
            "licensed" -> 3
            "publishing finished" -> 4
            "cancelled" -> 5
            "on hiatus" -> 6
            else -> 0
        }

        return BackupManga(
            source = sourceId,
            url = novel.path,
            title = novel.name,
            artist = novel.artist,
            author = novel.author,
            description = novel.summary,
            genre = novel.genres?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            status = status,
            thumbnailUrl = novel.cover?.let { cover ->
                if (cover.startsWith("/Novels/") || cover.startsWith("/storage/")) {
                    null
                } else {
                    cover
                }
            },
            favorite = novel.inLibrary != 0,
            chapters = backupChapters,
            categories = categoryOrders,
            history = backupHistory,
            dateAdded = System.currentTimeMillis(),
            isNovel = true,
        )
    }

    private fun parseDate(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            // Try ISO 8601 format
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(dateStr)?.time
        } catch (_: Exception) {
            try {
                // Try simple date format
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)?.time
            } catch (_: Exception) {
                try {
                    // Try timestamp as number
                    dateStr.toLongOrNull()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
