package mihon.core.archive

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.io.InputStream

/**
 * Wrapper over ArchiveReader to load files in epub format.
 */
class EpubReader(private val reader: ArchiveReader) : Closeable by reader {

    /**
     * Path separator used by this epub.
     */
    private val pathSeparator = getPathSeparator()

    /**
     * Returns the path of the cover image.
     */
    fun getCoverImage(): String? {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val basePath = getParentDirectory(ref)

        // EPUB 3
        val coverItem = doc.select("manifest > item[properties~=cover-image]").first()
        if (coverItem != null) {
            return resolveZipPath(basePath, coverItem.attr("href"))
        }

        // EPUB 2
        val coverMeta = doc.select("metadata > meta[name=cover]").first()
        if (coverMeta != null) {
            val coverId = coverMeta.attr("content")
            val item = doc.select("manifest > item[id=$coverId]").first()
            if (item != null) {
                return resolveZipPath(basePath, item.attr("href"))
            }
        }

        // Fallback: Check for cover.xhtml / titlepage.xhtml / cover.html in manifest
        // Some EPUBs wrap the cover image in an XHTML page
        val coverPageItem = doc.select("manifest > item[href~=(?i)cover\\.(x)html|(?i)titlepage\\.(x)html]").first()
        if (coverPageItem != null) {
            val pagePath = resolveZipPath(basePath, coverPageItem.attr("href"))
            try {
                getInputStream(pagePath)?.use { stream ->
                    val pageDoc = Jsoup.parse(stream, null, "")
                    // Find first image in the cover page
                    val img = pageDoc.select("img, image").first()
                    val src = img?.attr("src")?.takeIf { it.isNotEmpty() } ?: img?.attr("xlink:href")
                    if (!src.isNullOrEmpty()) {
                        val pageBasePath = getParentDirectory(pagePath)
                        return resolveZipPath(pageBasePath, src)
                    }
                }
            } catch (e: Exception) {
                // Ignore parsing errors
            }
        }

        // Fallback: common cover filenames inside archive
        val commonPattern = Regex("(?i)(^|.*?/)(cover)\\.(jpg|jpeg|png|webp)$")
        val commonCandidates = reader.useEntries { entries ->
            entries.mapNotNull { entry ->
                val name = entry.name
                if (commonPattern.matches(name)) name else null
            }.toList()
        }
        if (commonCandidates.isNotEmpty()) {
            return commonCandidates.sortedBy { it.length }.first()
        }

        return null
    }

    /**
     * Returns an input stream for reading the contents of the specified zip file entry.
     */
    fun getInputStream(entryName: String): InputStream? {
        return reader.getInputStream(entryName)
    }

