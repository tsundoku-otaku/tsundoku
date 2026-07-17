package eu.kanade.tachiyomi.source.custom

import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal fun rebaseCustomSourceUrl(
    url: String?,
    customBaseUrl: String,
    sourceBaseUrl: String? = null,
): String? {
    val value = normalizeCustomUrl(url).orEmpty()
    if (value.isBlank()) return url

    val customBase = customBaseUrl.trimEnd('/')
    val sourceBase = sourceBaseUrl?.trimEnd('/')

    if (sourceBase != null && value.startsWith(sourceBase)) {
        return customBase + value.removePrefix(sourceBase)
    }

    if (value.startsWith("http://") || value.startsWith("https://")) {
        return value
    }

    return customBase + "/" + value.removePrefix("/")
}

internal fun mapCustomUrlToSourceUrl(
    url: String?,
    customBaseUrl: String,
    sourceBaseUrl: String? = null,
): String? {
    val value = normalizeCustomUrl(url).orEmpty()
    if (value.isBlank()) return url

    val customBase = customBaseUrl.trimEnd('/')
    val sourceBase = sourceBaseUrl?.trimEnd('/') ?: return value

    if (value.startsWith(customBase)) {
        return sourceBase + value.removePrefix(customBase)
    }

    return value
}

internal fun rebaseCustomSourceManga(
    manga: SManga,
    customBaseUrl: String,
    sourceBaseUrl: String? = null,
): SManga {
    return safeCopyManga(manga).apply {
        val originalUrl = runCatching { manga.url }.getOrNull()
        url = rebaseCustomSourceUrl(originalUrl, customBaseUrl, sourceBaseUrl) ?: (originalUrl ?: "")
        val originalThumb = runCatching { manga.thumbnail_url }.getOrNull()
        thumbnail_url = rebaseCustomSourceUrl(originalThumb, customBaseUrl, sourceBaseUrl) ?: originalThumb
    }
}

internal fun rebaseCustomSourceChapter(
    chapter: SChapter,
    customBaseUrl: String,
    sourceBaseUrl: String? = null,
): SChapter {
    return SChapter.create().also { rebased ->
        rebased.copyFrom(chapter)
        rebased.url = rebaseCustomSourceUrl(chapter.url, customBaseUrl, sourceBaseUrl) ?: chapter.url
    }
}

internal fun rebaseCustomSourcePage(
    page: Page,
    customBaseUrl: String,
    sourceBaseUrl: String? = null,
): Page {
    return Page(
        page.index,
        rebaseCustomSourceUrl(page.url, customBaseUrl, sourceBaseUrl).orEmpty(),
        page.imageUrl?.let { rebaseCustomSourceUrl(it, customBaseUrl, sourceBaseUrl) },
        page.uri,
    ).also {
        it.text = page.text
    }
}

internal fun rebaseCustomSourceMangasPage(
    mangasPage: MangasPage,
    customBaseUrl: String,
    sourceBaseUrl: String? = null,
): MangasPage {
    return MangasPage(
        mangas = mangasPage.mangas.map { rebaseCustomSourceManga(it, customBaseUrl, sourceBaseUrl) },
        hasNextPage = mangasPage.hasNextPage,
    )
}

internal fun stableCustomSourceId(name: String, baseUrl: String): Long {
    return (name + baseUrl).hashCode().toLong() and 0x7FFFFFFF
}

// Safe copy helper that avoids reading lateinit properties which may not be initialized.
private fun safeCopyManga(manga: SManga): SManga {
    val copy = SManga.create()
    copy.url = runCatching { manga.url }.getOrNull() ?: ""
    copy.title = runCatching { manga.title }.getOrNull() ?: ""
    copy.artist = runCatching { manga.artist }.getOrNull()
    copy.author = runCatching { manga.author }.getOrNull()
    copy.description = runCatching { manga.description }.getOrNull()
    copy.genre = runCatching { manga.genre }.getOrNull()
    copy.status = runCatching { manga.status }.getOrElse { 0 }
    copy.thumbnail_url = runCatching { manga.thumbnail_url }.getOrNull()
    copy.update_strategy =
        runCatching {
            manga.update_strategy
        }.getOrElse { eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE }
    copy.initialized = runCatching { manga.initialized }.getOrElse { false }
    copy.altTitles = runCatching { manga.altTitles }.getOrElse { emptyList() }
    return copy
}

private fun toHttpSourceRequestPath(url: String?, sourceBaseUrl: String?): String? {
    val value = normalizeCustomUrl(url).orEmpty()
    if (value.isBlank()) return url

    val sourceBase = sourceBaseUrl?.trimEnd('/')
    val httpUrl = value.toHttpUrlOrNull()

    if (httpUrl != null) {
        val path = buildString {
            append(httpUrl.encodedPath.ifBlank { "/" })
            if (httpUrl.encodedQuery != null) {
                append('?')
                append(httpUrl.encodedQuery)
            }
            if (httpUrl.encodedFragment != null) {
                append('#')
                append(httpUrl.encodedFragment)
            }
        }
        return if (path.startsWith('/')) path else "/$path"
    }

    if (sourceBase != null && value.startsWith(sourceBase)) {
        val path = value.removePrefix(sourceBase)
        return if (path.startsWith('/')) path else "/${path.removePrefix("/")}"
    }

    return if (value.startsWith('/')) value else "/${value.removePrefix("/")}"
}

private fun normalizeCustomUrl(url: String?): String? {
    val value = url?.trim().orEmpty()
    if (value.isBlank()) return url

    val embeddedAbsoluteStart = listOf("https://", "http://", "https//", "http//")
        .mapNotNull { scheme ->
            val idx = value.lastIndexOf(scheme)
            if (idx > 0) idx else null
        }
        .maxOrNull()
    if (embeddedAbsoluteStart != null) {
        val embedded = value.substring(embeddedAbsoluteStart)
        return when {
            embedded.startsWith("https//") -> "https://" + embedded.removePrefix("https//")
            embedded.startsWith("http//") -> "http://" + embedded.removePrefix("http//")
            else -> embedded
        }
    }

    return when {
        value.startsWith("https//") -> "https://" + value.removePrefix("https//")
        value.startsWith("http//") -> "http://" + value.removePrefix("http//")
        else -> value
    }
}

private fun trySetFieldRecursively(target: Any, fieldName: String, value: Any): Boolean {
    var current: Class<*>? = target.javaClass
    while (current != null) {
        val field = runCatching { current.getDeclaredField(fieldName) }.getOrNull()
        if (field != null) {
            return runCatching {
                field.isAccessible = true
                field.set(target, value)
                true
            }.getOrDefault(false)
        }
        current = current.superclass
    }
    return false
}

