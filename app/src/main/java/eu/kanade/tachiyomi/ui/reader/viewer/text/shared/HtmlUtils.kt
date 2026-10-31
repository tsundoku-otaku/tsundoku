@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.HtmlUtils.titlePatterns
import logcat.LogPriority
import logcat.logcat
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.TextNode

object HtmlUtils {

    private enum class ChapterTextKind { HTML, MARKDOWN, PLAIN_TEXT }

    private val frontMatterRegex = Regex("^\\uFEFF?---\\s*\\r?\\n([\\s\\S]*?)\\r?\\n---\\s*(\\r?\\n|$)")
    private const val CHAPTER_TITLE_SEARCH_LIMIT = 3000
    private const val MAX_SANITIZE_PASSES = 4

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

    // Attributes whose value is a URL that a browser may load or navigate to.
    private val urlAttributes = setOf("href", "src", "action", "formaction", "xlink:href", "data", "poster", "background", "cite")

    // Elements that only render a data: URL as media, never as a document that runs script.
    private val dataUrlMediaTags = setOf("img", "image", "source", "video", "audio", "track")

    private val textCommentRegex = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

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

    /**
     * Sanitizes on the DOM and serializes, so no text-level rewrite can turn attribute contents
     * back into markup. Clean chapters are returned verbatim when embedded JS is kept.
     */
    fun sanitizeForRender(
        content: String,
        target: RenderTarget,
        keepEmbeddedCss: Boolean,
        keepEmbeddedJs: Boolean,
        blockMedia: Boolean,
    ): String {
        val stripJs = target == RenderTarget.TEXT_VIEW || !keepEmbeddedJs
        val stripCss = target == RenderTarget.TEXT_VIEW || !keepEmbeddedCss

        val first = sanitizePass(content, target, stripJs, stripCss, blockMedia)
        if (!stripJs) return if (first.changed) first.html else content

        // Serialized markup can parse differently than the original (foreign content mXSS), and the
        // WebView only ever parses our serialization, so repeat until it is stable and clean.
        var html = first.html
        repeat(MAX_SANITIZE_PASSES) {
            val pass = sanitizePass(html, target, stripJs, stripCss, blockMedia)
            if (!pass.changed && pass.html == html) return html
            html = pass.html
        }
        return escapeHtml(Jsoup.parseBodyFragment(html).text())
    }

    private class SanitizeResult(val html: String, val changed: Boolean)

    private fun sanitizePass(
        content: String,
        target: RenderTarget,
        stripJs: Boolean,
        stripCss: Boolean,
        blockMedia: Boolean,
    ): SanitizeResult {
        val doc = Jsoup.parseBodyFragment(content)
        doc.outputSettings().prettyPrint(false)
        val body = doc.body()
        var changed = false

        fun removeAll(query: String) {
            val found = body.select(query)
            if (found.isNotEmpty()) {
                found.remove()
                changed = true
            }
        }

        removeAll("noscript, base")
        if (stripJs) {
            // Embedded documents run their own script; SVG animations can set href to javascript: at runtime.
            removeAll(
                "script, meta[http-equiv], iframe, frame, frameset, object, embed, " +
                    "animate, set, animatemotion, animatetransform",
            )
        }
        if (stripCss) removeAll("style, link[rel~=(?i)stylesheet]")
        if (blockMedia) removeAll("img, image, video, audio, source")

        for (element in body.allElements) {
            for (node in element.childNodes().toList()) {
                if (node is Comment) {
                    node.remove()
                    changed = true
                } else if (node is TextNode && "<!--" in node.wholeText) {
                    node.text(node.wholeText.replace(textCommentRegex, ""))
                    changed = true
                }
            }

            if (stripCss && target == RenderTarget.WEB_VIEW && element.hasAttr("style")) {
                element.removeAttr("style")
                changed = true
            }

            if (stripJs) {
                val unsafe = element.attributes().asList().filter { isUnsafeAttribute(element.normalName(), it.key, it.value) }
                unsafe.forEach { element.removeAttr(it.key) }
                if (unsafe.isNotEmpty()) changed = true
            }

            // Native loading="lazy" never fires inside the custom scroll/paged-transform containers.
            if (target == RenderTarget.WEB_VIEW &&
                (element.normalName() == "img" || element.normalName() == "iframe") &&
                element.attr("loading").equals("lazy", ignoreCase = true)
            ) {
                element.attr("loading", "eager")
                changed = true
            }
        }

        return SanitizeResult(body.html(), changed)
    }

    private fun isUnsafeAttribute(tag: String, key: String, value: String): Boolean {
        val name = key.lowercase()
        if (name.length > 2 && name.startsWith("on") && name.drop(2).all { it in 'a'..'z' }) return true
        if (name == "srcdoc") return true
        if (name !in urlAttributes) return false
        val url = value.filterNot { it.isWhitespace() || it.isISOControl() }.lowercase()
        return url.startsWith("javascript:") ||
            url.startsWith("vbscript:") ||
            (url.startsWith("data:") && tag !in dataUrlMediaTags)
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
