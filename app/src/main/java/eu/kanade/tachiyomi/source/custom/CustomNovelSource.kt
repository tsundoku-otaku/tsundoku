package eu.kanade.tachiyomi.source.custom

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.fetchNovelPageText
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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Custom Novel Extension - User-defined novel source
 *
 * This class allows users to create custom novel sources by defining:
 * - CSS selectors for different page elements
 * - URL patterns for navigation
 * - Custom headers if needed
 *
 * The configuration is stored as JSON and can be edited through the app UI
 * or shared with other users.
 */
class CustomNovelSource(
    val config: CustomSourceConfig,
) : HttpSource(), NovelSource {

    // Mark this as a novel source for HttpPageLoader detection
    override val isNovelSource: Boolean = config.isNovel

    override val name: String = config.name
    override val baseUrl: String = config.baseUrl
    override val lang: String = config.language
    override val id: Long = config.id ?: generateId(config.name, config.baseUrl)
    override val supportsLatest: Boolean = config.latestUrl != null

    override val client = if (config.useCloudflare) network.cloudflareClient else network.client

    /**
     * When basedOnSourceId is set, we delegate all fetching/parsing to the base
     * extension source but substitute our own baseUrl in requests.
     * Supports HttpSource (APK extensions), JsSource (JS plugins), and any CatalogueSource.
     */
    private val baseSource: CatalogueSource? by lazy {
        config.basedOnSourceId?.let { sourceId ->
            try {
                Injekt.get<SourceManager>().get(sourceId) as? CatalogueSource
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().apply {
        config.headers.forEach { (key, value) ->
            add(key, value)
        }
    }

    // ======================== Extension Delegation ========================
    // When basedOnSourceId is set, delegate to the base source's public API
    // (protected request/parse methods aren't accessible on external instances)

    override suspend fun getPopularManga(page: Int): MangasPage {
        baseSource?.let { return it.getPopularManga(page) }
        return super.getPopularManga(page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        baseSource?.let { return it.getLatestUpdates(page) }
        return super.getLatestUpdates(page)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        baseSource?.let { return it.getSearchManga(page, query, filters) }
        return super.getSearchManga(page, query, filters)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        baseSource?.let { return it.getMangaDetails(manga) }
        return super.getMangaDetails(manga)
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        baseSource?.let { return it.getChapterList(manga) }
        return super.getChapterList(manga)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        baseSource?.let { return it.getPageList(chapter) }
        return super.getPageList(chapter)
    }

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        return GET(config.popularUrl.buildUrl(baseUrl, page), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaList(document, config.selectors.popular)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(
            (config.latestUrl ?: config.popularUrl).buildUrl(baseUrl, page),
            headers,
        )
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET(config.searchUrl.buildSearchUrl(baseUrl, query, page), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaList(document, config.selectors.search ?: config.selectors.popular)
    }

    // ======================== Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val selectors = config.selectors.details

        return SManga.create().apply {
            title = document.selectText(selectors.title) ?: ""
            author = document.selectText(selectors.author)
            artist = document.selectText(selectors.artist)
            description = document.selectText(selectors.description)
            genre = document.selectText(selectors.genre)
            thumbnail_url = document.selectAttr(selectors.cover, "src", "data-src", "data-lazy-src")
            status = parseStatus(document.selectText(selectors.status))
        }
    }

    // ======================== Chapters ========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val selectors = config.selectors.chapters

        // Check if we need to make an AJAX request for chapters
        if (config.chapterAjax != null) {
            val novelId = extractNovelId(document, response.request.url.toString())
            if (novelId != null) {
                val ajaxResponse = client.newCall(
                    GET(config.chapterAjax!!.buildAjaxUrl(baseUrl, novelId), headers),
                ).execute()
                return ajaxResponse.use { parseChapterList(it.asJsoup(), selectors) }
            }
        }

        return parseChapterList(document, selectors)
    }

    override fun chapterPageParse(response: Response): SChapter {
        val document = response.asJsoup()
        val selectors = config.selectors.chapters

        val link = document.selectFirst(selectors.link ?: "a")
            ?: document.selectFirst("a[href*=chapter]")

        return SChapter.create().apply {
            url = link?.attr("abs:href")?.removePrefix(baseUrl) ?: ""
            name = document.selectText(selectors.name)
                ?: link?.text()?.trim()
                ?: "Chapter"
            date_upload = parseDate(document.selectText(selectors.date))
        }
    }

    private fun parseChapterList(document: Document, selectors: ChapterSelectors): List<SChapter> {
        return document.select(selectors.list).mapNotNull { element ->
            try {
                val link = element.selectFirst(selectors.link ?: "a") ?: element.selectFirst("a")
                val url = link?.attr("abs:href")?.removePrefix(baseUrl) ?: return@mapNotNull null

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
        }.let { chapters ->
            if (config.reverseChapters) chapters.reversed() else chapters
        }
    }

    // ======================== Pages (Novel Content) ========================

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, url))
    }

    override suspend fun fetchPageText(page: Page): String {
        val bs = baseSource
        if (bs != null && bs.isNovelSource()) {
            return bs.fetchNovelPageText(page)
        }

        // If based on extension but source is not a novel source, fall through to CSS selectors
        // This allows hybrid setups where browsing is delegated but content uses custom selectors

        val response = client.newCall(GET(baseUrl + page.url, headers)).awaitSuccess()
        val document = response.asJsoup()

        val selectors = config.selectors.content

        // Guard against empty selector (Jsoup throws on empty string)
        if (selectors.primary.isBlank()) return ""

        // Try primary selector
        var element = document.selectFirst(selectors.primary)

        // Try fallback selectors
        if (element == null && selectors.fallbacks != null) {
            for (fallback in selectors.fallbacks!!) {
                element = document.selectFirst(fallback)
                if (element != null) break
            }
        }

        if (element == null) return ""

        // Remove unwanted elements
        selectors.removeSelectors?.forEach { selector ->
            element!!.select(selector).remove()
        }

        // Fix relative URLs
        element.select("img, video, audio, source").forEach { media ->
            listOf("src", "data-src", "data-lazy-src").forEach { attr ->
                if (media.hasAttr(attr)) {
                    media.attr(attr, media.absUrl(attr))
                }
            }
        }

        return element.html()
    }

    override fun imageUrlParse(response: Response) = ""

    override fun getFilterList(): FilterList {
        baseSource?.let { return it.getFilterList() }
        return FilterList()
    }

    // ======================== Helper Functions ========================

    private fun parseMangaList(document: Document, selectors: MangaListSelectors): MangasPage {
        val mangas = document.select(selectors.list).mapNotNull { element ->
            try {
                SManga.create().apply {
                    val link = element.selectFirst(selectors.link ?: "a[href]")
                        ?: element.selectFirst("a")
                    url = link?.attr("abs:href")?.removePrefix(baseUrl) ?: return@mapNotNull null
                    title = element.selectText(selectors.title)
                        ?: link?.attr("title")?.ifBlank { null }
                        ?: link?.text()?.trim()
                        ?: return@mapNotNull null
                    thumbnail_url = element.selectAttr(selectors.cover, "src", "data-src", "data-lazy-src")
                }
            } catch (e: Exception) {
                null
            }
        }

        val hasNextPage = if (selectors.nextPage != null) {
            document.selectFirst(selectors.nextPage!!) != null
        } else {
            mangas.isNotEmpty()
        }

        return MangasPage(mangas, hasNextPage)
    }

    private fun Document.selectText(selector: String?): String? {
        if (selector.isNullOrBlank()) return null
        return selectFirst(selector)?.text()?.trim()?.ifBlank { null }
    }

    private fun Element.selectText(selector: String?): String? {
        if (selector.isNullOrBlank()) return null
        return selectFirst(selector)?.text()?.trim()?.ifBlank { null }
    }

    private fun Element.selectAttr(selector: String?, vararg attrs: String): String? {
        if (selector.isNullOrBlank()) return null
        val element = selectFirst(selector) ?: return null
        for (attr in attrs) {
            val value = element.attr(attr).ifBlank { null } ?: element.attr("abs:$attr").ifBlank { null }
            if (value != null) return value
        }
        return null
    }

    private fun parseStatus(status: String?): Int {
        if (status == null) return SManga.UNKNOWN
        return when {
            status.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
            status.contains("completed", ignoreCase = true) -> SManga.COMPLETED
            status.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            status.contains("cancelled", ignoreCase = true) -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        // Basic date parsing - can be extended
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun extractNovelId(document: Document, url: String): String? {
        // Try config-defined ID extraction
        config.novelIdSelector?.let { selector ->
            document.selectFirst(selector)?.let { element ->
                config.novelIdAttr?.let { attr ->
                    return element.attr(attr).ifBlank { null }
                }
                return element.text().trim().ifBlank { null }
            }
        }

        // Fallback: extract from URL
        config.novelIdPattern?.let { pattern ->
            Regex(pattern).find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }

        return null
    }

    private fun String.buildUrl(baseUrl: String, page: Int): String {
        var url = this.replace("{baseUrl}", baseUrl)

        // Handle {page} - only include if page > 1 or URL requires it
        if (url.contains("{page}")) {
            if (page == 1 && !url.contains("page={page}") && !url.contains("pg={page}")) {
                // For URLs like "https://site.com/{page}" on page 1, remove the placeholder entirely
                url = url.replace("/{page}", "")
                    .replace("?page={page}", "")
                    .replace("&page={page}", "")
                    .replace("{page}", "")
            } else {
                url = url.replace("{page}", page.toString())
            }
        }

        return url.trimEnd('/', '?', '&')
    }

    private fun String.buildSearchUrl(baseUrl: String, query: String, page: Int): String {
        var url = this.replace("{baseUrl}", baseUrl)

        // Handle query parameter - detect if using s, q, query, keyword, or search
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        url = url.replace("{query}", encodedQuery)

        // Handle {page} same as buildUrl
        if (url.contains("{page}")) {
            if (page == 1 && !url.contains("page={page}") && !url.contains("pg={page}")) {
                url = url.replace("&page={page}", "")
                    .replace("?page={page}", "")
                    .replace("{page}", "")
            } else {
                url = url.replace("{page}", page.toString())
            }
        }

        return url.trimEnd('/', '?', '&')
    }

    private fun String.buildAjaxUrl(baseUrl: String, novelId: String): String {
        return this.replace("{baseUrl}", baseUrl)
            .replace("{novelId}", novelId)
    }

    companion object {
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

/**
 * Source type enum for different multisrc configurations
 */
@Serializable
enum class CustomSourceType {
    GENERIC, // Generic CSS selector based
    MADARA, // Madara WordPress theme (uses AJAX for chapters)
    READNOVELFULL, // ReadNovelFull style sites
    LIGHTNOVELWP, // LightNovelWP theme
    READWN, // ReadWN style sites
}

@Serializable
data class CustomSourceConfig(
    val name: String,
    val baseUrl: String,
    val language: String = "en",
    val id: Long? = null,
    val sourceType: CustomSourceType = CustomSourceType.GENERIC,
    val popularUrl: String,
    val latestUrl: String? = null,
    val searchUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val selectors: SourceSelectors,
    val chapterAjax: String? = null,
    val novelIdSelector: String? = null,
    val novelIdAttr: String? = null,
    val novelIdPattern: String? = null,
    val reverseChapters: Boolean = false,
    val useCloudflare: Boolean = true,
    val postSearch: Boolean = false,
    val basedOnSourceId: Long? = null,
    val isNovel: Boolean = true,
)

@Serializable
data class SourceSelectors(
    val popular: MangaListSelectors,
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
)

@Serializable
data class ContentSelectors(
    val primary: String,
    val fallbacks: List<String>? = null,
    val removeSelectors: List<String>? = null,
)

// Templates removed — use extension repos for pre-built novel source themes
// (Madara, LightNovelWP, ReadNovelFull, ReadWN, WordPress Novel)
