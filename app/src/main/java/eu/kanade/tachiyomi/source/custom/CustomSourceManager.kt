package eu.kanade.tachiyomi.source.custom

import android.content.Context
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Manager for custom user-defined novel sources
 *
 * Handles:
 * - Loading/saving custom source configurations
 * - Creating CustomNovelSource instances from configs
 * - Validating source configurations
 * - Providing templates for common site structures
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
     * Get all custom sources as CatalogueSource list
     */
    fun getSources(): List<CatalogueSource> = _customSources.value

    /**
     * Create a new custom source from configuration
     */
    fun createSource(config: CustomSourceConfig): Result<CustomNovelSource> {
        return try {
            // Validate config
            val normalizedConfig = config.withStableId()
            validateConfig(normalizedConfig)

            // Create source
            val source = CustomNovelSource(normalizedConfig)

            // Save to disk
            saveSourceConfig(normalizedConfig)

            // Add to list
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

            // Remove old source
            deleteSource(oldId)

            // Create new source
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

        // Delete both the stable id file and the legacy name-based file.
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
            return Result.failure(IllegalArgumentException("JSON is empty"))
        }
        val config = try {
            json.decodeFromString<CustomSourceConfig>(jsonString)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException(friendlyParseError(e)))
        }
        // createSource runs validateConfig, which throws joined, field-level error messages.
        return createSource(config)
    }

    private fun friendlyParseError(e: Throwable): String = customSourceFriendlyParseError(e)

    /**
     * A documented, hand-editable skeleton config. Placeholder selectors show the expected shape.
     */
    fun blankTemplateJson(): String {
        val template = CustomSourceConfig(
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
        return json.encodeToString(template)
    }

    /**
     * Create a blank config with the given name and base URL.
     * Templates have been removed — use extension repos for pre-built themes.
     */
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
     * Validate a source configuration
     */
    fun validateConfig(config: CustomSourceConfig): List<String> {
        val errors = mutableListOf<String>()

        if (config.name.isBlank()) {
            errors.add("Name is required")
        }

        if (config.baseUrl.isBlank()) {
            errors.add("Base URL is required")
        } else if (!config.baseUrl.startsWith("http://") && !config.baseUrl.startsWith("https://")) {
            errors.add("Base URL must start with http:// or https://")
        }

        // Skip URL and selector validation when based on an extension
        if (config.basedOnSourceId == null) {
            if (config.popularUrl.isBlank()) {
                errors.add("Popular URL is required")
            }

            if (config.searchUrl.isBlank()) {
                errors.add("Search URL is required")
            }

            if (config.selectors.popular.list.isBlank()) {
                errors.add("Popular list selector is required")
            }

            if (config.selectors.details.title.isBlank()) {
                errors.add("Details title selector is required")
            }

            if (config.selectors.chapters.list.isBlank()) {
                errors.add("Chapters list selector is required")
            }

            if (config.selectors.content.primary.isBlank()) {
                errors.add("Content primary selector is required")
            }
        }

        // Check for duplicate name
        if (_customSources.value.any { it.name == config.name && it.id != config.id }) {
            errors.add("A source with this name already exists")
        }
        val normalizedBase = config.baseUrl.trim().trimEnd('/').lowercase()
        if (normalizedBase.isNotBlank() &&
            _customSources.value.any {
                it.id != config.id && it.config.baseUrl.trim().trimEnd('/').lowercase() == normalizedBase
            }
        ) {
            errors.add("A source with this base URL already exists")
        }

        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(errors.joinToString("; "))
        }

        return errors
    }

    /**
     * Test a source configuration by making actual requests. [section] scopes the test so the
     * wizard can validate one part at a time (after each step) instead of only at the very end;
     * [SourceTestSection.ALL] runs every endpoint. Always runs off the main thread so the WebView
     * wizard never triggers NetworkOnMainThreadException.
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

        // Verifies listing pagination by fetching page 2 when the listing reports a next page.
        // Returns null when there's no page 2 to test; otherwise (ok, human-readable note).
        suspend fun verifyPage2(
            hasNextPage: Boolean,
            fetch: suspend () -> eu.kanade.tachiyomi.source.model.MangasPage,
        ): Pair<Boolean, String>? {
            if (!hasNextPage) return null
            return try {
                val p2 = fetch()
                if (p2.mangas.isNotEmpty()) {
                    true to "Page 2: ${p2.mangas.size} found"
                } else {
                    false to "Page 2 returned nothing"
                }
            } catch (e: Exception) {
                false to "Page 2 error: ${e.message}"
            }
        }

        // Popular
        if (all || section == SourceTestSection.POPULAR) {
            try {
                val popular = source.getPopularManga(1)
                val success = popular.mangas.isNotEmpty()
                val page2 = if (success) verifyPage2(popular.hasNextPage) { source.getPopularManga(2) } else null
                results["popular"] = TestStepResult(
                    success = success && (page2?.first ?: true),
                    message = buildString {
                        append(
                            if (success) {
                                "Found ${popular.mangas.size} novels"
                            } else {
                                "No novels found (URL may need adjustment - some sites have novels on homepage without page param)"
                            },
                        )
                        page2?.let {
                            append(" · ")
                            append(it.second)
                        }
                    },
                    data = buildMap {
                        popular.mangas.firstOrNull()?.let {
                            put("First Title", it.title)
                            put("First URL", it.url)
                            put("First Cover", it.thumbnail_url ?: "None")
                        }
                        page2?.let { put("Page 2", it.second) }
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
                val page2 = if (success) verifyPage2(latest.hasNextPage) { source.getLatestUpdates(2) } else null
                results["latest"] = TestStepResult(
                    success = success && (page2?.first ?: true),
                    message = buildString {
                        append(if (success) "Found ${latest.mangas.size} novels" else "No novels found")
                        page2?.let {
                            append(" · ")
                            append(it.second)
                        }
                    },
                    data = buildMap {
                        latest.mangas.firstOrNull()?.let {
                            put("First Title", it.title)
                            put("First URL", it.url)
                        }
                        page2?.let { put("Page 2", it.second) }
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
                val page2 = if (success) {
                    verifyPage2(search.hasNextPage) {
                        source.getSearchManga(2, searchQuery, eu.kanade.tachiyomi.source.model.FilterList())
                    }
                } else {
                    null
                }
                results["search"] = TestStepResult(
                    success = success && (page2?.first ?: true),
                    message = buildString {
                        append(
                            if (success) {
                                "Found ${search.mangas.size} results for '$searchQuery'"
                            } else {
                                "No results found for '$searchQuery'"
                            },
                        )
                        page2?.let {
                            append(" · ")
                            append(it.second)
                        }
                    },
                    data = buildMap {
                        search.mangas.take(3).forEachIndexed { index, manga ->
                            put("Result ${index + 1}", manga.title)
                        }
                        page2?.let { put("Page 2", it.second) }
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
            if (testManga == null) {
                testManga = findSampleManga(source)?.also { testMangaSource = "auto" }
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
        try {
            val details = source.getMangaDetails(testManga)
            val success = details.title.isNotBlank()
            results["details"] = TestStepResult(
                success = success,
                message = if (success) "Got details for: ${details.title}" else "Failed to get title",
                data = mapOf(
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
            results["details"] = TestStepResult(success = false, message = "Error: ${e.message}")
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
                try {
                    val chapterToTest = chapters.last()
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
                                "Empty content - check content selectors or base source compatibility"
                            },
                            data = mapOf("Preview" to preview),
                        )
                    } else {
                        results["content"] = TestStepResult(
                            success = false,
                            message = "No pages returned from getPageList",
                        )
                    }
                } catch (e: Exception) {
                    results["content"] = TestStepResult(success = false, message = "Error: ${e.message}")
                }

                // "Page 2" of reading: confirm a second chapter's content also resolves.
                if (chapters.size > 1) {
                    try {
                        val secondChapter = chapters[chapters.size - 2]
                        val pages2 = source.getPageList(secondChapter)
                        val content2 = pages2.firstOrNull()?.let { source.fetchPageText(it) }.orEmpty()
                        results["content_page2"] = TestStepResult(
                            success = content2.isNotBlank(),
                            message = if (content2.isNotBlank()) {
                                "Second chapter content: ${content2.length} chars"
                            } else {
                                "Second chapter returned empty content"
                            },
                            data = mapOf("Chapter" to secondChapter.name),
                        )
                    } catch (e: Exception) {
                        results["content_page2"] = TestStepResult(success = false, message = "Error: ${e.message}")
                    }
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