private fun createBaseUrlRewriteClient(
    client: OkHttpClient,
    sourceBaseUrl: String,
    customBaseUrl: String,
): OkHttpClient {
    val sourceBase = sourceBaseUrl.trimEnd('/')
    val customBase = customBaseUrl.trimEnd('/')
    val sourceBaseHttp = sourceBase.toHttpUrlOrNull()
    val customBaseHttp = customBase.toHttpUrlOrNull()

    return client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val reqUrl = request.url
            var nextRequest = request

            // Prefer structural comparison using HttpUrl when possible to avoid string concat bugs
            if (sourceBaseHttp != null && customBaseHttp != null) {
                if (reqUrl.host == sourceBaseHttp.host && reqUrl.scheme == sourceBaseHttp.scheme) {
                    val newUrl = reqUrl.newBuilder()
                        .scheme(customBaseHttp.scheme)
                        .host(customBaseHttp.host)
                        .port(customBaseHttp.port)
                        .build()
                    nextRequest = request.newBuilder().url(newUrl).build()
                } else {
                    // Fallback: handle exact string-prefix replacement only when request string actually starts with sourceBase
                    val reqStr = reqUrl.toString()
                    if (reqStr.startsWith(sourceBase)) {
                        val rewritten = customBase + reqStr.removePrefix(sourceBase)
                        rewritten.toHttpUrlOrNull()?.let { newHttpUrl ->
                            nextRequest = request.newBuilder().url(newHttpUrl).build()
                        }
                    }
                }
            } else {
                // If parsing failed for any reason, still attempt conservative string-based rewrite
                val reqStr = reqUrl.toString()
                if (reqStr.startsWith(sourceBase)) {
                    val rewritten = customBase + reqStr.removePrefix(sourceBase)
                    rewritten.toHttpUrlOrNull()?.let { newHttpUrl ->
                        nextRequest = request.newBuilder().url(newHttpUrl).build()
                    }
                }
            }
            val finalRequest = run {
                val reqStr = nextRequest.url.toString()
                // look for second (or later) occurrence of scheme
                val idxHttp = reqStr.indexOf("http://", 8).let { if (it >= 0) it else reqStr.indexOf("https://", 8) }
                if (idxHttp > 0) {
                    val embedded = reqStr.substring(idxHttp)
                    embedded.toHttpUrlOrNull()?.let { embeddedUrl ->
                        return@run nextRequest.newBuilder().url(embeddedUrl).build()
                    }
                }
                nextRequest
            }

            chain.proceed(finalRequest)
        }
        .build()
}

private fun patchHttpSourceForCustomBaseUrl(source: HttpSource, customBaseUrl: String): HttpSource {
    val sourceBaseUrl = source.baseUrl.trimEnd('/')
    val targetBaseUrl = customBaseUrl.trimEnd('/')
    if (targetBaseUrl.isBlank() || sourceBaseUrl == targetBaseUrl) return source

    // Patch a fresh instance, not the shared SourceManager singleton: mutating its client
    // leaks the rewrite interceptor onto the original source's own requests.
    val target = source.freshCopyOrNull() ?: source
    val rewrittenClient = createBaseUrlRewriteClient(target.client, sourceBaseUrl, targetBaseUrl)
    trySetFieldRecursively(target, "client", rewrittenClient)
    // Keep baseUrl original so the interceptor still matches outgoing requests.
    return target
}

// Null for SourceFactory-built sources with no no-arg constructor; caller falls back to shared.
private fun HttpSource.freshCopyOrNull(): HttpSource? = try {
    javaClass.getDeclaredConstructor()
        .apply { isAccessible = true }
        .newInstance() as? HttpSource
} catch (_: Throwable) {
    null
}

internal fun customSourceStorageFileCandidates(
    directory: java.io.File,
    sourceId: Long,
    sourceName: String,
): List<java.io.File> {
    return listOf(
        java.io.File(directory, "$sourceId.json"),
        java.io.File(directory, "${sanitizeCustomSourceFilename(sourceName)}.json"),
    )
}

private fun sanitizeCustomSourceFilename(name: String): String {
    return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}

// {page} is substituted on every page (page 1 included). For sites whose first page has no page
// segment, the caller passes the verbatim page-1 URL and uses the {page} template only for page 2+.
internal fun buildPagedUrlTemplate(template: String, baseUrl: String, page: Int): String {
    return template
        .replace("{baseUrl}", baseUrl)
        .replace("{page}", page.toString())
        .trimEnd('/', '?', '&')
}

internal fun buildPagedSearchUrlTemplate(template: String, baseUrl: String, query: String, page: Int): String {
    return template
        .replace("{baseUrl}", baseUrl)
        .replace("{query}", java.net.URLEncoder.encode(query, "UTF-8"))
        .replace("{page}", page.toString())
        .trimEnd('/', '?', '&')
}

/**
 * Maps a site's status text to an [SManga] status constant. [mapping] (substring -> ongoing/
 * completed/hiatus/cancelled, case-insensitive) wins so non-English sites define their own words;
 * built-in English keywords are the fallback.
 */
internal fun parseCustomSourceStatus(status: String?, mapping: Map<String, String>? = null): Int {
    if (status.isNullOrBlank()) return SManga.UNKNOWN
    fun toConst(value: String): Int = when (value.trim().lowercase()) {
        "ongoing", "publishing", "releasing" -> SManga.ONGOING
        "completed", "complete", "finished" -> SManga.COMPLETED
        "hiatus", "on_hiatus", "on hiatus", "paused" -> SManga.ON_HIATUS
        "cancelled", "canceled", "dropped" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }
    mapping?.forEach { (needle, value) ->
        if (needle.isNotBlank() && status.contains(needle, ignoreCase = true)) {
            val mapped = toConst(value)
            if (mapped != SManga.UNKNOWN) return mapped
        }
    }
    return when {
        status.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
        status.contains("completed", ignoreCase = true) -> SManga.COMPLETED
        status.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
        status.contains("cancelled", ignoreCase = true) || status.contains("canceled", ignoreCase = true) ->
            SManga.CANCELLED
        else -> SManga.UNKNOWN
    }
}

// Cover-image attributes tried in order before plain src (lazy-load data-*, srcset, poster, og:image).
private val IMAGE_URL_ATTRS = listOf(
    "data-src", "data-original", "data-lazy-src", "data-lazy", "data-cfsrc",
    "data-echo", "data-srcset", "srcset", "src", "poster", "content",
)

