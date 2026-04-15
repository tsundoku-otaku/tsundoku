package eu.kanade.tachiyomi.jsplugin

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.jsplugin.model.InstalledJsPlugin
import eu.kanade.tachiyomi.jsplugin.model.JsPlugin
import eu.kanade.tachiyomi.jsplugin.model.JsPluginRepository
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Manages JavaScript plugins from LNReader-compatible repositories.
 * Handles fetching plugin lists, downloading plugins, caching, and creating JsSource instances.
 */
class JsPluginManager(
    private val context: Context,
) {
    private val networkHelper: NetworkHelper = Injekt.get()
    private val sourcePreferences: SourcePreferences = Injekt.get()
    private val storageManager: StorageManager = Injekt.get()
    private val client: OkHttpClient get() = networkHelper.client
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Storage directories
    private val pluginsDir: UniFile?
        get() = storageManager.getLNReaderPluginsDirectory()
            ?: run {
                // SAF/base storage may be unavailable on some devices/configurations.
                // Fall back to app-private storage so plugin install/import still works.
                val fallbackDir = File(context.filesDir, "lnreader_plugins").apply { mkdirs() }
                UniFile.fromFile(fallbackDir)
            }

    private val cacheDir: File = File(context.cacheDir, "lnreader_plugins_cache")

    // Persistent icon cache — stored in filesDir so Android won't purge it (cacheDir is ephemeral)
    private val iconsCacheDir: File
        get() = File(context.filesDir, "lnreader_icons_cache").apply { mkdirs() }

    // State
    private val _repositories = MutableStateFlow<List<JsPluginRepository>>(emptyList())
    val repositories: StateFlow<List<JsPluginRepository>> = _repositories.asStateFlow()

    private val _availablePlugins = MutableStateFlow<List<JsPlugin>>(emptyList())
    val availablePlugins: StateFlow<List<JsPlugin>> = _availablePlugins.asStateFlow()

    private val _installedPlugins = MutableStateFlow<List<InstalledJsPlugin>>(emptyList())
    val installedPlugins: StateFlow<List<InstalledJsPlugin>> = _installedPlugins.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // True once the first installed-plugin scan has completed (successfully or not).
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val refreshMutex = Mutex()

    private val _jsSources = MutableStateFlow<List<CatalogueSource>>(emptyList())
    val jsSources: StateFlow<List<CatalogueSource>> = _jsSources.asStateFlow()

    init {
        cacheDir.mkdirs()
        loadRepositoriesFromPrefs()
        scope.launch {
            loadRepositories()
            loadInstalledPlugins()
            loadCachedPluginList()
        }

        storageManager.changes
            .onEach {
                logcat(LogPriority.INFO) { "JsPluginManager: storage changed, reloading plugins" }
                loadInstalledPlugins()
            }
            .launchIn(scope)
    }

    private fun saveCachedPluginList(plugins: List<JsPlugin>) {
        try {
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val cacheFile = File(cacheDir, "plugin_list_cache.json")
            cacheFile.parentFile?.mkdirs()
            val jsonString = json.encodeToString(plugins)
            cacheFile.writeText(jsonString)
            logcat(LogPriority.DEBUG) { "Saved ${plugins.size} plugins to cache" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to save plugin list cache" }
        }
    }

    private fun loadCachedPluginList() {
        try {
            val cacheFile = File(cacheDir, "plugin_list_cache.json")
            if (cacheFile.exists()) {
                val jsonString = cacheFile.readText()
                val plugins = json.decodeFromString<List<JsPlugin>>(jsonString)
                _availablePlugins.value = plugins
                logcat(LogPriority.DEBUG) { "Loaded ${plugins.size} plugins from cache" }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to load plugin list cache" }
        }
    }

    /**
     * Refresh available plugins from all repositories
     */
    suspend fun refreshAvailablePlugins(forceRefresh: Boolean = false) {
        // Skip if another refresh is already in progress — result flows through _availablePlugins StateFlow
        if (!refreshMutex.tryLock()) return
        try {
            _isLoading.value = true
            try {
                // Load from cache first if not forcing refresh
                if (!forceRefresh && _availablePlugins.value.isNotEmpty()) {
                    logcat(LogPriority.DEBUG) { "Using cached plugin list (${_availablePlugins.value.size} plugins)" }
                    return
                }

                val allPlugins = mutableListOf<JsPlugin>()

                for (repo in _repositories.value.filter { it.enabled }) {
                    try {
                        val plugins = fetchPluginList(repo.url)
                        plugins.forEach { it.repositoryUrl = repo.url }
                        allPlugins.addAll(plugins)
                        logcat(LogPriority.DEBUG) { "Loaded ${plugins.size} plugins from ${repo.name}" }
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to fetch plugins from ${repo.name}" }
                    }
                }

                _availablePlugins.value = allPlugins

                // Save to cache file
                saveCachedPluginList(allPlugins)

                // Cache icons to avoid re-fetching each time
                cacheIcons(allPlugins)
            } finally {
                _isLoading.value = false
            }
        } finally {
            refreshMutex.unlock()
        }
    }

    /**
     * Fetch plugin list from a repository URL
     */
    private suspend fun fetchPluginList(url: String): List<JsPlugin> = withContext(Dispatchers.IO) {
        try {
            // Use URL as is, do not append index or modify
            val response = client.newCall(GET(url)).execute()
            val body = response.use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("HTTP ${resp.code}")
                }
                resp.body?.string() ?: return@withContext emptyList()
            }
            try {
                json.decodeFromString<List<JsPlugin>>(body)
            } catch (e: kotlinx.serialization.SerializationException) {
                logcat(LogPriority.WARN) {
                    "Failed to parse $url as JS plugin list: ${e.message}"
                }
                emptyList()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to fetch plugin list from $url" }
            emptyList()
        }
    }

    /**
     * Install a plugin by downloading and caching its code
     */
    suspend fun installPlugin(plugin: JsPlugin, repositoryUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            _isLoading.value = true

            val dir = pluginsDir ?: throw Exception("Plugin directory not available")

            // Download plugin code
            val response = client.newCall(GET(plugin.url)).execute()
            val code = response.use { resp ->
                if (!resp.isSuccessful) {
                    logcat(LogPriority.ERROR) { "Failed to download plugin ${plugin.name}: HTTP ${resp.code}" }
                    return@withContext false
                }
                resp.body?.string() ?: return@withContext false
            }

            // Save to disk
            val pluginFile = dir.createFile("${plugin.id}.js") ?: throw Exception("Failed to create plugin file")
            pluginFile.writeUtf8(code)

            // Save metadata
            val metadataFile = dir.createFile("${plugin.id}.json") ?: throw Exception("Failed to create metadata file")
            val installedPlugin = InstalledJsPlugin(
                plugin = plugin,
                code = code,
                installedVersion = plugin.version,
                repositoryUrl = repositoryUrl,
            )
            val metadataJson = json.encodeToString(installedPlugin.plugin)
            logcat(LogPriority.INFO) { "Saving metadata for ${plugin.id}: ${metadataJson.take(200)}" }
            metadataFile.writeText(metadataJson)

            // Update state
            _installedPlugins.update { current ->
                current.filter { it.plugin.id != plugin.id } + installedPlugin
            }

            // Rebuild sources
            rebuildSources()

            // Auto-enable the plugin's language so the source appears in Novel Sources tab
            val langCode = plugin.langCode()
            if (langCode.isNotEmpty()) {
                val currentLangs = sourcePreferences.enabledLanguages.get()
                if (langCode !in currentLangs) {
                    sourcePreferences.enabledLanguages.set(currentLangs + langCode)
                    logcat(LogPriority.INFO) { "Auto-enabled language '$langCode' for plugin ${plugin.name}" }
                }
            }

            logcat(LogPriority.INFO) { "Installed plugin: ${plugin.name} v${plugin.version}" }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to install plugin ${plugin.name}" }
            false
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Uninstall a plugin
     */
    suspend fun uninstallPlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = pluginsDir ?: return@withContext false

            // Delete files
            dir.findFile("$pluginId.js")?.delete()
            dir.findFile("$pluginId.json")?.delete()

            // Update state
            _installedPlugins.update { current ->
                current.filter { it.plugin.id != pluginId }
            }

            // Rebuild sources
            rebuildSources()

            logcat(LogPriority.INFO) { "Uninstalled plugin: $pluginId" }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to uninstall plugin $pluginId" }
            false
        }
    }

    /**
     * Check if a plugin has an update available
     */
    fun hasUpdate(installedPlugin: InstalledJsPlugin): Boolean {
        val available = _availablePlugins.value.find { it.id == installedPlugin.plugin.id }
        return available != null && available.version != installedPlugin.installedVersion
    }

    /**
     * Install a plugin from raw JS code (e.g. from LNReader backup).
     * Only installs if the plugin is not already installed or if the provided version is newer.
     */
    suspend fun installPluginFromCode(pluginId: String, code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = pluginsDir ?: throw Exception("Plugin directory not available")

            val plugin = extractPluginInfo(code, pluginId)

            val existing = _installedPlugins.value.find { it.plugin.id == plugin.id }
            if (existing != null && existing.installedVersion >= plugin.version) {
                logcat(LogPriority.DEBUG) {
                    "Plugin '${plugin.id}' v${existing.installedVersion} already installed (backup has v${plugin.version}), skipping"
                }
                return@withContext false
            }

            val pluginFile = dir.createFile("${plugin.id}.js") ?: throw Exception("Failed to create plugin file")
            pluginFile.writeUtf8(code)

            val metadataFile = dir.createFile("${plugin.id}.json") ?: throw Exception("Failed to create metadata file")
            val metadataJson = json.encodeToString(plugin)
            metadataFile.writeText(metadataJson)

            val installedPlugin = InstalledJsPlugin(
                plugin = plugin,
                code = code,
                installedVersion = plugin.version,
                repositoryUrl = "",
            )
            _installedPlugins.update { current ->
                current.filter { it.plugin.id != plugin.id } + installedPlugin
            }

            rebuildSources()

            val langCode = plugin.langCode()
            if (langCode.isNotEmpty()) {
                val currentLangs = sourcePreferences.enabledLanguages.get()
                if (langCode !in currentLangs) {
                    sourcePreferences.enabledLanguages.set(currentLangs + langCode)
                }
            }

            logcat(LogPriority.INFO) { "Installed plugin from backup: ${plugin.name} v${plugin.version}" }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to install plugin '$pluginId' from backup" }
            false
        }
    }

    /**
     * Update a plugin to the latest version
     */
    suspend fun updatePlugin(installedPlugin: InstalledJsPlugin): Boolean {
        val available = _availablePlugins.value.find { it.id == installedPlugin.plugin.id }
            ?: return false
        return installPlugin(available, installedPlugin.repositoryUrl)
    }

    /**
     * Add a new repository
     */
    fun addRepository(name: String, url: String) {
        logcat(LogPriority.INFO) { "JsPluginManager: addRepository called — name='$name', url='$url'" }
        _repositories.update { current ->
            if (current.any { it.url == url }) {
                logcat(LogPriority.DEBUG) { "JsPluginManager: repo already exists, skipping: $url" }
                current
            } else {
                logcat(LogPriority.INFO) { "JsPluginManager: adding new repo — total will be ${current.size + 1}" }
                current + JsPluginRepository(name, url)
            }
        }
        saveRepositories()
        scope.launch { refreshAvailablePlugins() }
    }

    /**
     * Remove a repository
     */
    fun removeRepository(url: String) {
        _repositories.update { current ->
            current.filter { it.url != url }
        }
        saveRepositories()
    }

    /**
     * Toggle repository enabled state
     */
    fun setRepositoryEnabled(url: String, enabled: Boolean) {
        _repositories.update { current ->
            current.map {
                if (it.url == url) it.copy(enabled = enabled) else it
            }
        }
        saveRepositories()
    }

    /**
     * Get all JS sources as CatalogueSources
     */
    fun getSources(): List<CatalogueSource> = _jsSources.value

    /**
     * Get a specific source by ID
     */
    fun getSource(sourceId: Long): CatalogueSource? {
        return _jsSources.value.find { it.id == sourceId }
    }

    // Private helpers

    private fun loadInstalledPlugins() {
        scope.launch {
            try {
                val dir = pluginsDir
                if (dir == null) {
                    logcat(LogPriority.WARN) { "Plugins directory not available - storage may not be configured" }
                    return@launch
                }

                logcat(LogPriority.DEBUG) { "Loading installed plugins from: ${dir.uri}" }

                val allFiles = dir.listFiles()?.toList() ?: emptyList()
                val jsFiles = allFiles.filter { it.name?.endsWith(".js") == true }
                val jsonFiles = allFiles.filter { it.name?.endsWith(".json") == true }
                logcat(LogPriority.DEBUG) {
                    "Found ${jsFiles.size} .js files and ${jsonFiles.size} .json files in plugins directory"
                }
                jsonFiles.forEach { f -> logcat(LogPriority.DEBUG) { "  JSON file: ${f.name}" } }

                val plugins = jsFiles.mapNotNull { file ->
                    try {
                        var code = file.readUtf8()
                        if (code.isBlank()) {
                            logcat(LogPriority.WARN) { "Plugin file ${file.name} is empty, skipping" }
                            return@mapNotNull null
                        }
                        val nameWithoutExtension = file.name?.substringBeforeLast(".") ?: return@mapNotNull null
                        val metadataFile = dir.findFile("$nameWithoutExtension.json")
                        logcat(LogPriority.DEBUG) {
                            "Looking for metadata: $nameWithoutExtension.json, found=${metadataFile != null}"
                        }
                        val plugin = if (metadataFile != null && metadataFile.exists()) {
                            val metadataJson = metadataFile.openInputStream().bufferedReader().readText()
                            logcat(LogPriority.DEBUG) {
                                "Metadata content (len=${metadataJson.length}): ${metadataJson.take(100)}"
                            }
                            if (metadataJson.isNotBlank() && metadataJson.trim().startsWith("{")) {
                                logcat(LogPriority.DEBUG) { "Loading metadata for $nameWithoutExtension" }
                                json.decodeFromString<JsPlugin>(metadataJson)
                            } else {
                                logcat(LogPriority.DEBUG) {
                                    "Metadata file empty for $nameWithoutExtension, extracting from code"
                            }
                            extractPluginInfo(code, nameWithoutExtension)
                        }
                    } else {
                        logcat(LogPriority.DEBUG) {
                            "No metadata found for $nameWithoutExtension, extracting from code"
                        }
                        extractPluginInfo(code, nameWithoutExtension)
                    }

                    // Auto-heal: if the plugin code looks truncated/incomplete, try re-download once.
                    // A common symptom is missing the final `exports.default = ...` assignment.
                    if (!code.contains("exports.default") && plugin.url.isNotBlank()) {
                        logcat(LogPriority.WARN) {
                            "Plugin '$nameWithoutExtension' code looks incomplete (len=${code.length}); re-downloading from ${plugin.url}"
                        }
                        try {
                            val response = client.newCall(GET(plugin.url)).execute()
                            response.use { resp ->
                                if (resp.isSuccessful) {
                                    val fresh = resp.body?.string().orEmpty()
                                    if (fresh.isNotBlank() && fresh.contains("exports.default")) {
                                        file.writeUtf8(fresh)
                                        code = fresh
                                        logcat(LogPriority.INFO) {
                                            "Re-downloaded plugin '$nameWithoutExtension' successfully (len=${fresh.length})"
                                        }
                                    } else {
                                        logcat(LogPriority.WARN) {
                                            "Re-download for '$nameWithoutExtension' returned unexpected content (len=${fresh.length})"
                                        }
                                    }
                                } else {
                                    logcat(LogPriority.WARN) {
                                        "Re-download failed for '$nameWithoutExtension': HTTP ${resp.code}"
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "Re-download failed for '$nameWithoutExtension'" }
                        }
                    }

                    InstalledJsPlugin(
                        plugin = plugin,
                        code = code,
                        installedVersion = plugin.version,
                        repositoryUrl = plugin.repositoryUrl ?: "",
                    )
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to load plugin: ${file.name}" }
                    null
                }
            }

            _installedPlugins.value = plugins
            rebuildSources()

            logcat(LogPriority.INFO) { "Loaded ${plugins.size} installed JS plugins" }
        } finally {
            // Unblock startup consumers waiting for the first JS source scan.
            _isInitialized.value = true
        }
    }

    private fun extractPluginInfo(code: String, fallbackId: String): JsPlugin {
        // Try to extract plugin info from the minified JS code
        val idRegex = """this\.id\s*=\s*["']([^"']+)["']""".toRegex()
        val nameRegex = """this\.name\s*=\s*["']([^"']+)["']""".toRegex()
        val siteRegex = """this\.site\s*=\s*["']([^"']+)["']""".toRegex()
        val versionRegex = """this\.version\s*=\s*["']([^"']+)["']""".toRegex()

        val id = idRegex.find(code)?.groupValues?.get(1) ?: fallbackId
        val name = nameRegex.find(code)?.groupValues?.get(1) ?: fallbackId
        val site = siteRegex.find(code)?.groupValues?.get(1) ?: ""
        val version = versionRegex.find(code)?.groupValues?.get(1) ?: "1.0.0"

        return JsPlugin(
            id = id,
            name = name,
            site = site,
            lang = "English",
            version = version,
            url = "",
            iconUrl = "",
        )
    }

    private fun rebuildSources() {
        val sources = _installedPlugins.value.map { installedPlugin ->
            JsSource(installedPlugin)
        }
        logcat(LogPriority.INFO) {
            "JsPluginManager: rebuildSources() - emitting ${sources.size} sources to jsSources StateFlow"
        }
        sources.forEach { s ->
            logcat(LogPriority.DEBUG) { "  JsSource: id=${s.id}, name=${s.name}, lang=${s.lang}" }
        }
        _jsSources.value = sources
        logcat(LogPriority.INFO) {
            "JsPluginManager: rebuildSources() - _jsSources.value updated, new count: ${_jsSources.value.size}"
        }
    }

    /**
     * Load repositories from the plugin directory file (requires storage to be available).
     * If the directory is unavailable, keeps current repos (possibly already loaded from prefs).
     */
    private fun loadRepositories() {
        try {
            val dir = pluginsDir
            if (dir == null) {
                logcat(LogPriority.WARN) {
                    "Plugins directory not available for loading repositories — keeping existing (${_repositories.value.size} repos)"
                }
                return
            }
            val reposFile = dir.findFile("repositories.json")
            if (reposFile != null && reposFile.exists()) {
                val content = reposFile.readText().trim()
                if (content.isNotBlank() && content.startsWith("[")) {
                    val allRepos = mutableListOf<JsPluginRepository>()
                    try {
                        allRepos.addAll(json.decodeFromString<List<JsPluginRepository>>(content))
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN) {
                            "repositories.json has concatenated JSON, attempting to split and merge"
                        }
                        val segments = content.split(Regex("""\]\s*\["""))
                        for ((i, segment) in segments.withIndex()) {
                            val fixed = when {
                                i == 0 && !segment.endsWith("]") -> "$segment]"
                                i == segments.lastIndex && !segment.startsWith("[") -> "[$segment"
                                !segment.startsWith("[") && !segment.endsWith("]") -> "[$segment]"
                                else -> segment
                            }
                            try {
                                allRepos.addAll(json.decodeFromString<List<JsPluginRepository>>(fixed))
                            } catch (e2: Exception) {
                                logcat(LogPriority.WARN) {
                                    "Skipping malformed JSON segment in repositories.json: ${e2.message}"
                                }
                            }
                        }
                    }
                    val distinct = allRepos.distinctBy { it.url }
                    val merged = (_repositories.value + distinct).distinctBy { it.url }
                    _repositories.value = merged
                    logcat(LogPriority.INFO) {
                        "Loaded ${distinct.size} repositories from disk (merged total: ${merged.size})"
                    }
                    saveRepositories()
                    return
                }
            }
            if (_repositories.value.isNotEmpty()) {
                saveRepositoriesToFile()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to load repositories from disk" }
        }
    }

    /**
     * Load repositories from SharedPreferences.
     */
    private fun loadRepositoriesFromPrefs() {
        try {
            val json2 = sourcePreferences.jsRepositoriesBackup.get()
            if (json2.isNotBlank()) {
                val repos = json.decodeFromString<List<JsPluginRepository>>(json2)
                _repositories.value = repos.distinctBy { it.url }
                logcat(LogPriority.INFO) { "Loaded ${repos.size} repositories from SharedPreferences backup" }
            } else {
                logcat(LogPriority.DEBUG) { "No SharedPreferences repos backup found" }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to load repositories from SharedPreferences" }
        }
    }

    private fun saveRepositories() {
        try {
            val jsonContent = json.encodeToString(_repositories.value)
            sourcePreferences.jsRepositoriesBackup.set(jsonContent)
            logcat(LogPriority.DEBUG) { "Saved ${_repositories.value.size} repositories to SharedPreferences" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to save repositories to SharedPreferences" }
        }
        saveRepositoriesToFile()
    }

    private fun saveRepositoriesToFile() {
        try {
            val dir = pluginsDir
            if (dir == null) {
                logcat(LogPriority.WARN) { "Plugins directory not available for saving repositories.json" }
                return
            }
            val reposFile = dir.createFile("repositories.json")
            if (reposFile == null) {
                logcat(LogPriority.ERROR) { "Failed to create repositories.json file" }
                return
            }
            val jsonContent = json.encodeToString(_repositories.value)
            reposFile.writeText(jsonContent)
            logcat(LogPriority.INFO) { "Wrote ${jsonContent.length} chars to repositories.json" }
            logcat(LogPriority.INFO) { "Saved ${_repositories.value.size} repositories to disk" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to save repositories to disk" }
        }
    }

    /**
     * Group plugins by language
     */
    fun getPluginsByLanguage(): Map<String, List<JsPlugin>> {
        return _availablePlugins.value.groupBy { it.lang }
    }

    /**
     * Filter plugins by search query
     */
    fun searchPlugins(query: String): List<JsPlugin> {
        if (query.isBlank()) return _availablePlugins.value

        val lowerQuery = query.lowercase()
        return _availablePlugins.value.filter { plugin ->
            plugin.name.lowercase().contains(lowerQuery) ||
                plugin.site.lowercase().contains(lowerQuery) ||
                plugin.id.lowercase().contains(lowerQuery)
        }
    }

    /**
     * Check if a plugin is installed
     */
    fun isInstalled(pluginId: String): Boolean {
        return _installedPlugins.value.any { it.plugin.id == pluginId }
    }

    /**
     * Get installed plugin by ID
     */
    fun getInstalledPlugin(pluginId: String): InstalledJsPlugin? {
        return _installedPlugins.value.find { it.plugin.id == pluginId }
    }

    // Icon caching

    /**
     * Returns the local cached icon file for a plugin, or null if not cached.
     */
    fun getCachedIconFile(pluginId: String): File? {
        val file = File(iconsCacheDir, "$pluginId.png")
        return file.takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * Returns the local icon path if cached, otherwise the original URL.
     * This avoids re-fetching icons on every screen visit.
     */
    fun getIconUrl(plugin: JsPlugin): String {
        val cached = getCachedIconFile(plugin.id)
        return cached?.let { "file://${it.absolutePath}" } ?: plugin.iconUrl
    }

    /**
     * Download and cache icons for a list of plugins in the background.
     */
    private suspend fun cacheIcons(plugins: List<JsPlugin>) = withContext(Dispatchers.IO) {
        for (plugin in plugins) {
            if (plugin.iconUrl.isBlank()) continue
            val iconFile = File(iconsCacheDir, "${plugin.id}.png")
            if (iconFile.exists() && iconFile.length() > 0) continue
            try {
                val response = client.newCall(GET(plugin.iconUrl)).execute()
                response.use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.byteStream()?.use { input ->
                            iconFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Failed to cache icon for ${plugin.name}: ${e.message}" }
            }
        }
    }

    // UniFile helpers
    private fun UniFile.readText(): String {
        return this.openInputStream().use { it.reader().readText() }
    }

    private fun UniFile.writeText(text: String) {
        this.openOutputStream().use { output ->
            val writer = output.bufferedWriter(StandardCharsets.UTF_8)
            writer.write(text)
            writer.flush()
        }
        logcat(LogPriority.DEBUG) { "Wrote ${text.length} chars to ${this.name}" }
    }

    private fun UniFile.readUtf8(): String {
        return this.openInputStream().use { input ->
            val bytes = input.readBytes()
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    private fun UniFile.writeUtf8(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        this.openOutputStream().use { output ->
            output.write(bytes)
        }
    }
}
