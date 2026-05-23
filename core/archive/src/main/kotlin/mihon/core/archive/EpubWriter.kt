package mihon.core.archive

import org.jsoup.Jsoup
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB writer for creating EPUB 3.0 files (with EPUB 2 compatibility).
 * Creates a valid EPUB with: mimetype, META-INF/container.xml, content.opf, nav.xhtml, chapter HTML files,
 * and optionally embedded chapter images.
 *
 * @param deflateLevel Deflate compression level (0-9). 0 = no compression (stored), 9 = max compression.
 *                     Default is [java.util.zip.Deflater.DEFAULT_COMPRESSION] (-1).
 *                     The `mimetype` entry is ALWAYS stored uncompressed per the EPUB spec.
 */
class EpubWriter(
    private val deflateLevel: Int = java.util.zip.Deflater.DEFAULT_COMPRESSION,
) {

    data class EmbeddedImage(
        val id: String,
        val bytes: ByteArray,
        val mimeType: String,
        val extension: String,
    )

    data class Chapter(
        val title: String,
        val content: String,
        val order: Int = 0,
        val images: List<EmbeddedImage> = emptyList(),
    )

    data class Metadata(
        val title: String,
        val author: String? = null,
        val description: String? = null,
        val language: String = "en",
        val coverImagePath: String? = null,
        val genres: List<String> = emptyList(),
        val publisher: String? = null,
    )

    fun write(
        outputStream: OutputStream,
        metadata: Metadata,
        chapters: List<Chapter>,
        coverImage: ByteArray? = null,
        customCss: String? = null,
        customJs: String? = null,
    ) {
        val bookId = UUID.randomUUID().toString()

        val (coverMimeType, coverExtension) = if (coverImage != null) detectImageType(coverImage) else "image/jpeg" to "jpg"
        val coverFileName = "cover.$coverExtension"

        val cssBody = customCss?.takeIf { it.isNotBlank() }
        val jsBody = customJs?.takeIf { it.isNotBlank() }

        ZipOutputStream(outputStream).use { zip ->
            zip.setLevel(deflateLevel)
            writeMimetype(zip)
            writeContainerXml(zip)

            if (coverImage != null) {
                writeEntry(zip, "OEBPS/images/$coverFileName", coverImage)
            }

            if (cssBody != null) {
                writeEntry(zip, "OEBPS/$CUSTOM_CSS_PATH", cssBody)
            }
            if (jsBody != null) {
                writeEntry(zip, "OEBPS/$CUSTOM_JS_PATH", jsBody)
            }

            chapters.forEachIndexed { chIdx, chapter ->
                val chapterPrefix = chapterFilePrefix(chIdx)
                chapter.images.forEach { img ->
                    val epubFileName = chapterImageFileName(chapterPrefix, img)
                    writeEntry(zip, "OEBPS/images/$epubFileName", img.bytes)
                }
            }

            chapters.forEachIndexed { index, chapter ->
                writeChapter(zip, index, chapter, includeCustomCss = cssBody != null, includeCustomJs = jsBody != null)
            }

            writeNavDocument(zip, chapters)
            writeNcxDocument(zip, metadata, chapters, bookId)
            writePackageDocument(
                zip = zip,
                metadata = metadata,
                chapters = chapters,
                hasCover = coverImage != null,
                bookId = bookId,
                coverFileName = coverFileName,
                coverMimeType = coverMimeType,
                hasCustomCss = cssBody != null,
                hasCustomJs = jsBody != null,
            )
        }
    }

    private fun writeMimetype(zip: ZipOutputStream) {
        val content = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = content.size.toLong()
            compressedSize = content.size.toLong()
            crc = CRC32().apply { update(content) }.value
        }
        zip.putNextEntry(entry)
        zip.write(content)
        zip.closeEntry()
    }

    private fun writeContainerXml(zip: ZipOutputStream) {
        val content = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
</container>"""
        writeEntry(zip, "META-INF/container.xml", content)
    }

    private fun writeChapter(
        zip: ZipOutputStream,
        index: Int,
        chapter: Chapter,
        includeCustomCss: Boolean,
        includeCustomJs: Boolean,
    ) {
        val chapterPrefix = chapterFilePrefix(index)
        val bodyContent = rewriteChapterHtml(chapter.content, chapterPrefix, chapter.images)

        val customCssLink = if (includeCustomCss) {
            "\n    <link rel=\"stylesheet\" type=\"text/css\" href=\"$CUSTOM_CSS_PATH\"/>"
        } else {
            ""
        }
        val customJsTag = if (includeCustomJs) {
            "\n    <script type=\"text/javascript\" src=\"$CUSTOM_JS_PATH\"><!-- --></script>"
        } else {
            ""
        }

        val content = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en">
<head>
    <meta charset="UTF-8"/>
    <title>${escapeXml(chapter.title)}</title>
    <style type="text/css">
        body { font-family: serif; line-height: 1.6; margin: 1em; }
        h1, h2, h3 { font-family: sans-serif; }
        p { margin: 0.5em 0; text-indent: 1em; }
        .chapter-title { text-align: center; margin-bottom: 2em; }
        img { max-width: 100%; height: auto; }
    </style>$customCssLink$customJsTag
</head>
<body>
    <h1 class="chapter-title">${escapeXml(chapter.title)}</h1>
    <div class="chapter-content">
        $bodyContent
    </div>
</body>
</html>"""
        writeEntry(zip, "OEBPS/$chapterPrefix.xhtml", content)
    }

    /**
     * Rewrites [content] HTML for EPUB embedding:
     * - Replaces tsundoku-novel-image:// src attributes with relative EPUB image paths.
     * - Removes orphan tsundoku-novel-image:// img elements (image not in [images]).
     * - Strips <source> children from <picture>, keeping only the <img> fallback.
     * - Removes <picture> elements that have no usable <img> fallback.
     */
    private fun rewriteChapterHtml(content: String, chapterPrefix: String, images: List<EmbeddedImage>): String {
        val hasTsundokuPaths = content.contains("tsundoku-novel-image://")
        val hasPictures = content.contains("<picture")
        if (images.isEmpty() && !hasTsundokuPaths && !hasPictures) return content

        val imageMap = images.associate { img ->
            "tsundoku-novel-image://${img.id}" to "images/${chapterImageFileName(chapterPrefix, img)}"
        }

        val doc = Jsoup.parseBodyFragment(content)

        // Process <picture> elements: strip <source> elements, unwrap to bare <img>
        doc.select("picture").forEach { picture ->
            val existingImg = picture.selectFirst("img")
            val img = existingImg ?: run {
                // Build an img from the first <source> that has a usable src/srcset
                val source = picture.selectFirst("source")
                val src = source?.attr("srcset")
                    ?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: source?.attr("src")?.takeIf { it.isNotBlank() }
                if (src != null) {
                    val newImg = doc.createElement("img")
                    newImg.attr("src", src)
                    val altText = source?.attr("alt")?.ifBlank { picture.attr("alt") }
                    if (!altText.isNullOrBlank()) newImg.attr("alt", altText)
                    newImg
                } else null
            }

            if (img != null) {
                picture.select("source").remove()
                picture.replaceWith(img)
            } else {
                picture.remove()
            }
        }

        // Replace / remove tsundoku-novel-image:// src attributes
        doc.select("img[src]").forEach { imgEl ->
            val src = imgEl.attr("src")
            if (src.startsWith("tsundoku-novel-image://")) {
                val mapped = imageMap[src]
                if (mapped != null) {
                    imgEl.attr("src", mapped)
                } else {
                    imgEl.remove()
                }
            }
        }

        return doc.body().html()
    }

    private fun writeNavDocument(zip: ZipOutputStream, chapters: List<Chapter>) {
        val tocItems = chapters.mapIndexed { index, chapter ->
            val filename = "${chapterFilePrefix(index)}.xhtml"
            """            <li><a href="$filename">${escapeXml(chapter.title)}</a></li>"""
        }.joinToString("\n")

        val content = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en">
<head>
    <meta charset="UTF-8"/>
    <title>Table of Contents</title>
</head>
<body>
    <nav epub:type="toc" id="toc">
        <h1>Table of Contents</h1>
        <ol>
$tocItems
        </ol>
    </nav>
</body>
</html>"""
        writeEntry(zip, "OEBPS/nav.xhtml", content)
    }

    private fun writeNcxDocument(
        zip: ZipOutputStream,
        metadata: Metadata,
        chapters: List<Chapter>,
        bookId: String,
    ) {
        val navPoints = chapters.mapIndexed { index, chapter ->
            val filename = "${chapterFilePrefix(index)}.xhtml"
            """        <navPoint id="navpoint-${index + 1}" playOrder="${index + 1}">
            <navLabel><text>${escapeXml(chapter.title)}</text></navLabel>
            <content src="$filename"/>
        </navPoint>"""
        }.joinToString("\n")

        val content = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
    <head>
        <meta name="dtb:uid" content="urn:uuid:$bookId"/>
        <meta name="dtb:depth" content="1"/>
        <meta name="dtb:totalPageCount" content="0"/>
        <meta name="dtb:maxPageNumber" content="0"/>
    </head>
    <docTitle><text>${escapeXml(metadata.title)}</text></docTitle>
    <navMap>
$navPoints
    </navMap>
</ncx>"""
        writeEntry(zip, "OEBPS/toc.ncx", content)
    }

    private fun writePackageDocument(
        zip: ZipOutputStream,
        metadata: Metadata,
        chapters: List<Chapter>,
        hasCover: Boolean,
        bookId: String,
        coverFileName: String,
        coverMimeType: String,
        hasCustomCss: Boolean,
        hasCustomJs: Boolean,
    ) {
        val chapterProperties = if (hasCustomJs) " properties=\"scripted\"" else ""

        val manifestItems = buildString {
            appendLine("""        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
            appendLine("""        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
            if (hasCover) {
                appendLine("""        <item id="cover-image" href="images/$coverFileName" media-type="$coverMimeType" properties="cover-image"/>""")
            }
            if (hasCustomCss) {
                appendLine("""        <item id="tsundoku-style" href="$CUSTOM_CSS_PATH" media-type="text/css"/>""")
            }
            if (hasCustomJs) {
                appendLine("""        <item id="tsundoku-script" href="$CUSTOM_JS_PATH" media-type="application/javascript"/>""")
            }
            chapters.forEachIndexed { chIdx, chapter ->
                val prefix = chapterFilePrefix(chIdx)
                appendLine("""        <item id="$prefix" href="$prefix.xhtml" media-type="application/xhtml+xml"$chapterProperties/>""")
                chapter.images.forEach { img ->
                    val epubFileName = chapterImageFileName(prefix, img)
                    val manifestId = "$prefix-img-${img.id.replace('.', '-').replace('_', '-')}"
                    appendLine("""        <item id="$manifestId" href="images/$epubFileName" media-type="${img.mimeType}"/>""")
                }
            }
        }.trimEnd()

        val spineItems = chapters.mapIndexed { index, _ ->
            """        <itemref idref="${chapterFilePrefix(index)}"/>"""
        }.joinToString("\n")

        val genresMetadata = metadata.genres.joinToString("\n") { genre ->
            """        <dc:subject>${escapeXml(genre)}</dc:subject>"""
        }

        val epub2CoverMeta = if (hasCover) {
            """        <meta name="cover" content="cover-image"/>"""
        } else {
            ""
        }

        val content = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
        <dc:identifier id="BookId">urn:uuid:$bookId</dc:identifier>
        <dc:title>${escapeXml(metadata.title)}</dc:title>
        <dc:language>${metadata.language}</dc:language>
${metadata.author?.let { """        <dc:creator>${escapeXml(it)}</dc:creator>""" } ?: ""}
${metadata.description?.let { """        <dc:description>${escapeXml(it)}</dc:description>""" } ?: ""}
${metadata.publisher?.let { """        <dc:publisher>${escapeXml(it)}</dc:publisher>""" } ?: ""}
$genresMetadata
$epub2CoverMeta
        <meta property="dcterms:modified">${java.time.Instant.now().toString().substringBefore('.') + "Z"}</meta>
    </metadata>
    <manifest>
$manifestItems
    </manifest>
    <spine toc="ncx">
$spineItems
    </spine>
</package>"""
        writeEntry(zip, "OEBPS/content.opf", content)
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: String) {
        writeEntry(zip, path, content.toByteArray(Charsets.UTF_8))
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: ByteArray) {
        val entry = ZipEntry(path).apply {
            method = ZipEntry.DEFLATED
        }
        zip.putNextEntry(entry)
        zip.write(content)
        zip.closeEntry()
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun chapterFilePrefix(index: Int) = "chapter${index.toString().padStart(4, '0')}"

    private fun chapterImageFileName(chapterPrefix: String, img: EmbeddedImage): String {
        val baseName = img.id.substringBeforeLast(".", img.id)
        return "${chapterPrefix}_${baseName}.${img.extension}"
    }

    companion object {
        /**
         * Detects image MIME type and extension from magic bytes.
         * Returns Pair(mimeType, extension).
         */
        fun detectImageType(bytes: ByteArray): Pair<String, String> {
            if (bytes.size < 4) return "image/jpeg" to "jpg"
            return when {
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ->
                    "image/jpeg" to "jpg"
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() ->
                    "image/png" to "png"
                bytes.size >= 12 &&
                    bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                    bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                    bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() ->
                    "image/webp" to "webp"
                bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() ->
                    "image/gif" to "gif"
                else -> "image/jpeg" to "jpg"
            }
        }

        /**
         * Convenience method to write EPUB to a file.
         */
        fun writeToFile(
            file: File,
            metadata: Metadata,
            chapters: List<Chapter>,
            coverImage: ByteArray? = null,
            deflateLevel: Int = java.util.zip.Deflater.DEFAULT_COMPRESSION,
            customCss: String? = null,
            customJs: String? = null,
        ) {
            file.outputStream().use { outputStream ->
                EpubWriter(deflateLevel).write(
                    outputStream = outputStream,
                    metadata = metadata,
                    chapters = chapters,
                    coverImage = coverImage,
                    customCss = customCss,
                    customJs = customJs,
                )
            }
        }
        const val CUSTOM_CSS_PATH = "styles/tsundoku-style.css"
        const val CUSTOM_JS_PATH = "scripts/tsundoku-script.js"
    }
}