/**
 * Resolves an absolute image URL from [element]. Prefers each attribute's `abs:` form (so relative
 * covers aren't returned bare), falls back to a `background-image:url(...)`, unwraps `srcset` to its
 * first URL. Null when nothing usable.
 */
internal fun resolveImageUrl(element: Element): String? {
    for (attr in IMAGE_URL_ATTRS) {
        // abs: yields the resolved absolute URL when the attribute holds a relative path.
        val raw = element.attr("abs:$attr").ifBlank { element.attr(attr) }.ifBlank { null } ?: continue
        val first = raw.trim().substringBefore(' ').substringBefore(',').trim()
        if (first.isNotBlank()) return first
    }
    // CSS background-image on the element itself.
    val style = element.attr("style")
    if (style.isNotBlank()) {
        Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""").find(style)?.groupValues?.getOrNull(1)
            ?.trim()?.ifBlank { null }?.let { return it }
    }
    return null
}

/**
 * Inner HTML to plain text, preserving paragraph breaks (`<br>` -> newline, `</p>`/`</div>` -> blank
 * line) so multi-paragraph synopses don't collapse to one line like Jsoup `.text()` does.
 */
internal fun htmlToFormattedText(html: String): String {
    val withBreaks = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|li|h[1-6])>"), "\n\n")
    return org.jsoup.Jsoup.parse(withBreaks).wholeText()
        .lineSequence()
        .joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** Numeric chapter URL/name pair for a generated chapter list (mode B). */
internal data class GeneratedChapterEntry(val url: String, val name: String, val number: Float)

/** Substitutes `{novelUrl}` in a generated-chapter pattern with the current novel's path. */
internal fun applyNovelUrlToPattern(pattern: String, novelUrl: String): String =
    pattern.replace("{novelUrl}", novelUrl.trim().trimEnd('/'))

/**
 * From two chapter URLs of the SAME novel, derive a portable numeric pattern + the two numbers. The
 * last digit run is the chapter number (robust to a novel id earlier in the path); a [novelUrl]
 * prefix is replaced with `{novelUrl}` to generalize. Returns (pattern, first, last) or null.
 */
internal fun deriveGenericChapterPattern(
    chapterUrl1: String,
    chapterUrl2: String,
    baseUrl: String,
    novelUrl: String?,
): Triple<String, Int, Int>? {
    if (chapterUrl1.isBlank() || chapterUrl2.isBlank()) return null
    val rx = Regex("""\d+""")
    val m1 = rx.findAll(chapterUrl1).lastOrNull() ?: return null
    val m2 = rx.findAll(chapterUrl2).lastOrNull() ?: return null
    val n1 = m1.value.toIntOrNull() ?: return null
    val n2 = m2.value.toIntOrNull() ?: return null
    if (n1 == n2) return null
    val patternAbs = chapterUrl1.substring(0, m1.range.first) + "{n}" + chapterUrl1.substring(m1.range.last + 1)
    val base = baseUrl.trim().trimEnd('/')
    var pattern = if (patternAbs.startsWith(base)) patternAbs.removePrefix(base) else patternAbs
    val novelRel = novelUrl?.trim()?.let { if (it.startsWith(base)) it.removePrefix(base) else it }
        ?.trimEnd('/')
    if (!novelRel.isNullOrBlank() && pattern.startsWith(novelRel)) {
        pattern = "{novelUrl}" + pattern.removePrefix(novelRel)
    }
    return Triple(pattern, n1, n2)
}

/** Builds [start]..[end] chapter entries from a numeric URL [pattern]; callers normalize the URLs. */
internal fun generatedChapterEntries(
    pattern: String,
    start: Int,
    end: Int,
    nameTemplate: String?,
): List<GeneratedChapterEntry> {
    if (end < start) return emptyList()
    val names = nameTemplate?.ifBlank { null } ?: "Chapter {n}"
    return (start..end).map { n ->
        GeneratedChapterEntry(
            url = pattern.replace("{n}", n.toString()),
            name = names.replace("{n}", n.toString()),
            number = n.toFloat(),
        )
    }
}

/**
 * User-defined novel source driven by [CustomSourceConfig] (CSS selectors, URL patterns, headers).
 * Config is JSON, editable in-app and shareable. When [CustomSourceConfig.basedOnSourceId] is set,
 * delegates to an installed extension and only rebases its base URL.
 */
class CustomNovelSource(
    val config: CustomSourceConfig,
) : HttpSource() {

    // Mark this as a novel source for HttpPageLoader detection
    override val isNovelSource: Boolean = config.isNovel

    override val name: String = config.name
    override val baseUrl: String = config.baseUrl
    override val lang: String = config.language
    override val id: Long = config.id ?: generateId(config.name, config.baseUrl)
    override val supportsLatest: Boolean
        get() = config.latestUrl != null || baseSource?.supportsLatest == true

    // Must match StubSource.toString() so translations/quotes resolve to the same on-disk directory
    // whether this source is loaded or currently stubbed. Novel sources render as "Name (LANG) (JS)".
    override fun toString(): String =
        if (isNovelSource) "$name (${lang.uppercase()}) (JS)" else "$name (${lang.uppercase()})"

    override val client = network.cloudflareClient

    /**
     * When basedOnSourceId is set, we delegate all fetching/parsing to the base
     * extension source but substitute our own baseUrl in requests.
     * Supports HttpSource (APK extensions), JsSource (JS plugins), and any CatalogueSource.
     */
    private val baseSource: CatalogueSource? by lazy {
        config.basedOnSourceId?.let { sourceId ->
            try {
                val source = Injekt.get<SourceManager>().get(sourceId) as? CatalogueSource
                // Capture original base URL BEFORE any patching
                val originalBaseUrl = when (source) {
                    is JsSource -> source.baseUrl.trimEnd('/')
                    is HttpSource -> source.baseUrl.trimEnd('/')
                    else -> null
                }
                baseSourceOriginalUrl = originalBaseUrl

                when {
                    source is JsSource && baseUrl.isNotBlank() -> source.withSiteOverride(baseUrl)
                    source is HttpSource && baseUrl.isNotBlank() -> patchHttpSourceForCustomBaseUrl(source, baseUrl)
                    else -> source
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    // Store the original source base URL before any patching/overrides
    private var baseSourceOriginalUrl: String? = null

    private val baseSourceUrl: String? by lazy {
        // Ensure baseSource initialization happens first (which sets baseSourceOriginalUrl),
        // then return the captured original URL
        baseSource
        baseSourceOriginalUrl
    }

    /**
     * The base URL to use when translating between the custom source and the delegated source.
     * Prefer the delegated source URL so absolute links can be mapped back correctly.
     */
    private val effectiveRebaseUrl: String?
        get() = baseSourceUrl ?: baseUrl.trimEnd('/')

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().apply {
        config.headers.forEach { (key, value) ->
            add(key, value)
        }
    }

    // ======================== Extension Delegation ========================
    // When basedOnSourceId is set, delegate to the base source's public API
    // (protected request/parse methods aren't accessible on external instances)

    override suspend fun getPopularManga(page: Int): MangasPage {
        baseSource?.let { source ->
            return rebaseMangasPage(source.getPopularManga(page))
        }
        return super.getPopularManga(page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        baseSource?.let { source ->
            return rebaseMangasPage(source.getLatestUpdates(page))
        }
        return super.getLatestUpdates(page)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        baseSource?.let { source ->
            return rebaseMangasPage(source.getSearchManga(page, query, filters))
        }
        return super.getSearchManga(page, query, filters)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        baseSource?.let { source ->
            return rebaseManga(source.getMangaDetails(toBaseSourceManga(manga)))
        }
        return super.getMangaDetails(manga)
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        baseSource?.let { source ->
            return source.getChapterList(toBaseSourceManga(manga)).map { rebaseChapter(it) }
        }
        val chSel = config.selectors.chapters
        if (isNovelSource && !chSel.urlPattern.isNullOrBlank()) {
            return generateChapterList(manga)
        }
        if (isNovelSource && (
                !chSel.nextPage.isNullOrBlank() || !chSel.indexLinkSelector.isNullOrBlank() ||
                    !chSel.pagedUrlPattern.isNullOrBlank()
                )
        ) {
            return fetchResolvedChapterList(manga, null)
        }
        return super.getChapterList(manga)
    }

    override suspend fun getChapterList(
        manga: SManga,
        context: eu.kanade.tachiyomi.source.model.RefreshContext,
    ): List<SChapter> {
        if (baseSource != null) return getChapterList(manga)
        val chSel = config.selectors.chapters
        if (isNovelSource && !chSel.urlPattern.isNullOrBlank()) {
            return generateChapterList(manga)
        }
        if (isNovelSource && (
                !chSel.nextPage.isNullOrBlank() || !chSel.indexLinkSelector.isNullOrBlank() ||
                    !chSel.pagedUrlPattern.isNullOrBlank()
                )
        ) {
            return fetchResolvedChapterList(manga, context)
        }
        return getChapterList(manga)
    }

    // Mode B: build the list from a numeric URL pattern. start = numbering base; end is read per
    // novel from countSelector (lastNumber is the fallback). At most one fetch (to read the count).
    private suspend fun generateChapterList(manga: SManga): List<SChapter> {
        val sel = config.selectors.chapters
        val pattern = sel.urlPattern ?: return emptyList()
        val start = sel.firstNumber ?: 1
        val countSel = sel.countSelector
        val end = if (!countSel.isNullOrBlank()) {
            val doc = client.newCall(GET(buildAbsoluteUrl(manga.url), headers)).awaitSuccess().asJsoup()
            doc.selectFirst(countSel)?.text()
                ?.let { Regex("""\d+""").find(it.replace(",", ""))?.value?.toIntOrNull() }
                ?: sel.lastNumber ?: start
        } else {
            sel.lastNumber ?: start
        }
        val resolvedPattern = applyNovelUrlToPattern(pattern, manga.url)
        val chapters = generatedChapterEntries(resolvedPattern, start, end, sel.nameTemplate).map { entry ->
            SChapter.create().apply {
                // Normalize to a path relative to baseUrl (getPageList rebuilds the absolute URL).
                url = toRelativeStoredUrl(entry.url).ifBlank { entry.url }
                name = entry.name
                chapter_number = entry.number
            }
        }
        // Lists are stored newest-first in the app; generated order is ascending, so reverse unless
        // the source explicitly wants ascending (reverseChapters).
        return if (config.reverseChapters) chapters else chapters.reversed()
    }

    // Resolves the chapter list across optional dimensions: indexLinkSelector (list on a separate
    // linked page), pagedUrlPattern (numbered URL pages, preferred over the next-button since it
    // can't be fooled by an ambiguous prev/next selector), and nextPage (follow the link, fallback).
    // De-dupes by URL across pages and reverses once at the end.
    private suspend fun fetchResolvedChapterList(
        manga: SManga,
        context: eu.kanade.tachiyomi.source.model.RefreshContext?,
    ): List<SChapter> {
        val selectors = config.selectors.chapters

        // If the first page introduces no chapter the library lacks (and not a forced refresh),
        // keep the existing list. We only short-circuit this "no new chapters" case since the
        // result replaces the stored list and must otherwise be the full set.
        val knownUrls = context?.takeIf { !it.forceRefresh }
            ?.existingChapters?.mapNotNull { it.url.ifBlank { null } }?.toSet().orEmpty()

        val seen = mutableSetOf<String>()
        val all = mutableListOf<SChapter>()
        fun addNew(chapters: List<SChapter>): List<SChapter> {
            val fresh = chapters.filter { seen.add(it.url) }
            all += fresh
            return fresh
        }

        // URL-pattern pagination: build page URLs directly instead of chasing a next link.
        val pagePattern = selectors.pagedUrlPattern
        if (!pagePattern.isNullOrBlank()) {
            val resolved = applyNovelUrlToPattern(pagePattern, manga.url)
            val visited = mutableSetOf<String>()
            var page = 1
            while (page <= MAX_CHAPTER_PAGES) {
                val pageUrl = buildAbsoluteUrl(resolved.replace("{page}", page.toString()))
                if (!visited.add(pageUrl)) break
                val doc = client.newCall(GET(pageUrl, headers)).awaitSuccess().asJsoup()
                val fresh = addNew(parseChapterElements(doc, selectors))
                // First page with nothing new => no updates; keep the existing list.
                if (page == 1 && knownUrls.isNotEmpty() && all.isNotEmpty() &&
                    all.none { it.url !in knownUrls }
                ) {
                    return context!!.existingChapters
                }
                // A page that added no new chapters means we've run past the end.
                if (fresh.isEmpty()) break
                page++
            }
            return if (config.reverseChapters) all.reversed() else all
        }

        val detailsUrl = buildAbsoluteUrl(manga.url)
        val detailsDoc = client.newCall(GET(detailsUrl, headers)).awaitSuccess().asJsoup()

        // Where the chapter list starts: the linked index page, or the details page itself.
        var url: String? = if (!selectors.indexLinkSelector.isNullOrBlank()) {
            detailsDoc.selectFirst(selectors.indexLinkSelector)?.absUrl("href")?.ifBlank { null }
        } else {
            detailsUrl
        }

        if (url == null) {
            // Index link configured but missing on the page: fall back to the details doc.
            addNew(parseChapterElements(detailsDoc, selectors))
        } else {
            val visited = mutableSetOf<String>()
            var guard = 0
            var firstDoc: Document? = if (url == detailsUrl) detailsDoc else null
            while (url != null && guard++ < MAX_CHAPTER_PAGES && visited.add(url)) {
                val doc = firstDoc ?: client.newCall(GET(url, headers)).awaitSuccess().asJsoup()
                firstDoc = null
                val firstPage = guard == 1
                addNew(parseChapterElements(doc, selectors))

                // First page, all already known => no updates; stop and keep the existing list.
                if (firstPage && knownUrls.isNotEmpty() && all.isNotEmpty() &&
                    all.none { it.url !in knownUrls }
                ) {
                    return context!!.existingChapters
                }

                val next = if (selectors.nextPage.isNullOrBlank()) {
                    null
                } else {
                    resolveNextPageUrl(doc, selectors.nextPage, url, visited)
                }
                url = if (next == null || next == url) null else next
            }
        }
        return if (config.reverseChapters) all.reversed() else all
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        baseSource?.let { source ->
            return source.getPageList(toBaseSourceChapter(chapter)).map { rebasePage(it) }
        }
        // Novel: single metadata page, no fetch. fetchPageText rebuilds the absolute URL from this
        // relative path, so the one content fetch happens there.
        if (isNovelSource) return listOf(Page(0, chapter.url))
        return super.getPageList(chapter)
    }

    // Delegate image resolution/download so the ext's own image interceptors and headers apply.
    override suspend fun getImageUrl(page: Page): String {
        (baseSource as? HttpSource)?.let { source ->
            return source.getImageUrl(toBaseSourcePage(page))
        }
        return super.getImageUrl(page)
    }

    override suspend fun getImage(page: Page): Response {
        (baseSource as? HttpSource)?.let { source ->
            return source.getImage(toBaseSourcePage(page))
        }
        return super.getImage(page)
    }

    // Route through buildAbsoluteUrl so an already-absolute stored url isn't glued onto baseUrl.
    override fun mangaDetailsRequest(manga: SManga): Request = GET(buildAbsoluteUrl(manga.url), headers)

    override fun chapterListRequest(manga: SManga): Request = GET(buildAbsoluteUrl(manga.url), headers)

    override fun pageListRequest(chapter: SChapter): Request = GET(buildAbsoluteUrl(chapter.url), headers)

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        // Page 1 = the verbatim first-page URL; later pages = the {page} template.
        val template = if (page <= 1) config.popularUrl else (config.popularPagedUrl ?: config.popularUrl)
        return GET(template.buildUrl(baseUrl, page), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val pageable = config.popularPagedUrl != null || config.popularUrl.contains("{page}")
        return parseMangaList(document, config.selectors.popular, pageable)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        val page1 = config.latestUrl ?: config.popularUrl
        val template = if (page <= 1) page1 else (config.latestPagedUrl ?: config.popularPagedUrl ?: page1)
        return GET(template.buildUrl(baseUrl, page), headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val pageable = config.latestPagedUrl != null || config.popularPagedUrl != null ||
            (config.latestUrl ?: config.popularUrl).contains("{page}")
        return parseMangaList(document, config.selectors.latest ?: config.selectors.popular, pageable)
    }

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val template = if (page <= 1) config.searchUrl else (config.searchPagedUrl ?: config.searchUrl)
        val built = template.buildSearchUrl(baseUrl, query, page)
        if (config.postSearch) {
            // POST search: send the query string as a form body so the endpoint URL stays clean.
            // "https://site/search?s=foo&page=2" -> POST https://site/search  body: s=foo&page=2
            val url = built.substringBefore('?')
            val body = built.substringAfter('?', "")
                .toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
            return POST(url, headers, body)
        }
        return GET(built, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val pageable = config.searchPagedUrl != null || config.searchUrl.contains("{page}")
        return parseMangaList(document, config.selectors.search ?: config.selectors.popular, pageable)
    }

    // ======================== Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val selectors = config.selectors.details

        return SManga.create().apply {
            title = document.selectText(selectors.title) ?: ""
            author = document.selectText(selectors.author)
            artist = document.selectText(selectors.artist)
            description = document.selectFormattedText(selectors.description)
            genre = document.selectJoinedText(selectors.genre)
            thumbnail_url = document.selectImageUrl(selectors.cover)
            status = parseStatus(document.selectText(selectors.status))
        }
    }

    // ======================== Chapters ========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return parseChapterList(document, config.selectors.chapters)
    }

    fun chapterPageParse(response: Response): SChapter {
        val document = response.asJsoup()
        val selectors = config.selectors.chapters

        val link = document.selectFirst(selectors.link ?: "a")
            ?: document.selectFirst("a[href*=chapter]")

        return SChapter.create().apply {
            url = toRelativeStoredUrl(link?.attr("abs:href"))
            name = document.selectText(selectors.name)
                ?: link?.text()?.trim()
                ?: "Chapter"
            date_upload = parseDate(document.selectText(selectors.date))
        }
    }

    private fun parseChapterList(document: Document, selectors: ChapterSelectors): List<SChapter> {
        val chapters = parseChapterElements(document, selectors)
        return if (config.reverseChapters) chapters.reversed() else chapters
    }

    // Picks the real "next page" link when [selector] is ambiguous (shared prev/next class).
    // Skips prev links and visited pages; prefers explicit next markers (rel=next, next/»/›/→).
    private fun resolveNextPageUrl(
        document: Document,
        selector: String,
        currentUrl: String?,
        visited: Set<String>,
    ): String? {
        var fallback: String? = null
        for (el in document.select(selector)) {
            val href = el.absUrl("href").ifBlank { null } ?: continue
            if (href == currentUrl || href in visited) continue
            val text = el.text().trim().lowercase()
            val rel = el.attr("rel").lowercase()
            val cls = el.className().lowercase()
            val isPrev = rel == "prev" || rel == "previous" || "prev" in text || "previous" in text ||
                "prev" in cls || "«" in text || "‹" in text
            val isNext = rel == "next" || "next" in text || "next" in cls ||
                "»" in text || "›" in text || "→" in text
            if (isNext && !isPrev) return href
            if (!isPrev && fallback == null) fallback = href
        }
        return fallback
    }

    // Parses a single chapter-list page without applying reversal (callers reverse once).
    private fun parseChapterElements(document: Document, selectors: ChapterSelectors): List<SChapter> {
        if (selectors.list.isBlank()) return emptyList()
        return document.select(selectors.list).mapNotNull { element ->
            try {
                val link = resolveLink(element, selectors.link)
                val url = toRelativeStoredUrl(link?.attr("abs:href")).ifBlank { return@mapNotNull null }

                SChapter.create().apply {
                    this.url = url
                    name = element.selectText(selectors.name)
                        ?: link?.text()?.trim()
                        ?: return@mapNotNull null
                    date_upload = parseDate(element.selectText(selectors.date))
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ======================== Pages (Novel Content) ========================

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url
        val path = buildString {
            append(url.encodedPath)
            if (url.encodedQuery != null) {
                append('?')
                append(url.encodedQuery)
            }
            if (url.encodedFragment != null) {
                append('#')
                append(url.encodedFragment)
            }
        }
        return listOf(Page(0, path))
    }

    override suspend fun fetchPageText(page: Page): String {
        val bs = baseSource
        // A custom content selector overrides delegation.
        if (bs != null && bs.isNovelSource() && config.selectors.content.primary.isBlank()) {
            // For content fetching, we need absolute URLs (not relative paths)
            // buildAbsoluteUrl() converts relative to absolute on custom base
            val absoluteUrl = buildAbsoluteUrl(page.url)
            // mapCustomUrlToSourceUrl() converts custom base to source base if mirror
            val sourceUrl = mapCustomUrlToSourceUrl(absoluteUrl, baseUrl, effectiveRebaseUrl) ?: absoluteUrl

            // Sanity check: ensure URL is absolute before passing to delegate
            if (!sourceUrl.startsWith("http://") && !sourceUrl.startsWith("https://")) {
                throw IllegalArgumentException(
                    "Invalid URL format for content fetching: $sourceUrl (original: ${page.url})",
                )
            }

            val pageToFetch = Page(page.index, sourceUrl, page.imageUrl, page.uri).also { it.text = page.text }
            return bs.fetchPageText(pageToFetch)
        }

        // If based on extension but source is not a novel source, fall through to CSS selectors
        // This allows hybrid setups where browsing is delegated but content uses custom selectors

        val pageUrl = buildAbsoluteUrl(page.url)
        val selectors = config.selectors.content

        // Guard against empty selector (Jsoup throws on empty string)
        if (selectors.primary.isBlank()) return ""

        return fetchContentHtml(pageUrl, selectors) ?: ""
    }

    private suspend fun fetchContentHtml(url: String, selectors: ContentSelectors): String? {
        val document = client.newCall(GET(url, headers)).awaitSuccess().asJsoup()

        var element = document.selectFirst(selectors.primary)
        if (element == null && selectors.fallbacks != null) {
            for (fallback in selectors.fallbacks!!) {
                if (fallback.isBlank()) continue
                element = document.selectFirst(fallback)
                if (element != null) break
            }
        }
        if (element == null) return null

        // User-defined removals first.
        selectors.removeSelectors?.forEach { selector ->
            if (selector.isNotBlank()) element!!.select(selector).remove()
        }

        if (selectors.removeBoilerplate) {
            cleanContentElement(element)
        }

        // Fix relative media URLs.
        element.select("img, video, audio, source").forEach { media ->
            listOf("src", "data-src", "data-lazy-src").forEach { attr ->
                if (media.hasAttr(attr)) {
                    media.attr(attr, media.absUrl(attr))
                }
            }
        }

        return element.html()
    }

    /** Strips boilerplate: scripts/styles, ad/share widgets, chapter-nav links, empty nodes. */
    private fun cleanContentElement(element: Element) {
        element.select(BOILERPLATE_SELECTOR).remove()

        // Remove next/prev/chapter-navigation hyperlinks that bleed into the text. Match on the
        // link text and on reliable nav signals (rel / class) rather than substring-matching the
        // href, which over-removed legitimate in-text links whose URL merely contained "next".
        element.select("a").forEach { a ->
            val text = a.text().trim().lowercase()
            val rel = a.attr("rel").lowercase()
            val cls = a.className().lowercase()
            val isNav = NAV_LINK_WORDS.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") } ||
                rel == "next" || rel == "prev" || rel == "previous" ||
                cls.contains("next") || cls.contains("prev")
            if (isNav && a.text().length < 40) a.remove()
        }

        // Drop empty wrappers left behind.
        element.select("p, div, span").forEach { el ->
            if (el.text().isBlank() && el.select("img, video, audio, br").isEmpty()) {
                el.remove()
            }
        }
    }

    override fun imageUrlParse(response: Response) = ""

    override fun getFilterList(): FilterList {
        baseSource?.let { return it.getFilterList() }
        return FilterList()
    }

    // ======================== Helper Functions ========================

    private fun parseMangaList(
        document: Document,
        selectors: MangaListSelectors,
        pageable: Boolean = true,
    ): MangasPage {
        // Jsoup throws "String must not be empty" on a blank selector; surface a clearer error.
        if (selectors.list.isBlank()) {
            throw IllegalStateException("List selector is empty. Capture the popular list item selector first.")
        }
        val mangas = document.select(selectors.list).mapNotNull { element ->
            try {
                SManga.create().apply {
                    val link = resolveLink(element, selectors.link)
                    url = toRelativeStoredUrl(link?.attr("abs:href")).ifBlank { return@mapNotNull null }
                    title = element.selectText(selectors.title)
                        ?: link?.attr("title")?.ifBlank { null }
                        ?: link?.text()?.trim()
                        ?: return@mapNotNull null
                    thumbnail_url = element.selectImageUrl(selectors.cover)
                }
            } catch (e: Exception) {
                null
            }
        }

        val hasNextPage = when {
            selectors.nextPage != null -> document.selectFirst(selectors.nextPage!!) != null
            // No {page} in the URL template = single page; avoid re-requesting the same page forever.
            !pageable -> false
            else -> mangas.isNotEmpty()
        }

        return MangasPage(mangas, hasNextPage)
    }

    // Resolves the link element for a list/chapter item. Handles the common case where the item
    // selector matches the <a> itself (e.g. a chapter list of bare links), which selectFirst("a")
    // would miss because it only searches descendants.
    private fun resolveLink(element: Element, linkSelector: String?): Element? {
        if (!linkSelector.isNullOrBlank()) {
            val bySelector = element.selectFirst(linkSelector)
                ?: if (runCatching { element.`is`(linkSelector) }.getOrDefault(false)) element else null
            // The picked element may not be the <a> itself (user tapped a <span> inside the link, or
            // an <li> wrapping it). Always resolve down to a real href.
            bySelector?.let { return it.ensureHref() }
        }
        return element.ensureHref()
    }

    // Returns the nearest element carrying an href: self, a descendant <a[href]>, or an ancestor
    // <a[href]> (chapter rows often wrap the link text in a <span> inside the <a>).
    private fun Element.ensureHref(): Element? {
        if (tagName() == "a" && hasAttr("href")) return this
        if (hasAttr("href")) return this
        selectFirst("a[href]")?.let { return it }
        var p = parent()
        var guard = 0
        while (p != null && guard++ < 6) {
            if (p.tagName() == "a" && p.hasAttr("href")) return p
            p = p.parent()
        }
        return null
    }

    private fun Document.selectText(selector: String?): String? {
        if (selector.isNullOrBlank()) return null
        return selectFirst(selector)?.text()?.trim()?.ifBlank { null }
    }

    // Joins the text of EVERY element matching [selector] (deduped, comma-separated). Used for genre
    // so a selector group like "a.tag1, a.tag2" (or a multi-match ".genre a") merges into one string.
    private fun Document.selectJoinedText(selector: String?): String? {
        if (selector.isNullOrBlank()) return null
        return select(selector)
            .mapNotNull { it.text().trim().ifBlank { null } }
            .distinct()
            .joinToString(", ")
            .ifBlank { null }
    }

    private fun Element.selectText(selector: String?): String? {
        if (selector.isNullOrBlank()) return null
        return selectFirst(selector)?.text()?.trim()?.ifBlank { null }
    }

    private fun Element.selectImageUrl(selector: String?): String? {
        if (selector.isNullOrBlank()) return null
        val element = selectFirst(selector) ?: return null
        return resolveImageUrl(element)
    }

    private fun Element.selectFormattedText(selector: String?): String? {
        if (selector.isNullOrBlank()) return null
        val element = selectFirst(selector) ?: return null
        return htmlToFormattedText(element.html()).ifBlank { null }
    }

    private fun parseStatus(status: String?): Int =
        parseCustomSourceStatus(status, config.statusMapping)

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val raw = dateStr.trim()

        // Config-specified format wins, then a list of language-neutral numeric formats. No relative
        // ("2 days ago") or month-name parsing: those are language-specific. Sites with worded dates
        // should set config.dateFormat with their own locale-appropriate pattern.
        val formats = buildList {
            config.dateFormat?.let { add(it) }
            addAll(DATE_FORMATS)
        }
        for (pattern in formats) {
            try {
                val parsed = java.text.SimpleDateFormat(pattern, java.util.Locale.ROOT)
                    .parse(raw)?.time
                if (parsed != null) return parsed
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    private fun String.buildUrl(baseUrl: String, page: Int): String =
        buildPagedUrlTemplate(this, baseUrl, page)

    private fun String.buildSearchUrl(baseUrl: String, query: String, page: Int): String =
        buildPagedSearchUrlTemplate(this, baseUrl, query, page)

    // These delegate to the top-level rebaseCustomSource* helpers (same logic, unit-tested there),
    // resolving customBase = this source's baseUrl and sourceBase = the delegated source URL.
    internal fun rebaseMangasPage(
        mangasPage: MangasPage,
        sourceBaseUrlOverride: String? = null,
    ): MangasPage =
        rebaseCustomSourceMangasPage(mangasPage, baseUrl, sourceBaseUrlOverride ?: effectiveRebaseUrl)

    internal fun rebaseManga(manga: SManga, sourceBaseUrlOverride: String? = null): SManga =
        rebaseCustomSourceManga(manga, baseUrl, sourceBaseUrlOverride ?: effectiveRebaseUrl)

    internal fun rebaseChapter(chapter: SChapter, sourceBaseUrlOverride: String? = null): SChapter =
        rebaseCustomSourceChapter(chapter, baseUrl, sourceBaseUrlOverride ?: effectiveRebaseUrl)

    internal fun rebasePage(page: Page, sourceBaseUrlOverride: String? = null): Page =
        rebaseCustomSourcePage(page, baseUrl, sourceBaseUrlOverride ?: effectiveRebaseUrl)

    internal fun rebaseUrl(url: String?, sourceBaseUrlOverride: String? = null): String? =
        rebaseCustomSourceUrl(url, baseUrl, sourceBaseUrlOverride ?: effectiveRebaseUrl)

    internal fun toBaseSourceUrl(url: String?, sourceBaseUrlOverride: String? = null): String? {
        val override = sourceBaseUrlOverride ?: effectiveRebaseUrl
        val repairedUrl = normalizeCustomUrl(url)
        val value = repairedUrl.orEmpty()
        if (value.isBlank()) return url

        if (baseSource is JsSource) {
            val customBase = baseUrl.trimEnd('/')
            val sourceBase = override?.trimEnd('/')
            val relativePath = when {
                value.startsWith(customBase) -> value.removePrefix(customBase)
                sourceBase != null && value.startsWith(sourceBase) -> value.removePrefix(sourceBase)
                else -> {
                    // If URL doesn't match either base, it might be absolute from a redirect
                    // Return it as-is so delegate can use it directly
                    value
                }
            }
            return if (relativePath.startsWith("/")) relativePath else "/${relativePath.removePrefix("/")}"
        }

        return toHttpSourceRequestPath(mapCustomUrlToSourceUrl(value, baseUrl, override), override)
    }

    private fun toBaseSourceManga(manga: SManga, sourceBaseUrlOverride: String? = null): SManga {
        val override = sourceBaseUrlOverride ?: effectiveRebaseUrl
        return safeCopyManga(manga).apply {
            val originalUrl = runCatching { manga.url }.getOrNull()
            url = toBaseSourceUrl(originalUrl, override) ?: (originalUrl ?: "")
            val originalThumb = runCatching { manga.thumbnail_url }.getOrNull()
            thumbnail_url = toBaseSourceUrl(originalThumb, override) ?: originalThumb
        }
    }

    private fun toBaseSourceChapter(chapter: SChapter, sourceBaseUrlOverride: String? = null): SChapter {
        val override = sourceBaseUrlOverride ?: effectiveRebaseUrl
        return SChapter.create().also { sourceChapter ->
            sourceChapter.copyFrom(chapter)
            sourceChapter.url = toBaseSourceUrl(chapter.url, override) ?: chapter.url
        }
    }

    private fun toBaseSourcePage(page: Page, sourceBaseUrlOverride: String? = null): Page {
        val override = sourceBaseUrlOverride ?: effectiveRebaseUrl
        return Page(
            page.index,
            toBaseSourceUrl(page.url, override).orEmpty(),
            page.imageUrl?.let { toBaseSourceUrl(it, override) },
            page.uri,
        ).also { it.text = page.text }
    }

    /**
     * Builds an absolute URL from a relative or absolute path.
     * Handles redirects where the URL might already be absolute after a 301/302 redirect.
     */
    private fun buildAbsoluteUrl(url: String?): String {
        val trimmedUrl = normalizeCustomUrl(url)?.trim().orEmpty()
        if (trimmedUrl.isBlank()) return baseUrl

        // If URL already has a scheme, it's absolute (e.g., after a redirect to a different domain)
        if (trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")) {
            return trimmedUrl
        }

        // If URL starts with /, it's root-relative
        if (trimmedUrl.startsWith("/")) {
            return baseUrl + trimmedUrl
        }

        // Otherwise, it's a relative path - add slash between baseUrl and path
        return "$baseUrl/$trimmedUrl"
    }

    // Strip scheme+host only when it's baseUrl itself (mod www.), so a same-site url stays
    // portable across baseUrl edits. A genuinely different host (real mirror/CDN) is kept
    // absolute -- buildAbsoluteUrl() passes absolute urls through untouched -- instead of being
    // silently glued onto baseUrl and pointed at the wrong site.
    private fun toRelativeStoredUrl(href: String?): String {
        val value = normalizeCustomUrl(href)?.trim().orEmpty()
        if (value.isBlank()) return ""
        value.toHttpUrlOrNull()?.let { http ->
            val baseHost = baseUrl.toHttpUrlOrNull()?.host?.removePrefix("www.")
            if (baseHost == null || !http.host.removePrefix("www.").equals(baseHost, ignoreCase = true)) {
                return value
            }
            return buildString {
                append(http.encodedPath)
                http.encodedQuery?.let { append('?').append(it) }
                http.encodedFragment?.let { append('#').append(it) }
            }
        }
        return if (value.startsWith("/")) value else "/$value"
    }

    companion object {
        private const val MAX_CHAPTER_PAGES = 30

        private val NAV_LINK_WORDS = listOf(
            "next", "previous", "prev", "next chapter", "previous chapter",
            "table of contents", "toc", "index", "back",
        )

        private const val BOILERPLATE_SELECTOR =
            "script, style, noscript, ins, iframe, " +
                ".adsbygoogle, ins.adsbygoogle, [class*=ads], [id*=ads], " +
                ".sharedaddy, .sharepost, .share, .social, .sociable, " +
                ".wpcnt, .wp-next-post-navi, .wp-block-buttons, " +
                ".ezoic-ad, .ezoic-adpicker-ad, .code-block, " +
                ".navigation, .nav-links, .chapter-nav, .pagination"

        // Language-neutral numeric formats only (no month names). Worded/relative dates need a
        // user-supplied config.dateFormat.
        private val DATE_FORMATS = listOf(
            "yyyy-MM-dd",
            "yyyy/MM/dd",
            "MM/dd/yyyy",
            "dd/MM/yyyy",
            "dd.MM.yyyy",
            "yyyy.MM.dd",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
        )

        private fun generateId(name: String, baseUrl: String): Long {
            return (name + baseUrl).hashCode().toLong() and 0x7FFFFFFF
        }

        /**
         * Create a CustomNovelSource from JSON configuration
         */
        fun fromJson(json: String): CustomNovelSource {
            val config = Json.decodeFromString<CustomSourceConfig>(json)
            return CustomNovelSource(config)
        }
    }
}

// ======================== Configuration Data Classes ========================

@Serializable
data class CustomSourceConfig(
    val name: String,
    val baseUrl: String,
    val language: String = "en",
    val id: Long? = null,
    val popularUrl: String,
    val latestUrl: String? = null,
    val searchUrl: String,
    // Page-2+ templates (with {page}). When set, page 1 uses the plain *Url above verbatim.
    val popularPagedUrl: String? = null,
    val latestPagedUrl: String? = null,
    val searchPagedUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val selectors: SourceSelectors,
    val reverseChapters: Boolean = false,
    val postSearch: Boolean = false,
    val basedOnSourceId: Long? = null,
    val isNovel: Boolean = true,
    // Test query; set from the wizard's search probe word.
    val testSearchQuery: String? = null,
    // SimpleDateFormat pattern for chapter dates, e.g. "yyyy-MM-dd". Required for worded dates.
    val dateFormat: String? = null,
    // Status-word mapping (substring, case-insensitive) -> ongoing/completed/hiatus/cancelled.
    val statusMapping: Map<String, String>? = null,
    // Novel URL the wizard's reading test opens directly, so it works without a working listing.
    val sampleNovelUrl: String? = null,
)

@Serializable
data class SourceSelectors(
    val popular: MangaListSelectors,
    val latest: MangaListSelectors? = null,
    val search: MangaListSelectors? = null,
    val details: DetailSelectors,
    val chapters: ChapterSelectors,
    val content: ContentSelectors,
)

@Serializable
data class MangaListSelectors(
    val list: String,
    val link: String? = null,
    val title: String? = null,
    val cover: String? = null,
    val nextPage: String? = null,
)

@Serializable
data class DetailSelectors(
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: String? = null,
    val cover: String? = null,
)

@Serializable
data class ChapterSelectors(
    val list: String,
    val link: String? = null,
    val name: String? = null,
    val date: String? = null,
    // "Next page" link selector when the chapter list spans multiple pages.
    val nextPage: String? = null,
    // Details-page selector for a separate chapter-list/index page; chapters read from there.
    val indexLinkSelector: String? = null,
    // Numbered-pagination URL template, e.g. "{novelUrl}?page={page}". Preferred over nextPage.
    val pagedUrlPattern: String? = null,
    // ---- Mode B: generate chapter URLs from a numeric pattern (e.g. /novel/chapter-{n}). ----
    // Chapter URL template with {n}.
    val urlPattern: String? = null,
    // Details-page selector whose text holds the total chapter count.
    val countSelector: String? = null,
    // Explicit range; lastNumber falls back to the parsed countSelector value.
    val firstNumber: Int? = null,
    val lastNumber: Int? = null,
    // Name template with {n}. Defaults to "Chapter {n}".
    val nameTemplate: String? = null,
)

@Serializable
data class ContentSelectors(
    val primary: String,
    val fallbacks: List<String>? = null,
    val removeSelectors: List<String>? = null,
    // When true (default), strip common boilerplate (scripts, ads, share widgets,
    // next/prev chapter links, empty nodes) in addition to removeSelectors.
    val removeBoilerplate: Boolean = true,
)

// Templates removed; use extension repos for pre-built novel source themes
// (Madara, LightNovelWP, ReadNovelFull, ReadWN, WordPress Novel)
