package eu.kanade.tachiyomi.data.translation

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Stateless utility functions for HTML manipulation during translation.
 *
 * Extracted from [TranslationService] to satisfy SRP and allow reuse
 * from other call-sites (e.g. real-time reader translation).
 */
object TranslationHtmlUtils {

    private const val SOURCE_HASH_COMMENT_PREFIX = "<!-- tsundoku-source-hash:"

    // ── Image / media preservation ──────────────────────────────────

    /** CSS selector for elements that must survive the translate round-trip. */
    private const val MEDIA_SELECTOR = "img, figure, picture, video, source, svg"

    /**
     * Replace media elements with unique placeholders so they are not mangled
     * by the translation engine.
     *
     * @return the modified HTML **and** a map of placeholder → original outer-HTML.
     */
    fun extractImages(html: String): Pair<String, Map<String, String>> {
        val doc = Jsoup.parse(html)
        doc.outputSettings().prettyPrint(false)
        val images = mutableMapOf<String, String>()
        var index = 0
        doc.select(MEDIA_SELECTOR).forEach { element: Element ->
            val placeholder = "[IMG_PLACEHOLDER_$index]"
            images[placeholder] = element.outerHtml()
            element.replaceWith(org.jsoup.nodes.TextNode("\n$placeholder\n"))
            index++
        }
        return doc.body().html() to images
    }

    /**
     * Put the original media elements back in place of their placeholders.
     */
    fun reinsertImages(translatedHtml: String, images: Map<String, String>): String {
        var result = translatedHtml
        for ((placeholder, originalTag) in images) {
            result = result.replace(placeholder, originalTag)
        }
        return result
    }

    /**
     * Normalize line-break variants so paragraph handling is stable.
     */
    fun normalizeLineBreaks(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u2028', '\n')
            .replace('\u2029', '\n')
    }

    // ── Text ↔ HTML conversion ──────────────────────────────────────

    /**
     * Convert HTML to plain text, preserving paragraph boundaries as `\n\n`.
     *
     * Uses Jsoup for robust entity decoding instead of manual replacement.
     */
    fun extractTextFromHtml(html: String): String {
        // Strip embedded base64 data URIs before parsing (they can be huge)
        val cleaned = html.replace(
            Regex("data:[a-zA-Z0-9/+.-]+;base64,[A-Za-z0-9+/=\\s]+"),
            "",
        )

        val doc = Jsoup.parse(cleaned)
        // Insert markers for structural breaks so we can split later
        doc.select("p, div, br, h1, h2, h3, h4, h5, h6, li, blockquote").forEach { el ->
            if (el.tagName() == "br") {
                el.before(org.jsoup.nodes.TextNode("\n"))
            } else {
                el.before(org.jsoup.nodes.TextNode("\n\n"))
            }
        }

        return normalizeLineBreaks(doc.body().wholeText())
            .replace(Regex("[ \\t\\u000B\\f]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Wrap plain text (paragraphs separated by `\n\n`) back into `<p>` elements.
     */
    fun wrapTextInHtml(text: String): String {
        return splitParagraphsPreserving(text)
            .joinToString("") { paragraph ->
                "<p>${escapeHtml(normalizeLineBreaks(paragraph).trim()).replace("\n", "<br/>")}</p>"
            }
    }

    fun hasSourceHashTag(content: String): Boolean {
        return content.startsWith(SOURCE_HASH_COMMENT_PREFIX)
    }

    /**
     * Build a complete translated-chapter HTML string from an optional title
     * and a list of already-translated paragraph strings.
     *
     * This is the single source of truth previously duplicated in
     * `translateChapter` and `savePartialTranslation`.
     */
    fun buildTranslatedHtml(
        translatedTitle: String?,
        translatedParagraphs: List<String>,
    ): String = buildString {
        if (!translatedTitle.isNullOrBlank()) {
            append("<h1>${escapeHtml(translatedTitle.trim())}</h1>")
        }
        translatedParagraphs.forEach { paragraph ->
            append("<p>${escapeHtml(paragraph.trim()).replace("\n", "<br/>")}</p>")
        }
    }

    // ── Chunking ────────────────────────────────────────────────────

    /**
     * Split a list of paragraphs into translation-friendly chunks.
     *
     * Each chunk is a single string with paragraphs separated by `\n\n`.
     */
    fun buildChunks(paragraphs: List<String>, chunkSize: Int): List<String> {
        if (paragraphs.isEmpty()) return emptyList()
        return paragraphs.chunked(chunkSize.coerceAtLeast(1)).map { group ->
            group.joinToString("\n\n")
        }
    }

    // ── LLM helpers ─────────────────────────────────────────────────

    /**
     * Strip contextual-anchoring markers that the LLM might echo back.
     *
     * If the response still contains the `=== TEXT TO TRANSLATE` header,
     * only the text after that header is kept.
     */
    fun stripContextLeakage(translated: String): String {
        val marker = "=== TEXT TO TRANSLATE"
        val markerIndex = translated.indexOf(marker)
        if (markerIndex < 0) return translated
        val afterMarker = translated.indexOf("\n", markerIndex)
        if (afterMarker < 0) return translated
        return translated.substring(afterMarker + 1).trim()
    }

    // ── Language code normalisation (fixes 6.4) ─────────────────────

    /**
     * Normalise a language code to its base 2-letter form for comparison.
     *
     * Examples: `"EN-US"` → `"en"`, `"zh-TW"` → `"zh"`, `"ja"` → `"ja"`.
     */
    fun normalizeLanguageCode(code: String): String {
        return code.lowercase().substringBefore('-').substringBefore('_')
    }

    /**
     * Compare two language codes ignoring case and regional suffixes.
     */
    fun languageCodesMatch(a: String, b: String): Boolean {
        return normalizeLanguageCode(a) == normalizeLanguageCode(b)
    }

    // ── HTML escaping (fixes 6.5) ───────────────────────────────────

    /**
     * Minimal HTML entity escaping for text that will be embedded inside tags.
     *
     * Uses Jsoup's [Parser.unescapeEntities] in reverse via [org.jsoup.nodes.Entities].
     */
    fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    /**
     * Split translated text into paragraphs while being robust to model output.
     * Prefer splitting on two-or-more newlines; if none are present, fall back
     * to single-line breaks so models that preserve only single newlines still
     * return sensible paragraphs.
     */
    fun splitParagraphsPreserving(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = normalizeLineBreaks(text)
        val byDouble = normalized.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotBlank() }
        if (byDouble.size > 1) return byDouble
        // Fallback to single-line breaks
        return normalized.split(Regex("\n")).map { it.trim() }.filter { it.isNotBlank() }
    }
}
