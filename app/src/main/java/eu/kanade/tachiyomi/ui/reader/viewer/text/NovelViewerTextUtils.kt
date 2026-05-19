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
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap
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
    private const val CHAPTER_TITLE_SEARCH_LIMIT = 3000

    // Cache compiled regex replacements keyed by the JSON preference string.
    private val regexReplacementsCache = ConcurrentHashMap<String, List<Pair<Regex, String>>>()
    private val intMapJsonCache = ConcurrentHashMap<String, Map<String, Int>>()

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

    fun normalizeUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isBlank()) return url
        return when {
            value.startsWith("https//") -> "https://" + value.removePrefix("https//")
            value.startsWith("http//") -> "http://" + value.removePrefix("http//")
            else -> value
        }
    }

    fun decodeIntMapPreference(json: String): MutableMap<String, Int> {
        if (json.isBlank()) return mutableMapOf()
        val cached = intMapJsonCache[json]
        if (cached != null) return cached.toMutableMap()

        return try {
            val decoded = kotlinx.serialization.json.Json.decodeFromString<Map<String, Int>>(json)
            intMapJsonCache[json] = decoded
            decoded.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }
    /**
     * Normalizes chapter content to HTML so both WebView and TextView pipelines
     * can share rendering behavior for html/plain-text/markdown chapters.
     */
    fun normalizeContentForHtml(content: String, chapterUrl: String?): String {
        val normalized = content.replace("\u0000", "")
        if (isPlainTextChapter(chapterUrl)) {
            logcat(LogPriority.DEBUG) { "normalizeContentForHtml: PLAIN_TEXT (forced by extension)" }
            return plainTextToHtml(normalized)
        }
        val kind = detectTextKind(chapterUrl, normalized)
        logcat(LogPriority.DEBUG) { "normalizeContentForHtml: $kind len=${normalized.length}" }
        return when (kind) {
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
        return markdown.replaceFirst(frontMatterRegex, "")
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

        val compiled = regexReplacementsCache.computeIfAbsent(rulesJson) { json ->
            try {
                val rules: List<RegexReplacement> = kotlinx.serialization.json.Json.decodeFromString(json)
                rules.mapNotNull { rule ->
                    if (!rule.enabled || rule.pattern.isBlank()) return@mapNotNull null
                    try {
                        if (rule.isRegex) {
                            val options = if (rule.caseSensitive) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
                            Regex(rule.pattern, options) to rule.replacement
                        } else {
                            val escapedPattern = Regex.escape(rule.pattern)
                            val boundedPattern = if (rule.matchWholeWord) {
                                "(?<![\\p{L}\\p{N}_])(?:$escapedPattern)(?![\\p{L}\\p{N}_])"
                            } else {
                                escapedPattern
                            }
                            val options = if (rule.caseSensitive) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
                            Regex(boundedPattern, options) to rule.replacement
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN) { "Failed to compile regex for '${rule.title}': ${e.message}" }
                        null
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Failed to parse regex replacements: ${e.message}" }
                emptyList()
            }
        }

        var result = content
        for ((regex, replacement) in compiled) {
            try {
                result = regex.replace(result, replacement)
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Regex replacement failed: ${e.message}" }
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
        val searchArea = content.take(CHAPTER_TITLE_SEARCH_LIMIT)

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

        // Use Jsoup to parse HTML robustly and extract block-level elements as paragraphs.
        val doc = try {
            Jsoup.parse(text)
        } catch (e: Exception) {
            // Fallback to naive behavior if parsing fails
            null
        }

        val plainText = doc?.text() ?: text.replace(Regex("<[^>]+>"), " ")

        // Prefer block elements when available
        val blocks = doc?.select("p, div, section, article, h1, h2, h3, h4, h5, h6, li")

        if (blocks == null || blocks.isEmpty()) {
            // Fallback: split on blank lines in the plain text
            val lines = plainText.split(Regex("\\n\\s*\\n"))
            var charOffset = 0
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    charOffset += line.length + 2
                    continue
                }
                val startChar = plainText.indexOf(trimmed, charOffset)
                if (startChar < 0) continue
                val endChar = startChar + trimmed.length
                paragraphs.add(ParagraphInfo(paragraphs.size, startChar, endChar, trimmed))
                charOffset = endChar
            }
            return paragraphs
        }

        var charOffset = 0
        for (elem in blocks) {
            val trimmed = elem.text().trim()
            if (trimmed.isEmpty()) continue
            val startChar = plainText.indexOf(trimmed, charOffset)
            if (startChar < 0) continue
            val endChar = startChar + trimmed.length
            paragraphs.add(ParagraphInfo(paragraphs.size, startChar, endChar, trimmed))
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

    /**
     * Data class holding theme token information for CSS variables and JS exposure.
     */
    data class ThemeTokens(
        val cssVariables: String,        // CSS variables declaration (:root { ... })
        val jsObject: String,            // JS object JSON string for window.TsundokuTheme
    )

    /**
     * Generates Material Design and Tsundoku reader theme tokens.
     * Resolves Material 3 color tokens from the activity's theme and reader preferences.
     *
     * @param activity The hosting activity — needed for resolving Material theme attributes.
     * @param preferences Reader preferences for background/font color overrides.
     * @param theme The theme key ("app", "dark", "sepia", "black", "grey", "custom", …).
     * @return A [ThemeTokens] object containing CSS variables and a JS-serializable token map.
     */
    fun getThemeTokens(activity: Activity, preferences: ReaderPreferences, theme: String): ThemeTokens {
        // Get reader background and text colors
        val (readerBgColor, readerTextColor) = getThemeColors(activity, preferences, theme)

        // Convert to hex strings
        val readerBgHex = String.format("#%06X", 0xFFFFFF and readerBgColor)
        val readerTextHex = String.format("#%06X", 0xFFFFFF and readerTextColor)

        // Resolve Material Design 3 system colors from app theme
        val typedValue = android.util.TypedValue()
        val appTheme = activity.theme

        // Material Design 3 system color tokens
        val mdSysColorPrimary = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorPrimary, 0xFF006A6A)
        val mdSysColorOnPrimary = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF)
        val mdSysColorPrimaryContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorPrimaryContainer, 0xFF6FF7F6)
        val mdSysColorOnPrimaryContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF002020)
        val mdSysColorSecondary = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorSecondary, 0xFF006A6A)
        val mdSysColorOnSecondary = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnSecondary, 0xFFFFFFFF)
        val mdSysColorSecondaryContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorSecondaryContainer, 0xFF6FF7F6)
        val mdSysColorOnSecondaryContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF002020)
        val mdSysColorTertiary = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorTertiary, 0xFF006A6A)
        val mdSysColorOnTertiary = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnTertiary, 0xFFFFFFFF)
        val mdSysColorTertiaryContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorTertiaryContainer, 0xFF6FF7F6)
        val mdSysColorOnTertiaryContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnTertiaryContainer, 0xFF002020)
        val mdSysColorError = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorError, 0xFFB3261E)
        val mdSysColorOnError = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnError, 0xFFFFFFFF)
        val mdSysColorErrorContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorErrorContainer, 0xFFF9DEDC)
        val mdSysColorOnErrorContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnErrorContainer, 0xFF410E0B)
        val mdSysColorBackground = resolveColorAttribute(appTheme, typedValue, android.R.attr.colorBackground, readerBgColor.toLong())
        val mdSysColorOnBackground = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnBackground, readerTextColor.toLong())
        val mdSysColorSurface = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorSurface, readerBgColor.toLong())
        val mdSysColorOnSurface = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnSurface, readerTextColor.toLong())
        val mdSysColorSurfaceVariant = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFCCC7C0)
        val mdSysColorOnSurfaceVariant = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454E)
        val mdSysColorOutline = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOutline, 0xFF79747E)

        // Convert to hex strings
        val mdHexColorPrimary = String.format("#%06X", 0xFFFFFF and mdSysColorPrimary)
        val mdHexColorOnPrimary = String.format("#%06X", 0xFFFFFF and mdSysColorOnPrimary)
        val mdHexColorPrimaryContainer = String.format("#%06X", 0xFFFFFF and mdSysColorPrimaryContainer)
        val mdHexColorOnPrimaryContainer = String.format("#%06X", 0xFFFFFF and mdSysColorOnPrimaryContainer)
        val mdHexColorSecondary = String.format("#%06X", 0xFFFFFF and mdSysColorSecondary)
        val mdHexColorOnSecondary = String.format("#%06X", 0xFFFFFF and mdSysColorOnSecondary)
        val mdHexColorSecondaryContainer = String.format("#%06X", 0xFFFFFF and mdSysColorSecondaryContainer)
        val mdHexColorOnSecondaryContainer = String.format("#%06X", 0xFFFFFF and mdSysColorOnSecondaryContainer)
        val mdHexColorTertiary = String.format("#%06X", 0xFFFFFF and mdSysColorTertiary)
        val mdHexColorOnTertiary = String.format("#%06X", 0xFFFFFF and mdSysColorOnTertiary)
        val mdHexColorTertiaryContainer = String.format("#%06X", 0xFFFFFF and mdSysColorTertiaryContainer)
        val mdHexColorOnTertiaryContainer = String.format("#%06X", 0xFFFFFF and mdSysColorOnTertiaryContainer)
        val mdHexColorError = String.format("#%06X", 0xFFFFFF and mdSysColorError)
        val mdHexColorOnError = String.format("#%06X", 0xFFFFFF and mdSysColorOnError)
        val mdHexColorErrorContainer = String.format("#%06X", 0xFFFFFF and mdSysColorErrorContainer)
        val mdHexColorOnErrorContainer = String.format("#%06X", 0xFFFFFF and mdSysColorOnErrorContainer)
        val mdHexColorBackground = String.format("#%06X", 0xFFFFFF and mdSysColorBackground)
        val mdHexColorOnBackground = String.format("#%06X", 0xFFFFFF and mdSysColorOnBackground)
        val mdHexColorSurface = String.format("#%06X", 0xFFFFFF and mdSysColorSurface)
        val mdHexColorOnSurface = String.format("#%06X", 0xFFFFFF and mdSysColorOnSurface)
        val mdHexColorSurfaceVariant = String.format("#%06X", 0xFFFFFF and mdSysColorSurfaceVariant)
        val mdHexColorOnSurfaceVariant = String.format("#%06X", 0xFFFFFF and mdSysColorOnSurfaceVariant)
        val mdHexColorOutline = String.format("#%06X", 0xFFFFFF and mdSysColorOutline)

        // Build CSS variables for :root
        val cssVariables = """
            :root {
                --md-sys-color-primary: $mdHexColorPrimary;
                --md-sys-color-on-primary: $mdHexColorOnPrimary;
                --md-sys-color-primary-container: $mdHexColorPrimaryContainer;
                --md-sys-color-on-primary-container: $mdHexColorOnPrimaryContainer;
                --md-sys-color-secondary: $mdHexColorSecondary;
                --md-sys-color-on-secondary: $mdHexColorOnSecondary;
                --md-sys-color-secondary-container: $mdHexColorSecondaryContainer;
                --md-sys-color-on-secondary-container: $mdHexColorOnSecondaryContainer;
                --md-sys-color-tertiary: $mdHexColorTertiary;
                --md-sys-color-on-tertiary: $mdHexColorOnTertiary;
                --md-sys-color-tertiary-container: $mdHexColorTertiaryContainer;
                --md-sys-color-on-tertiary-container: $mdHexColorOnTertiaryContainer;
                --md-sys-color-error: $mdHexColorError;
                --md-sys-color-on-error: $mdHexColorOnError;
                --md-sys-color-error-container: $mdHexColorErrorContainer;
                --md-sys-color-on-error-container: $mdHexColorOnErrorContainer;
                --md-sys-color-background: $mdHexColorBackground;
                --md-sys-color-on-background: $mdHexColorOnBackground;
                --md-sys-color-surface: $mdHexColorSurface;
                --md-sys-color-on-surface: $mdHexColorOnSurface;
                --md-sys-color-surface-variant: $mdHexColorSurfaceVariant;
                --md-sys-color-on-surface-variant: $mdHexColorOnSurfaceVariant;
                --md-sys-color-outline: $mdHexColorOutline;
                --tsundoku-reader-background: $readerBgHex;
                --tsundoku-reader-text: $readerTextHex;
            }
        """.trimIndent()

        // Build JS object
        val jsObject = """
            {
                "mdSysColorPrimary": "$mdHexColorPrimary",
                "mdSysColorOnPrimary": "$mdHexColorOnPrimary",
                "mdSysColorPrimaryContainer": "$mdHexColorPrimaryContainer",
                "mdSysColorOnPrimaryContainer": "$mdHexColorOnPrimaryContainer",
                "mdSysColorSecondary": "$mdHexColorSecondary",
                "mdSysColorOnSecondary": "$mdHexColorOnSecondary",
                "mdSysColorSecondaryContainer": "$mdHexColorSecondaryContainer",
                "mdSysColorOnSecondaryContainer": "$mdHexColorOnSecondaryContainer",
                "mdSysColorTertiary": "$mdHexColorTertiary",
                "mdSysColorOnTertiary": "$mdHexColorOnTertiary",
                "mdSysColorTertiaryContainer": "$mdHexColorTertiaryContainer",
                "mdSysColorOnTertiaryContainer": "$mdHexColorOnTertiaryContainer",
                "mdSysColorError": "$mdHexColorError",
                "mdSysColorOnError": "$mdHexColorOnError",
                "mdSysColorErrorContainer": "$mdHexColorErrorContainer",
                "mdSysColorOnErrorContainer": "$mdHexColorOnErrorContainer",
                "mdSysColorBackground": "$mdHexColorBackground",
                "mdSysColorOnBackground": "$mdHexColorOnBackground",
                "mdSysColorSurface": "$mdHexColorSurface",
                "mdSysColorOnSurface": "$mdHexColorOnSurface",
                "mdSysColorSurfaceVariant": "$mdHexColorSurfaceVariant",
                "mdSysColorOnSurfaceVariant": "$mdHexColorOnSurfaceVariant",
                "mdSysColorOutline": "$mdHexColorOutline",
                "tsundokuReaderBackground": "$readerBgHex",
                "tsundokuReaderText": "$readerTextHex"
            }
        """.trimIndent()

        return ThemeTokens(cssVariables, jsObject)
    }

    /**
     * Helper function to resolve a Material theme color attribute.
     * Returns the resolved color or a fallback default.
     */
    private fun resolveColorAttribute(
        theme: android.content.res.Resources.Theme,
        typedValue: android.util.TypedValue,
        attr: Int,
        fallback: Long,
    ): Int {
        return if (theme.resolveAttribute(attr, typedValue, true)) {
            typedValue.data
        } else {
            fallback.toInt()
        }
    }
}
