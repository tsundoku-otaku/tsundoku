package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.HtmlUtils
import java.net.URI

/**
 * Chapter-metadata DOM and JavaScript builders for the WebView reader.
 *
 * Owns the contract between the Kotlin side and the page-side JavaScript:
 * - HTML attribute names that the JS uses to identify per-chapter elements.
 * - Keys under the `window.Tsundoku` object that scripts read.
 * - String escaping helpers used when injecting Kotlin values into JS literals
 *   or HTML attributes.
 * - Chapter-wrapper JSON / script builders.
 *
 * All members are pure (`buildTsundokuScript` takes a snapshot via
 * [TsundokuScriptContext] rather than reading viewer state directly), so this
 * file is easy to unit-test.
 */
internal object NovelWebViewChapterMeta {

    // ── Chapter-element DOM contract ───────────────────────────────────────────
    const val CHAPTER_TAG_NAME = "tsundoku-chapter"
    const val CHAPTER_ID_ATTR = "data-chapter-id"
    const val CHAPTER_TITLE_ATTR = "data-chapter-title"
    const val CHAPTER_NUMBER_ATTR = "data-chapter-number"
    const val CHAPTER_PATH_ATTR = "data-chapter-path"
    const val CHAPTER_URL_ATTR = "data-chapter-url"
    const val TSUNDOKU_CHAPTER_ATTR = "data-tsundoku-chapter"
    const val CHAPTER_DIVIDER_CLASS = "tsundoku-chapter-divider"
    const val TSUNDOKU_CHAPTERS_CONTAINER_ID = "tsundoku-chapters-container"

    // ── window.Tsundoku object contract ───────────────────────────────────────
    const val TSUNDOKU_OBJECT_NAME = "Tsundoku"
    const val TSUNDOKU_NOVEL_URL_KEY = "novelUrl"
    const val TSUNDOKU_CURRENT_CHAPTER_KEY = "currentChapter"
    const val TSUNDOKU_CHAPTERS_KEY = "chapters"
    const val TSUNDOKU_IS_EDIT_MODE_KEY = "isEditMode"
    const val TSUNDOKU_IS_INF_SCROLL_KEY = "isInfScroll"
    const val TSUNDOKU_TEXT_SELECTION_BLOCKED_KEY = "textSelectionBlocked"
    const val TSUNDOKU_FORCED_LOWERCASE_KEY = "forcedLowercase"

    /**
     * Escape a string for safe embedding inside a JS double-quoted literal.
     * Also escapes `</script>` sequences which would prematurely close the
     * surrounding `<script>` tag.
     */
    fun String.jsEscape(): String =
        this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("</script>", "<\\/script>")
            .replace("</Script>", "<\\/Script>")
            .replace("</SCRIPT>", "<\\/SCRIPT>")

    /**
     * Escape a string for safe use inside an HTML attribute value.
     */
    fun String.htmlAttributeEscape(): String =
        this.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /**
     * Quote [value] for embedding inside a JS / JSON string literal. Matches
     * `org.json.JSONObject.quote` (escapes `"`, `\`, control chars, and `/`
     * for `</script>` safety) but is a pure Kotlin implementation so it works
     * in JVM unit tests where the Android JSON stub throws.
     */
    fun quoteForJson(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '/' -> sb.append("\\/")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Resolve a chapter path to an absolute URL using the [novelUrl] as base
     * when [chapterPath] is relative. Falls back to the (normalized) chapter
     * path when the novel URL is unusable.
     */
    fun toAbsoluteChapterUrl(chapterPath: String?, novelUrl: String?): String {
        val normalized = HtmlUtils.normalizeUrl(chapterPath).orEmpty().trim()
        if (normalized.isBlank()) return ""
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) return normalized

        val novel = HtmlUtils.normalizeUrl(novelUrl).orEmpty().trim()
        if (!(novel.startsWith("http://") || novel.startsWith("https://"))) return normalized

        return try {
            URI(novel).resolve(normalized).toString()
        } catch (_: Exception) {
            normalized
        }
    }

