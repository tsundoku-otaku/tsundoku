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

/**
 * Shared utility functions used by both [NovelViewer] (native TextView) and
 * [NovelWebViewViewer] (WebView).  Extracted to eliminate code duplication.
 */
object NovelViewerTextUtils {

    /**
     * Apply user-configured find & replace rules to content.
     * Rules are stored as JSON in the novelRegexReplacements preference.
     * Each enabled rule is applied in order — supports both plain text and regex patterns.
     */
    fun applyRegexReplacements(content: String, preferences: ReaderPreferences): String {
        val rulesJson = preferences.novelRegexReplacements().get()
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
     * Searches within the first ~500 characters for chapter title or name matches.
     */
    fun stripChapterTitle(content: String, chapterName: String): String {
        val normalizedChapterName = chapterName.trim().lowercase()
        // Search within first 500 chars for title (to handle leading whitespace/tags)
        val searchArea = content.take(500)

        // Try to remove first heading (H1-H6) anywhere in search area
        val headingRegex = """<h[1-6][^>]*>(.*?)</h[1-6]>""".toRegex(RegexOption.IGNORE_CASE)
        val headingMatch = headingRegex.find(searchArea)
        if (headingMatch != null) {
            val headingText = headingMatch.groupValues[1].replace(Regex("<[^>]+>"), "").trim().lowercase()
            if (isTitleMatch(headingText, normalizedChapterName)) {
                return content.substring(0, headingMatch.range.first) +
                    content.substring(headingMatch.range.last + 1)
            }
        }

        // Try to remove first strong/b/em tag if it looks like a title
        val strongRegex = """<(strong|b|em)[^>]*>(.*?)</\1>""".toRegex(RegexOption.IGNORE_CASE)
        val strongMatch = strongRegex.find(searchArea)
        if (strongMatch != null) {
            val strongText = strongMatch.groupValues[2].replace(Regex("<[^>]+>"), "").trim().lowercase()
            if (isTitleMatch(strongText, normalizedChapterName)) {
                return content.substring(0, strongMatch.range.first) +
                    content.substring(strongMatch.range.last + 1)
            }
        }

        // Try to remove first paragraph if it matches chapter name
        val paragraphRegex = """<p[^>]*>(.*?)</p>""".toRegex(RegexOption.IGNORE_CASE)
        val pMatch = paragraphRegex.find(searchArea)
        if (pMatch != null) {
            val pText = pMatch.groupValues[1].replace(Regex("<[^>]+>"), "").trim().lowercase()
            if (isTitleMatch(pText, normalizedChapterName)) {
                return content.substring(0, pMatch.range.first) +
                    content.substring(pMatch.range.last + 1)
            }
        }

        // Try to remove first div or span if it matches chapter name
        val divSpanRegex = """<(div|span)[^>]*>(.*?)</\1>""".toRegex(RegexOption.IGNORE_CASE)
        val divMatch = divSpanRegex.find(searchArea)
        if (divMatch != null) {
            val divText = divMatch.groupValues[2].replace(Regex("<[^>]+>"), "").trim().lowercase()
            if (isTitleMatch(divText, normalizedChapterName)) {
                return content.substring(0, divMatch.range.first) +
                    content.substring(divMatch.range.last + 1)
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
        // Chapter name contains the text (e.g., "Chapter 1" contains "1")
        if (chapterName.contains(text) && text.length > 2) return true
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
        val backgroundColor = preferences.novelBackgroundColor().get()
        val fontColor = preferences.novelFontColor().get()

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
}
