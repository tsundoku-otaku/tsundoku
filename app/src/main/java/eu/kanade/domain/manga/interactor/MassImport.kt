package eu.kanade.domain.manga.interactor

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.source.normalizeSourcePath
import eu.kanade.tachiyomi.util.source.toggleLeadingSlash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.download.service.NovelDownloadPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.util.system.workManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class MassImport(
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val novelDownloadPreferences: NovelDownloadPreferences = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) {
    private val missingSourceHostLogCache = ConcurrentHashMap<String, Boolean>()

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

    suspend fun import(
        urls: List<String>,
        addToLibrary: Boolean = true,
    ) {
        importInternal(
            urls = urls,
            addToLibrary = addToLibrary,
            categoryId = null,
            fetchDetails = true,
            fetchChapters = false,
        )
    }

    private suspend fun importInternal(
        urls: List<String>,
        addToLibrary: Boolean,
        categoryId: Long?,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
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
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val activeImports = ConcurrentHashMap<String, Boolean>()
        val sourceSemaphores = ConcurrentHashMap<Long, Semaphore>()
        val sourceLastRequest = ConcurrentHashMap<Long, AtomicLong>()

        _progress.value = ImportProgress(0, validUrls.size, "", "Starting...", true, emptyList(), concurrency)

        validUrls.asFlow()
            .flatMapMerge(concurrency) { rawUrl ->
                    flow {
                        if (isCancelled) return@flow
                        val cleanUrl = rawUrl.trim()
                        if (cleanUrl.isEmpty()) return@flow

                        val source = findMatchingSource(cleanUrl, novelSources) ?: run {
                            synchronized(currentResult) {
                                if (currentResult.errored.size < 10) currentResult.errored.add(ErroredNovel(cleanUrl, "No matching source found for URL"))
                            }
                            completedCount.incrementAndGet()
                            return@flow
                        }
                        val sourceId = source.id
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
                                    source = source,
                                    result = currentResult,
                                    addToLibrary = addToLibrary,
                                    categoryId = categoryId,
                                    fetchDetails = fetchDetails,
                                    fetchChapters = fetchChapters,
                                    libraryUrlIndex = libraryUrlIndex,
                                )
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR, e) { "Error importing $cleanUrl" }
                                synchronized(currentResult) {
                                    if (currentResult.errored.size < 10) currentResult.errored.add(ErroredNovel(cleanUrl, e.message ?: "Unknown error"))
                                }
                            } finally {
                                activeImports.remove(cleanUrl)
                                val done = completedCount.incrementAndGet()
                                _progress.update {
                                    val statusText = if (activeImports.isEmpty()) "Finishing..." else "Processing ${activeImports.size} novel(s)..."
                                    it?.copy(current = done, status = statusText, activeImports = activeImports.keys.toList())
                                }
                                sourceLastRequest.computeIfAbsent(sourceId) { AtomicLong(0L) }.set(System.currentTimeMillis())
                            }
                        }
                        emit(Unit)
                    }
                }
                .collect()

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
        source: CatalogueSource,
        result: ImportResult,
        addToLibrary: Boolean,
        categoryId: Long?,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        libraryUrlIndex: Set<Pair<Long, String>>,
    ) {
        val path = extractPathFromUrl(url, getSourceBaseUrl(source), source)
        if (path.isEmpty()) {
            synchronized(result) {
                if (result.errored.size < 10) result.errored.add(ErroredNovel(url, "Could not extract path from URL"))
            }
            return
        }

        if (libraryUrlIndex.contains(source.id to path)) {
            synchronized(result) {
                if (result.skipped.size < 10) result.skipped.add(SkippedNovel(url, url, "Already in library"))
            }
            return
        }

        val existingManga = mangaRepository.getLiteMangaByUrlAndSourceId(path, source.id)
        if (existingManga != null && existingManga.favorite) {
            synchronized(result) {
                if (result.skipped.size < 10) result.skipped.add(SkippedNovel(existingManga.title, url, "Already in library"))
            }
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
            } else {
                synchronized(result) {
                    if (result.added.size < 10) result.added.add(ImportedNovel(manga.title, url, manga))
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to fetch novel from $url" }
            synchronized(result) {
                if (result.errored.size < 10) result.errored.add(ErroredNovel(url, "Failed to fetch: ${e.message}"))
            }
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

    data class Batch(
        val id: String,
        val urls: List<String>,
        val sourceUrls: List<String> = urls,
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
        Paused
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

        private val _sharedQueue = MutableStateFlow<List<Batch>>(emptyList())
        val sharedQueue = _sharedQueue.asStateFlow()

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
