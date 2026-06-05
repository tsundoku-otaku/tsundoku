package eu.kanade.domain.manga.interactor

import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.source.normalizeSourcePath
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.Dispatchers
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Helper for the URL-based mass import flow. Provides URL parsing/analysis and per-URL novel
 * resolution. The actual batched import is executed by
 * [eu.kanade.tachiyomi.data.massimport.MassImportJob].
 */
class MassImport(
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) {
    private val missingSourceHostLogCache = ConcurrentHashMap<String, Boolean>()

    suspend fun resolveMangaUrl(url: String, path: String, source: CatalogueSource): Manga {
        val inputUrl = normalizeSourcePath(source, path)
        // No search-by-URL or slash-toggle fallbacks here: they never resolved anything reliably
        // and their failures (e.g. UnknownHostException from a slash-less baseUrl + url concat)
        // masked the real error from the direct details fetch.
        var resolved: eu.kanade.tachiyomi.source.model.SManga? = null
        if (source is eu.kanade.tachiyomi.source.online.ResolvableSource &&
            source.getUriType(url) == eu.kanade.tachiyomi.source.online.UriType.Manga
        ) {
            resolved = runCatching { source.getManga(url) }.getOrNull()
        }

        val sManga = resolved ?: source.getMangaDetails(
            eu.kanade.tachiyomi.source.model.SManga.create().apply {
                this.url = inputUrl
            },
        )

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
        // The source was already matched to this URL by the caller, so whenever the URL parses
        // as an absolute URL just take its path + query. Comparing hosts here breaks on
        // www./mirror-subdomain mismatches (e.g. sonicmtl.com vs www.sonicmtl.com) and used to
        // leak the host into the path, producing requests like "https://www.sonicmtl.comsonicmtl.com/...".
        val extractedPath = try {
            val urlUri = URI(url)
            if (urlUri.host != null) {
                buildString {
                    append(urlUri.rawPath ?: "")
                    val q = urlUri.rawQuery
                    if (!q.isNullOrBlank()) {
                        append('?')
                        append(q)
                    }
                }
            } else {
                extractPathFallback(url, baseUrl)
            }
        } catch (_: Exception) {
            extractPathFallback(url, baseUrl)
        }

        val rawPath = source?.let { normalizeSourcePath(it, extractedPath) } ?: extractedPath
        return normalizeUrl(rawPath)
    }

    /**
     * String-based path extraction for URLs that [URI] can't parse. Preserves path casing and
     * never leaks the host into the returned path even when it doesn't match [baseUrl].
     */
    private fun extractPathFallback(url: String, baseUrl: String): String {
        val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+\\-.]*://")
        val rawUrl = url.trim().replace(schemeRegex, "")
        val normalizedUrl = rawUrl.removePrefix("www.")
        val normalizedBase = baseUrl.trim().replace(schemeRegex, "")
            .removePrefix("www.")
            .removeSuffix("/")

        if (normalizedUrl.startsWith(normalizedBase, ignoreCase = true)) {
            return normalizedUrl.substring(normalizedBase.length)
        }

        // Host mismatch (mirror/subdomain): drop everything before the first slash.
        val slashIndex = normalizedUrl.indexOf('/')
        return if (slashIndex >= 0) normalizedUrl.substring(slashIndex) else normalizedUrl
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
