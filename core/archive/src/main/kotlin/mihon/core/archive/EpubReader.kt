package mihon.core.archive

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.InputStream
import java.net.URLDecoder

/**
 * Wrapper over ArchiveReader to load files in epub format.
 */
class EpubReader(private val reader: ArchiveReader) : Closeable by reader {

    private fun String.urlDecoded(): String = urlDecode(this)

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
            } catch (_: Exception) {
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
        val doc = getInputStream(ref)!!.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
        return stripNamespacePrefixes(doc)
    }

    /**
     * Returns all the pages from the epub.
     */
    fun getPagesFromDocument(document: Document): List<String> = extractSpineHrefs(document)

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
        val depth: Int = 0,
    )

    /**
     * Returns the table of contents (chapters) from the EPUB.
     * Tries EPUB 3 NAV first, then falls back to EPUB 2 NCX.
     */
    private val cachedTableOfContents: List<EpubChapter> by lazy { computeTableOfContents() }

    fun getTableOfContents(): List<EpubChapter> = cachedTableOfContents

    private fun computeTableOfContents(): List<EpubChapter> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val opfBasePath = getParentDirectory(ref)

        // Try EPUB 3 NAV first
        val navHref = findNavHref(doc)
        if (navHref.isNotEmpty()) {
            val navChapters = parseEpub3Nav(resolveZipPath(opfBasePath, navHref))
            if (navChapters.isNotEmpty()) return navChapters
        }

        // Fall back to EPUB 2 NCX
        val ncxHref = findNcxHref(doc)
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
        return normalizeTableOfContents(getTableOfContents())
    }

    /**
     * Parse EPUB 3 NAV document for TOC, preserving the nested <ol>/<li> hierarchy as [EpubChapter.depth].
     * NAV hrefs are relative to the NAV file location.
     * Keep hash fragments because many EPUBs map sub-sections using anchors in the same XHTML file.
     */
    private fun parseEpub3Nav(navPath: String): List<EpubChapter> {
        val navBasePath = getParentDirectory(navPath)
        return getInputStream(navPath)?.use { inputStream ->
            val doc = Jsoup.parse(inputStream, null, "", Parser.xmlParser())
            // EPUB 3 NAV uses <nav epub:type="toc"> or <nav id="toc">
            val navElement = doc.selectFirst("nav[*|type=toc], nav#toc, nav[epub\\:type=toc]")
            val rootList: Element? = navElement?.selectFirst("> ol") ?: navElement?.selectFirst("ol")
            if (rootList == null) {
                emptyList()
            } else {
                buildTocFromNavList(rootList, navPath) { path -> resolveZipPath(navBasePath, path) }
            }
        }.orEmpty()
    }

    /**
     * Parse EPUB 2 NCX document for TOC, preserving nested <navPoint> hierarchy as [EpubChapter.depth].
     * NCX src paths are resolved relative to the NCX file location.
     * Keep hash fragments because many EPUBs map sub-sections using anchors in the same XHTML file.
     */
    private fun parseEpub2Ncx(ncxPath: String): List<EpubChapter> {
        val ncxBasePath = getParentDirectory(ncxPath)
        return getInputStream(ncxPath)?.use { inputStream ->
            val doc = Jsoup.parse(inputStream, null, "", Parser.xmlParser())
            val navMap = doc.selectFirst("navMap") ?: doc
            buildTocFromNcxNavMap(navMap, ncxPath) { path -> resolveZipPath(ncxBasePath, path) }
        }.orEmpty()
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

    data class ChapterExportData(
        val html: String,
        val images: Map<String, ByteArray>,
    )

    fun extractChapterForExport(chapterHref: String): ChapterExportData {
        val collected = linkedMapOf<String, ByteArray>()
        val html = getChapterContentInternal(
            chapterHref = chapterHref,
            useReaderImageScheme = false,
            bodyOnly = true,
            imageCollector = collected,
        )
        return ChapterExportData(html, collected)
    }

    private fun getChapterContentInternal(
        chapterHref: String,
        useReaderImageScheme: Boolean,
        bodyOnly: Boolean,
        imageCollector: MutableMap<String, ByteArray>? = null,
    ): String {
        val pathPart = chapterHref.substringBefore("#").trim().urlDecoded()
        val fragment = chapterHref.substringAfter("#", "").takeIf { it.isNotBlank() }

        val packagePath = getPackageHref()
        val packageBasePath = getParentDirectory(packagePath)

        val entryPath = when {
            pathPart.isBlank() -> packagePath
            getInputStream(pathPart) != null -> pathPart
            else -> resolveZipPath(packageBasePath, pathPart)
        }

        // Slice by fragment only when several TOC entries share this file; a lone entry's anchor is a
        // chapter-top marker and must yield the whole file.
        val effectiveFragment = fragment?.takeIf { tocEntriesForPath(entryPath) > 1 }

        return getInputStream(entryPath)?.use { inputStream ->
            val document = Jsoup.parse(inputStream, null, "", Parser.xmlParser())

            val imageBasePath = getParentDirectory(entryPath)
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

            document.select("img, image").forEach { img ->
                val rawSrc = when {
                    img.hasAttr("src") -> img.attr("src")
                    img.hasAttr("xlink:href") -> img.attr("xlink:href")
                    img.hasAttr("href") -> img.attr("href")
                    else -> return@forEach
                }

                val src = rawSrc.substringBefore("#").trim().urlDecoded()
                if (src.isBlank() || src.startsWith("http") || src.startsWith("//") || src.startsWith("data:")) {
                    return@forEach
                }

                val imagePath = resolveZipPath(imageBasePath, src)
                val replacement = when {
                    useReaderImageScheme -> {
                        "tsundoku-novel-image://${java.net.URLEncoder.encode(imagePath, "UTF-8")}"
                    }
                    imageCollector != null -> {
                        val bytes = runCatching {
                            getInputStream(imagePath)?.use { it.readBytes() }
                        }.getOrNull()
                        if (bytes == null || bytes.isEmpty()) return@forEach
                        val id = buildExportImageId(imagePath)
                        imageCollector.putIfAbsent(id, bytes)
                        "tsundoku-novel-image://$id"
                    }
                    else -> inlineAssetAsDataUri(imagePath) ?: return@forEach
                }

                when {
                    img.hasAttr("src") -> img.attr("src", replacement)
                    img.hasAttr("xlink:href") -> img.attr("xlink:href", replacement)
                    else -> img.attr("href", replacement)
                }
            }

            // Inline EPUB CSS
            document.select("link[rel=stylesheet]").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank()) {
                    val cssPath = resolveZipPath(imageBasePath, href.urlDecoded())
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
                                    resolveZipPath(
                                        cssDir,
                                        assetUrl.substringBefore("?").substringBefore("#").urlDecoded(),
                                    )
                                inlineAssetAsDataUri(assetPath)?.let { "url('$it')" } ?: match.value
                            }

                            val style = Element("style")
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
                    val jsPath = resolveZipPath(imageBasePath, src.urlDecoded())
                    try {
                        getInputStream(jsPath)?.use { stream ->
                            val jsText = stream.reader().readText()
                            val inlineScript = Element("script")
                            inlineScript.attr("data-epub-js", "true")
                            inlineScript.attr("data-file", src.substringAfterLast('/'))
                            // Using dataNode for plain text in script to avoid weird HTML escaping.
                            inlineScript.appendChild(DataNode(jsText))
                            script.replaceWith(inlineScript)
                        }
                    } catch (_: Exception) { }
                }
            }

            // Remove title to prevent bleeding into text viewers.
            document.getElementsByTag("title").remove()

            val fragmentHtml = effectiveFragment?.let { extractFragmentHtml(document, it) }.orEmpty()
            when {
                fragmentHtml.isNotBlank() -> fragmentHtml
                bodyOnly -> document.body().html().ifBlank { document.outerHtml() }
                else -> document.outerHtml()
            }
        } ?: ""
    }

    private fun buildExportImageId(imagePath: String): String = computeExportImageId(imagePath)

    companion object {
        private fun urlDecode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

        /**
         * Resolves a raw TOC href to an archive path while keeping any fragment. Fragment-only hrefs
         * (href="#anchor") reuse [previousPath] so they continue pointing into the prior chapter file.
         * Returns the resolved href and the path to remember for the next fragment-only entry.
         */
        private fun resolveTocHref(
            rawHref: String,
            defaultPath: String,
            previousPath: String?,
            resolvePath: (String) -> String,
        ): Pair<String, String> {
            val href = urlDecode(rawHref.trim())
            val pathPart = href.substringBefore("#")
            val fragment = href.substringAfter("#", "")
            val resolvedPath = if (pathPart.isNotBlank()) resolvePath(pathPart) else previousPath ?: defaultPath
            val resolvedHref = if (fragment.isNotEmpty()) "$resolvedPath#$fragment" else resolvedPath
            return resolvedHref to resolvedPath
        }

        /**
         * Walks an EPUB 2 NCX <navMap> in document order, emitting one [EpubChapter] per <navPoint> and
         * recording nesting depth from the <navPoint> tree.
         */
        fun buildTocFromNcxNavMap(
            navMap: Element,
            defaultPath: String,
            resolvePath: (String) -> String,
        ): List<EpubChapter> {
            val chapters = mutableListOf<EpubChapter>()
            var order = 0
            var previousPath: String? = null

            fun walk(navPoint: Element, depth: Int) {
                val title = navPoint.selectFirst("> navLabel > text")?.text()?.trim().orEmpty()
                val src = navPoint.selectFirst("> content")?.attr("src")?.trim().orEmpty()
                if (title.isNotEmpty() && src.isNotEmpty()) {
                    val (resolvedHref, resolvedPath) = resolveTocHref(src, defaultPath, previousPath, resolvePath)
                    if (src.substringBefore("#").isNotBlank()) previousPath = resolvedPath
                    chapters.add(EpubChapter(title, resolvedHref, order++, depth))
                }
                navPoint.children()
                    .filter { it.normalName() == "navpoint" }
                    .forEach { walk(it, depth + 1) }
            }

            navMap.children()
                .filter { it.normalName() == "navpoint" }
                .forEach { walk(it, 0) }
            return chapters
        }

        /**
         * Walks an EPUB 3 nav <ol> in document order, emitting one [EpubChapter] per <li> and recording
         * nesting depth from the nested <ol>/<li> tree. Unlinked heading <li>s reuse their first
         * descendant link so they still carry a title into the depth hierarchy.
         */
        fun buildTocFromNavList(
            rootList: Element,
            defaultPath: String,
            resolvePath: (String) -> String,
        ): List<EpubChapter> {
            val chapters = mutableListOf<EpubChapter>()
            var order = 0
            var previousPath: String? = null

            fun walk(list: Element, depth: Int) {
                list.children()
                    .filter { it.normalName() == "li" }
                    .forEach { li ->
                        val childList: Element? = li.selectFirst("> ol")
                        val anchor = li.selectFirst("> a") ?: li.selectFirst("> span > a")
                        val title = (anchor?.text() ?: li.selectFirst("> span")?.text() ?: li.ownText()).trim()
                        // Unlinked heading (<li><span>Title</span><ol>…</ol></li>): keep its text as an
                        // ancestor prefix by pointing at its first descendant link so it stays navigable.
                        val href = anchor?.attr("href")?.trim().orEmpty().ifEmpty {
                            if (childList != null) li.selectFirst("a[href]")?.attr("href")?.trim().orEmpty() else ""
                        }
                        if (title.isNotEmpty() && href.isNotEmpty()) {
                            val (resolvedHref, resolvedPath) =
                                resolveTocHref(href, defaultPath, previousPath, resolvePath)
                            if (href.substringBefore("#").isNotBlank()) previousPath = resolvedPath
                            chapters.add(EpubChapter(title, resolvedHref, order++, depth))
                        }
                        if (childList != null) walk(childList, depth + 1)
                    }
            }

            walk(rootList, 0)
            return chapters
        }

        // Fallback for flat TOCs only: matches bare subsection labels like "Part 2" or "Chapter 3." A
        // descriptive title such as "Chapter 1: Operating Behind the Scenes" must NOT match, otherwise real
        // chapters get prefixed with the previous entry's title. Structural nesting is preferred over this.
        private val subsectionTitleRegex =
            Regex(
                "(?i)^(part|section|episode|ep\\.?|act|book|volume|vol\\.?|chapter|ch\\.?)" +
                    "\\s*[0-9ivxlcdm]+\\s*[.:)-]?\\s*$",
            )

        /**
         * Turns a TOC into display titles where subsections carry their parent's title
         * ("Parent - Child"). When the TOC carries real nesting ([EpubChapter.depth]) this is done
         * structurally and is locale-agnostic. Purely flat TOCs fall back to a keyword heuristic.
         */
        fun normalizeTableOfContents(toc: List<EpubChapter>): List<EpubChapter> {
            if (toc.isEmpty()) return emptyList()
            return if (toc.any { it.depth > 0 }) {
                normalizeByDepth(toc)
            } else {
                normalizeByHeuristic(toc)
            }
        }

        private fun normalizeByDepth(toc: List<EpubChapter>): List<EpubChapter> {
            val ancestors = mutableListOf<String>()

            return toc.mapIndexed { index, chapter ->
                val rawTitle = chapter.title.trim()
                val depth = chapter.depth.coerceAtLeast(0)

                // Drop any ancestor titles at or below the current depth, then pad gaps in the hierarchy.
                if (ancestors.size > depth) ancestors.subList(depth, ancestors.size).clear()
                while (ancestors.size < depth) ancestors.add("")

                val prefix = ancestors.filter { it.isNotBlank() }.joinToString(" - ")
                val normalizedTitle = when {
                    rawTitle.isBlank() -> "Chapter ${index + 1}"
                    depth > 0 && prefix.isNotBlank() -> "$prefix - $rawTitle"
                    else -> rawTitle
                }

                ancestors.add(rawTitle)
                chapter.copy(title = normalizedTitle)
            }
        }

        private fun normalizeByHeuristic(toc: List<EpubChapter>): List<EpubChapter> {
            var latestPrimaryTitle: String? = null

            return toc.mapIndexed { index, chapter ->
                val rawTitle = chapter.title.trim()
                val isSubsection = subsectionTitleRegex.matches(rawTitle)

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

        /**
         * Strips namespace prefixes from element tag names (e.g. "ns0:item" -> "item"). Some EPUBs
         * (commonly produced by Python's xml.etree.ElementTree without a registered default namespace)
         * qualify every OPF element with an auto-generated prefix instead of the usual unprefixed
         * default-namespace form. Jsoup's XML parser keeps the prefix as part of the literal tag name,
         * so plain-tag CSS selectors like "manifest > item" would otherwise match nothing.
         *
         * "dc:" is kept because [fillMetadata] queries Dublin Core metadata by prefixed tag name.
         */
        fun stripNamespacePrefixes(doc: Document): Document {
            doc.allElements.forEach { element ->
                val colonIndex = element.tagName().indexOf(':')
                if (colonIndex > 0 && !element.tagName().startsWith("dc:", ignoreCase = true)) {
                    element.tagName(element.tagName().substring(colonIndex + 1))
                }
            }
            return doc
        }

        /** Resolves the OPF manifest+spine into the ordered list of xhtml page hrefs. */
        fun extractSpineHrefs(document: Document): List<String> {
            val pages = document.select("manifest > item")
                .filter { node -> "application/xhtml+xml" == node.attr("media-type") }
                .associateBy { it.attr("id") }

            val spine = document.select("spine > itemref").map { it.attr("idref") }
            return spine.mapNotNull { pages[it] }.map { it.attr("href").let(::urlDecode) }
        }

        /** Returns the manifest item href for the EPUB 3 NAV document, or empty if absent. */
        fun findNavHref(document: Document): String =
            document.select("manifest > item[properties*=nav]").attr("href")

        /** Returns the manifest item href for the EPUB 2 NCX document, or empty if absent. */
        fun findNcxHref(document: Document): String =
            document.select("manifest > item[media-type='application/x-dtbncx+xml']").attr("href")

        fun computeExportImageId(imagePath: String): String {
            val normalized = imagePath.replace('\\', '/')
            val tail = normalized.substringAfterLast('.', "")
            val extension = tail
                .lowercase()
                .takeIf { it.isNotBlank() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
            val stemSource = if (extension != null) {
                normalized.removeSuffix(".$tail")
            } else {
                normalized
            }
            val squashed = stemSource
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .trim('_')
                .ifBlank { "image" }
                .take(80)
            return if (extension != null) "$squashed.$extension" else squashed
        }

        /** Counts TOC entries whose target file (href minus fragment) equals [entryPath]. */
        fun tocEntryCountForPath(toc: List<EpubChapter>, entryPath: String): Int =
            toc.count { urlDecode(it.href.substringBefore("#").trim()) == entryPath }

        /** Returns the HTML anchored at [fragment], or null when it resolves to no slice-able element. */
        fun extractFragmentHtml(document: Document, fragment: String): String? {
            val candidates = buildList {
                val primary = fragment.substringAfterLast("#").trim().removePrefix("#")
                if (primary.isNotBlank()) {
                    add(primary)
                    val decoded = runCatching { URLDecoder.decode(primary, "UTF-8") }.getOrDefault("")
                    if (decoded.isNotBlank() && decoded != primary) {
                        add(decoded)
                    }
                }
            }.distinct()

            for (candidate in candidates) {
                val elementById = document.getElementById(candidate)
                if (elementById != null) {
                    val materialized = materializeFragmentElement(elementById)
                    if (materialized.isNotBlank()) return materialized
                }

                val escaped = candidate
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                val elementByName = document.selectFirst("*[id=\"$escaped\"], *[name=\"$escaped\"]")
                if (elementByName != null) {
                    val materialized = materializeFragmentElement(elementByName)
                    if (materialized.isNotBlank()) return materialized
                }
            }

            return null
        }

        private fun materializeFragmentElement(element: Element): String {
            findHeadingElement(element)?.let { heading ->
                return extractHeadingSectionHtml(heading)
            }

            if (isMeaningfulFragmentElement(element)) {
                return element.outerHtml()
            }

            return ""
        }

        private fun findHeadingElement(element: Element): Element? {
            return if (isHeadingTag(element.tagName())) {
                element
            } else {
                element.parents().firstOrNull { isHeadingTag(it.tagName()) }
            }
        }

        private fun extractHeadingSectionHtml(heading: Element): String {
            val headingLevel = heading.tagName().removePrefix("h").toIntOrNull() ?: return heading.outerHtml()
            val section = Element("div")

            var node: Node? = heading
            while (node != null) {
                if (node !== heading && node is Element && isHeadingTag(node.tagName())) {
                    val siblingHeadingLevel = node.tagName().removePrefix("h").toIntOrNull() ?: Int.MAX_VALUE
                    if (siblingHeadingLevel <= headingLevel) break
                }

                section.appendChild(node.clone())
                node = node.nextSibling()
            }

            return section.html().ifBlank { heading.outerHtml() }
        }

        private fun isMeaningfulFragmentElement(element: Element): Boolean {
            val text = element.text().trim()
            if (text.length >= 80) return true

            return element.select("p, div, section, article, table, ul, ol, blockquote, pre, figure, img, svg")
                .isNotEmpty()
        }

        private fun isHeadingTag(tagName: String): Boolean {
            return tagName.length == 2 && tagName[0] == 'h' && tagName[1] in '1'..'6'
        }
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

    private fun tocEntriesForPath(entryPath: String): Int =
        tocEntryCountForPath(getTableOfContents(), entryPath)
}
