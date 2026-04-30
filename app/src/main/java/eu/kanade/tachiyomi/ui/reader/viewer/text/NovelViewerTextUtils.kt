package eu.kanade.tachiyomi.ui.reader.viewer.text

import android.app.Activity
import eu.kanade.presentation.reader.settings.RegexReplacement
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import logcat.logcat
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Shared utility functions used by both [NovelViewer] (native TextView) and
 * [NovelWebViewViewer] (WebView).  Extracted to eliminate code duplication.
 */
object NovelViewerTextUtils {

    private enum class ChapterTextKind {
        HTML,
        MARKDOWN,
        PLAIN_TEXT,
    }

    private val frontMatterRegex = Regex("^\\uFEFF?---\\s*\\r?\\n([\\s\\S]*?)\\r?\\n---\\s*(\\r?\\n|$)")

    fun isPlainTextChapter(chapterUrl: String?): Boolean {
        val ext = chapterUrl
            ?.substringBefore('#')
            ?.substringBefore('?')
            ?.substringAfterLast('/', "")
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()

        return ext == "txt" || ext == "text"
    }

    fun normalizePlainTextContent(content: String): String {
        return content
            .replace("\u0000", "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
    }
    /**
     * Normalizes chapter content to HTML so both WebView and TextView pipelines
     * can share rendering behavior for html/plain-text/markdown chapters.
     */
    fun normalizeContentForHtml(content: String, chapterUrl: String?): String {
        val normalized = content.replace("\u0000", "")
        if (isPlainTextChapter(chapterUrl)) {
            return plainTextToHtml(normalized)
        }
        return when (detectTextKind(chapterUrl, normalized)) {
            ChapterTextKind.HTML -> normalized
            ChapterTextKind.MARKDOWN -> markdownToHtml(stripFrontMatter(normalized))
            ChapterTextKind.PLAIN_TEXT -> plainTextToHtml(normalized)
        }
    }

    private fun detectTextKind(chapterUrl: String?, content: String): ChapterTextKind {
        val ext = chapterUrl
            ?.substringBefore('#')
            ?.substringBefore('?')
            ?.substringAfterLast('/', "")
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()

        return when (ext) {
            "md", "markdown" -> ChapterTextKind.MARKDOWN
            "txt", "text" -> ChapterTextKind.PLAIN_TEXT
            "html", "htm", "xhtml", "epub" -> ChapterTextKind.HTML
            else -> {
                val hasCommonHtmlTags = Regex(
                    "<\\s*(html|head|body|div|p|span|br|h[1-6]|img|a|table|ul|ol|li|blockquote|article|section|!doctype)\\b",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(content)
                val hasClosingTag = Regex("</\\s*[a-z][a-z0-9:-]*\\s*>", RegexOption.IGNORE_CASE)
                    .containsMatchIn(content)
                if (hasCommonHtmlTags || hasClosingTag) {
                    ChapterTextKind.HTML
                } else {
                    ChapterTextKind.PLAIN_TEXT
                }
            }
        }
    }

    private fun stripFrontMatter(markdown: String): String {
        return frontMatterRegex.replaceFirst(markdown, "")
    }

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

    private fun plainTextToHtml(text: String): String {
        val normalized = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        val escaped = escapeHtml(normalized)
        return "<pre data-tsundoku-plain-text=\"1\" style=\"white-space: pre-wrap; word-break: break-word; overflow-wrap: anywhere; margin: 0;\">$escaped</pre>"
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /**
     * Apply user-configured find & replace rules to content.
     * Rules are stored as JSON in the novelRegexReplacements preference.
     * Each enabled rule is applied in order — supports both plain text and regex patterns.
     */
    fun applyRegexReplacements(content: String, preferences: ReaderPreferences): String {
        val rulesJson = preferences.novelRegexReplacements.get()
        if (rulesJson.isBlank() || rulesJson == "[]") return content

        val rules: List<RegexReplacement> = try {
            kotlinx.serialization.json.Json.decodeFromString(rulesJson)
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Failed to parse regex replacements: ${e.message}" }
            return content
        }

        var result = content
        for (rule in rules) {
            if (!rule.enabled || rule.pattern.isBlank()) continue
            try {
                result = if (rule.isRegex) {
                    val regex = Regex(rule.pattern)
                    regex.replace(result, rule.replacement)
                } else {
                    result.replace(rule.pattern, rule.replacement)
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Regex replacement '${rule.title}' failed: ${e.message}" }
            }
        }
        return result
    }

    /**
     * Strips the chapter title from the beginning of the content.
     * Searches within the first ~3000 characters for chapter title or name matches.
     */
    fun stripChapterTitle(content: String, chapterName: String): String {
        val normalizedChapterName = chapterName.trim().lowercase()
        // Search within first 3000 chars for title (to handle leading whitespace/tags/extra prefixes)
        val searchArea = content.take(3000)

        // Try to remove first heading, or other elements if they match the chapter name.
        // We unconditionally hide the first heading.
        val extractPatterns = listOf(
            """<h[1-6][^>]*>.*?</h[1-6]>""".toRegex(RegexOption.IGNORE_CASE) to true,
            """<(strong|[bi]|em)\b[^>]*>.*?</\1>""".toRegex(RegexOption.IGNORE_CASE) to false,
            """<p\b[^>]*>.*?</p>""".toRegex(RegexOption.IGNORE_CASE) to false,
            """<(div|span)[^>]*>.*?</\1>""".toRegex(RegexOption.IGNORE_CASE) to false,
        )

        for ((regex, unconditional) in extractPatterns) {
            val match = regex.find(searchArea)
            if (match != null) {
                // Strip tags from the entire matched HTML to get just the text
                val matchText = match.value.replace(Regex("<[^>]+>"), "").trim().lowercase()
                if (unconditional || isTitleMatch(matchText, normalizedChapterName)) {
                    return content.substring(0, match.range.first) +
                        content.substring(match.range.last + 1)
                }
            }
        }

        // Try to find and remove plain text that matches chapter name at the very start
        val plainTextContent = content.replace(Regex("<[^>]+>"), " ").trim()
        val firstLine = plainTextContent.lines().firstOrNull()?.trim()?.lowercase() ?: ""
        if (firstLine.isNotEmpty() && isTitleMatch(firstLine, normalizedChapterName)) {
            // Find where this text ends in the original HTML and remove it
            val escapedFirstLine = Regex.escape(content.lines().firstOrNull()?.trim() ?: "")
            if (escapedFirstLine.isNotEmpty()) {
                val lineRegex = """^\s*$escapedFirstLine\s*""".toRegex(RegexOption.IGNORE_CASE)
                return content.replace(lineRegex, "").trimStart()
            }
        }

        // No title found to strip
        return content
    }

    /**
     * Checks whether the given [text] is a title match for [chapterName].
     * Both arguments should already be lowercased.
     */
    fun isTitleMatch(text: String, chapterName: String): Boolean {
        if (text.isEmpty() || chapterName.isEmpty()) return false
        // Exact match
        if (text == chapterName) return true
        // Chapter name contains the text, but only if it covers a significant portion
        if (chapterName.contains(text) && text.length > chapterName.length * 0.5) return true
        // Text contains chapter name
        if (text.contains(chapterName)) return true
        // Check for common chapter patterns
        val chapterPatterns = listOf(
            """chapter\s*\d+""".toRegex(RegexOption.IGNORE_CASE),
            """ch\.?\s*\d+""".toRegex(RegexOption.IGNORE_CASE),
            """episode\s*\d+""".toRegex(RegexOption.IGNORE_CASE),
            """part\s*\d+""".toRegex(RegexOption.IGNORE_CASE),
        )
        return chapterPatterns.any { it.matches(text) && it.containsMatchIn(chapterName) }
    }

    /**
     * Splits [text] into chunks suitable for TTS playback,
     * breaking at sentence boundaries or spaces when possible.
     */
    fun splitTextForTts(text: String, maxLength: Int): List<String> {
        val chunks = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLength) {
                chunks.add(remaining)
                break
            }

            // Find a good break point (sentence end, paragraph, or space)
            var breakPoint = maxLength

            // Try to find sentence end (. ! ?) before maxLength
            val sentenceEnd = remaining.substring(0, maxLength).lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
            if (sentenceEnd > maxLength / 2) {
                breakPoint = sentenceEnd + 1
            } else {
                // Fall back to last space
                val lastSpace = remaining.substring(0, maxLength).lastIndexOf(' ')
                if (lastSpace > maxLength / 2) {
                    breakPoint = lastSpace + 1
                }
            }

            chunks.add(remaining.substring(0, breakPoint).trim())
            remaining = remaining.substring(breakPoint).trim()
        }

        return chunks
    }

    /**
     * Resolves theme colours (background, text) from the user's novel-theme preference.
     *
     * @param activity  The hosting activity — needed for [Activity.getTheme] when `theme == "app"`.
     * @param preferences Reader preferences to read background/font colour overrides.
     * @param theme      The theme key ("app", "dark", "sepia", "black", "grey", "custom", …).
     * @return A [Pair] of `(backgroundColor, textColor)` as packed ARGB [Int]s.
     */
    fun getThemeColors(activity: Activity, preferences: ReaderPreferences, theme: String): Pair<Int, Int> {
        val backgroundColor = preferences.novelBackgroundColor.get()
        val fontColor = preferences.novelFontColor.get()

        return when (theme) {
            "app" -> {
                // Follow app theme — use actual Material theme colors
                val typedValue = android.util.TypedValue()
                val actTheme = activity.theme
                val bgColor = if (actTheme.resolveAttribute(
                        com.google.android.material.R.attr.colorSurface,
                        typedValue,
                        true,
                    )
                ) {
                    typedValue.data
                } else {
                    val nightMode = activity.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                        0xFF121212.toInt()
                    } else {
                        0xFFFFFFFF.toInt()
                    }
                }
                val textColor = if (actTheme.resolveAttribute(
                        com.google.android.material.R.attr.colorOnSurface,
                        typedValue,
                        true,
                    )
                ) {
                    typedValue.data
                } else {
                    val nightMode = activity.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                        0xFFE0E0E0.toInt()
                    } else {
                        0xFF000000.toInt()
                    }
                }
                bgColor to textColor
            }
            "dark" -> 0xFF121212.toInt() to 0xFFE0E0E0.toInt()
            "sepia" -> 0xFFF4ECD8.toInt() to 0xFF5B4636.toInt()
            "black" -> 0xFF000000.toInt() to 0xFFCCCCCC.toInt()
            "grey" -> 0xFF292832.toInt() to 0xFFCCCCCC.toInt()
            "custom" -> {
                val bg = if (backgroundColor != 0) backgroundColor else 0xFFFFFFFF.toInt()
                val text = if (fontColor != 0) fontColor else 0xFF000000.toInt()
                bg to text
            }
            else -> 0xFFFFFFFF.toInt() to 0xFF000000.toInt()
        }
    }

