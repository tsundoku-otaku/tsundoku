package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.HtmlUtils
import java.net.URI

internal object NovelWebViewChapterMeta {

    const val CHAPTER_TAG_NAME = "tsundoku-chapter"
    const val CHAPTER_ID_ATTR = "data-chapter-id"
    const val CHAPTER_TITLE_ATTR = "data-chapter-title"
    const val CHAPTER_NUMBER_ATTR = "data-chapter-number"
    const val CHAPTER_PATH_ATTR = "data-chapter-path"
    const val CHAPTER_URL_ATTR = "data-chapter-url"
    const val TSUNDOKU_CHAPTER_ATTR = "data-tsundoku-chapter"
    const val CHAPTER_DIVIDER_CLASS = "tsundoku-chapter-divider"
    const val TSUNDOKU_CHAPTERS_CONTAINER_ID = "tsundoku-chapters-container"

    const val TSUNDOKU_OBJECT_NAME = "Tsundoku"
    const val TSUNDOKU_NOVEL_URL_KEY = "novelUrl"
    const val TSUNDOKU_CURRENT_CHAPTER_KEY = "currentChapter"
    const val TSUNDOKU_CHAPTERS_KEY = "chapters"
    const val TSUNDOKU_IS_EDIT_MODE_KEY = "isEditMode"
    const val TSUNDOKU_IS_INF_SCROLL_KEY = "isInfScroll"
    const val TSUNDOKU_TEXT_SELECTION_BLOCKED_KEY = "textSelectionBlocked"
    const val TSUNDOKU_FORCED_LOWERCASE_KEY = "forcedLowercase"
    const val TSUNDOKU_MENU_VISIBLE_KEY = "menuVisible"
    const val TSUNDOKU_IMMERSIVE_KEY = "immersive"
    const val TSUNDOKU_TTS_STATE_KEY = "ttsState"
    const val TSUNDOKU_LOADING_CHAPTER_KEY = "loadingChapter"

    // Event names dispatched on `window` so plugins/snippets can subscribe with addEventListener.
    const val EVENT_MENU_VISIBILITY = "tsundoku:menuvisibilitychange"
    const val EVENT_CHAPTER_NAVIGATE = "tsundoku:chapternavigate"
    const val EVENT_CHAPTER_LOADING = "tsundoku:chapterloading"
    const val EVENT_TTS_STATE = "tsundoku:ttsstatechange"

    // Fired page-side (scroll-tracking.js) as reading progress advances, throttled with the slider
    // bridge. Dispatched from JS, not Kotlin, so there is no per-frame bridge hop.
    const val EVENT_PROGRESS = "tsundoku:progresschange"

    // Safe-area CSS custom properties: the reader menu bar heights the page must clear (0 while the
    // menu is hidden). Single source of truth for both the injector and the CSS that reads them.
    const val CSS_VAR_SAFE_TOP = "--tsundoku-safe-top"
    const val CSS_VAR_SAFE_BOTTOM = "--tsundoku-safe-bottom"

    fun String.jsEscape(): String =
        this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("</script>", "<\\/script>")
            .replace("</Script>", "<\\/Script>")
            .replace("</SCRIPT>", "<\\/SCRIPT>")

    fun String.htmlAttributeEscape(): String =
        this.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

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

    fun resolveWebViewBaseUrl(chapterUrl: String?, novelUrl: String?, sourceBaseUrl: String? = null): String? {
        val repaired = HtmlUtils.normalizeUrl(chapterUrl)
        val absoluteChapterUrl = repaired?.trim().takeUnless { it.isNullOrBlank() }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (absoluteChapterUrl != null) return absoluteChapterUrl
        val novel = HtmlUtils.normalizeUrl(novelUrl)?.trim().takeUnless { it.isNullOrBlank() }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (novel != null) return novel

        // Neither url is absolute (common: sources store relative paths). Anchor the WebView base
        // on the source's site so the browser resolves relative asset urls (e.g. /uploads/x.webp)
        // itself, exactly like a normal page load. Prefer the chapter path, then the novel path.
        val base = sourceBaseUrl?.trim().takeUnless { it.isNullOrBlank() }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        val relative = repaired?.trim().takeUnless { it.isNullOrBlank() }
            ?: HtmlUtils.normalizeUrl(novelUrl)?.trim().takeUnless { it.isNullOrBlank() }
            ?: return base
        return try {
            URI(base.trimEnd('/') + "/").resolve(relative.trimStart('/')).toString()
        } catch (_: Exception) {
            base
        }
    }

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

    fun buildChaptersJson(chapters: List<ReaderChapter>, novelUrl: String?): String =
        chapters.joinToString(prefix = "[", postfix = "]") { buildChapterJson(it, novelUrl) }

    data class TsundokuScriptContext(
        val novelUrl: String?,
        val currentChapter: ReaderChapter?,
        val chaptersInOrder: List<ReaderChapter>,
        val isEditingMode: Boolean,
        val isInfiniteScroll: Boolean,
        val textSelectionBlocked: Boolean,
        val forcedLowercase: Boolean,
        val menuVisible: Boolean = false,
        val immersive: Boolean = false,
        val ttsState: String = "stopped",
        val loadingChapter: Boolean = false,
    )

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
            window.$TSUNDOKU_OBJECT_NAME.runtime.$TSUNDOKU_MENU_VISIBLE_KEY = ${context.menuVisible};
            window.$TSUNDOKU_OBJECT_NAME.runtime.$TSUNDOKU_IMMERSIVE_KEY = ${context.immersive};
            window.$TSUNDOKU_OBJECT_NAME.runtime.$TSUNDOKU_TTS_STATE_KEY = ${quoteForJson(context.ttsState)};
            window.$TSUNDOKU_OBJECT_NAME.runtime.$TSUNDOKU_LOADING_CHAPTER_KEY = ${context.loadingChapter};
        """.trimIndent()
    }
}