    /**
     * Returns the base URL passed to `WebView.loadDataWithBaseURL` so relative
     * URLs inside chapter HTML resolve correctly. Prefers the absolute chapter
     * URL; falls back to the novel URL.
     */
    fun resolveWebViewBaseUrl(chapterUrl: String?, novelUrl: String?): String? {
        val repaired = HtmlUtils.normalizeUrl(chapterUrl)
        val absoluteChapterUrl = repaired?.trim().takeUnless { it.isNullOrBlank() }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (absoluteChapterUrl != null) return absoluteChapterUrl
        val novel = HtmlUtils.normalizeUrl(novelUrl)?.trim().takeUnless { it.isNullOrBlank() }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        return novel
    }

    /**
     * Build a single `{id, title, number, path, url}` JS object literal for
     * one chapter. Returns the literal for a null chapter with sentinel values
     * (`id: -1`, blank title/path/url).
     */
    fun buildChapterJson(chapter: ReaderChapter?, novelUrl: String?): String {
        val chapterModel = chapter?.chapter
        val chapterId = chapterModel?.id ?: -1L
        val chapterTitle = quoteForJson(chapterModel?.name.orEmpty())
        val chapterNumber = chapterModel?.chapter_number ?: -1f
        val chapterPath = quoteForJson(chapterModel?.url.orEmpty())
        val chapterUrl = quoteForJson(toAbsoluteChapterUrl(chapterModel?.url, novelUrl))
        return buildString {
            append('{')
            append("\"id\": ").append(chapterId).append(',')
            append("\"title\": ").append(chapterTitle).append(',')
            append("\"number\": ").append(chapterNumber).append(',')
            append("\"path\": ").append(chapterPath).append(',')
            append("\"url\": ").append(chapterUrl)
            append('}')
        }
    }

    /**
     * Build the `Tsundoku.chapters = [...]` JS array literal.
     */
    fun buildChaptersJson(chapters: List<ReaderChapter>, novelUrl: String?): String =
        chapters.joinToString(prefix = "[", postfix = "]") { buildChapterJson(it, novelUrl) }

    /**
     * Snapshot of viewer state needed to render the `window.Tsundoku` JS bootstrap.
     */
    data class TsundokuScriptContext(
        val novelUrl: String?,
        val currentChapter: ReaderChapter?,
        val chaptersInOrder: List<ReaderChapter>,
        val isEditingMode: Boolean,
        val isInfiniteScroll: Boolean,
        val textSelectionBlocked: Boolean,
        val forcedLowercase: Boolean,
    )

    /**
     * Build the global `window.Tsundoku = {...}` JS bootstrap script body.
     */
    fun buildTsundokuScript(context: TsundokuScriptContext): String {
        val novelUrl = quoteForJson(HtmlUtils.normalizeUrl(context.novelUrl).orEmpty())
        val currentChapterJson = buildChapterJson(context.currentChapter, context.novelUrl)
        val chaptersJson = buildChaptersJson(context.chaptersInOrder, context.novelUrl)
        return """
            window.$TSUNDOKU_OBJECT_NAME = window.$TSUNDOKU_OBJECT_NAME || {};
            window.$TSUNDOKU_OBJECT_NAME.$TSUNDOKU_NOVEL_URL_KEY = $novelUrl;
            window.$TSUNDOKU_OBJECT_NAME.$TSUNDOKU_CURRENT_CHAPTER_KEY = $currentChapterJson;
            window.$TSUNDOKU_OBJECT_NAME.$TSUNDOKU_CHAPTERS_KEY = $chaptersJson;
            window.$TSUNDOKU_OBJECT_NAME.runtime = window.$TSUNDOKU_OBJECT_NAME.runtime || {};
            window.$TSUNDOKU_OBJECT_NAME.runtime.$TSUNDOKU_IS_EDIT_MODE_KEY = ${context.isEditingMode};
            window.$TSUNDOKU_OBJECT_NAME.runtime.$TSUNDOKU_IS_INF_SCROLL_KEY = ${context.isInfiniteScroll};
            window.$TSUNDOKU_OBJECT_NAME.runtime.$TSUNDOKU_TEXT_SELECTION_BLOCKED_KEY = ${context.textSelectionBlocked};
            window.$TSUNDOKU_OBJECT_NAME.runtime.$TSUNDOKU_FORCED_LOWERCASE_KEY = ${context.forcedLowercase};
        """.trimIndent()
    }
}
