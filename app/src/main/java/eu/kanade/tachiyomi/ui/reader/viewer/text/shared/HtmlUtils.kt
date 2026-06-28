@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import logcat.LogPriority
import logcat.logcat
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

object HtmlUtils {

    private enum class ChapterTextKind { HTML, MARKDOWN, PLAIN_TEXT }

    private val frontMatterRegex = Regex("^\\uFEFF?---\\s*\\r?\\n([\\s\\S]*?)\\r?\\n---\\s*(\\r?\\n|$)")
    private const val CHAPTER_TITLE_SEARCH_LIMIT = 3000

    private val htmlTagRegex = Regex(
        "<\\s*(html|head|body|div|p|span|br|h[1-6]|img|a|table|ul|ol|li|blockquote|article|section|!doctype)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val closingTagRegex = Regex("</\\s*[a-z][a-z0-9:-]*\\s*>", RegexOption.IGNORE_CASE)
    private val stripTagsRegex = Regex("<[^>]+>")

    private val titlePatterns = listOf(
        Regex("""<h[1-6][^>]*>.*?</h[1-6]>""", RegexOption.IGNORE_CASE) to true,
        Regex("""<(strong|[bi]|em)\b[^>]*>.*?</\1>""", RegexOption.IGNORE_CASE) to false,
        Regex("""<p\b[^>]*>.*?</p>""", RegexOption.IGNORE_CASE) to false,
        Regex("""<(div|span)[^>]*>.*?</\1>""", RegexOption.IGNORE_CASE) to false,
    )

    private val chapterNumPatterns = listOf(
        Regex("""chapter\s*\d+""", RegexOption.IGNORE_CASE),
        Regex("""ch\.?\s*\d+""", RegexOption.IGNORE_CASE),
        Regex("""episode\s*\d+""", RegexOption.IGNORE_CASE),
        Regex("""part\s*\d+""", RegexOption.IGNORE_CASE),
    )

    @Suppress("ktlint:standard:max-line-length")
    private val scriptTagRegex =
        Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val scriptSelfClosingRegex = Regex("<script[^>]*/>", RegexOption.IGNORE_CASE)

    @Suppress("ktlint:standard:max-line-length")
    private val styleTagRegex =
        Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val styleSelfClosingRegex = Regex("<style[^>]*/>", RegexOption.IGNORE_CASE)
    private val linkStylesheetRegex1 = Regex("<link[^>]*rel[^>]*stylesheet[^>]*>", RegexOption.IGNORE_CASE)
    private val linkStylesheetRegex2 = Regex("<link[^>]*stylesheet[^>]*rel[^>]*>", RegexOption.IGNORE_CASE)

    @Suppress("ktlint:standard:max-line-length")
    private val noscriptTagRegex =
        Regex("<noscript[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val htmlCommentRegex = Regex("<!--.*?-->", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val encodedCommentRegex = Regex("&lt;!--.*?--&gt;", RegexOption.DOT_MATCHES_ALL)

    private val imgTagRegex = Regex("<img[^>]*>", RegexOption.IGNORE_CASE)
    private val imageTagRegex = Regex("</?image[^>]*>", RegexOption.IGNORE_CASE)

    @Suppress("ktlint:standard:max-line-length")
    private val videoTagRegex =
        Regex("<video[^>]*>.*?</video>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    @Suppress("ktlint:standard:max-line-length")
    private val audioTagRegex =
        Regex("<audio[^>]*>.*?</audio>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val sourceTagRegex = Regex("<source[^>]*>", RegexOption.IGNORE_CASE)

    fun isPlainTextChapter(chapterUrl: String?): Boolean {
        val ext = extensionFor(chapterUrl)
        return ext == "txt" || ext == "text"
    }

    private fun extensionFor(chapterUrl: String?): String {
        return chapterUrl
            ?.substringBefore('#')
            ?.substringBefore('?')
            ?.substringAfterLast('/')
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()
    }

    fun normalizePlainTextContent(content: String): String {
        return content
            .replace("\u0000", "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
    }

    fun normalizeUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isBlank()) return null
        return when {
            value.startsWith("https//") -> "https://" + value.removePrefix("https//")
            value.startsWith("http//") -> "http://" + value.removePrefix("http//")
            else -> value
        }
    }

    fun normalizeContentForHtml(content: String, chapterUrl: String?): String {
        val normalized = content.replace("\u0000", "")
        if (isPlainTextChapter(chapterUrl)) {
            logcat(LogPriority.DEBUG) { "HtmlUtils.normalizeContentForHtml: PLAIN_TEXT (forced by extension)" }
            return plainTextToHtml(normalized)
        }
        val kind = detectTextKind(chapterUrl, normalized)
        logcat(LogPriority.DEBUG) { "HtmlUtils.normalizeContentForHtml: $kind len=${normalized.length}" }
        return when (kind) {
            ChapterTextKind.HTML -> normalized
            ChapterTextKind.MARKDOWN -> markdownToHtml(stripFrontMatter(normalized))
            ChapterTextKind.PLAIN_TEXT -> plainTextToHtml(normalized)
        }
    }

    private fun detectTextKind(chapterUrl: String?, content: String): ChapterTextKind {
        val ext = extensionFor(chapterUrl)

        return when (ext) {
            "md", "markdown" -> ChapterTextKind.MARKDOWN
            "txt", "text" -> ChapterTextKind.PLAIN_TEXT
            "html", "htm", "xhtml", "epub" -> ChapterTextKind.HTML
            else -> {
                if (htmlTagRegex.containsMatchIn(content) || closingTagRegex.containsMatchIn(content)) {
                    ChapterTextKind.HTML
                } else {
                    ChapterTextKind.PLAIN_TEXT
                }
            }
        }
    }

    private fun stripFrontMatter(markdown: String): String =
        markdown.replaceFirst(frontMatterRegex, "")

    private fun markdownToHtml(markdown: String): String {
        return try {
            val flavour = GFMFlavourDescriptor()
            val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
            val rendered = HtmlGenerator(markdown, parsedTree, flavour).generateHtml()
            "<div data-tsundoku-markdown=\"1\">$rendered</div>"
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Markdown render fallback to plain text: ${e.message}" }
            plainTextToHtml(markdown)
        }
    }

    @Suppress("ktlint:standard:max-line-length")
    private fun plainTextToHtml(text: String): String {
        val normalized = normalizePlainTextContent(text)
        // Unescape any HTML entities before re-escaping to avoid double-encoding.
        // JS sources may return content with &lt;D&gt; already encoded; escapeHtml would
        // turn & into &amp;, producing &amp;lt;D&amp;gt; which renders as &lt;D&gt;.
        val decoded = if (normalized.contains('&')) {
            org.jsoup.parser.Parser.unescapeEntities(normalized, false)
        } else {
            normalized
        }
        val escaped = escapeHtml(decoded)
        return "<pre data-tsundoku-plain-text=\"1\" style=\"white-space: pre-wrap; word-break: break-word; overflow-wrap: anywhere; margin: 0;\">$escaped</pre>"
    }

    internal fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /**
     * The first `<h1>–<h6>` tag is removed unconditionally (the `true` flag in
     * [titlePatterns]). Sources routinely embed a redundant heading identical to
     * the chapter name already shown in the reader UI; matching by text would
     * silently keep headings with different casing or punctuation.
     */
    fun stripChapterTitle(content: String, chapterName: String): String {
        val normalizedChapterName = chapterName.trim().lowercase()
        val searchArea = content.take(CHAPTER_TITLE_SEARCH_LIMIT)

        for ((regex, unconditional) in titlePatterns) {
            val match = regex.find(searchArea)
            if (match != null) {
                val matchText = match.value.replace(stripTagsRegex, "").trim().lowercase()
                if (unconditional || isTitleMatch(matchText, normalizedChapterName)) {
                    return content.substring(0, match.range.first) +
                        content.substring(match.range.last + 1)
                }
            }
        }

        val plainTextContent = searchArea.replace(stripTagsRegex, " ").trim()
        val firstLine = plainTextContent.lines().firstOrNull()?.trim()?.lowercase() ?: ""
        if (firstLine.isNotEmpty() && isTitleMatch(firstLine, normalizedChapterName)) {
            val rawFirstLine = content.lines().firstOrNull()?.trim() ?: ""
            if (rawFirstLine.isNotEmpty()) {
                return content.removePrefix(rawFirstLine).trimStart('\n', '\r', ' ')
            }
        }

        return content
    }

    fun isTitleMatch(text: String, chapterName: String): Boolean {
        if (text.isEmpty() || chapterName.isEmpty()) return false
        if (text == chapterName) return true
        if (chapterName.contains(text) && text.length > chapterName.length * 0.8) return true
        if (text.contains(chapterName)) return true
        return chapterNumPatterns.any { it.matches(text) && it.containsMatchIn(chapterName) }
    }

    fun sanitizeForRender(
        content: String,
        target: RenderTarget,
        keepEmbeddedCss: Boolean,
        keepEmbeddedJs: Boolean,
        blockMedia: Boolean,
    ): String {
        var result = content

        val stripJs = target == RenderTarget.TEXT_VIEW || !keepEmbeddedJs
        val stripCss = target == RenderTarget.TEXT_VIEW || !keepEmbeddedCss

        if (stripJs) {
            result = result.replace(scriptTagRegex, "")
            result = result.replace(scriptSelfClosingRegex, "")
        }

        if (stripCss) {
            result = result.replace(styleTagRegex, "")
            result = result.replace(styleSelfClosingRegex, "")
            if (target == RenderTarget.WEB_VIEW) {
                result = result.replace(linkStylesheetRegex1, "")
                result = result.replace(linkStylesheetRegex2, "")
                try {
                    val doc = org.jsoup.Jsoup.parseBodyFragment(result)
                    doc.body().select("*").removeAttr("style")
                    result = doc.body().html()
                } catch (_: Exception) {
                    // Keep the partially sanitized result if parsing fails.
                }
            }
        }

        result = result.replace(noscriptTagRegex, "")
        result = result.replace(htmlCommentRegex, "")
        result = result.replace(encodedCommentRegex, "")

        if (blockMedia) result = stripMediaTags(result)
        return result
    }

    /** Parsed `<table>`: [rows] of cell text, [hasHeader] true when the first row used `<th>`. */
    data class TableModel(val rows: List<List<String>>, val hasHeader: Boolean)

    // Plain-ASCII sentinel left in place of each <table>; survives Html.fromHtml as literal text,
    // then the TextView renderer swaps each match for a drawn table span. The token is distinctive
    // enough not to occur in real chapter text.
    private const val TABLE_TOKEN_OPEN = "[[tsdtbl:"
    private const val TABLE_TOKEN_CLOSE = "]]"
    val tableSentinelRegex = Regex("""\[\[tsdtbl:(\d+)]]""")

    /**
     * Android's `Html.fromHtml` (used by the TextView reader) has no `<table>` support: it drops the
     * table tags and concatenates every cell's text with no structure, fusing values like `51LCK`.
     *
     * Instead of flattening to (fragile) monospace text, this pulls each `<table>` out into a
     * [TableModel] and leaves a `[[tsdtbl:index]]` sentinel in its place. After `Html.fromHtml` the
     * TextView renderer swaps each sentinel range for a [NovelTableSpan] that measures with the real
     * paint and draws a proper aligned, bordered table. The WebView reader renders real tables and
     * does not call this. Returns the original html + empty list when there is no `<table>`.
     */
    fun extractTables(html: String): Pair<String, List<TableModel>> {
        if (!html.contains("<table", ignoreCase = true)) return html to emptyList()
        return try {
            val doc = org.jsoup.Jsoup.parseBodyFragment(html)
            val tableEls = doc.select("table")
            if (tableEls.isEmpty()) return html to emptyList()
            val models = ArrayList<TableModel>(tableEls.size)
            tableEls.forEach { table ->
                val trs = table.select("tr")
                val rows = trs.mapNotNull { row ->
                    val cells = row.select("th, td")
                    if (cells.isEmpty()) null else cells.map { cellToText(it) }
                }.filter { cells -> cells.any { it.isNotBlank() } }

                if (rows.isEmpty()) {
                    table.remove()
                    return@forEach
                }
                val hasHeader = trs.firstOrNull()?.select("th")?.isNotEmpty() == true
                val index = models.size
                models.add(TableModel(rows, hasHeader))
                val sentinel = "$TABLE_TOKEN_OPEN$index$TABLE_TOKEN_CLOSE"
                table.replaceWith(org.jsoup.Jsoup.parseBodyFragment("<p>$sentinel</p>").body().child(0))
            }
            doc.body().html() to models
        } catch (_: Exception) {
            html to emptyList()
        }
    }

    private val cellBlockTags = setOf(
        "p", "div", "li", "ul", "ol", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "table",
    )

    /**
     * Extracts a table cell's text, honoring `<br>` and block tags (`<p>`, `<div>`, `<li>`…) as
     * line breaks while collapsing other whitespace. Walks the already-parsed cell node, so it costs
     * nothing extra (no second Jsoup parse per cell). Returns the cell text with `\n` between lines.
     */
    private fun cellToText(cell: org.jsoup.nodes.Element): String {
        val sb = StringBuilder()
        fun walk(node: org.jsoup.nodes.Node) {
            when (node) {
                is org.jsoup.nodes.TextNode -> sb.append(node.text())
                is org.jsoup.nodes.Element -> {
                    val tag = node.tagName().lowercase()
                    if (tag == "br") {
                        sb.append('\n')
                        return
                    }
                    node.childNodes().forEach { walk(it) }
                    if (tag in cellBlockTags) sb.append('\n')
                }
                else -> {}
            }
        }
        cell.childNodes().forEach { walk(it) }
        return sb.toString()
            .split('\n')
            .joinToString("\n") { it.replace(Regex("\\s+"), " ").trim() }
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
    }

    fun stripMediaTags(content: String): String {
        return content
            .replace(imgTagRegex, "")
            .replace(imageTagRegex, "")
            .replace(videoTagRegex, "")
            .replace(audioTagRegex, "")
            .replace(sourceTagRegex, "")
    }
}