    /**
     * Waits for [page] to finish loading text, triggering the load if still queued.
     *
     * @param tag       Log tag for diagnostics (e.g. "NovelViewer" or "NovelWebViewViewer").
     * @param page      The [ReaderPage] whose text we need.
     * @param loader    The [PageLoader] that knows how to load [page].
     * @param timeoutMs Timeout in milliseconds.
     * @param scope     A [CoroutineScope] used for the fire-and-forget `loadPage` call.
     * @return `true` if the page became ready and contains non-blank text.
     */
    suspend fun awaitPageText(
        tag: String,
        page: ReaderPage,
        loader: PageLoader,
        timeoutMs: Long,
        scope: CoroutineScope,
    ): Boolean {
        // If already loaded and has content, don't trigger a second request.
        if (!page.text.isNullOrBlank() && page.status is Page.State.Ready) {
            logcat(LogPriority.DEBUG) { "$tag: page already ready, text.length=${page.text?.length ?: 0}" }
            return true
        }

        // Trigger loading if still queued.
        // IMPORTANT: loadPage() never returns (suspends forever), so launch in scope
        // and keep the job reference so it gets cancelled with the scope.
        var loadJob: kotlinx.coroutines.Job? = null
        if (page.status is Page.State.Queue) {
            loadJob = scope.launch(Dispatchers.IO) {
                try {
                    loader.loadPage(page)
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Expected when scope is cancelled
                }
            }
        }

        // Wait for statusFlow to emit Ready or Error
        return try {
            val finalState = withTimeout(timeoutMs) {
                page.statusFlow.first { state ->
                    state is Page.State.Ready || state is Page.State.Error
                }
            }

            when (finalState) {
                is Page.State.Ready -> {
                    logcat(LogPriority.DEBUG) { "$tag: page ready, text.length=${page.text?.length ?: 0}" }
                    !page.text.isNullOrBlank()
                }
                is Page.State.Error -> {
                    logcat(LogPriority.ERROR) { "$tag: page error: ${finalState.error.message}" }
                    false
                }
                else -> false
            }
        } finally {
            loadJob?.cancel()
        }
    }

