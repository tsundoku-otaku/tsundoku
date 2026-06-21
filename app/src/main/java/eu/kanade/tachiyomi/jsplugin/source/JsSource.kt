package eu.kanade.tachiyomi.jsplugin.source

import android.content.Context
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.jsplugin.model.InstalledJsPlugin
import eu.kanade.tachiyomi.jsplugin.model.JsPlugin
import eu.kanade.tachiyomi.jsplugin.resolveJsPluginSite
import eu.kanade.tachiyomi.jsplugin.runtime.PluginRuntime
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.lang.normalizeHtmlDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import okhttp3.Headers
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A CatalogueSource implementation backed by a JavaScript plugin.
 * Executes LNReader-compatible plugins through JsPluginRuntime.
 */
class JsSource(
    private val installedPlugin: InstalledJsPlugin,
    private val siteOverride: String? = null,
) : CatalogueSource, ConfigurableSource {

    private val plugin: JsPlugin = installedPlugin.plugin
    private val jsCode: String = installedPlugin.code
    private val context: Context = Injekt.get()
    private val networkHelper: NetworkHelper = Injekt.get()

    private val json = Json { ignoreUnknownKeys = true }

    // Single-thread executor for JS execution to avoid JNI caching issues
    // QuickJS caches the JNI environment on the thread that creates it,
    // so all operations must happen on the same thread
    private val jsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "JsSource-$pluginId").apply { isDaemon = true }
    }
    private val jsDispatcher = jsExecutor.asCoroutineDispatcher()

    // Cached runtime instance to avoid recreating for every method call
    @Volatile private var cachedInstance: eu.kanade.tachiyomi.jsplugin.runtime.PluginInstance? = null
    private val instanceLock = Any()
    private val instanceMutex = kotlinx.coroutines.sync.Mutex()
    private var lastUsed = System.currentTimeMillis()

    // Chapter list may aggregate parseNovel + N parsePage calls; re-deriving it would refetch every page.
    private val chaptersCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<SChapter>, Long>>()

    // Short so a manual details/chapter-list refresh actually refetches.
    private val cacheTimeout = 60_000L

    // getMangaDetails and getChapterList both map to one plugin.parseNovel; parseChapter serves
    // reader, translation and download paths. Mutex makes concurrent callers share one fetch.
    private val parseNovelCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val parseNovelMutex = kotlinx.coroutines.sync.Mutex()
    private val chapterTextCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val chapterTextMutex = kotlinx.coroutines.sync.Mutex()

    // inferHasNextPage probe result, reused when the user pages forward.
    private val browseProbeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    // Raw JSON of plugin.filters / plugin.pluginSettings. Static per plugin version; caching it
    // limits the runBlocking JS execution in getFilterList/setupPreferenceScreen to the first call.
    @Volatile private var filtersJsonCache: String? = null

    @Volatile private var pluginSettingsJsonCache: String? = null

    companion object {
        private const val INSTANCE_TIMEOUT_MS = 60_000L // 1 minute timeout
        private const val BROWSE_PROBE_TTL_MS = 60_000L

        // Sentinel handed to plugin.resolveUrl to discover its base/prefix join shape.
        private const val PATH_PROBE_TOKEN = "__tsundoku_path_probe__"

        private val HTML_TAG_REGEX = Regex(
            "<(?:p|div|br|span|h[1-6]|ul|ol|li|a|img|table|blockquote|strong|em|b|i|code)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val DOUBLE_ENCODED_ENTITY_REGEX = Regex(
            "&amp;([a-zA-Z][a-zA-Z0-9]{1,30}|#[0-9]{1,7}|#x[0-9a-fA-F]{1,6});",
        )

        /** True when [text] contains structural or inline HTML tags. */
        internal fun looksLikeHtml(text: String): Boolean =
            HTML_TAG_REGEX.containsMatchIn(text)

        /**
         * Normalize raw chapter text extracted from a JS plugin response.
         *
         * - Plain text: fully unescape all HTML entities so `&lt;D&gt;` → `<D>`.
         *   The viewer's `escapeHtml` re-encodes them correctly for display.
         * - HTML: fix only double-encoded entities (`&amp;lt;` → `&lt;`) without
         *   destroying HTML structure (full unescape would turn `&lt;tag&gt;` into a
         *   real `<tag>` element).
         */
        internal fun normalizePluginContent(raw: String): String =
            if (!looksLikeHtml(raw)) {
                org.jsoup.parser.Parser.unescapeEntities(raw, false)
            } else {
                fixDoubleEncodedEntities(raw)
            }

        /** Fixes `&amp;lt;` → `&lt;`, `&amp;nbsp;` → `&nbsp;`, etc. for HTML content. */
        internal fun fixDoubleEncodedEntities(html: String): String {
            if (!html.contains("&amp;")) return html
            return html.replace(DOUBLE_ENCODED_ENTITY_REGEX) { "&${it.groupValues[1]};" }
        }

        /**
         * Pick the content field from a parsed JSON plugin response object.
         * Returns null when none of the known fields are present.
         */
        internal fun pickContentField(obj: JsonObject): String? =
            obj["chapterText"]?.jsonPrimitive?.content
                ?: obj["text"]?.jsonPrimitive?.content
                ?: obj["content"]?.jsonPrimitive?.content
    }

    private val pluginId: String = plugin.id

    override val id: Long = plugin.sourceId()
    override val name: String = plugin.name
    override val lang: String = plugin.langCode()
    override val supportsLatest: Boolean = true

    // Novel source marker
    override val isNovelSource: Boolean = true

    // Visible name of the source with language and JS marker
    override fun toString(): String = "$name (${lang.uppercase()}) (JS)"

    val baseUrl: String = siteOverride?.trim()?.trimEnd('/').orEmpty()
        .ifBlank { resolveJsPluginSite(metadataSite = plugin.site, code = jsCode) }
        .ifBlank { "https://example.com" }
    val iconUrl: String = plugin.iconUrl
    val version: String = plugin.version

    fun getCoverRequestHeaders(coverUrl: String?): Headers {
        return try {
            // Return default referer header for cover images.
            // Plugins can override via imageRequestInit or headers properties if needed.
            Headers.Builder()
                .set("Referer", "$baseUrl/")
                .build()
        } catch (_: Exception) {
            Headers.Builder()
                .set("Referer", "$baseUrl/")
                .build()
        }
    }

    /**
     * Get or create a cached plugin instance to avoid expensive re-initialization.
     * Must be called from jsDispatcher to ensure proper JNI environment.
     */
    private suspend fun getOrCreateInstance(): eu.kanade.tachiyomi.jsplugin.runtime.PluginInstance = withContext(
        jsDispatcher,
    ) {
        // instanceMutex serializes check-create-assign; instanceLock additionally guards
        // cachedInstance so invalidateInstance (any thread) can't race these accesses.
        instanceMutex.withLock {
            val now = System.currentTimeMillis()
            val existing = synchronized(instanceLock) {
                val current = cachedInstance
                when {
                    current == null -> null
                    (now - lastUsed) < INSTANCE_TIMEOUT_MS -> {
                        lastUsed = now
                        current
                    }
                    else -> {
                        runCatching { current.close() }
                        cachedInstance = null
                        null
                    }
                }
            }
            if (existing != null) return@withLock existing

            val runtime = PluginRuntime(pluginId, context, jsDispatcher, baseUrl)
            val newInstance = try {
                runtime.executePlugin(jsCode)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "JsSource[$pluginId]: Failed to execute plugin" }
                throw e
            }

            if (!siteOverride.isNullOrBlank()) {
                val escapedSiteOverride = escapeJsString(siteOverride)
                newInstance.execute(
                    """
                    if (globalThis.plugin) {
                        globalThis.plugin.site = '$escapedSiteOverride';
                        globalThis.plugin.sourceSite = globalThis.plugin.site;
                    }
                    """.trimIndent(),
                )
            }

            synchronized(instanceLock) {
                lastUsed = System.currentTimeMillis()
                cachedInstance = newInstance
            }
            newInstance
        }
    }

    /**
     * Invalidate the cached instance (call on errors).
     * [expected] guards against closing a runtime that was already replaced: a failing call
     * holding an old instance must not kill the fresh one a concurrent caller created.
     */
    private fun invalidateInstance(expected: eu.kanade.tachiyomi.jsplugin.runtime.PluginInstance? = null) {
        synchronized(instanceLock) {
            if (expected != null && cachedInstance !== expected) return
            runCatching { cachedInstance?.close() }
            cachedInstance = null
        }
    }

    /**
     * Close the cached QuickJS runtime to free native memory without killing the executor.
     * Safe while the source may still be referenced; the next call recreates the runtime.
     */
    suspend fun releaseRuntime() = withContext(jsDispatcher) {
        invalidateInstance()
    }

    /**
     * Force cleanup of resources. Call when navigating away.
     */
    fun cleanup() {
        invalidateInstance()
        jsExecutor.shutdown()
    }

    fun withSiteOverride(site: String?): JsSource {
        return JsSource(installedPlugin, site)
    }

    /** True when this source was built from the same plugin version and code. */
    fun isSamePlugin(other: InstalledJsPlugin): Boolean =
        installedPlugin.installedVersion == other.installedVersion &&
            installedPlugin.code == other.code

    /**
     * Execute a plugin method and return JSON result.
     * All JS execution happens on jsDispatcher to ensure JNI environment is consistent.
     *
     * Note: some plugin methods are async and return a Promise (via TS __awaiter).
     * QuickJS-KT doesn't always properly await Promises returned from evaluate().
     * We use a global variable approach to store the result after Promise resolution.
     */
    private suspend fun executePluginMethod(methodCall: String): String = withContext(jsDispatcher) {
        try {
            executePluginMethodAttempt(methodCall)
        } catch (e: Exception) {
            // The runtime can be closed under an in-flight call (releaseRuntime after a plugin
            // update, instance timeout); the attempt already invalidated it, so retry once.
            if (e.message.orEmpty().contains("context is destroyed", ignoreCase = true)) {
                logcat(LogPriority.WARN) { "JsSource[$pluginId]: runtime closed mid-call, retrying: $methodCall" }
                executePluginMethodAttempt(methodCall)
            } else {
                throw e
            }
        }
    }

    private suspend fun executePluginMethodAttempt(methodCall: String): String {
        val instance = try {
            getOrCreateInstance()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "JsSource[$pluginId]: Failed to create plugin instance" }
            throw e
        }

        val token = "tsundoku_${System.nanoTime()}"
        val escapedSiteOverride = siteOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { escapeJsString(it) }
        try {
            if (escapedSiteOverride != null) {
                instance.execute(
                    """
                    if (globalThis.plugin) {
                        globalThis.plugin.site = '$escapedSiteOverride';
                        globalThis.plugin.sourceSite = globalThis.plugin.site;
                    }
                    """.trimIndent(),
                )
            }
            logcat(LogPriority.INFO) { "JsSource[$pluginId]: Executing: $methodCall" }

            // Store result in global variable, handle Promise resolution in JS
            instance.execute(
                """
                (function() {
                    globalThis.__tsundoku_result_$token = null;
                    globalThis.__tsundoku_error_$token = null;
                    globalThis.__tsundoku_done_$token = false;

                    try {
                        var maybePromise = ($methodCall);
                        Promise.resolve(maybePromise).then(function(result) {
                            try {
                                globalThis.__tsundoku_result_$token = JSON.stringify(result);
                            } catch(e) {
                                globalThis.__tsundoku_result_$token = 'null';
                            }
                            globalThis.__tsundoku_done_$token = true;
                        }).catch(function(e) {
                            globalThis.__tsundoku_error_$token = (e && e.stack) ? (String(e) + '\n' + e.stack) : String(e);
                            globalThis.__tsundoku_done_$token = true;
                        });
                    } catch(e) {
                        globalThis.__tsundoku_error_$token = (e && e.stack) ? (String(e) + '\n' + e.stack) : String(e);
                        globalThis.__tsundoku_done_$token = true;
                    }
                })();
                """.trimIndent(),
            )

            // Poll for completion with proper async waiting
            // QuickJS asyncFunction needs actual time to execute Kotlin coroutines
            var attempts = 0
            val maxAttempts = 600 // 30 seconds max (600 * 50ms)
            while (attempts < maxAttempts) {
                val done = instance.execute("globalThis.__tsundoku_done_$token") as? Boolean ?: false
                if (done) break
                attempts++
                // Give async functions time to execute their Kotlin coroutines
                kotlinx.coroutines.delay(50)
                // Also process JS microtasks
                instance.execute("null")
            }

            if (attempts >= maxAttempts) {
                logcat(LogPriority.WARN) { "JsSource[$pluginId]: Execution timed out after ${maxAttempts * 50}ms" }
            }

            // Read results FIRST before cleanup
            val error = instance.execute("globalThis.__tsundoku_error_$token") as? String
            val jsonResult = instance.execute("globalThis.__tsundoku_result_$token") as? String

            // Cleanup global variables AFTER reading
            instance.execute(
                """
                delete globalThis.__tsundoku_result_$token;
                delete globalThis.__tsundoku_error_$token;
                delete globalThis.__tsundoku_done_$token;
                if (globalThis.__clearCheerioCache) {
                    globalThis.__clearCheerioCache();
                }
                """.trimIndent(),
            )

            // Check for errors - "null" string from error means no error, not "Plugin error: null"
            if (!error.isNullOrEmpty() && error != "null") {
                throw Exception("Plugin error while executing [$methodCall]: $error")
            }

            logcat(LogPriority.INFO) { "JsSource[$pluginId]: Result: ${jsonResult?.take(200)}" }
            return jsonResult ?: "null"
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "JsSource[$pluginId]: Error executing: $methodCall" }
            // Invalidate on critical errors so the dead runtime isn't reused
            val message = e.message.orEmpty()
            if (message.contains("SyntaxError", ignoreCase = true) ||
                message.contains("vm is not cached", ignoreCase = true) ||
                message.contains("context is destroyed", ignoreCase = true)
            ) {
                invalidateInstance(expected = instance)
            }
            throw e
        }
    }

    /**
     * Execute a browse method call, consuming a matching inferHasNextPage probe result if one
     * is pending so paging forward doesn't refetch the page the probe already pulled.
     */
    private suspend fun executeBrowseMethod(methodCall: String): String {
        browseProbeCache.remove(methodCall)?.let { (cached, ts) ->
            if (System.currentTimeMillis() - ts < BROWSE_PROBE_TTL_MS) {
                return cached
            }
        }
        return executePluginMethod(methodCall)
    }

    // CatalogueSource implementation

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        try {
            val currentResult =
                executeBrowseMethod("plugin.popularNovels($page, { showLatestNovels: false, filters: plugin.filters })")
            val parsed = parseMangasPage(currentResult, page)
            inferHasNextPage(
                currentPage = page,
                current = parsed,
                methodCallForPage = { probePage ->
                    "plugin.popularNovels($probePage, { showLatestNovels: false, filters: plugin.filters })"
                },
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error in getPopularManga for ${plugin.name}" }
            MangasPage(emptyList(), false)
        }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = withContext(Dispatchers.IO) {
        try {
            val currentResult =
                executeBrowseMethod("plugin.popularNovels($page, { showLatestNovels: true, filters: plugin.filters })")
            val parsed = parseMangasPage(currentResult, page)
            inferHasNextPage(
                currentPage = page,
                current = parsed,
                methodCallForPage = { probePage ->
                    "plugin.popularNovels($probePage, { showLatestNovels: true, filters: plugin.filters })"
                },
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error in getLatestUpdates for ${plugin.name}" }
            MangasPage(emptyList(), false)
        }
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withContext(
        Dispatchers.IO,
    ) {
        try {
            val escapedQuery = escapeJsString(query)

            // Determine if non-default filters are present
            val hasActiveFilters = filters.isNotEmpty() && filters.any { filter ->
                when (filter) {
                    is JsSelectFilter -> filter.state != 0
                    is JsCheckboxGroup -> filter.selectedValues().isNotEmpty()
                    is JsTriStateGroup -> filter.includedValues().isNotEmpty() || filter.excludedValues().isNotEmpty()
                    is JsSwitchFilter -> filter.state
                    is JsTextFilter -> filter.state.isNotBlank()
                    is Filter.CheckBox -> filter.state
                    is Filter.Text -> filter.state.isNotBlank()
                    else -> false
                }
            }

            if (query.isNotBlank()) {
                // Use searchNovels for text search
                val currentResult = executeBrowseMethod("plugin.searchNovels('$escapedQuery', $page)")
                val parsed = parseMangasPage(currentResult, page)
                inferHasNextPage(
                    currentPage = page,
                    current = parsed,
                    methodCallForPage = { probePage -> "plugin.searchNovels('$escapedQuery', $probePage)" },
                )
            } else if (hasActiveFilters) {
                // Use popularNovels with user-modified filters
                val filtersJs = convertFiltersToJs(filters)
                val currentResult =
                    executeBrowseMethod("plugin.popularNovels($page, { showLatestNovels: false, filters: $filtersJs })")
                val parsed = parseMangasPage(currentResult, page)
                inferHasNextPage(
                    currentPage = page,
                    current = parsed,
                    methodCallForPage = { probePage ->
                        "plugin.popularNovels($probePage, { showLatestNovels: false, filters: $filtersJs })"
                    },
                )
            } else {
                // Default to popular with plugin's original filters
                val currentResult =
                    executeBrowseMethod(
                        "plugin.popularNovels($page, { showLatestNovels: false, filters: plugin.filters })",
                    )
                val parsed = parseMangasPage(currentResult, page)
                inferHasNextPage(
                    currentPage = page,
                    current = parsed,
                    methodCallForPage = { probePage ->
                        "plugin.popularNovels($probePage, { showLatestNovels: false, filters: plugin.filters })"
                    },
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error in getSearchManga for ${plugin.name}" }
            MangasPage(emptyList(), false)
        }
    }

    /**
     * Convert Tsundoku FilterList back to JS filter object format for plugin.
     * Uses JsonObject builder to avoid kotlinx.serialization issues with Map<String, Any>.
     */
    private fun convertFiltersToJs(filters: FilterList): String {
        val filterEntries = mutableMapOf<String, JsonElement>()

        filters.forEach { filter ->
            when (filter) {
                is JsSelectFilter -> {
                    filterEntries[filter.key] = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("Picker"),
                            "value" to JsonPrimitive(filter.selectedValue()),
                        ),
                    )
                }
                is JsCheckboxGroup -> {
                    filterEntries[filter.key] = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("Checkbox"),
                            "value" to JsonArray(filter.selectedValues().map { JsonPrimitive(it) }),
                        ),
                    )
                }
                is JsTriStateGroup -> {
                    filterEntries[filter.key] = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("XCheckbox"),
                            "value" to JsonObject(
                                mapOf(
                                    "include" to JsonArray(filter.includedValues().map { JsonPrimitive(it) }),
                                    "exclude" to JsonArray(filter.excludedValues().map { JsonPrimitive(it) }),
                                ),
                            ),
                        ),
                    )
                }
                is JsSwitchFilter -> {
                    filterEntries[filter.key] = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("Switch"),
                            "value" to JsonPrimitive(filter.state),
                        ),
                    )
                }
                is Filter.CheckBox -> {
                    filterEntries[filter.name] = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("Switch"),
                            "value" to JsonPrimitive(filter.state),
                        ),
                    )
                }
                is JsTextFilter -> {
                    filterEntries[filter.key] = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("Text"),
                            "value" to JsonPrimitive(filter.state),
                        ),
                    )
                }
                is Filter.Text -> {
                    filterEntries[filter.name] = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("Text"),
                            "value" to JsonPrimitive(filter.state),
                        ),
                    )
                }
                else -> {
                    // Ignore other filter types (headers, separators)
                }
            }
        }

        return JsonObject(filterEntries).toString()
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        try {
            // parseNovelCached dedupes the fetch with getChapterList; parsing the raw JSON is cheap.
            parseNovelDetails(parseNovelCached(manga.url), manga)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error in getMangaDetails for ${plugin.name}" }
            manga
        }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        try {
            // Check cache first
            val cached = chaptersCache[manga.url]
            val now = System.currentTimeMillis()
            if (cached != null && (now - cached.second) < cacheTimeout) {
                return@withContext cached.first
            }

            val path = escapeJsString(resolvePluginPath(manga.url))
            val result = parseNovelCached(manga.url)
            val chapters = parseChapterList(result).toMutableList()

            // Support paged chapter sources (like novelight, novelfire)
            // If parseNovel returns totalPages > 1, fetch remaining pages via parsePage
            val totalPages = try {
                val obj = json.parseToJsonElement(result).jsonObject
                obj["totalPages"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            } catch (_: Exception) {
                1
            }

            if (totalPages > 1 || chapters.isEmpty()) {
                val hasParsePage =
                    executePluginMethod("typeof plugin.parsePage === 'function'") == "true"
                val maxPages = if (totalPages > 0) totalPages else 1
                val startPage = if (chapters.isEmpty()) 1 else 2
                logcat(LogPriority.DEBUG) {
                    "JsSource[$pluginId]: Paged source detected, totalPages=$totalPages, startPage=$startPage, existingChapters=${chapters.size}, hasParsePage=$hasParsePage"
                }
                if (hasParsePage && startPage <= maxPages) {
                    for (page in startPage..maxPages) {
                        try {
                            val pageResult = executePluginMethod("plugin.parsePage('$path', $page)")
                            val pageChapters = parsePageChapters(pageResult)
                            chapters.addAll(pageChapters)
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) {
                                "JsSource[$pluginId]: Error fetching page $page of $maxPages"
                            }
                        }
                    }
                }
            }

            val total = chapters.size
            chapters.forEachIndexed { index, chapter ->
                // Only override chapter_number if the plugin didn't provide one
                // (default SChapter chapter_number is -1)
                if (chapter.chapter_number < 0) {
                    // Assign sequential numbers matching position: index 0 → 1, index 1 → 2, etc.
                    chapter.chapter_number = (index + 1).toFloat()
                }
            }

            // LNReader plugins return chapters newest-first; reverse to oldest-first
            // so sourceOrder aligns with chapter_number (chapter 1 = index 0).
            // The per-source "Reverse chapter list" toggle in SyncChaptersWithSource
            // can override this if needed.
            chapters.reverse()

            // Cache the result
            chaptersCache[manga.url] = chapters to now
            chapters
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error in getChapterList for ${plugin.name}" }
            throw e
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // Validate chapter URL is not empty/blank to avoid fetching base URL
        if (chapter.url.isBlank()) {
            logcat(LogPriority.ERROR) { "[$id] getPageList: chapter.url is blank, cannot parse chapter" }
            return emptyList()
        }
        // A novel chapter is always one page; the single fetch happens in fetchPageText.
        return listOf(Page(0, chapter.url, ""))
    }

    override fun getFilterList(): FilterList {
        return try {
            // Synchronous interface, so the first call must runBlocking into JS. Cache the raw
            // JSON but re-parse per call; Filter instances are stateful.
            val result = filtersJsonCache
                ?: runBlocking { executePluginMethod("plugin.filters || {}") }
                    .also { filtersJsonCache = it }
            parseFiltersFromJson(result)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error getting filters for ${plugin.name}" }
            FilterList()
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Read plugin's pluginSettings and create preferences backed by persistent storage
        try {
            val result = pluginSettingsJsonCache
                ?: runBlocking { executePluginMethod("JSON.stringify(plugin.pluginSettings || {})") }
                    .also { pluginSettingsJsonCache = it }
            val settingsJson = decodeJsonStringIfQuoted(result)
            if (settingsJson.isBlank() || settingsJson == "{}" || settingsJson == "null") return

            val settings = json.parseToJsonElement(settingsJson).jsonObject
            val prefs = screen.context.getSharedPreferences(
                "jsplugin_storage_$pluginId",
                android.content.Context.MODE_PRIVATE,
            )

            settings.forEach { (key, value) ->
                val settingObj = value as? JsonObject ?: return@forEach
                val label = settingObj["label"]?.jsonPrimitive?.content ?: key
                val type = settingObj["type"]?.jsonPrimitive?.content ?: "Text"

                when (type) {
                    "Switch" -> {
                        val storedValue = prefs.getString(key, "")?.toBooleanStrictOrNull() ?: false
                        logcat(LogPriority.DEBUG) { "[$pluginId] Switch pref '$key' loaded: $storedValue" }
                        val pref = androidx.preference.SwitchPreferenceCompat(screen.context).apply {
                            // Set isPersistent=false BEFORE key to prevent framework auto-load
                            this.isPersistent = false
                            this.key = key
                            this.title = label
                            this.setDefaultValue(storedValue)
                            setOnPreferenceChangeListener { _, newValue ->
                                val boolVal = newValue as? Boolean ?: false
                                prefs.edit().putString(key, boolVal.toString()).apply()
                                logcat(LogPriority.DEBUG) { "[$pluginId] Switch pref '$key' changed: $boolVal" }
                                true
                            }
                        }
                        screen.addPreference(pref)
                        // Set isChecked AFTER addPreference so onSetInitialValue doesn't override it
                        pref.isChecked = storedValue
                    }
                    "Text" -> {
                        val defaultValue = settingObj["value"]?.jsonPrimitive?.content ?: ""
                        val storedText = prefs.getString(key, defaultValue) ?: defaultValue
                        val pref = androidx.preference.EditTextPreference(screen.context).apply {
                            // Set isPersistent=false BEFORE key to prevent framework auto-load
                            this.isPersistent = false
                            this.key = key
                            this.title = label
                            this.setDefaultValue(defaultValue)
                            setOnPreferenceChangeListener { p, newValue ->
                                val strVal = newValue.toString()
                                prefs.edit().putString(key, strVal).apply()
                                (p as? androidx.preference.EditTextPreference)?.summary = strVal
                                true
                            }
                        }
                        screen.addPreference(pref)
                        // Set text/summary AFTER addPreference so onSetInitialValue doesn't override
                        pref.text = storedText
                        pref.summary = storedText
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error setting up preference screen for ${plugin.name}" }
        }
    }

    private fun anyToJsonElement(any: Any?): JsonElement {
        return when (any) {
            null -> kotlinx.serialization.json.JsonNull
            is Boolean -> JsonPrimitive(any)
            is Number -> JsonPrimitive(any)
            is String -> JsonPrimitive(any)
            is List<*> -> JsonArray(any.map { anyToJsonElement(it) })
            is Map<*, *> -> JsonObject(any.entries.associate { (k, v) -> (k.toString()) to anyToJsonElement(v) })
            else -> JsonPrimitive(any.toString())
        }
    }

    private fun decodeJsonStringIfQuoted(jsonValue: String): String {
        if (jsonValue.isBlank() || jsonValue == "null") return ""
        return try {
            val el = json.parseToJsonElement(jsonValue)
            if (el is JsonPrimitive && el.isString) el.content else jsonValue
        } catch (_: Exception) {
            jsonValue
        }
    }

    private fun normalizeDoubleSlashes(value: String): String {
        if (value.isBlank()) return value
        val parts = value.split("://", limit = 2)
        return if (parts.size == 2) {
            val normalizedPath = parts[1].replace(Regex("/{2,}"), "/")
            "${parts[0]}://$normalizedPath"
        } else {
            value.replace(Regex("/{2,}"), "/")
        }
    }

    private fun normalizePluginPath(value: String): String {
        val normalized = normalizeDoubleSlashes(value.trim())
        return if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            normalized
        } else {
            normalized.removePrefix("/")
        }
    }

    // Whether the plugin's URL join (resolveUrl) ends with a slash. Probed once via a sentinel.
    // Plugins that build URLs from a trailing-slash `site` want the path WITHOUT a leading slash;
    // plugins that use a prefix without a trailing slash need the
    // leading slash kept.
    @Volatile private var resolveUrlPrefixProbe: String? = null

    @Volatile private var resolveUrlProbed = false

    private suspend fun pluginPrefixEndsWithSlash(): Boolean? {
        if (resolveUrlProbed) return resolveUrlPrefixProbe?.endsWith("/")
        val prefix = runCatching {
            if (executePluginMethod("typeof plugin.resolveUrl === 'function'") != "true") return@runCatching null
            val raw = executePluginMethod("plugin.resolveUrl('$PATH_PROBE_TOKEN')")
            val decoded = decodeJsonStringIfQuoted(raw)
            if (decoded.endsWith(PATH_PROBE_TOKEN)) decoded.removeSuffix(PATH_PROBE_TOKEN) else null
        }.getOrNull()
        resolveUrlPrefixProbe = prefix
        resolveUrlProbed = true
        return prefix?.endsWith("/")
    }

    /**
     * Path passed to parseNovel/parseChapter/parsePage, with its leading slash reconciled to the
     * plugin's URL-join convention (see [pluginPrefixEndsWithSlash]). Falls back to the legacy
     * leading-slash strip when the plugin exposes no usable resolveUrl.
     */
    private suspend fun resolvePluginPath(value: String): String {
        val base = normalizeDoubleSlashes(value.trim())
        if (base.startsWith("http://") || base.startsWith("https://")) return base
        return when (pluginPrefixEndsWithSlash()) {
            true -> base.removePrefix("/")
            false -> if (base.startsWith("/")) base else "/$base"
            null -> base.removePrefix("/")
        }
    }

    /** Escape a value for embedding in a single-quoted JS string literal. */
    private fun escapeJsString(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")

    /** plugin.parseNovel with raw-result caching and in-flight dedup. */
    private suspend fun parseNovelCached(mangaUrl: String): String {
        val path = escapeJsString(resolvePluginPath(mangaUrl))
        return parseNovelMutex.withLock {
            val now = System.currentTimeMillis()
            parseNovelCache[path]?.takeIf { (now - it.second) < cacheTimeout }?.let { return@withLock it.first }
            val result = executePluginMethod("plugin.parseNovel('$path')")
            parseNovelCache[path] = result to now
            trimRawCache(parseNovelCache)
            result
        }
    }

    /** plugin.parseChapter with raw-result caching and in-flight dedup. */
    private suspend fun parseChapterCached(chapterUrl: String): String {
        val path = escapeJsString(resolvePluginPath(chapterUrl))
        return chapterTextMutex.withLock {
            val now = System.currentTimeMillis()
            chapterTextCache[path]?.takeIf { (now - it.second) < cacheTimeout }?.let { return@withLock it.first }
            val result = executePluginMethod("plugin.parseChapter('$path')")
            chapterTextCache[path] = result to now
            trimRawCache(chapterTextCache)
            result
        }
    }

    /** Keep raw caches small; chapter HTML payloads can be large. */
    private fun trimRawCache(cache: java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>) {
        val maxEntries = 8
        if (cache.size <= maxEntries) return
        cache.entries.sortedBy { it.value.second }
            .take(cache.size - maxEntries)
            .forEach { cache.remove(it.key) }
    }

    // Parsing helpers

    private fun String.decodeEntities(): String {
        return org.jsoup.parser.Parser.unescapeEntities(this, true)
    }

    private fun parseMangasPage(jsonResult: String, page: Int): MangasPage {
        if (jsonResult == "null" || jsonResult.isBlank()) {
            return MangasPage(emptyList(), false)
        }

        try {
            val parsed = json.parseToJsonElement(jsonResult)

            // Check if response is an array (simple case) or object with pagination metadata
            val (mangaArray, hasNextPageExplicit) = if (parsed is JsonArray) {
                Pair(parsed, null)
            } else if (parsed is JsonObject) {
                // Plugin can return { novels: [...], hasNextPage: true } or similar structure
                val novels = parsed["novels"]?.jsonArray
                    ?: parsed["results"]?.jsonArray
                    ?: return MangasPage(emptyList(), false)
                val hasNext = parsed["hasNextPage"]?.jsonPrimitive?.content?.toBoolean()
                Pair(novels, hasNext)
            } else {
                return MangasPage(emptyList(), false)
            }

            val mangas = mangaArray.mapNotNull { item ->
                try {
                    val obj = item.jsonObject
                    SManga.create().apply {
                        title = obj["name"]?.jsonPrimitive?.content?.decodeEntities() ?: return@mapNotNull null
                        url =
                            (obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null).let {
                                if (it.startsWith("/")) it else "/$it"
                            }
                        // Ensure thumbnail_url is a valid URL or null
                        val coverUrl = obj["cover"]?.jsonPrimitive?.content
                        thumbnail_url = when {
                            coverUrl.isNullOrBlank() || coverUrl == "null" -> null
                            coverUrl.startsWith("http://") || coverUrl.startsWith("https://") -> coverUrl
                            coverUrl.startsWith("/") -> baseUrl + coverUrl // baseUrl has no trailing slash
                            else -> "$baseUrl/$coverUrl" // Add slash between baseUrl and relative path
                        }
                        author = obj["author"]?.jsonPrimitive?.content?.decodeEntities()
                    }
                } catch (e: Exception) {
                    null
                }
            }

            // Use explicit hasNextPage if provided by plugin; otherwise use heuristic
            val hasNext = when {
                hasNextPageExplicit != null -> hasNextPageExplicit
                mangas.isEmpty() -> false
                else -> mangas.size >= 20 // Assume more pages if we got 20+ results
            }
            return MangasPage(mangas, hasNext)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to parse mangas: $jsonResult" }
            return MangasPage(emptyList(), false)
        }
    }

    private suspend fun inferHasNextPage(
        currentPage: Int,
        current: MangasPage,
        methodCallForPage: (Int) -> String,
    ): MangasPage {
        if (current.hasNextPage || current.mangas.isEmpty()) {
            return current
        }

        val probePage = currentPage + 1
        return try {
            val probeCall = methodCallForPage(probePage)
            val nextResult = executePluginMethod(probeCall)
            // Save so paging to probePage serves this result instead of refetching it.
            // Evict oldest under lock so a concurrent probe's fresh entry isn't wiped.
            synchronized(browseProbeCache) {
                while (browseProbeCache.size > 4) {
                    val oldest = browseProbeCache.minByOrNull { it.value.second }?.key ?: break
                    browseProbeCache.remove(oldest)
                }
                browseProbeCache[probeCall] = nextResult to System.currentTimeMillis()
            }
            val nextParsed = parseMangasPage(nextResult, probePage)
            MangasPage(current.mangas, nextParsed.mangas.isNotEmpty())
        } catch (_: Exception) {
            current
        }
    }

    private fun parseNovelDetails(jsonResult: String, existing: SManga): SManga {
        if (jsonResult == "null" || jsonResult.isBlank()) {
            return existing
        }

        try {
            val obj = json.parseToJsonElement(jsonResult).jsonObject
            return SManga.create().apply {
                url = existing.url
                title = obj["name"]?.jsonPrimitive?.content?.decodeEntities() ?: existing.title
                author = obj["author"]?.jsonPrimitive?.content?.decodeEntities() ?: existing.author
                artist = obj["artist"]?.jsonPrimitive?.content?.decodeEntities()
                description = normalizeHtmlDescription(
                    obj["summary"]?.jsonPrimitive?.content
                        ?: obj["desc"]?.jsonPrimitive?.content
                        ?: obj["description"]?.jsonPrimitive?.content,
                )
                genre = obj["genres"]?.jsonPrimitive?.content?.decodeEntities()
                    ?: obj["tags"]?.jsonPrimitive?.content?.decodeEntities()
                    ?: obj["genre"]?.jsonPrimitive?.content?.decodeEntities()
                // Parse alternative names if available
                val altNames = obj["alternativeNames"]?.jsonPrimitive?.content?.decodeEntities()
                    ?: obj["altNames"]?.jsonPrimitive?.content?.decodeEntities()
                if (!altNames.isNullOrBlank()) {
                    altTitles = altNames.split(",", ";", "/", "|")
                        .map { it.trim() }
                        .filter { it.isNotBlank() && it != title }
                }
                // Validate cover URL
                val coverUrl = obj["cover"]?.jsonPrimitive?.content
                thumbnail_url = when {
                    coverUrl.isNullOrBlank() || coverUrl == "null" -> existing.thumbnail_url
                    coverUrl.startsWith("http://") || coverUrl.startsWith("https://") -> coverUrl
                    coverUrl.startsWith("/") -> baseUrl + coverUrl
                    else -> "$baseUrl/$coverUrl"
                }
                status = when (obj["status"]?.jsonPrimitive?.content) {
                    "Ongoing" -> SManga.ONGOING
                    "Completed" -> SManga.COMPLETED
                    "On Hiatus", "OnHiatus" -> SManga.ON_HIATUS
                    "Cancelled" -> SManga.CANCELLED
                    "Licensed" -> SManga.LICENSED
                    else -> SManga.UNKNOWN
                }
                initialized = true
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to parse novel details: $jsonResult" }
            return existing
        }
    }

    private fun parseChapterList(jsonResult: String): List<SChapter> {
        if (jsonResult == "null" || jsonResult.isBlank()) {
            return emptyList()
        }

        try {
            val obj = json.parseToJsonElement(jsonResult).jsonObject
            val chaptersArray = obj["chapters"]?.jsonArray ?: return emptyList()

            // Return chapters in source order (newest-first from LNReader);
            // reversal and chapter_number assignment happen in getChapterList() after all pages are collected.
            return chaptersArray.mapIndexedNotNull { index, item ->
                try {
                    val chapterObj = item.jsonObject
                    SChapter.create().apply {
                        name = chapterObj["name"]?.jsonPrimitive?.content?.decodeEntities() ?: "Chapter ${index + 1}"
                        url = chapterObj["path"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
                        // Keep plugin-provided chapterNumber if available; otherwise leave as -1
                        // (global numbering is assigned later in getChapterList)
                        chapterObj["chapterNumber"]?.jsonPrimitive?.content?.toFloatOrNull()?.let {
                            chapter_number = it
                        }
                        date_upload = try {
                            chapterObj["releaseTime"]?.jsonPrimitive?.content?.let { dateStr ->
                                parseReleaseTime(dateStr)
                            } ?: 0L
                        } catch (e: Exception) {
                            0L
                        }
                        scanlator = chapterObj["page"]?.jsonPrimitive?.content // Volume info
                    }
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to parse chapters: $jsonResult" }
            return emptyList()
        }
    }

    /**
     * Parse chapters from a parsePage() result.
     * parsePage returns { chapters: [...] } like parseNovel, but just for one page.
     * Returns chapters in source order (newest-first from LNReader);
     * reversal and chapter_number assignment happen in getChapterList().
     */
    private fun parsePageChapters(jsonResult: String): List<SChapter> {
        if (jsonResult == "null" || jsonResult.isBlank()) return emptyList()
        try {
            val element = json.parseToJsonElement(jsonResult)
            val chaptersArray = when (element) {
                is JsonObject -> element["chapters"]?.jsonArray ?: return emptyList()
                is JsonArray -> element // Some plugins return a plain array
                else -> return emptyList()
            }
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            return chaptersArray.mapIndexedNotNull { index, item ->
                try {
                    val chapterObj = item.jsonObject
                    SChapter.create().apply {
                        name = chapterObj["name"]?.jsonPrimitive?.content?.decodeEntities() ?: "Chapter ${index + 1}"
                        url = chapterObj["path"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
                        chapterObj["chapterNumber"]?.jsonPrimitive?.content?.toFloatOrNull()?.let {
                            chapter_number = it
                        }
                        date_upload = try {
                            chapterObj["releaseTime"]?.jsonPrimitive?.content?.let { dateStr ->
                                parseReleaseTime(dateStr)
                            } ?: 0L
                        } catch (_: Exception) {
                            0L
                        }
                        scanlator = chapterObj["page"]?.jsonPrimitive?.content
                    }
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to parse page chapters: $jsonResult" }
            return emptyList()
        }
    }

    /**
     * Parse release time string from JS plugins.
     * Handles ISO format, "YYYY-MM-DD", locale-specific formats like "January 5, 2025",
     * and relative dates like "3 days ago".
     */
    private fun parseReleaseTime(dateStr: String): Long {
        val trimmed = dateStr.trim()
        if (trimmed.isEmpty()) return 0L

        // ISO format (contains "T")
        if (trimmed.contains("T")) {
            return try {
                java.time.Instant.parse(trimmed).toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }

        // Relative dates: "X hours/days/weeks/months/years ago"
        val relativeMatch = Regex(
            """(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""",
            RegexOption.IGNORE_CASE,
        )
            .find(trimmed)
        if (relativeMatch != null) {
            val amount = relativeMatch.groupValues[1].toLongOrNull() ?: return 0L
            val unit = relativeMatch.groupValues[2].lowercase()
            val millis = when (unit) {
                "second" -> amount * 1000L
                "minute" -> amount * 60_000L
                "hour" -> amount * 3_600_000L
                "day" -> amount * 86_400_000L
                "week" -> amount * 604_800_000L
                "month" -> amount * 2_592_000_000L // ~30 days
                "year" -> amount * 31_536_000_000L // ~365 days
                else -> return 0L
            }
            return System.currentTimeMillis() - millis
        }

        // Try common date formats
        val formats = listOf(
            "yyyy-MM-dd",
            "MMMM dd, yyyy",
            "MMMM d, yyyy",
            "MMM dd, yyyy",
            "MMM d, yyyy",
            "dd MMMM yyyy",
            "dd MMM yyyy",
            "dd/MM/yyyy",
            "MM/dd/yyyy",
        )
        for (format in formats) {
            try {
                return SimpleDateFormat(format, Locale.US).parse(trimmed)?.time ?: continue
            } catch (_: Exception) {
                // Try next format
            }
        }

        return 0L
    }

    private fun parseFiltersFromJson(jsonResult: String): FilterList {
        if (jsonResult == "null" || jsonResult.isBlank() || jsonResult == "{}" || jsonResult == "[]") {
            return FilterList()
        }

        try {
            val element = json.parseToJsonElement(jsonResult)
            val filters = mutableListOf<Filter<*>>()

            when (element) {
                is JsonObject -> {
                    // Object format: { "filterKey": { type: ..., label: ..., options: ... } }
                    element.forEach { (key, value) ->
                        val filterObj = value as? JsonObject ?: return@forEach
                        parseFilterObject(key, filterObj, filters)
                    }
                }
                is JsonArray -> {
                    // Array format: [ { type: ..., label: ..., options: ... }, ... ]
                    element.forEachIndexed { index, value ->
                        val filterObj = value as? JsonObject ?: return@forEachIndexed
                        parseFilterObject("filter_$index", filterObj, filters)
                    }
                }
                else -> {
                    // Invalid format
                    return FilterList()
                }
            }

            return FilterList(filters)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to parse filters: $jsonResult" }
            return FilterList()
        }
    }

    // Custom filter classes that store both label and value

    /** Picker filter that stores label-value pairs */
    class JsSelectFilter(
        name: String,
        val key: String,
        val options: List<Pair<String, String>>, // Pair of (label, value)
        defaultIndex: Int = 0,
    ) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), defaultIndex) {
        /** Get the selected option's value (not label) */
        fun selectedValue(): String = options.getOrNull(state)?.second ?: ""
    }

    /** Checkbox group filter where each checkbox has a label and value */
    class JsCheckboxGroup(
        name: String,
        val key: String,
        val checkboxes: List<JsCheckbox>,
    ) : Filter.Group<JsCheckbox>(name, checkboxes) {
        /** Get list of selected values */
        fun selectedValues(): List<String> = state.filter { it.state }.map { it.value }
    }

    /** Single checkbox with associated value */
    class JsCheckbox(
        val label: String,
        val value: String,
    ) : Filter.CheckBox(label)

    /** TriState checkbox group for include/exclude functionality */
    class JsTriStateGroup(
        name: String,
        val key: String,
        val triStates: List<JsTriState>,
    ) : Filter.Group<JsTriState>(name, triStates) {
        /** Get included values */
        fun includedValues(): List<String> = state.filter { it.isIncluded() }.map { it.value }

        /** Get excluded values */
        fun excludedValues(): List<String> = state.filter { it.isExcluded() }.map { it.value }
    }

    /** Single TriState checkbox with associated value */
    class JsTriState(
        val label: String,
        val value: String,
    ) : Filter.TriState(label)

    /** Text input filter that stores the original JSON key */
    class JsTextFilter(
        name: String,
        val key: String,
        defaultValue: String = "",
    ) : Filter.Text(name) {
        init {
            state = defaultValue
        }
    }

    /** Switch filter that stores the original JSON key */
    class JsSwitchFilter(
        name: String,
        val key: String,
        defaultValue: Boolean = false,
    ) : Filter.CheckBox(name, defaultValue)

    private fun parseFilterObject(key: String, filterObj: JsonObject, filters: MutableList<Filter<*>>) {
        val type = filterObj["type"]?.jsonPrimitive?.content
        val label = filterObj["label"]?.jsonPrimitive?.content ?: key

        when (type) {
            "Text" -> {
                val defaultValue = filterObj["value"]?.jsonPrimitive?.content ?: ""
                filters.add(JsTextFilter(label, key, defaultValue))
            }

            "Picker" -> {
                // Parse options as label-value pairs
                val options = filterObj["options"]?.jsonArray?.mapNotNull { optionEl ->
                    when (optionEl) {
                        is JsonPrimitive -> Pair(optionEl.content, optionEl.content)
                        is JsonObject -> {
                            val optLabel = optionEl["label"]?.jsonPrimitive?.content ?: ""
                            val optValue = optionEl["value"]?.jsonPrimitive?.content ?: optLabel
                            if (optLabel.isNotEmpty()) Pair(optLabel, optValue) else null
                        }
                        else -> null
                    }
                } ?: emptyList()

                // Get default value and find its index
                val defaultValue = filterObj["value"]?.jsonPrimitive?.content
                val defaultIndex = if (defaultValue != null) {
                    options.indexOfFirst { it.second == defaultValue }.takeIf { it >= 0 } ?: 0
                } else {
                    0
                }

                filters.add(JsSelectFilter(label, key, options, defaultIndex))
            }

            "Checkbox" -> {
                // CheckboxGroup: array of checkboxes
                val options = filterObj["options"]?.jsonArray?.mapNotNull { optionEl ->
                    when (optionEl) {
                        is JsonPrimitive -> JsCheckbox(optionEl.content, optionEl.content)
                        is JsonObject -> {
                            val optLabel = optionEl["label"]?.jsonPrimitive?.content ?: ""
                            val optValue = optionEl["value"]?.jsonPrimitive?.content ?: optLabel
                            if (optLabel.isNotEmpty()) JsCheckbox(optLabel, optValue) else null
                        }
                        else -> null
                    }
                } ?: emptyList()

                // Get default selected values
                val defaultValues = filterObj["value"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive?.content
                }?.toSet() ?: emptySet()

                // Set initial state for checkboxes
                options.forEach { checkbox ->
                    checkbox.state = checkbox.value in defaultValues
                }

                filters.add(JsCheckboxGroup(label, key, options))
            }

            "Switch" -> {
                val defaultValue = filterObj["value"]?.jsonPrimitive?.let {
                    it.content.toBooleanStrictOrNull() ?: (it.content == "true")
                } ?: false
                filters.add(JsSwitchFilter(label, key, defaultValue))
            }

            "XCheckbox" -> {
                // ExcludableCheckboxGroup: TriState checkboxes for include/exclude
                val options = filterObj["options"]?.jsonArray?.mapNotNull { optionEl ->
                    when (optionEl) {
                        is JsonPrimitive -> JsTriState(optionEl.content, optionEl.content)
                        is JsonObject -> {
                            val optLabel = optionEl["label"]?.jsonPrimitive?.content ?: ""
                            val optValue = optionEl["value"]?.jsonPrimitive?.content ?: optLabel
                            if (optLabel.isNotEmpty()) JsTriState(optLabel, optValue) else null
                        }
                        else -> null
                    }
                } ?: emptyList()

                // Get default include/exclude values
                val defaultValueObj = filterObj["value"]?.jsonObject
                val includeValues = defaultValueObj?.get("include")?.jsonArray?.mapNotNull {
                    it.jsonPrimitive?.content
                }?.toSet() ?: emptySet()
                val excludeValues = defaultValueObj?.get("exclude")?.jsonArray?.mapNotNull {
                    it.jsonPrimitive?.content
                }?.toSet() ?: emptySet()

                // Set initial state using Filter.TriState constants
                options.forEach { triState ->
                    triState.state = when (triState.value) {
                        in includeValues -> Filter.TriState.STATE_INCLUDE
                        in excludeValues -> Filter.TriState.STATE_EXCLUDE
                        else -> Filter.TriState.STATE_IGNORE
                    }
                }

                filters.add(JsTriStateGroup(label, key, options))
            }
        }
    }

    override suspend fun fetchPageText(page: Page): String = withContext(Dispatchers.IO) {
        // If the page already has text content (set by getPageList), return it directly
        if (!page.text.isNullOrBlank()) {
            return@withContext page.text!!
        }

        // Validate URL before calling plugin - avoid fetching base URL with empty path
        if (normalizePluginPath(page.url).isBlank()) {
            logcat(LogPriority.WARN) { "[$id] fetchPageText: page.url is blank, cannot parse chapter" }
            throw IllegalStateException("Chapter content unavailable (empty URL)")
        }

        try {
            val result = parseChapterCached(page.url)
            extractChapterText(result)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error fetching page text for ${plugin.name}" }
            throw e
        }
    }

    // Extract chapter text from executePluginMethod result.
    // Plugins may return a plain JSON string or a JSON object with chapterText/text/content field.
    // decodeJsonStringIfQuoted returns raw JSON for objects, which viewers then misrender.
    private fun extractChapterText(result: String): String {
        val raw = if (result.startsWith("{")) {
            try {
                val obj = json.parseToJsonElement(result).jsonObject
                pickContentField(obj) ?: decodeJsonStringIfQuoted(result)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "extractChapterText: failed to parse JSON object from ${plugin.name}" }
                decodeJsonStringIfQuoted(result)
            }
        } else {
            decodeJsonStringIfQuoted(result)
        }
        return normalizePluginContent(raw)
    }
}