    /**
     * Returns the path of all the images found in the epub file.
     */
    fun getImagesFromPages(): List<String> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val pages = getPagesFromDocument(doc)
        return getImagesFromPages(pages, ref)
    }

    /**
     * Returns the path to the package document.
     */
    fun getPackageHref(): String {
        val meta = getInputStream(resolveZipPath("META-INF", "container.xml"))
        if (meta != null) {
            val metaDoc = meta.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
            val path = metaDoc.getElementsByTag("rootfile").first()?.attr("full-path")
            if (path != null) {
                return path
            }
        }
        return resolveZipPath("OEBPS", "content.opf")
    }

    /**
     * Returns the package document where all the files are listed.
     */
    fun getPackageDocument(ref: String): Document {
        return getInputStream(ref)!!.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    }

    /**
     * Returns all the pages from the epub.
     */
    fun getPagesFromDocument(document: Document): List<String> {
        val pages = document.select("manifest > item")
            .filter { node -> "application/xhtml+xml" == node.attr("media-type") }
            .associateBy { it.attr("id") }

        val spine = document.select("spine > itemref").map { it.attr("idref") }
        return spine.mapNotNull { pages[it] }.map { it.attr("href") }
    }

    /**
     * Returns all the images contained in every page from the epub.
     */
    private fun getImagesFromPages(pages: List<String>, packageHref: String): List<String> {
        val result = mutableListOf<String>()
        val basePath = getParentDirectory(packageHref)
        pages.forEach { page ->
            val entryPath = resolveZipPath(basePath, page)
            val document = getInputStream(entryPath)!!.use { Jsoup.parse(it, null, "") }
            val imageBasePath = getParentDirectory(entryPath)

            document.allElements.forEach {
                when (it.tagName()) {
                    "img" -> result.add(resolveZipPath(imageBasePath, it.attr("src")))
                    "image" -> {
                        val href = it.attr("xlink:href").takeIf { it.isNotBlank() } ?: it.attr("href")
                        if (href.isNotBlank()) result.add(resolveZipPath(imageBasePath, href))
                    }
                }
            }
        }

        return result
    }

    /**
     * Returns the path separator used by the epub file.
     */
    private fun getPathSeparator(): String {
        val meta = getInputStream("META-INF\\container.xml")
        return if (meta != null) {
            meta.close()
            "\\"
        } else {
            "/"
        }
    }

    /**
     * Resolves a zip path from base and relative components and a path separator.
     */
    private fun resolveZipPath(basePath: String, relativePath: String): String {
        if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) {
            return relativePath
        }

        val separator = pathSeparator
        val fullPath = if (relativePath.startsWith(separator)) {
            relativePath
        } else if (basePath.isEmpty()) {
            relativePath
        } else {
            "$basePath$separator$relativePath"
        }

        val segments = fullPath.split(separator)
        val resolved = mutableListOf<String>()

        for (segment in segments) {
            if (segment == "." || segment.isEmpty()) continue
            if (segment == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
            } else {
                resolved.add(segment)
            }
        }

        return resolved.joinToString(separator)
    }

    /**
     * Gets the parent directory of a path.
     */
    private fun getParentDirectory(path: String): String {
        val separatorIndex = path.lastIndexOf(pathSeparator)
        return if (separatorIndex >= 0) {
            path.substring(0, separatorIndex)
        } else {
            ""
        }
    }

    /**
     * Returns the text content of all pages in the epub as HTML.
     */
    fun getTextContent(): String {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val pages = getPagesFromDocument(doc)
        val basePath = getParentDirectory(ref)

        val content = StringBuilder()
        pages.forEach { page ->
            val entryPath = resolveZipPath(basePath, page)
            getInputStream(entryPath)?.use { inputStream ->
                val document = Jsoup.parse(inputStream, null, "")

                // Link images to standard schema to support NovelWebViewViewer
                val imageBasePath = getParentDirectory(entryPath)
                document.select("img[src], image[xlink:href]").forEach { img ->
                    val src = if (img.hasAttr("src")) img.attr("src") else img.attr("xlink:href")
                    if (!src.startsWith("http") && !src.startsWith("data:") &&
                        !src.startsWith("tsundoku-novel-image://")
                    ) {
                        val imagePath = resolveZipPath(imageBasePath, src)
                        val novelUrl = "tsundoku-novel-image://${java.net.URLEncoder.encode(imagePath, "UTF-8")}"
                        if (img.hasAttr("src")) {
                            img.attr("src", novelUrl)
                        } else {
                            img.attr("xlink:href", novelUrl)
                        }
                    }
                }

                // Get body content, preserving HTML structure for proper rendering
                document.body().let { body ->
                    content.append(body.html())
                    content.append("\n\n")
                }
            }
        }

        return content.toString()
    }

    /**
     * Data class representing an EPUB chapter/section from TOC.
     */
    data class EpubChapter(
        val title: String,
        val href: String,
        val order: Int,
    )

    /**
     * Returns the table of contents (chapters) from the EPUB.
     * Tries EPUB 3 NAV first, then falls back to EPUB 2 NCX.
     */
    fun getTableOfContents(): List<EpubChapter> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val opfBasePath = getParentDirectory(ref)

        // Try EPUB 3 NAV first
        val navHref = doc.select("manifest > item[properties*=nav]").attr("href")
        if (navHref.isNotEmpty()) {
            val navChapters = parseEpub3Nav(resolveZipPath(opfBasePath, navHref))
            if (navChapters.isNotEmpty()) return navChapters
        }

        // Fall back to EPUB 2 NCX
        val ncxHref = doc.select("manifest > item[media-type='application/x-dtbncx+xml']").attr("href")
        if (ncxHref.isNotEmpty()) {
            val ncxChapters = parseEpub2Ncx(resolveZipPath(opfBasePath, ncxHref))
            if (ncxChapters.isNotEmpty()) return ncxChapters
        }

        // If no TOC found, create chapters from spine pages
        val pages = getPagesFromDocument(doc)
        return pages.mapIndexed { index, page ->
            EpubChapter(
                title = "Chapter ${index + 1}",
                href = resolveZipPath(opfBasePath, page),
                order = index,
            )
        }
    }

    /**
     * Returns XHTML resources listed in OPF spine, resolved to archive paths.
     */
    fun getSpinePageHrefs(): List<String> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val opfBasePath = getParentDirectory(ref)
        return getPagesFromDocument(doc).map { resolveZipPath(opfBasePath, it) }
    }

    /**
     * Returns the TOC with subsection labels normalized.
     *
     * Example: if the TOC has "Chapter 1" followed by "Part 2", it becomes
     * "Chapter 1 - Part 2". This keeps preview labels and chapter list labels consistent.
     */
    fun getNormalizedTableOfContents(): List<EpubChapter> {
        val toc = getTableOfContents()
        if (toc.isEmpty()) return emptyList()

        var latestPrimaryTitle: String? = null

        return toc.mapIndexed { index, chapter ->
            val rawTitle = chapter.title.trim()
            val isSubsection = SUBSECTION_TITLE_REGEX.matches(rawTitle)

            val normalizedTitle = when {
                rawTitle.isBlank() -> "Chapter ${index + 1}"
                isSubsection && !latestPrimaryTitle.isNullOrBlank() -> "$latestPrimaryTitle - $rawTitle"
                else -> rawTitle
            }

            if (!isSubsection && rawTitle.isNotBlank()) {
                latestPrimaryTitle = rawTitle
            }

            chapter.copy(title = normalizedTitle)
        }
    }

    private val SUBSECTION_TITLE_REGEX =
        Regex("(?i)^(part|section|episode|ep\\.?|act|book|volume|vol\\.?|chapter|ch\\.?)\\s*[0-9ivxlcdm]+\\b")

    /**
     * Parse EPUB 3 NAV document for TOC.
     * NAV hrefs are relative to the NAV file location.
     * Keep hash fragments because many EPUBs map sub-sections using anchors in the same XHTML file.
     */
    private fun parseEpub3Nav(navPath: String): List<EpubChapter> {
        val chapters = mutableListOf<EpubChapter>()
        val navBasePath = getParentDirectory(navPath)
        var previousResolvedPath: String? = null
        getInputStream(navPath)?.use { inputStream ->
            val doc = Jsoup.parse(inputStream, null, "", Parser.xmlParser())

            // EPUB 3 NAV uses <nav epub:type="toc"> or <nav id="toc">
            val navElement = doc.selectFirst("nav[*|type=toc], nav#toc, nav[epub\\:type=toc]")
            navElement?.select("li a")?.forEachIndexed { index, element ->
                val title = element.text().trim()
                val href = element.attr("href").trim()
                if (title.isNotEmpty() && href.isNotEmpty()) {
                    // Resolve path and then restore fragment (if present).
                    val pathPart = href.substringBefore("#")
                    val fragment = href.substringAfter("#", "")
                    val resolvedPath = if (pathPart.isNotBlank()) {
                        resolveZipPath(navBasePath, pathPart).also { previousResolvedPath = it }
                    } else {
                        // Some EPUBs use href="#fragment" to continue pointing into the prior chapter file.
                        previousResolvedPath ?: navPath
                    }
                    val resolvedHref = if (fragment.isNotEmpty()) {
                        "$resolvedPath#$fragment"
                    } else {
                        resolvedPath
                    }
                    chapters.add(EpubChapter(title, resolvedHref, index))
                }
            }
        }
        return chapters
    }

    /**
     * Parse EPUB 2 NCX document for TOC.
     * NCX src paths are resolved relative to the NCX file location.
     * Keep hash fragments because many EPUBs map sub-sections using anchors in the same XHTML file.
     */
    private fun parseEpub2Ncx(ncxPath: String): List<EpubChapter> {
        val chapters = mutableListOf<EpubChapter>()
        val ncxBasePath = getParentDirectory(ncxPath)
        var previousResolvedPath: String? = null
        getInputStream(ncxPath)?.use { inputStream ->
            val doc = Jsoup.parse(inputStream, null, "", Parser.xmlParser())
            doc.select("navPoint").forEachIndexed { index, navPoint ->
                val title = navPoint.selectFirst("navLabel > text")?.text()?.trim() ?: ""
                val href = navPoint.selectFirst("content")?.attr("src")?.trim() ?: ""
                if (title.isNotEmpty() && href.isNotEmpty()) {
                    // Resolve path and then restore fragment (if present).
                    val pathPart = href.substringBefore("#")
                    val fragment = href.substringAfter("#", "")
                    val resolvedPath = if (pathPart.isNotBlank()) {
                        resolveZipPath(ncxBasePath, pathPart).also { previousResolvedPath = it }
                    } else {
                        previousResolvedPath ?: ncxPath
                    }
                    val resolvedHref = if (fragment.isNotEmpty()) {
                        "$resolvedPath#$fragment"
                    } else {
                        resolvedPath
                    }
                    chapters.add(EpubChapter(title, resolvedHref, index))
                }
            }
        }
        return chapters
    }

    /**
     * Returns the text content of a specific chapter/page from the EPUB.
     * @param chapterHref The relative path to the chapter within the EPUB
     */
    fun getChapterContent(chapterHref: String): String {
        return getChapterContentInternal(
            chapterHref = chapterHref,
            useReaderImageScheme = true,
            bodyOnly = false,
        )
    }

    /**
     * Returns chapter content for export.
     * Internal image assets are converted to data URIs, so written EPUB chapters stay self-contained.
     */
    fun getChapterContentForExport(chapterHref: String): String {
        return getChapterContentInternal(
            chapterHref = chapterHref,
            useReaderImageScheme = false,
            bodyOnly = true,
        )
    }

    private fun getChapterContentInternal(
        chapterHref: String,
        useReaderImageScheme: Boolean,
        bodyOnly: Boolean,
    ): String {
        val pathPart = chapterHref.substringBefore("#").trim()
        val fragment = chapterHref.substringAfter("#", "").takeIf { it.isNotBlank() }

        val packagePath = getPackageHref()
        val packageBasePath = getParentDirectory(packagePath)

        val entryPath = when {
            pathPart.isBlank() -> packagePath
            getInputStream(pathPart) != null -> pathPart
            else -> resolveZipPath(packageBasePath, pathPart)
        }

        return getInputStream(entryPath)?.use { inputStream ->
            val document = Jsoup.parse(inputStream, null, "", Parser.xmlParser())

            // Inline EPUB-internal media.
            val imageBasePath = getParentDirectory(entryPath)
            document.select("img, image").forEach { img ->
                val rawSrc = when {
                    img.hasAttr("src") -> img.attr("src")
                    img.hasAttr("xlink:href") -> img.attr("xlink:href")
                    img.hasAttr("href") -> img.attr("href")
                    else -> return@forEach
                }

                val src = rawSrc.substringBefore("#").trim()
                if (src.isBlank() || src.startsWith("http") || src.startsWith("//") || src.startsWith("data:")) {
                    return@forEach
                }

                val imagePath = resolveZipPath(imageBasePath, src)
                val replacement = if (useReaderImageScheme) {
                    "tsundoku-novel-image://${java.net.URLEncoder.encode(imagePath, "UTF-8")}"
                } else {
                    inlineAssetAsDataUri(imagePath) ?: return@forEach
                }

                when {
                    img.hasAttr("src") -> img.attr("src", replacement)
                    img.hasAttr("xlink:href") -> img.attr("xlink:href", replacement)
                    else -> img.attr("href", replacement)
                }
            }

            // TextView-based rendering doesn't reliably support SVG nodes.
            // Convert simple SVG image containers to regular <img> tags.
            document.select("svg").forEach { svg ->
                val svgImage =
                    svg.select("image").firstOrNull { it.hasAttr("xlink:href") || it.hasAttr("href") } ?: return@forEach
                val imageSrc = when {
                    svgImage.hasAttr("xlink:href") -> svgImage.attr("xlink:href")
                    svgImage.hasAttr("href") -> svgImage.attr("href")
                    else -> ""
                }
                if (imageSrc.isBlank()) return@forEach

                val imgElement = Element("img")
                imgElement.attr("src", imageSrc)
                imgElement.attr("style", "max-width:100%;height:auto;")
                svg.replaceWith(imgElement)
            }

            // Inline EPUB CSS
            document.select("link[rel=stylesheet]").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank()) {
                    val cssPath = resolveZipPath(imageBasePath, href)
                    try {
                        getInputStream(cssPath)?.use { stream ->
                            var cssText = stream.reader().readText()

                            // Resolve uris in url(...) inside CSS too.
                            val urlRegex = Regex("""url\(['"]?(.*?)['"]?\)""")
                            cssText = urlRegex.replace(cssText) { match ->
                                val assetUrl = match.groupValues[1]
                                if (assetUrl.startsWith("data:") || assetUrl.startsWith("http")) {
                                    return@replace match.value
                                }

                                val cssDir = getParentDirectory(cssPath)
                                val assetPath =
                                    resolveZipPath(cssDir, assetUrl.substringBefore("?").substringBefore("#"))
                                inlineAssetAsDataUri(assetPath)?.let { "url('$it')" } ?: match.value
                            }

                            val style = org.jsoup.nodes.Element("style")
                            style.attr("data-epub-css", "true")
                            style.attr("data-file", href.substringAfterLast('/'))
                            style.text(cssText)
                            link.replaceWith(style)
                        }
                    } catch (_: Exception) { }
                }
            }

            // Inline EPUB JS
            document.select("script[src]").forEach { script ->
                val src = script.attr("src")
                if (src.isNotBlank() && !src.startsWith("http") && !src.startsWith("//")) {
                    val jsPath = resolveZipPath(imageBasePath, src)
                    try {
                        getInputStream(jsPath)?.use { stream ->
                            val jsText = stream.reader().readText()
                            val inlineScript = org.jsoup.nodes.Element("script")
                            inlineScript.attr("data-epub-js", "true")
                            inlineScript.attr("data-file", src.substringAfterLast('/'))
                            // Using dataNode for plain text in script to avoid weird HTML escaping.
                            inlineScript.appendChild(org.jsoup.nodes.DataNode(jsText))
                            script.replaceWith(inlineScript)
                        }
                    } catch (_: Exception) { }
                }
            }

            // Remove title to prevent bleeding into text viewers.
            document.getElementsByTag("title").remove()

            val fragmentHtml = fragment?.let { extractFragmentHtml(document, it) }
            when {
                !fragmentHtml.isNullOrBlank() -> fragmentHtml
                bodyOnly -> document.body().html().ifBlank { document.outerHtml() }
                else -> document.outerHtml()
            }
        } ?: ""
    }

    private fun inlineAssetAsDataUri(assetPath: String): String? {
        return try {
            getInputStream(assetPath)?.use { assetStream ->
                val bytes = assetStream.readBytes()
                val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
                val mimeType = when (assetPath.substringAfterLast('.', "").lowercase()) {
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "gif" -> "image/gif"
                    "svg" -> "image/svg+xml"
                    "ttf" -> "font/ttf"
                    "otf" -> "font/otf"
                    "woff" -> "font/woff"
                    "woff2" -> "font/woff2"
                    "js" -> "application/javascript"
                    "css" -> "text/css"
                    else -> "application/octet-stream"
                }
                "data:$mimeType;base64,$base64"
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFragmentHtml(document: Document, fragment: String): String? {
        val candidates = buildList {
            val primary = fragment.substringAfterLast("#").trim().removePrefix("#")
            if (primary.isNotBlank()) {
                add(primary)
                val decoded = runCatching { java.net.URLDecoder.decode(primary, "UTF-8") }.getOrNull()
                if (!decoded.isNullOrBlank() && decoded != primary) {
                    add(decoded)
                }
            }
        }.distinct()

        for (candidate in candidates) {
            document.getElementById(candidate)?.outerHtml()?.let { return it }

            val escaped = candidate
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            document
                .selectFirst("*[id=\"$escaped\"], *[name=\"$escaped\"]")
                ?.outerHtml()
                ?.let { return it }
        }

        return null
    }
}
