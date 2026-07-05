package eu.kanade.tachiyomi.source.custom

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import eu.kanade.tachiyomi.util.source.getChapterUrlOrNull
import eu.kanade.tachiyomi.util.source.getMangaUrlOrNull
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.novel.TDMR
import java.io.File

/**
 * Manager for custom user-defined novel sources: loads/saves configs, creates [CustomNovelSource]
 * instances, validates and tests configurations.
 */
class CustomSourceManager(
    private val context: Context,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val customSourcesDir: File by lazy {
        File(context.filesDir, "custom_sources").apply { mkdirs() }
    }

    private val _customSources = MutableStateFlow<List<CustomNovelSource>>(emptyList())
    val customSources: Flow<List<CustomNovelSource>> = _customSources.asStateFlow()

    private val mutationLock = Any()

    init {
        loadAllSources()
    }

    /**
     * Load all saved custom sources from disk
     */
    fun loadAllSources() {
        val sources = mutableListOf<CustomNovelSource>()

        customSourcesDir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            try {
                val config = json.decodeFromString<CustomSourceConfig>(file.readText())
                sources.add(CustomNovelSource(config.withStableId()))
                logcat(LogPriority.DEBUG) { "Loaded custom source: ${config.name}" }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load custom source: ${file.name}" }
            }
        }

        _customSources.value = sources
    }

    /**
     * Create a new custom source from configuration
     */
    fun createSource(config: CustomSourceConfig): Result<CustomNovelSource> {
        return synchronized(mutationLock) { createSourceLocked(config) }
    }

    private fun createSourceLocked(config: CustomSourceConfig): Result<CustomNovelSource> {
        return try {
            val normalizedConfig = config.withStableId()
            validateConfig(normalizedConfig)
            val source = CustomNovelSource(normalizedConfig)
            saveSourceConfig(normalizedConfig)
            _customSources.update { it + source }
            Result.success(source)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update an existing custom source
     */
    fun updateSource(oldId: Long, newConfig: CustomSourceConfig): Result<CustomNovelSource> {
        return try {
            val normalizedConfig = newConfig.withStableId(oldId)
            validateConfig(normalizedConfig)
            deleteSource(oldId)
            createSource(normalizedConfig)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Delete a custom source
     */
    fun deleteSource(sourceId: Long): Boolean {
        val source = _customSources.value.find { it.id == sourceId } ?: return false

        // Remove from list
        _customSources.update { it.filter { s -> s.id != sourceId } }

        // Delete both the stable-id file and the legacy name-based file.
        val filesToDelete = customSourceStorageFileCandidates(customSourcesDir, source.id, source.name)
        return filesToDelete.any { it.delete() }
    }

    /**
     * Export a source configuration as JSON
     */
    fun exportSource(sourceId: Long): String? {
        val source = _customSources.value.find { it.id == sourceId } ?: return null
        val files = customSourceStorageFileCandidates(customSourcesDir, source.id, source.name)
        val file = files.firstOrNull { it.exists() } ?: return null
        return file.readText()
    }

    /**
     * Import a source from JSON string.
     * Parse failures and validation failures both surface readable, field-level messages.
     */
    fun importSource(jsonString: String): Result<CustomNovelSource> {
        if (jsonString.isBlank()) {
            return Result.failure(
                IllegalArgumentException(context.stringResource(TDMR.strings.custom_source_json_empty)),
            )
        }
        val config = try {
            json.decodeFromString<CustomSourceConfig>(jsonString)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException(friendlyParseError(e)))
        }
        val normalized = config.withStableId()
        return synchronized(mutationLock) {
            if (_customSources.value.any { it.id == normalized.id }) {
                Result.failure(
                    IllegalArgumentException(context.stringResource(TDMR.strings.custom_source_duplicate_exists)),
                )
            } else {
                createSourceLocked(normalized)
            }
        }
    }

    /** Export every custom source as a JSON array, for bulk backup/share. */
    fun exportAllSources(): String {
        val configs = _customSources.value.map { source ->
            val file = customSourceStorageFileCandidates(customSourcesDir, source.id, source.name)
                .firstOrNull { it.exists() }
            file?.let { runCatching { json.decodeFromString<CustomSourceConfig>(it.readText()) }.getOrNull() }
                ?: source.config
        }
        return json.encodeToString(configs)
    }

    /**
     * Import many sources from a JSON array (a single object is also accepted). Sources whose
     * stable id already exists are skipped; per-source validation failures are collected so one
     * bad entry doesn't abort the rest.
     */
    fun importSources(jsonString: String): Result<BulkImportResult> {
        if (jsonString.isBlank()) {
            return Result.failure(
                IllegalArgumentException(context.stringResource(TDMR.strings.custom_source_json_empty)),
            )
        }
        val listResult = runCatching { json.decodeFromString<List<CustomSourceConfig>>(jsonString) }
        val configs = listResult.getOrElse {
            runCatching { listOf(json.decodeFromString<CustomSourceConfig>(jsonString)) }
                .getOrElse { single ->
                    val cause = listResult.exceptionOrNull() ?: single
                    return Result.failure(IllegalArgumentException(friendlyParseError(cause)))
                }
        }

        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        synchronized(mutationLock) {
            configs.forEach { config ->
                val normalized = config.withStableId()
                if (_customSources.value.any { it.id == normalized.id }) {
                    skipped++
                    return@forEach
                }
                createSourceLocked(normalized).fold(
                    onSuccess = { imported++ },
                    onFailure = { errors.add("${config.name}: ${it.message}") },
                )
            }
        }
        return Result.success(BulkImportResult(imported, skipped, errors))
    }

    private fun friendlyParseError(e: Throwable): String = customSourceFriendlyParseError(e)

    /** Hand-editable skeleton config; placeholder selectors show the expected shape. */
    fun blankTemplateJson(): String = json.encodeToString(customSourceBlankTemplate())

    /** Blank config with the given name and base URL. */
    fun createBlankConfig(name: String, baseUrl: String): CustomSourceConfig {
        return CustomSourceConfig(
            name = name,
            baseUrl = baseUrl,
            popularUrl = "$baseUrl/page/{page}",
            searchUrl = "$baseUrl/?s={query}",
            selectors = SourceSelectors(
                popular = MangaListSelectors(list = ""),
                details = DetailSelectors(title = ""),
                chapters = ChapterSelectors(list = ""),
                content = ContentSelectors(primary = ""),
            ),
        )
    }

    /**
     * Throws [IllegalArgumentException] with joined field-level messages when invalid. Rules live in
     * [customSourceValidationErrors] so they can be unit-tested without a [Context].
     */
    fun validateConfig(config: CustomSourceConfig): List<String> {
        val errors = customSourceValidationErrors(config, _customSources.value.map { it.config })
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(errors.joinToString("; "))
        }
        return errors
    }

    /**
     * Test a config with real requests. [section] scopes the test to one part so the wizard can
     * validate per-step; [SourceTestSection.ALL] runs every endpoint. Off-main-thread.
     */
    suspend fun testSource(
        config: CustomSourceConfig,
        section: SourceTestSection = SourceTestSection.ALL,
    ): SourceTestResult = withContext(Dispatchers.IO) {
        val source = CustomNovelSource(config)
        val results = LinkedHashMap<String, TestStepResult>()
        var testManga: eu.kanade.tachiyomi.source.model.SManga? = null
        var testMangaSource: String? = null

        val all = section == SourceTestSection.ALL

        // Popular
        if (all || section == SourceTestSection.POPULAR) {
            try {
                val popular = source.getPopularManga(1)
                val success = popular.mangas.isNotEmpty()
                results["popular"] = TestStepResult(
                    success = success,
                    message = if (success) {
                        "Found ${popular.mangas.size} novels"
                    } else {
                        "No novels found (URL may need adjustment - some sites have novels on homepage without page param)"
                    },
                    data = buildMap {
                        popular.mangas.firstOrNull()?.let {
                            put("First Title", it.title)
                            put("First URL", it.url)
                            put("First Cover", it.thumbnail_url ?: "None")
                        }
                    }.ifEmpty { null },
                )
                if (success && testManga == null) {
                    testManga = popular.mangas.first()
                    testMangaSource = "popular"
                }
            } catch (e: Exception) {
                results["popular"] = TestStepResult(success = false, message = "Error: ${e.message}")
            }
        }

        // Latest
        if ((all || section == SourceTestSection.LATEST) && source.supportsLatest) {
            try {
                val latest = source.getLatestUpdates(1)
                val success = latest.mangas.isNotEmpty()
                results["latest"] = TestStepResult(
                    success = success,
                    message = if (success) "Found ${latest.mangas.size} novels" else "No novels found",
                    data = buildMap {
                        latest.mangas.firstOrNull()?.let {
                            put("First Title", it.title)
                            put("First URL", it.url)
                        }
                    }.ifEmpty { null },
                )
                if (success && testManga == null) {
                    testManga = latest.mangas.first()
                    testMangaSource = "latest"
                }
            } catch (e: Exception) {
                results["latest"] = TestStepResult(success = false, message = "Error: ${e.message}")
            }
        }

        // Search
        if (all || section == SourceTestSection.SEARCH) {
            try {
                val searchQuery = config.testSearchQuery?.ifBlank { null } ?: DEFAULT_TEST_QUERY
                val search = source.getSearchManga(1, searchQuery, eu.kanade.tachiyomi.source.model.FilterList())
                val success = search.mangas.isNotEmpty()
                results["search"] = TestStepResult(
                    success = success,
                    message = if (success) {
                        "Found ${search.mangas.size} results for '$searchQuery'"
                    } else {
                        "No results found for '$searchQuery'"
                    },
                    data = buildMap {
                        search.mangas.take(3).forEachIndexed { index, manga ->
                            put("Result ${index + 1}", manga.title)
                        }
                    }.ifEmpty { null },
                )
                if (success && testManga == null) {
                    testManga = search.mangas.first()
                    testMangaSource = "search"
                }
            } catch (e: Exception) {
                results["search"] = TestStepResult(success = false, message = "Error: ${e.message}")
            }
        }

        // Reading: details + chapters + content. Needs a sample novel; when this section is run on
        // its own, fetch one silently from whichever listing works instead of failing.
        if (all || section == SourceTestSection.READING) {
            // Prefer the sample novel over a listing's first result, which may be a stray non-novel link.
            val hasSample = !source.config.sampleNovelUrl.isNullOrBlank()
            if (testManga == null || hasSample) {
                findSampleManga(source)?.let {
                    testManga = it
                    testMangaSource = if (hasSample) "sample" else "auto"
                }
            }
            if (testManga == null) {
                results["reading"] = TestStepResult(
                    success = false,
                    message = "Couldn't open a novel to test. Check the popular/latest/search step first.",
                )
            } else {
                runReadingTest(source, testManga!!, testMangaSource, results)
            }
        }

        SourceTestResult(
            sourceName = config.name,
            overallSuccess = results.isNotEmpty() && results.values.all { it.success },
            steps = results,
        )
    }

    /** Try each listing endpoint until one yields a novel to drive the reading test. */
    private suspend fun findSampleManga(
        source: CustomNovelSource,
    ): eu.kanade.tachiyomi.source.model.SManga? {
        // Prefer the explicit sample novel URL captured in the wizard, so the reading test works
        // even when popular/latest/search aren't set up yet.
        source.config.sampleNovelUrl?.ifBlank { null }?.let { url ->
            val relative = if (url.startsWith("http")) url.removePrefix(source.config.baseUrl.trimEnd('/')) else url
            return eu.kanade.tachiyomi.source.model.SManga.create().apply {
                this.url = relative.ifBlank { url }
                title = "Sample"
            }
        }
        runCatching { source.getPopularManga(1).mangas.firstOrNull() }.getOrNull()?.let { return it }
        if (source.supportsLatest) {
            runCatching { source.getLatestUpdates(1).mangas.firstOrNull() }.getOrNull()?.let { return it }
        }
        val q = source.testSearchQueryOrDefault()
        return runCatching {
            source.getSearchManga(1, q, eu.kanade.tachiyomi.source.model.FilterList()).mangas.firstOrNull()
        }.getOrNull()
    }

    private suspend fun runReadingTest(
        source: CustomNovelSource,
        testManga: eu.kanade.tachiyomi.source.model.SManga,
        testMangaSource: String?,
        results: MutableMap<String, TestStepResult>,
    ) {
        val detailsUrl = source.getMangaUrlOrNull(testManga) ?: testManga.url
        try {
            val details = source.getMangaDetails(testManga)
            val success = details.title.isNotBlank()
            results["details"] = TestStepResult(
                success = success,
                message = if (success) "Got details for: ${details.title}" else "Failed to get title for $detailsUrl",
                data = mapOf(
                    "URL" to detailsUrl,
                    "Title" to details.title,
                    "Author" to (details.author ?: "N/A"),
                    "Description" to (details.description?.take(150)?.let { "$it..." } ?: "None"),
                    "Cover URL" to (details.thumbnail_url ?: "None"),
                    "Status" to when (details.status) {
                        eu.kanade.tachiyomi.source.model.SManga.ONGOING -> "Ongoing"
                        eu.kanade.tachiyomi.source.model.SManga.COMPLETED -> "Completed"
                        else -> "Unknown"
                    },
                    "Source" to (testMangaSource ?: "unknown"),
                ),
            )
        } catch (e: Exception) {
            results["details"] = TestStepResult(success = false, message = "Error for $detailsUrl: ${e.message}")
        }

        try {
            val chapters = source.getChapterList(testManga)
            val paginated = !source.config.selectors.chapters.nextPage.isNullOrBlank()
            results["chapters"] = TestStepResult(
                success = chapters.isNotEmpty(),
                message = if (chapters.isNotEmpty()) {
                    if (paginated) {
                        "Found ${chapters.size} chapters (followed list pagination)"
                    } else {
                        "Found ${chapters.size} chapters"
                    }
                } else {
                    "No chapters found"
                },
                data = if (chapters.isNotEmpty()) {
                    buildMap {
                        put("Total Chapters", chapters.size.toString())
                        put("First Chapter", chapters.last().name) // Reversed list usually
                        put("First URL", chapters.last().url)
                        put("Last Chapter", chapters.first().name)
                        put("Last URL", chapters.first().url)
                        if (paginated) put("List Pagination", "enabled")
                    }
                } else {
                    null
                },
            )

            if (chapters.isNotEmpty()) {
                val chapterToTest = chapters.last()
                val contentUrl = source.getChapterUrlOrNull(chapterToTest) ?: chapterToTest.url
                try {
                    val pages = source.getPageList(chapterToTest)
                    if (pages.isNotEmpty()) {
                        val content = source.fetchPageText(pages.first())
                        val cleanContent = content.replace(Regex("<[^>]+>"), "").trim()
                        val preview = if (cleanContent.length > 200) {
                            "${cleanContent.take(100)}...${cleanContent.takeLast(100)}"
                        } else {
                            cleanContent
                        }
                        results["content"] = TestStepResult(
                            success = content.isNotBlank(),
                            message = if (content.isNotBlank()) {
                                "Content length: ${content.length} chars"
                            } else {
                                "Empty content for $contentUrl - check content selectors or base source compatibility"
                            },
                            data = mapOf("URL" to contentUrl, "Preview" to preview),
                        )
                    } else {
                        results["content"] = TestStepResult(
                            success = false,
                            message = "No pages returned from getPageList for $contentUrl",
                        )
                    }
                } catch (e: Exception) {
                    results["content"] = TestStepResult(success = false, message = "Error for $contentUrl: ${e.message}")
                }
            }
        } catch (e: Exception) {
            results["chapters"] = TestStepResult(success = false, message = "Error: ${e.message}")
        }
    }

    private fun CustomNovelSource.testSearchQueryOrDefault(): String =
        config.testSearchQuery?.ifBlank { null } ?: DEFAULT_TEST_QUERY

    private fun saveSourceConfig(config: CustomSourceConfig) {
        val file = File(customSourcesDir, "${config.id ?: stableCustomSourceId(config.name, config.baseUrl)}.json")
        file.writeText(json.encodeToString(config))
    }

    private fun CustomSourceConfig.withStableId(id: Long? = this.id): CustomSourceConfig {
        return if (id != null) {
            copy(id = id)
        } else {
            copy(id = stableCustomSourceId(name, baseUrl))
        }
    }
}

/**
 * Validates only what the config actually uses: one listing URL (popular/latest/search), each
 * listing's list selector only when its URL is set, and chapters via a list selector OR a generated
 * URL pattern. [existing] is checked for duplicate name / base URL.
 */
internal fun customSourceValidationErrors(
    config: CustomSourceConfig,
    existing: List<CustomSourceConfig> = emptyList(),
): List<String> {
    val errors = mutableListOf<String>()

    if (config.name.isBlank()) {
        errors.add("Name is required")
    }

    if (config.baseUrl.isBlank()) {
        errors.add("Base URL is required")
    } else if (!config.baseUrl.startsWith("http://") && !config.baseUrl.startsWith("https://")) {
        errors.add("Base URL must start with http:// or https://")
    }

    // Skip URL and selector validation when delegating to an installed extension.
    if (config.basedOnSourceId == null) {
        val hasPopular = config.popularUrl.isNotBlank()
        val hasLatest = !config.latestUrl.isNullOrBlank()
        val hasSearch = config.searchUrl.isNotBlank()
        if (!hasPopular && !hasLatest && !hasSearch) {
            errors.add("At least one listing URL (popular, latest or search) is required")
        }

        // A listing's list selector is only needed if that listing is enabled. Latest and search
        // fall back to the popular list selector, so the popular list selector covers them.
        if (hasPopular && config.selectors.popular.list.isBlank()) {
            errors.add("Popular list selector is required")
        }
        if (!hasPopular && (hasLatest || hasSearch) &&
            config.selectors.popular.list.isBlank() &&
            config.selectors.latest?.list.isNullOrBlank() &&
            config.selectors.search?.list.isNullOrBlank()
        ) {
            errors.add("A list selector is required for the latest/search listing")
        }

        if (config.selectors.details.title.isBlank()) {
            errors.add("Details title selector is required")
        }

        val hasChapterList = config.selectors.chapters.list.isNotBlank()
        val hasChapterPattern = !config.selectors.chapters.urlPattern.isNullOrBlank()
        if (!hasChapterList && !hasChapterPattern) {
            errors.add("A chapter list selector or chapter URL pattern is required")
        }

        if (config.selectors.content.primary.isBlank()) {
            errors.add("Content primary selector is required")
        }
    }

    if (existing.any { it.name == config.name && it.id != config.id }) {
        errors.add("A source with this name already exists")
    }
    val normalizedBase = config.baseUrl.trim().trimEnd('/').lowercase()
    if (normalizedBase.isNotBlank() &&
        existing.any {
            it.id != config.id && it.baseUrl.trim().trimEnd('/').lowercase() == normalizedBase
        }
    ) {
        errors.add("A source with this base URL already exists")
    }

    return errors
}

/** Documented skeleton config used by the import dialog's "paste template" action. */
internal fun customSourceBlankTemplate(): CustomSourceConfig = CustomSourceConfig(
    name = "My Source",
    baseUrl = "https://example.com",
    language = "en",
    popularUrl = "https://example.com/page/{page}",
    latestUrl = "https://example.com/latest/page/{page}",
    searchUrl = "https://example.com/?s={query}",
    selectors = SourceSelectors(
        popular = MangaListSelectors(
            list = ".novel-item",
            link = "a",
            title = ".novel-title",
            cover = "img",
            nextPage = ".pagination .next",
        ),
        details = DetailSelectors(
            title = "h1.title",
            author = ".author a",
            description = ".description",
            genre = ".genre a",
            status = ".status",
            cover = ".cover img",
        ),
        chapters = ChapterSelectors(
            list = ".chapter-list li",
            link = "a",
            name = "a",
            date = ".date",
        ),
        content = ContentSelectors(
            primary = ".chapter-content",
        ),
    ),
    reverseChapters = false,
)

/** Scopes [CustomSourceManager.testSource] so each wizard step can validate just its own section. */
enum class SourceTestSection { POPULAR, LATEST, SEARCH, READING, ALL }

// Fallback search test query when the source has no testSearchQuery. Language-neutral; non-Latin
// sites should set testSearchQuery (the wizard fills it from the search probe word).
internal const val DEFAULT_TEST_QUERY = "a"

/** Turns a JSON decode failure into a readable, field-aware message for the import dialog. */
internal fun customSourceFriendlyParseError(e: Throwable): String {
    val msg = e.message.orEmpty()
    return when {
        msg.contains("missing", ignoreCase = true) || e is kotlinx.serialization.MissingFieldException ->
            "Missing required field(s). $msg".trim()
        msg.contains("Unexpected JSON token") || msg.contains("Expected") ->
            "Malformed JSON: $msg"
        else -> "Invalid source JSON: ${msg.ifBlank { e.javaClass.simpleName }}"
    }
}

data class BulkImportResult(
    val imported: Int,
    val skipped: Int,
    val errors: List<String>,
)

data class TestStepResult(
    val success: Boolean,
    val message: String,
    val data: Map<String, String>? = null,
)

data class SourceTestResult(
    val sourceName: String,
    val overallSuccess: Boolean,
    val steps: Map<String, TestStepResult>,
)
