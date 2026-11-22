@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.HtmlUtils.titlePatterns
import logcat.LogPriority
import logcat.logcat
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Attribute
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist

object HtmlUtils {

    private enum class ChapterTextKind { HTML, MARKDOWN, PLAIN_TEXT }

    private val frontMatterRegex = Regex("^\\uFEFF?---\\s*\\r?\\n([\\s\\S]*?)\\r?\\n---\\s*(\\r?\\n|$)")
    private const val CHAPTER_TITLE_SEARCH_LIMIT = 3000
    private const val MAX_SANITIZE_PASSES = 4
    private const val CHAPTER_CONTAINER_TAG = "tsundoku-chapter"

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
    private val urlAttributes =
        setOf("href", "src", "action", "formaction", "xlink:href", "data", "poster", "background", "cite")

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
            if (!pass.changed && pass.html == html && settlesAsDocumentBody(html, target, stripCss, blockMedia)) {
                return html
            }
            html = pass.html
        }
        // Still mutating on reparse: keep the structure but only through a plain HTML safelist.
        logcat(LogPriority.WARN) { "Chapter HTML did not settle after $MAX_SANITIZE_PASSES sanitize passes" }
        return cleanWithSafelist(html, keepCss = !stripCss, blockMedia = blockMedia)
    }

    // The first chapter of a page goes through a whole-document parse (its body) before the WebView
    // parses it, so that serialization must be clean too.
    private fun settlesAsDocumentBody(
        html: String,
        target: RenderTarget,
        stripCss: Boolean,
        blockMedia: Boolean,
    ): Boolean {
        val doc = Jsoup.parse(html)
        doc.outputSettings().prettyPrint(false)
        val body = doc.body().html()
        if (body == html) return true
        return !sanitizePass(body, target, stripJs = true, stripCss = stripCss, blockMedia = blockMedia).changed
    }

    // Relative links only survive cleaning when resolvable, so clean against a placeholder base;
    // preserveRelativeLinks keeps them relative in the output.
    internal fun cleanWithSafelist(html: String, keepCss: Boolean, blockMedia: Boolean): String =
        Jsoup.clean(
            html,
            "https://chapter.tsundoku.invalid/",
            fallbackSafelist(keepCss, blockMedia),
            Document.OutputSettings().prettyPrint(false),
        )

    private fun fallbackSafelist(keepCss: Boolean, blockMedia: Boolean): Safelist =
        DataAttributeSafelist()
            .preserveRelativeLinks(true)
            .addTags(
                "hr", "s", "del", "ins", "mark", "ruby", "rt", "rp", "section", "article", "aside",
                "header", "footer", "figure", "figcaption", "center",
                "video", "audio", "source", "track", "picture",
            )
            .addAttributes(":all", "class", "id", "dir", "lang", "title")
            .addAttributes("video", "src", "poster", "controls", "loop", "muted", "width", "height", "preload")
            .addAttributes("audio", "src", "controls", "loop", "muted", "preload")
            .addAttributes("source", "src", "srcset", "type", "media", "sizes")
            .addAttributes("track", "src", "kind", "srclang", "label", "default")
            .addAttributes("img", "srcset", "sizes", "loading")
            .addProtocols("img", "src", "http", "https", "data")
            .addProtocols("video", "src", "http", "https", "data")
            .addProtocols("video", "poster", "http", "https", "data")
            .addProtocols("audio", "src", "http", "https", "data")
            .addProtocols("source", "src", "http", "https", "data")
            .addProtocols("track", "src", "http", "https", "data")
            .apply {
                if (keepCss) {
                    addTags("style")
                    addAttributes(":all", "style")
                }
                if (blockMedia) removeTags("img", "video", "audio", "source", "track", "picture")
            }

    // data-* attributes carry lazy-load sources and reader state; their names can't be listed upfront.
    private class DataAttributeSafelist : Safelist(Safelist.relaxed()) {
        override fun isSafeAttribute(tagName: String, el: Element, attr: Attribute): Boolean =
            super.isSafeAttribute(tagName, el, attr) ||
                (attr.key.startsWith("data-") && !attr.value.contains("javascript:", ignoreCase = true))
    }

    private class SanitizeResult(val html: String, val changed: Boolean)

    private fun sanitizePass(
        content: String,
        target: RenderTarget,
        stripJs: Boolean,
        stripCss: Boolean,
        blockMedia: Boolean,
    ): SanitizeResult {
        // Parsed in the chapter element, where the WebView inserts the content, so a pass that is
        // stable here is stable there too.
        val doc = Document.createShell("")
        doc.outputSettings().prettyPrint(false)
        val body = doc.body().appendElement(CHAPTER_CONTAINER_TAG)
        body.appendChildren(Parser.parseFragment(content, body, ""))
        var changed = false

        fun removeAll(query: String) {
            val found = body.select(query)
            if (found.isNotEmpty()) {
                found.remove()
                changed = true
            }
        }

        if (resolveAgainstBase(body)) changed = true
        removeAll("noscript, base, meta[http-equiv]")
        if (stripJs) {
            // Embedded documents run their own script; SVG animations can set href to javascript: at runtime.
            removeAll(
                "script, iframe, frame, frameset, object, embed, " +
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
                    val cleaned = node.wholeText.replace(textCommentRegex, "")
                    if (cleaned != node.wholeText) {
                        node.text(cleaned)
                        changed = true
                    }
                }
            }

            if (stripCss && target == RenderTarget.WEB_VIEW && element.hasAttr("style")) {
                element.removeAttr("style")
                changed = true
            }

            if (stripJs) {
                val unsafe = element.attributes().asList().filter {
                    isUnsafeAttribute(element.normalName(), it.key, it.value)
                }
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

    // <base> is dropped so a chapter can't repoint the reader's own URLs, but the chapter's relative
    // links and media were written against it, so they are made absolute first.
    private fun resolveAgainstBase(root: Element): Boolean {
        val href = root.selectFirst("base[href]")?.attr("href")?.trim().orEmpty()
        // HttpUrl resolves RFC 3986 style, so "?page=2" keeps the base document; java.net drops it.
        val base = href.toHttpUrlOrNull() ?: return false
        fun resolve(value: String): String? {
            if (value.isEmpty() || value.startsWith("#") || SCHEME_PREFIX.containsMatchIn(value)) return null
            return base.resolve(value)?.toString()
        }
        var resolved = false
        for (element in root.select(BASE_RESOLVED_SELECTOR)) {
            for (key in BASE_RESOLVED_ATTRIBUTES) {
                val absolute = resolve(element.attr(key).trim()) ?: continue
                element.attr(key, absolute)
                resolved = true
            }
            for (key in SRCSET_ATTRIBUTES) {
                val value = element.attr(key).trim()
                if (value.isEmpty()) continue
                // Each candidate is "url [descriptor]"; only the url is resolved.
                var changed = false
                val rewritten = srcsetCandidates(value).joinToString(", ") { (rawUrl, descriptor) ->
                    val url = resolve(rawUrl)?.also { changed = true } ?: rawUrl
                    if (descriptor != null) "$url $descriptor" else url
                }
                if (changed) {
                    element.attr(key, rewritten)
                    resolved = true
                }
            }
        }
        return resolved
    }

    // Lazy loaders copy data-src/data-srcset into place later, after <base> is gone.
    private val BASE_RESOLVED_ATTRIBUTES = listOf("src", "href", "poster", "data-src")
    private val SRCSET_ATTRIBUTES = listOf("srcset", "data-srcset")

    // The HTML srcset rules: a url runs to whitespace and gives up trailing commas, and its descriptors
    // run to the next comma outside parentheses, so "a.jpg 1x,b.jpg 2x" is two candidates while a comma
    // inside a url stays part of it.
    internal fun srcsetCandidates(value: String): List<Pair<String, String?>> {
        val candidates = mutableListOf<Pair<String, String?>>()
        var i = 0
        while (i < value.length) {
            while (i < value.length && (value[i].isWhitespace() || value[i] == ',')) i++
            if (i >= value.length) break
            val urlStart = i
            while (i < value.length && !value[i].isWhitespace()) i++
            var url = value.substring(urlStart, i)
            if (url.endsWith(',')) {
                url = url.trimEnd(',')
                if (url.isNotEmpty()) candidates += url to null
                continue
            }
            val descriptorStart = i
            var depth = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '(') depth++ else if (c == ')' && depth > 0) depth-- else if (c == ',' && depth == 0) break
                i++
            }
            val descriptor = value.substring(descriptorStart, i).trim().ifEmpty { null }
            candidates += url to descriptor
        }
        return candidates
    }
    private const val BASE_RESOLVED_SELECTOR = "[src], [href], [poster], [data-src], [srcset], [data-srcset]"
    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

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