    /**
     * Data class representing a paragraph's position and boundaries in text.
     */
    data class ParagraphInfo(
        val index: Int,           // 0-based paragraph index
        val startChar: Int,       // Character offset where paragraph starts
        val endChar: Int,         // Character offset where paragraph ends (exclusive)
        val text: String,         // Paragraph text (without surrounding whitespace)
    )

    /**
     * Finds all paragraph boundaries in [text] by splitting on empty lines or paragraph tags.
     * Returns a list of [ParagraphInfo] objects with position data for each paragraph.
     */
    fun findParagraphs(text: String): List<ParagraphInfo> {
        val paragraphs = mutableListOf<ParagraphInfo>()

        // Strip HTML tags first to get plain text positions
        val plainText = text.replace(Regex("<[^>]+>"), " ")

        // Split on double newlines, paragraph tags, or significant whitespace
        val lines = plainText.split(Regex("\n\\s*\n|<p[^>]*>|</p>"))
        var charOffset = 0

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                charOffset += line.length + 2  // Account for split pattern
                continue
            }

            // Find the actual position in original text
            val startChar = plainText.indexOf(trimmed, charOffset)
            if (startChar < 0) continue

            val endChar = startChar + trimmed.length

            paragraphs.add(
                ParagraphInfo(
                    index = paragraphs.size,
                    startChar = startChar,
                    endChar = endChar,
                    text = trimmed,
                )
            )
            charOffset = endChar
        }

        return paragraphs
    }

    /**
     * Finds the paragraph index for a given character offset in text.
     * Used to resume TTS from a saved position.
     */
    fun findParagraphAtOffset(text: String, charOffset: Int, paragraphs: List<ParagraphInfo>): Int {
        return paragraphs.indexOfFirst { it.startChar <= charOffset && charOffset < it.endChar }
            .takeIf { it >= 0 } ?: 0
    }

    /**
     * Converts character offset to chunk index for TTS playback.
     * Uses the paragraph/chunk mapping to find which TTS chunk to resume from.
     */
    fun getChunkIndexFromOffset(charOffset: Int, ttsChunks: List<String>): Int {
        var currentOffset = 0
        for ((index, chunk) in ttsChunks.withIndex()) {
            if (currentOffset + chunk.length > charOffset) {
                return index
            }
            currentOffset += chunk.length
        }
        return 0
    }
}
