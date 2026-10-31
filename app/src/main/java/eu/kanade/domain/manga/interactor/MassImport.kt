package eu.kanade.domain.manga.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.massimport.DeeplinkResolver
import eu.kanade.tachiyomi.data.massimport.PackageManagerDeeplinkResolver
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.source.normalizeSourcePath
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
    private val deeplinkResolverFactory: () -> DeeplinkResolver = { PackageManagerDeeplinkResolver() },
) {
    private val missingSourceHostLogCache = ConcurrentHashMap<String, Boolean>()

    companion object {
        private val SCHEME_START = Regex("https?://", RegexOption.IGNORE_CASE)
        private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+\\-.]*://")
        private val SCHEMELESS_URL = Regex("(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d+)?(?:[/?#].*)?", RegexOption.IGNORE_CASE)
        private const val FIELD_SEPARATORS = ",;|`\"'<>[]{}"
        private const val URL_TERMINATORS = "\"<>`"
        private const val LIST_SEPARATORS = ",;|"

        // Smart quotes and CJK clause punctuation are never part of a pasted URL.
        private const val UNICODE_TERMINATORS =
            "“”„‘’‚«»‹›「」『』、。！＂＇，：；？"
        private const val SENTENCE_PUNCTUATION = ".:!?"
        private const val OPENERS = "([{"
        private const val CLOSERS = ")]}"

        // A scheme right after one of these belongs to the URL (archive and redirect links).
        private const val EMBEDDED_SCHEME_PREFIX = "/=?&#"

        // URLs plus the leftover non-URL fields, in line order.
        fun tokenizeLine(line: String): List<String> = scan(line, keepFields = true)

        fun urlTokens(line: String): List<String> = scan(line, keepFields = false)

        fun isHttpUrl(token: String): Boolean {
            val bodyStart = schemeLength(token, 0)
            if (bodyStart == 0 || bodyStart == token.length) return false
            val c = token[bodyStart]
            return c != '/' && c != '?' && c != '#'
        }

        // Null for plain text such as CSV columns, which is not worth listing as invalid.
        fun invalidTokenReason(token: String): String? = when {
            "://" in token -> "Not a valid URL"
            SCHEMELESS_URL.matches(token) && ('/' in token || token.startsWith("www.", ignoreCase = true)) ->
                "Missing http:// or https://"
            else -> null
        }

        private fun schemeLength(s: String, at: Int): Int = when {
            s.regionMatches(at, "https://", 0, 8, ignoreCase = true) -> 8
            s.regionMatches(at, "http://", 0, 7, ignoreCase = true) -> 7
            else -> 0
        }

        private fun isBreak(c: Char): Boolean =
            Character.isWhitespace(c) ||
                Character.isSpaceChar(c) ||
                c in '​'..'‍' ||
                c == '⁠' ||
                c == '﻿' ||
                c == '᠎' ||
                c in UNICODE_TERMINATORS

        private fun scan(rawLine: String, keepFields: Boolean): List<String> {
            // JSON encoders commonly escape slashes.
            val line = if ("\\/" in rawLine) rawLine.replace("\\/", "/") else rawLine
            val starts = mutableListOf<Int>()
            val ends = mutableListOf<Int>()
            var from = 0
            while (from < line.length) {
                val start = SCHEME_START.find(line, from)?.range?.first ?: break
                val end = urlEnd(line, start)
                if (end > start + schemeLength(line, start)) {
                    starts += start
                    ends += end
                }
                from = end
            }

            // Sentence punctuation is only trimmed when the URL sits in prose; a URL alone on its
            // line or among other URLs keeps it.
            var last = 0
            var prose = false
            for (i in starts.indices) {
                if (hasWordChar(line, last, starts[i])) prose = true
                last = ends[i]
            }
            if (hasWordChar(line, last, line.length)) prose = true

            val tokens = ArrayList<String>(starts.size)
            last = 0
            for (i in starts.indices) {
                if (keepFields) addFields(line, last, starts[i], tokens)
                val url = cleanUrl(line, starts[i], ends[i], line.getOrNull(starts[i] - 1) == '\'', prose)
                if (keepFields || isHttpUrl(url)) tokens += url
                last = ends[i]
            }
            if (keepFields) addFields(line, last, line.length, tokens)
            return tokens
        }

        private fun urlEnd(line: String, start: Int): Int {
            val bodyStart = start + schemeLength(line, start)
            var inQuery = false
            for (i in bodyStart until line.length) {
                val c = line[i]
                if (isBreak(c) || c in URL_TERMINATORS) return i
                if (c in LIST_SEPARATORS && separatorEndsUrl(line, i + 1, inQuery)) return i
                if (c == '?' || c == '#') inQuery = true
                if (i > bodyStart && line[i - 1] !in EMBEDDED_SCHEME_PREFIX && schemeLength(line, i) > 0) return i
            }
            return line.length
        }

        // Query values use commas as list separators, so there only a clear field boundary ends
        // the URL. In the path a comma is kept only before '_' or '%' (wiki-style titles).
        private fun separatorEndsUrl(line: String, next: Int, inQuery: Boolean): Boolean {
            val n = line.getOrNull(next) ?: return true
            if (isBreak(n) || n in URL_TERMINATORS || n == '\'' || schemeLength(line, next) > 0) return true
            return !inQuery && n != '_' && n != '%'
        }

        private fun cleanUrl(line: String, start: Int, end: Int, singleQuoted: Boolean, prose: Boolean): String {
            val bodyStart = start + schemeLength(line, start)
            val opens = IntArray(OPENERS.length)
            val closes = IntArray(CLOSERS.length)
            for (i in bodyStart until end) {
                val o = OPENERS.indexOf(line[i])
                if (o >= 0) opens[o]++
                val c = CLOSERS.indexOf(line[i])
                if (c >= 0) closes[c]++
            }
            var e = end
            while (e > bodyStart) {
                val ch = line[e - 1]
                val k = CLOSERS.indexOf(ch)
                val o = OPENERS.indexOf(ch)
                when {
                    ch in LIST_SEPARATORS -> {}
                    o >= 0 -> opens[o]--
                    prose && ch in SENTENCE_PUNCTUATION -> {}
                    singleQuoted && ch == '\'' -> {}
                    k >= 0 && closes[k] > opens[k] -> closes[k]--
                    else -> break
                }
                e--
            }
            return line.substring(start, bodyStart).lowercase() + line.substring(bodyStart, e)
        }

        private fun hasWordChar(line: String, from: Int, to: Int): Boolean {
            for (i in from until to) if (line[i].isLetterOrDigit()) return true
            return false
        }

        private fun addFields(line: String, from: Int, to: Int, into: MutableList<String>) {
            var fieldStart = from
            for (i in from..to) {
                if (i == to || isBreak(line[i]) || line[i] in FIELD_SEPARATORS) {
                    if (i > fieldStart) into += line.substring(fieldStart, i)
                    fieldStart = i + 1
                }
            }
        }
    }

    suspend fun resolveMangaUrl(url: String, path: String, source: CatalogueSource): Manga {
        val inputUrl = normalizeSourcePath(source, path)
        // No search-by-URL or slash-toggle fallbacks here: they never resolved anything reliably
        // and their failures (e.g. UnknownHostException from a slash-less baseUrl + url concat)
        // masked the real error from the direct details fetch. Deeplink-shaped URLs are already
        // resolved to their canonical path by the caller before path is passed in here.
        val sManga = source.getMangaUpdate(
            eu.kanade.tachiyomi.source.model.SManga.create().apply {
                this.url = inputUrl
            },
            emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga

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
        return sourceManager.getAll().filterIsInstance<CatalogueSource>().filter { it is HttpSource || it is JsSource }
    }

    // Single source-matching algorithm shared by the analysis preview and the worker
    // (MassImportJob delegates here) so the dialog's "valid" classification can't disagree with
    // what the import actually resolves. Host + path-prefix match, not a raw string startsWith:
    // startsWith broke on www./mirror-subdomain differences.
    fun findMatchingSource(
        url: String,
        sources: List<CatalogueSource> = getAllSources(),
        preferredSourceId: Long? = null,
    ): CatalogueSource? {
        val urlHost = try {
            URI(url).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
        val matchingSources = sources.filter { source ->
            try {
                val rawBase = getSourceBaseUrl(source)
                val baseForUri = if (rawBase.startsWith("http")) rawBase else "https://$rawBase"
                val baseUri = URI(baseForUri)
                val baseHost = baseUri.host?.lowercase()?.removePrefix("www.")
                val basePath = baseUri.path?.trimEnd('/')
                if (baseHost.isNullOrEmpty() || urlHost.isNullOrEmpty()) return@filter false

                val hostMatches = urlHost == baseHost ||
                    urlHost.endsWith(".$baseHost") ||
                    baseHost.endsWith(".$urlHost")
                if (!hostMatches) return@filter false

                if (!basePath.isNullOrBlank() && basePath != "/") {
                    val urlPath = URI(url).path ?: ""
                    urlPath.startsWith(basePath)
                } else {
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

        if (matchingSources.isEmpty()) {
            if (urlHost == null || missingSourceHostLogCache.putIfAbsent(urlHost, true) == null) {
                logcat(LogPriority.WARN) { "MassImport: No source match for $url host=$urlHost" }
            }
            return null
        }
        if (matchingSources.size == 1) return matchingSources.first()

        // Prefer the caller's source (e.g. the currently browsed source) when it matches.
        if (preferredSourceId != null) {
            matchingSources.firstOrNull { it.id == preferredSourceId }?.let { return it }
        }

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
        return text.lineSequence()
            .flatMap { urlTokens(it).asSequence() }
            .distinctBy { urlDedupKey(it) }
            .toList()
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
        val deeplinkResolver = deeplinkResolverFactory()
        val libraryUrlIndex = try {
            mangaRepository.getFavoriteSourceAndUrl().toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val rawLines = text.lineSequence().flatMap { tokenizeLine(it).asSequence() }
        val validUrls = mutableListOf<String>()
        val invalidUrls = mutableListOf<Pair<String, String>>()
        val duplicateUrls = mutableListOf<String>()
        val alreadyInLibrary = mutableListOf<String>()
        val seenKeys = mutableSetOf<String>()

        for (line in rawLines) {
            if (!isHttpUrl(line)) {
                invalidTokenReason(line)?.let { invalidUrls.add(line to it) }
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
            if (!deeplinkResolver.isDeeplinkUrl(source, line)) {
                val path = extractPathFromUrl(line, getSourceBaseUrl(source), source)
                if (libraryUrlIndex.contains(source.id to path)) {
                    alreadyInLibrary.add(line)
                    continue
                }
            }

            validUrls.add(line)
        }

        UrlAnalysisResult(validUrls, invalidUrls, duplicateUrls, alreadyInLibrary)
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
            // Unparseable URL: lowercase only the host, preserve case-sensitive path.
            val noScheme = url.trim().replace(SCHEME_REGEX, "")
            val slash = noScheme.indexOf('/')
            val key = if (slash < 0) {
                noScheme.lowercase()
            } else {
                noScheme.substring(0, slash).lowercase() + noScheme.substring(slash)
            }
            key.removeSuffix("/")
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
