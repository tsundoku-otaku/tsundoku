package eu.kanade.domain.manga.interactor

import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.source.normalizeSourcePath
import eu.kanade.tachiyomi.util.source.toggleLeadingSlash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.download.service.NovelDownloadPreferences
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import eu.kanade.domain.source.service.SourcePreferences
import tachiyomi.domain.storage.service.StorageManager
import eu.kanade.tachiyomi.util.system.workManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class MassImport(
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
    private val novelDownloadPreferences: NovelDownloadPreferences = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) {
    private val missingSourceHostLogCache = ConcurrentHashMap<String, Boolean>()
    private val csvLock = Any()

    data class ImportResult(
        val added: MutableList<ImportedNovel> = mutableListOf(),
        val skipped: MutableList<SkippedNovel> = mutableListOf(),
        val errored: MutableList<ErroredNovel> = mutableListOf(),
        val prefilterInvalid: List<Pair<String, String>> = emptyList(),
        val prefilterDuplicates: List<String> = emptyList(),
        val prefilterAlreadyInLibrary: List<String> = emptyList(),
    )

    data class ImportedNovel(val title: String, val url: String, val manga: Manga)
    data class SkippedNovel(val title: String, val url: String, val reason: String)
    data class ErroredNovel(val url: String, val error: String)

    data class ImportProgress(
        val current: Int,
        val total: Int,
        val currentUrl: String,
        val status: String,
        val isRunning: Boolean = false,
        val activeImports: List<String> = emptyList(),
        val concurrency: Int = 1,
    )

    private val _progress = MutableStateFlow<ImportProgress?>(null)
    val progress = _progress.asStateFlow()

    private val _result = MutableStateFlow<ImportResult?>(null)
    val result = _result.asStateFlow()

    private var isCancelled = false
    private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null

    fun cancel() {
        isCancelled = true
        runningJob?.cancel()
        runningJob = null
        _progress.update { it?.copy(isRunning = false, status = "Cancelled") }
    }

    fun clear() {
        _progress.value = null
        _result.value = null
        isCancelled = false
    }

    private fun addCsvBuffer(buffer: MutableList<String>, line: String) {
        synchronized(csvLock) {
            buffer.add(line)
        }
    }

    fun startImport(
        urls: List<String>,
        addToLibrary: Boolean = true,
        categoryId: Long? = null,
        fetchDetails: Boolean = true,
        fetchChapters: Boolean = false,
        batchId: String? = null,
    ): Job {
        cancel()
        isCancelled = false
        _result.value = null

        val job = importScope.launch {
            importInternal(
                urls = urls,
                addToLibrary = addToLibrary,
                categoryId = categoryId,
                fetchDetails = fetchDetails,
                fetchChapters = fetchChapters,
                batchId = batchId,
            )
        }
        runningJob = job
        return job
    }

    suspend fun import(
        urls: List<String>,
        addToLibrary: Boolean = true,
        batchId: String? = null,
    ) {
        importInternal(
            urls = urls,
            addToLibrary = addToLibrary,
            categoryId = null,
            fetchDetails = true,
            fetchChapters = false,
            batchId = batchId,
        )
    }

    private suspend fun importInternal(
        urls: List<String>,
        addToLibrary: Boolean,
        categoryId: Long?,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        batchId: String? = null,
    ) {
        isCancelled = false
        _result.value = null

        val novelSources = getAllSources()
        val libraryUrlIndex = try {
            mangaRepository.getFavoriteSourceAndUrl().toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val rawUrls = urls.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val validUrls = mutableListOf<String>()
        val invalidUrls = mutableListOf<Pair<String, String>>()
        val duplicateUrls = mutableListOf<String>()
        val alreadyInLibrary = mutableListOf<String>()
        val seenKeys = mutableSetOf<String>()

        for (url in rawUrls) {
            val key = urlDedupKey(url)
            if (key in seenKeys) {
                duplicateUrls.add(url)
                continue
            }
            seenKeys.add(key)

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                invalidUrls.add(url to "Not a valid URL")
                continue
            }

            val source = findMatchingSource(url, novelSources)
            if (source == null) {
                invalidUrls.add(url to "No matching source")
                continue
            }
            val path = extractPathFromUrl(url, getSourceBaseUrl(source), source)
            if (libraryUrlIndex.contains(source.id to path)) {
                alreadyInLibrary.add(url)
                continue
            }

            validUrls.add(url)
        }

        val currentResult = ImportResult(
            prefilterInvalid = invalidUrls,
            prefilterDuplicates = duplicateUrls,
            prefilterAlreadyInLibrary = alreadyInLibrary,
        )

        if (novelSources.isEmpty()) {
            validUrls.forEach { url -> currentResult.errored.add(ErroredNovel(url, "No novel sources installed")) }
            _result.value = currentResult
            return
        }

        val concurrency = novelDownloadPreferences.parallelMassImport().get()
        val semaphore = Semaphore(concurrency)
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val activeImports = ConcurrentHashMap<String, Boolean>()
        val sourceSemaphores = ConcurrentHashMap<Long, Semaphore>()
        val sourceLastRequest = ConcurrentHashMap<Long, AtomicLong>()

        _progress.value = ImportProgress(0, validUrls.size, "", "Starting...", true, emptyList(), concurrency)

        
        val storageManager: StorageManager = Injekt.get()
        val massDir: UniFile? = storageManager.getMassImportDirectory()
        val resultFileName = "mass_import_${batchId ?: System.currentTimeMillis().toString()}.csv"
        val flushBatchSize = 100

        val addedBuffer = Collections.synchronizedList(mutableListOf<String>())
        val skippedBuffer = Collections.synchronizedList(mutableListOf<String>())
        val erroredBuffer = Collections.synchronizedList(mutableListOf<String>())

        fun csvEscape(value: String?): String {
            val safe = value.orEmpty().replace("\"", "\"\"")
            return "\"$safe\""
        }

        fun appendLinesToResultFile(lines: List<String>) {
            if (lines.isEmpty()) return
            synchronized(csvLock) {
                try {
                    val file: UniFile = massDir?.findFile(resultFileName) ?: massDir?.createFile(resultFileName) ?: return
                    val existingLines = mutableListOf<String>()
                    if (file.exists()) {
                        try {
                            file.openInputStream().bufferedReader().useLines { seq -> seq.forEach { existingLines.add(it) } }
                        } catch (_: Exception) {
                        }
                    }
                    file.openOutputStream().bufferedWriter().use { writer ->
                        if (existingLines.isEmpty()) {
                            writer.appendLine("timestamp,type,url,id,title,message")
                        } else {
                            existingLines.forEach { writer.appendLine(it) }
                        }
                        lines.forEach { writer.appendLine(it) }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Failed to append results to $resultFileName" }
                }
            }
        }

        fun flushBuffers() {
            synchronized(csvLock) {
                if (addedBuffer.isNotEmpty()) {
                    appendLinesToResultFile(addedBuffer.map { entry ->
                        val parts = entry.split('\t')
                        listOf(parts.getOrNull(0), "added", parts.getOrNull(3), parts.getOrNull(1), parts.getOrNull(2), "")
                            .joinToString(",") { csvEscape(it) }
                    })
                    addedBuffer.clear()
                }
                if (skippedBuffer.isNotEmpty()) {
                    appendLinesToResultFile(skippedBuffer.map { entry ->
                        val parts = entry.split('\t')
                        listOf(parts.getOrNull(0), "skipped", parts.getOrNull(parts.size - 2) ?: parts.getOrNull(1), "", "", parts.getOrNull(parts.size - 1))
                            .joinToString(",") { csvEscape(it) }
                    })
                    skippedBuffer.clear()
                }
                if (erroredBuffer.isNotEmpty()) {
                    appendLinesToResultFile(erroredBuffer.map { entry ->
                        val parts = entry.split('\t')
                        listOf(parts.getOrNull(0), "errored", parts.getOrNull(1), "", "", parts.getOrNull(2))
                            .joinToString(",") { csvEscape(it) }
                    })
                    erroredBuffer.clear()
                }
            }
        }

        try {
            coroutineScope {
                val jobs = validUrls.map { rawUrl ->
                    async {
                        if (isCancelled) return@async

                        val cleanUrl = rawUrl.trim()
                        if (cleanUrl.isEmpty()) return@async

                        val sourceId = findMatchingSource(cleanUrl, novelSources)?.id ?: -1L
                        val sourceSemaphore = sourceSemaphores.computeIfAbsent(sourceId) { Semaphore(1) }

                        sourceSemaphore.withPermit {
                            if (isCancelled) return@withPermit

                            if (novelDownloadPreferences.enableMassImportThrottling().get()) {
                                val baseDelay = novelDownloadPreferences.massImportDelay().get().toLong()
                                val randomRange = novelDownloadPreferences.randomDelayRange().get().toLong()
                                val randomDelay = if (randomRange > 0) Random.nextLong(0, randomRange) else 0L
                                val last = sourceLastRequest.computeIfAbsent(sourceId) { AtomicLong(0L) }.get()
                                val waitUntil = last + baseDelay + randomDelay
                                val now = System.currentTimeMillis()
                                if (waitUntil > now) delay(waitUntil - now)
                            }

                            semaphore.withPermit {
                                if (isCancelled) return@withPermit

                                activeImports[cleanUrl] = true
                                _progress.update {
                                    it?.copy(
                                        current = (completedCount.get() + 1).coerceAtMost(validUrls.size),
                                        currentUrl = cleanUrl,
                                        status = "Processing ${activeImports.size} novel(s)...",
                                        activeImports = activeImports.keys.toList(),
                                    )
                                }

                                try {
                                    processUrl(
                                        url = cleanUrl,
                                        novelSources = novelSources,
                                        result = currentResult,
                                        addToLibrary = addToLibrary,
                                        categoryId = categoryId,
                                        fetchDetails = fetchDetails,
                                        fetchChapters = fetchChapters,
                                        libraryUrlIndex = libraryUrlIndex,
                                        addedBuffer = addedBuffer,
                                        skippedBuffer = skippedBuffer,
                                        erroredBuffer = erroredBuffer,
                                    )
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e) { "Error importing $cleanUrl" }
                                    synchronized(currentResult) {
                                        if (currentResult.errored.size < 10) {
                                            currentResult.errored.add(ErroredNovel(cleanUrl, e.message ?: "Unknown error"))
                                        }
                                    }
                                            addCsvBuffer(erroredBuffer, "${System.currentTimeMillis()}\t$cleanUrl\t${e.message ?: "Unknown error"}")
                                } finally {
                                    activeImports.remove(cleanUrl)
                                    val done = completedCount.incrementAndGet()
                                    _progress.update {
                                        val statusText = if (activeImports.isEmpty()) "Finishing..." else "Processing ${activeImports.size} novel(s)..."
                                        it?.copy(
                                            current = done,
                                            status = statusText,
                                            activeImports = activeImports.keys.toList(),
                                        )
                                    }
                                }

                                sourceLastRequest.computeIfAbsent(sourceId) { AtomicLong(0L) }.set(System.currentTimeMillis())

                                if (addedBuffer.size >= flushBatchSize || skippedBuffer.size >= flushBatchSize || erroredBuffer.size >= flushBatchSize) {
                                    flushBuffers()
                                }
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        } finally {
            flushBuffers()
        }

        _result.value = currentResult
        _progress.update { it?.copy(isRunning = false, status = "Complete") }

        if (currentResult.added.isNotEmpty()) {
            try {
                mangaRepository.refreshLibraryCache()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to refresh library cache after mass import" }
            }
        }
    }

    private suspend fun processUrl(
        url: String,
        novelSources: List<CatalogueSource>,
        result: ImportResult,
        addToLibrary: Boolean,
        categoryId: Long?,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        libraryUrlIndex: Set<Pair<Long, String>>,
        addedBuffer: MutableList<String>,
        skippedBuffer: MutableList<String>,
        erroredBuffer: MutableList<String>,
    ) {
        val source = getSourceForUrl(url, novelSources) ?: run {
            synchronized(result) {
                if (result.errored.size < 10) result.errored.add(ErroredNovel(url, "No matching source found for URL"))
            }
            addCsvBuffer(erroredBuffer, "${System.currentTimeMillis()}\t$url\tNo matching source found for URL")
            return
        }
        val path = extractPathFromUrl(url, getSourceBaseUrl(source), source)
        if (path.isEmpty()) {
            synchronized(result) {
                if (result.errored.size < 10) result.errored.add(ErroredNovel(url, "Could not extract path from URL"))
            }
            addCsvBuffer(erroredBuffer, "${System.currentTimeMillis()}\t$url\tCould not extract path from URL")
            return
        }

        if (libraryUrlIndex.contains(source.id to path)) {
            synchronized(result) {
                if (result.skipped.size < 10) result.skipped.add(SkippedNovel(url, url, "Already in library"))
            }
            addCsvBuffer(skippedBuffer, "${System.currentTimeMillis()}\t$url\tAlready in library")
            return
        }

        val existingManga = mangaRepository.getLiteMangaByUrlAndSourceId(path, source.id)
        if (existingManga != null && existingManga.favorite) {
            synchronized(result) {
                if (result.skipped.size < 10) result.skipped.add(SkippedNovel(existingManga.title, url, "Already in library"))
            }
            addCsvBuffer(skippedBuffer, "${System.currentTimeMillis()}\t${existingManga.id}\t${existingManga.title}\t$url\tAlready in library")
            return
        }

        if (existingManga != null && addToLibrary && !fetchDetails && !fetchChapters) {
            mangaRepository.update(
                MangaUpdate(
                    id = existingManga.id,
                    favorite = true,
                    dateAdded = System.currentTimeMillis(),
                ),
            )
            val updatedManga = existingManga.copy(favorite = true)
            if (categoryId != null && categoryId != 0L) {
                setMangaCategories.await(updatedManga.id, listOf(categoryId))
            }
            synchronized(result) {
                if (result.added.size < 10) result.added.add(ImportedNovel(updatedManga.title, url, updatedManga))
            }
            addCsvBuffer(addedBuffer, "${System.currentTimeMillis()}\t${updatedManga.id}\t${updatedManga.title}\t$url")
            return
        }

        try {
            val manga = resolveMangaUrl(url, path, source)
            if (addToLibrary) {
                mangaRepository.update(
                    MangaUpdate(
                        id = manga.id,
                        favorite = true,
                        dateAdded = System.currentTimeMillis(),
                    ),
                )

                val updatedManga = manga.copy(favorite = true)

                if (categoryId != null && categoryId != 0L) {
                    setMangaCategories.await(updatedManga.id, listOf(categoryId))
                }

                if (fetchChapters) {
                    try {
                        val sChapters = source.getChapterList(updatedManga.toSManga())
                        syncChaptersWithSource.await(sChapters, updatedManga, source)
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "Failed to sync chapters for $url" }
                    }
                }

                synchronized(result) {
                    if (result.added.size < 10) result.added.add(ImportedNovel(updatedManga.title, url, updatedManga))
                }
                addCsvBuffer(addedBuffer, "${System.currentTimeMillis()}\t${updatedManga.id}\t${updatedManga.title}\t$url")
            } else {
                synchronized(result) {
                    if (result.added.size < 10) result.added.add(ImportedNovel(manga.title, url, manga))
                }
                addCsvBuffer(addedBuffer, "${System.currentTimeMillis()}\t${manga.id}\t${manga.title}\t$url")
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to fetch novel from $url" }
            synchronized(result) {
                if (result.errored.size < 10) result.errored.add(ErroredNovel(url, "Failed to fetch: ${e.message}"))
            }
            addCsvBuffer(erroredBuffer, "${System.currentTimeMillis()}\t$url\tFailed to fetch: ${e.message}")
        }
    }

    suspend fun resolveMangaUrl(url: String, path: String, source: CatalogueSource): Manga {
        val inputUrl = normalizeSourcePath(source, path)
        val sManga = runCatching {
            var resolved: eu.kanade.tachiyomi.source.model.SManga? = null
            if (source is eu.kanade.tachiyomi.source.online.ResolvableSource &&
                source.getUriType(url) == eu.kanade.tachiyomi.source.online.UriType.Manga
            ) {
                resolved = runCatching { source.getManga(url) }.getOrNull()
            }

            resolved ?: source.getMangaDetails(
                eu.kanade.tachiyomi.source.model.SManga.create().apply {
                    this.url = inputUrl
                },
            )
        }.getOrElse { firstError ->
            if (source is HttpSource) {
                val fallback = runCatching {
                    val page = source.getSearchManga(1, url, eu.kanade.tachiyomi.source.model.FilterList())
                    page.mangas.firstOrNull()?.let { firstManga ->
                        source.getMangaDetails(firstManga).apply { this.url = firstManga.url }
                    }
                }.getOrNull()

                fallback ?: run {
                    val fallbackUrl = toggleLeadingSlash(inputUrl)
                    source.getMangaDetails(
                        eu.kanade.tachiyomi.source.model.SManga.create().apply { this.url = fallbackUrl },
                    ).apply {
                        this.url = fallbackUrl
                    }
                }
            } else {
                throw firstError
            }
        }

        try {
            val resolvedUrl = runCatching { sManga.url }.getOrNull().orEmpty()
            sManga.url = if (resolvedUrl.isBlank()) path else normalizeSourcePath(source, resolvedUrl)
        } catch (_: UninitializedPropertyAccessException) {
            sManga.url = path
        }

        try {
            @Suppress("UNUSED_VARIABLE")
            val titleCheck = sManga.title
        } catch (_: UninitializedPropertyAccessException) {
            throw Exception("Extension failed to parse novel title from $url")
        }

        return networkToLocalManga(sManga.toDomainManga(source.id, source.isNovelSource()))
    }

    private fun getAllSources(): List<CatalogueSource> {
        return sourceManager.getCatalogueSources().filter { it is HttpSource || it is JsSource }
    }

    private fun findMatchingSource(url: String, sources: List<CatalogueSource>): CatalogueSource? {
        val normalizedUrl = stripScheme(url).removePrefix("www.").removeSuffix("/")
        val matchingSources = sources.filter { source ->
            try {
                val baseUrl = stripScheme(getSourceBaseUrl(source)).removePrefix("www.").removeSuffix("/")
                normalizedUrl.startsWith(baseUrl)
            } catch (_: Exception) {
                false
            }
        }

        if (matchingSources.isEmpty()) {
            val hostKey = try {
                URI(url).host?.lowercase()?.removePrefix("www.")
            } catch (_: Exception) {
                null
            }
            if (hostKey == null || missingSourceHostLogCache.putIfAbsent(hostKey, true) == null) {
                logcat(LogPriority.WARN) { "MassImport: No source match for $url host=$hostKey" }
            }
            return null
        }
        if (matchingSources.size == 1) return matchingSources.first()

        val enabledLanguages = sourcePreferences.enabledLanguages.get()
        val disabledSources = sourcePreferences.disabledSources.get()
        val enabledSources = matchingSources.filter {
            it.lang in enabledLanguages && it.id.toString() !in disabledSources
        }
        val bestLangSources = if (enabledSources.isNotEmpty()) enabledSources else matchingSources
        val kotlinSources = bestLangSources.filter { it !is JsSource }

        return kotlinSources.firstOrNull() ?: bestLangSources.first()
    }

    fun getSourceForUrl(url: String, sources: List<CatalogueSource>): CatalogueSource? {
        return findMatchingSource(url, sources)
    }
    fun getSourceBaseUrl(source: CatalogueSource): String {
        return when (source) {
            is HttpSource -> source.baseUrl
            is JsSource -> source.baseUrl
            else -> ""
        }
    }

    fun extractPathFromUrl(url: String, baseUrl: String, source: CatalogueSource? = null): String {
        val extractedPath = try {
            val baseUri = URI(baseUrl)
            val urlUri = URI(url)

            val baseHost = baseUri.host?.lowercase()
            val urlHost = urlUri.host?.lowercase()

            if (baseHost != null && urlHost != null && baseHost == urlHost) {
                buildString {
                    append(urlUri.rawPath ?: "")
                    val q = urlUri.rawQuery
                    if (!q.isNullOrBlank()) {
                        append('?')
                        append(q)
                    }
                }
            } else {
                val normalizedBase = stripScheme(baseUrl).removeSuffix("/")
                val normalizedUrl = stripScheme(url)
                if (normalizedUrl.startsWith(normalizedBase)) {
                    normalizedUrl.removePrefix(normalizedBase)
                } else {
                    normalizedUrl
                }
            }
        } catch (_: Exception) {
            val normalizedBase = stripScheme(baseUrl).removeSuffix("/")
            val normalizedUrl = stripScheme(url)
            if (normalizedUrl.startsWith(normalizedBase)) {
                normalizedUrl.removePrefix(normalizedBase)
            } else {
                normalizedUrl
            }
        }

        val rawPath = source?.let { normalizeSourcePath(it, extractedPath) } ?: extractedPath
        return normalizeUrl(rawPath)
    }

    fun normalizeUrl(url: String): String {
        return url.trimEnd('/')
            .substringBefore('#')
            .replace(Regex("(?<!:)//+"), "/")
    }

    fun parseUrls(text: String): List<String> {
        val preprocessed = text
            .replace(Regex("(?<=[^\\s])(?=https?://)"), "\n")
            .replace(Regex("(?<!https?:)//+"), "/")

        return preprocessed
            .split("\n", ",", ";", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() && (it.startsWith("http://") || it.startsWith("https://")) }
            .distinctBy { urlDedupKey(it) }
    }

    data class UrlAnalysisResult(
        val validUrls: List<String>,
        val invalidUrls: List<Pair<String, String>>,
        val duplicateUrls: List<String>,
        val alreadyInLibrary: List<String>,
    ) {
        val totalValid get() = validUrls.size
        val totalInvalid get() = invalidUrls.size + duplicateUrls.size + alreadyInLibrary.size
    }

    suspend fun analyzeUrls(text: String): UrlAnalysisResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val novelSources = getAllSources()
        val libraryUrlIndex = try {
            mangaRepository.getFavoriteSourceAndUrl().toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val rawLines = text.split("\n", ",", ";", " ").map { it.trim() }.filter { it.isNotEmpty() }
        val validUrls = mutableListOf<String>()
        val invalidUrls = mutableListOf<Pair<String, String>>()
        val duplicateUrls = mutableListOf<String>()
        val alreadyInLibrary = mutableListOf<String>()
        val seenKeys = mutableSetOf<String>()

        for (line in rawLines) {
            if (!line.startsWith("http://") && !line.startsWith("https://")) {
                invalidUrls.add(line to "Not a valid URL")
                continue
            }

            val key = urlDedupKey(line)
            if (key in seenKeys) {
                duplicateUrls.add(line)
                continue
            }
            seenKeys.add(key)

            val source = findMatchingSource(line, novelSources)
            if (source == null) {
                invalidUrls.add(line to "No matching source")
                continue
            }
            val path = extractPathFromUrl(line, getSourceBaseUrl(source), source)
            if (libraryUrlIndex.contains(source.id to path)) {
                alreadyInLibrary.add(line)
                continue
            }

            validUrls.add(line)
        }

        UrlAnalysisResult(validUrls, invalidUrls, duplicateUrls, alreadyInLibrary)
    }

    private fun stripScheme(url: String): String {
        return url.trim().replace(Regex("^[a-zA-Z][a-zA-Z0-9+\\-.]*://"), "").lowercase()
    }

    private fun urlDedupKey(url: String): String {
        return try {
            val uri = URI(url.trim())
            buildString {
                append(uri.host?.lowercase() ?: "")
                append(uri.rawPath?.trimEnd('/') ?: "")
                val q = uri.rawQuery
                if (!q.isNullOrBlank()) append('?').append(q)
            }
        } catch (_: Exception) {
            stripScheme(url).removeSuffix("/")
        }
    }

    private fun getResultFileForBatch(batchId: String): UniFile? {
        return try {
            val storageManager: StorageManager = Injekt.get()
            val massDir = storageManager.getMassImportDirectory() ?: return null
            massDir.findFile("mass_import_${batchId}.csv")
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val c = line[index]
            when (c) {
                '"' -> {
                    if (inQuotes && index + 1 < line.length && line[index + 1] == '"') {
                        sb.append('"')
                        index += 1
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ',' -> if (inQuotes) sb.append(c) else {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            index += 1
        }
        out.add(sb.toString())
        return out
    }

    fun readAllResultsForBatch(batchId: String): List<List<String>> {
        val file = getResultFileForBatch(batchId) ?: return emptyList()
        val rows = mutableListOf<List<String>>()
        try {
            file.openInputStream().bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line -> rows.add(parseCsvLine(line)) }
            }
        } catch (_: Exception) {
        }
        return rows
    }

    fun readErroredUrlsForBatch(batchId: String): List<String> {
        return readAllResultsForBatch(batchId)
            .asSequence()
            .filter { it.getOrNull(1) == "errored" }
            .mapNotNull { it.getOrNull(2) }
            .toList()
    }

    data class Batch(
        val id: String,
        val urls: List<String>,
        val categoryId: Long,
        val addToLibrary: Boolean,
        val fetchChapters: Boolean,
        val fetchDetails: Boolean = true,
        val status: BatchStatus = BatchStatus.Pending,
        val progress: Int = 0,
        val total: Int = 0,
        val added: Int = 0,
        val skipped: Int = 0,
        val errored: Int = 0,
        val erroredUrls: List<String> = emptyList(),
        val skippedUrls: List<String> = emptyList(),
        val errorMessages: Map<String, String> = emptyMap(),
    )

    enum class BatchStatus {
        Pending,
        Running,
        Completed,
        Cancelled,
    }

    companion object {
        private const val TAG = "MassImportJob"
        const val KEY_URLS = "urls"
        const val KEY_URLS_FILE = "urlsFile"
        const val KEY_CATEGORY_ID = "categoryId"
        const val KEY_ADD_TO_LIBRARY = "addToLibrary"
        const val KEY_FETCH_DETAILS = "fetchDetails"
        const val KEY_FETCH_CHAPTERS = "fetchChapters"
        const val KEY_BATCH_ID = "batchId"

        private val _sharedResult = MutableStateFlow<ImportResult?>(null)
        val sharedResult = _sharedResult.asStateFlow()

        private val _sharedProgress = MutableStateFlow<ImportProgress?>(null)
        val sharedProgress = _sharedProgress.asStateFlow()

        private val _sharedQueue = MutableStateFlow<List<Batch>>(emptyList())
        val sharedQueue = _sharedQueue.asStateFlow()

        private data class RecoveryBatchData(
            val categoryId: Long,
            val urls: List<String>,
        )

        fun clearResult() {
            _sharedResult.value = null
            _sharedProgress.value = null
        }

        fun restoreQueueFromWorkManager(context: android.content.Context) {
            val workInfos = runCatching { context.workManager.getWorkInfosByTag(TAG).get() }.getOrDefault(emptyList())
            if (workInfos.isEmpty()) return

            _sharedQueue.update { current ->
                val batches = current.associateBy { it.id }.toMutableMap()
                workInfos.forEach { info ->
                    if (info.state == androidx.work.WorkInfo.State.SUCCEEDED || info.state == androidx.work.WorkInfo.State.FAILED) return@forEach
                    val batchId = info.tags.firstOrNull { it.startsWith("batch_") }?.removePrefix("batch_") ?: return@forEach
                    val recoveryBatch = readRecoveryBatch(batchId)
                    val urls = recoveryBatch?.urls.orEmpty()
                    if (urls.isEmpty()) return@forEach
                    val status = when (info.state) {
                        androidx.work.WorkInfo.State.RUNNING -> BatchStatus.Running
                        androidx.work.WorkInfo.State.CANCELLED -> BatchStatus.Cancelled
                        else -> BatchStatus.Pending
                    }
                    val existing = batches[batchId]
                    batches[batchId] = if (existing != null) {
                        existing.copy(status = status)
                    } else {
                        Batch(
                            id = batchId,
                            urls = urls,
                            categoryId = recoveryBatch?.categoryId ?: 0L,
                            addToLibrary = true,
                            fetchChapters = false,
                            status = status,
                            total = urls.size,
                        )
                    }
                }
                batches.values.toList()
            }
        }

        private fun readRecoveryBatch(batchId: String): RecoveryBatchData? {
            return try {
                val storageManager: StorageManager = Injekt.get()
                val dir = storageManager.getMassImportDirectory() ?: return null
                val file = dir.findFile("queue_$batchId.txt") ?: return null
                val lines = file.openInputStream().bufferedReader().use { it.readLines() }
                val categoryId = lines.firstOrNull()?.toLongOrNull() ?: 0L
                val urls = lines.drop(1).filter { it.isNotBlank() }
                RecoveryBatchData(categoryId = categoryId, urls = urls)
            } catch (_: Exception) {
                null
            }
        }

        fun isRunning(context: android.content.Context): Boolean {
            val workInfos = context.workManager.getWorkInfosByTag(TAG).get()
            return workInfos.any { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
        }

        fun start(
            context: android.content.Context,
            urls: List<String>,
            categoryId: Long = 0L,
            addToLibrary: Boolean = true,
            fetchDetails: Boolean = true,
            fetchChapters: Boolean = false,
        ) {
            val batchId = java.util.UUID.randomUUID().toString()
            val batch = Batch(
                id = batchId,
                urls = urls,
                categoryId = categoryId,
                addToLibrary = addToLibrary,
                fetchChapters = fetchChapters,
                fetchDetails = fetchDetails,
                total = urls.size,
            )

            _sharedQueue.update { it + batch }

            val offloadToFile = urls.size > 50 || urls.sumOf { it.length } > 500_000
            val payload = mutableListOf<Pair<String, Any?>>()
            payload += KEY_CATEGORY_ID to categoryId
            payload += KEY_ADD_TO_LIBRARY to addToLibrary
            payload += KEY_FETCH_DETAILS to fetchDetails
            payload += KEY_FETCH_CHAPTERS to fetchChapters
            payload += KEY_BATCH_ID to batchId

            if (offloadToFile) {
                val cacheFile = java.io.File(context.cacheDir, "mass_import_$batchId.txt")
                cacheFile.writeText(urls.joinToString("\n"))
                payload += KEY_URLS_FILE to cacheFile.absolutePath
            } else {
                payload += KEY_URLS to urls.toTypedArray()
            }

            val workRequest = androidx.work.OneTimeWorkRequestBuilder<eu.kanade.tachiyomi.data.massimport.MassImportJob>()
                .addTag(TAG)
                .addTag("batch_$batchId")
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(androidx.work.workDataOf(*payload.toTypedArray()))
                .build()

            context.workManager.enqueueUniqueWork("${TAG}_$batchId", androidx.work.ExistingWorkPolicy.KEEP, workRequest)
        }

        fun stop(context: android.content.Context) {
            context.workManager.cancelAllWorkByTag(TAG)
            _sharedQueue.update { list ->
                list.map {
                    if (it.status == BatchStatus.Pending || it.status == BatchStatus.Running) it.copy(status = BatchStatus.Cancelled) else it
                }
            }
        }

        fun cancelBatch(context: android.content.Context, batchId: String) {
            context.workManager.cancelUniqueWork("${TAG}_$batchId")
            _sharedQueue.update { list ->
                list.map {
                    if (it.id == batchId && (it.status == BatchStatus.Pending || it.status == BatchStatus.Running)) it.copy(status = BatchStatus.Cancelled) else it
                }
            }
        }

        fun removeBatch(batchId: String) {
            _sharedQueue.update { list -> list.filter { it.id != batchId } }
        }

        fun clearCompleted() {
            _sharedQueue.update { list -> list.filter { it.status != BatchStatus.Completed && it.status != BatchStatus.Cancelled } }
        }

        fun reinsertErrored(context: android.content.Context, batch: Batch) {
            val urls = MassImport().readErroredUrlsForBatch(batch.id).ifEmpty { batch.erroredUrls }
            if (urls.isEmpty()) return
            start(
                context = context,
                urls = urls,
                categoryId = batch.categoryId,
                addToLibrary = batch.addToLibrary,
                fetchChapters = batch.fetchChapters,
            )
        }

        fun requeueCancelled(context: android.content.Context, batch: Batch) {
            if (batch.status != BatchStatus.Cancelled) return
            if (batch.progress >= batch.total) return
            val processedCount = batch.progress
            val remainingUrls = if (processedCount < batch.urls.size) batch.urls.drop(processedCount) else batch.erroredUrls
            if (remainingUrls.isEmpty()) return
            start(
                context = context,
                urls = remainingUrls,
                categoryId = batch.categoryId,
                addToLibrary = batch.addToLibrary,
                fetchChapters = batch.fetchChapters,
            )
        }

        fun exportBatchUrls(batch: Batch): String = batch.urls.joinToString("\n")

        fun generateReport(batch: Batch): String {
            val rows = MassImport().readAllResultsForBatch(batch.id)
            val added = rows.count { it.getOrNull(1) == "added" }
            val skipped = rows.count { it.getOrNull(1) == "skipped" }
            val errored = rows.count { it.getOrNull(1) == "errored" }

            return buildString {
                appendLine("=== Mass Import Report ===")
                appendLine("Batch ID: ${batch.id}")
                appendLine("Status: ${batch.status}")
                appendLine()
                appendLine("=== Summary ===")
                appendLine("Total URLs: ${batch.urls.size}")
                appendLine("Successfully Added: $added")
                appendLine("Skipped (already in library): $skipped")
                appendLine("Errors: $errored")
                appendLine()
                if (errored > 0) {
                    appendLine("=== Failed URLs ($errored) ===")
                    rows.filter { it.getOrNull(1) == "errored" }.forEach { cols ->
                        val url = cols.getOrNull(2).orEmpty()
                        val msg = cols.getOrNull(5).orEmpty()
                        appendLine(url)
                        if (msg.isNotBlank()) appendLine("  Error: $msg")
                    }
                    appendLine()
                }
                if (skipped > 0) {
                    appendLine("=== Skipped URLs ($skipped) ===")
                    rows.filter { it.getOrNull(1) == "skipped" }.forEach { cols ->
                        appendLine(cols.getOrNull(2).orEmpty())
                    }
                    appendLine()
                }
                appendLine("=== All Input URLs (${batch.urls.size}) ===")
                batch.urls.forEach { appendLine(it) }
            }
        }

        fun generateErrorsWithMessages(batch: Batch): String {
            val rows = MassImport().readAllResultsForBatch(batch.id).filter { it.getOrNull(1) == "errored" }
            return buildString {
                rows.forEach { cols ->
                    val url = cols.getOrNull(2).orEmpty()
                    val msg = cols.getOrNull(5).orEmpty()
                    appendLine(url)
                    if (msg.isNotBlank()) appendLine("  -> $msg")
                }
            }
        }
    }
}

private fun eu.kanade.tachiyomi.source.model.SManga.toDomainManga(sourceId: Long, isNovel: Boolean = false): Manga {
    return Manga.create().copy(
        url = url,
        title = title,
        artist = artist,
        author = author,
        description = description,
        genre = genre?.split(", ") ?: emptyList(),
        status = status.toLong(),
        thumbnailUrl = thumbnail_url,
        initialized = initialized,
        source = sourceId,
        isNovel = isNovel,
    )
}
