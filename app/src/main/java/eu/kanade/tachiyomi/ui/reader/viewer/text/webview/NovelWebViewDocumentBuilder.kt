@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ProcessedContent
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_DIVIDER_CLASS
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_ID_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_NUMBER_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_PATH_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TAG_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TITLE_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_URL_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_CHAPTER_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.htmlAttributeEscape
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.quoteForJson

internal object NovelWebViewDocumentBuilder {

    data class DocumentInput(
        val processed: ProcessedContent,
        val chapter: ReaderChapter?,
        val style: NovelWebViewStyler.CustomStylePayload,
        val themeTokens: ThemeUtils.ThemeTokens,
        val tsundokuScript: String,
        val infiniteScrollEnabled: Boolean,
        val blockMedia: Boolean,
    )

    fun assemble(input: DocumentInput): String {
        val chapterModel = input.chapter?.chapter
        val chapterId = chapterModel?.id ?: -1L
        val chapterName = chapterModel?.name.orEmpty()
        val chapterNumber = chapterModel?.chapter_number ?: -1f
        val chapterPath = chapterModel?.url.orEmpty()

        val chapterDivider = buildChapterDivider(chapterId, chapterName, chapterNumber, chapterPath, input)
        val (chapterWrapperStart, chapterWrapperEnd) = buildChapterWrapper(
            chapterId,
            chapterName,
            chapterNumber,
            chapterPath,
            input,
        )

        val mediaBlockCss = if (input.blockMedia) {
            "img, video, audio, source, svg, image { display: none !important; }"
        } else {
            ""
        }

        val finalContent = if (input.processed.isPlainText) {
            """
                <pre class="$PLAIN_TEXT_CLASS" $ATTR_DATA_PLAIN_TEXT="1" style="white-space: pre-wrap; word-break: break-word; overflow-wrap: anywhere; margin: 0;"></pre>
                <script>
                    document.querySelector('.$PLAIN_TEXT_CLASS').textContent = ${quoteForJson(input.processed.text)};
                </script>
            """.trimIndent()
        } else {
            extractBodyOrFallback(input.processed.text)
        }

        val chapterDividerCss = if (input.infiniteScrollEnabled) {
            """.tsundoku-chapter-divider {
                        height: 1px;
                        margin: 32px auto;
                        padding: 0;
                        border: none;
                        border-top: 1px solid currentColor;
                        opacity: 0.4;
                        width: 60%;
                    }"""
        } else {
            ""
        }

        val escapedInitialStyle = input.style.css.escapeForStyleTag()
        val hideHeadingCss = if (input.style.hideChapterTitle) {
            "$CHAPTER_TAG_NAME h1:first-of-type, $CHAPTER_TAG_NAME h2:first-of-type, " +
                "$CHAPTER_TAG_NAME h3:first-of-type, $CHAPTER_TAG_NAME h4:first-of-type, " +
                "$CHAPTER_TAG_NAME h5:first-of-type, $CHAPTER_TAG_NAME h6:first-of-type " +
                "{ display: none !important; }"
        } else {
            ""
        }

        val escapedThemeCss = input.themeTokens.cssVariables.escapeForStyleTag()
        val escapedThemeJson = input.themeTokens.jsObject
            .replace("\\", "\\\\")
            .replace("</script>", "<\\/script>")
            .replace("</Script>", "<\\/Script>")
            .replace("</SCRIPT>", "<\\/SCRIPT>")
        val themeExposureScript = "window.TsundokuTheme = $escapedThemeJson;"

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    $escapedThemeCss
                    $chapterDividerCss
                    tsundoku-chapter {
                        display: block;
                    }
                    img {
                        max-width: 100%;
                        height: auto;
                        display: block;
                        margin: 8px auto;
                        min-height: 100px;
                        background: rgba(150, 150, 150, 0.2) url('data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 50 50"><circle cx="25" cy="25" r="20" fill="none" stroke="%23888" stroke-width="5" stroke-dasharray="31.4 31.4"><animateTransform attributeName="transform" type="rotate" from="0 25 25" to="360 25 25" dur="1s" repeatCount="indefinite"/></circle></svg>') no-repeat center center;
                    }
                    video {
                        max-width: 100%;
                        height: auto;
                    }
                    $hideHeadingCss
                    $mediaBlockCss
                </style>
                <style id="tsundoku-custom-style">$escapedInitialStyle</style>
                <script>${input.tsundokuScript}</script>
                <script>$themeExposureScript</script>
            </head>
            <body>
                $chapterDivider
                $chapterWrapperStart
                $finalContent
                $chapterWrapperEnd
            </body>
            </html>
        """.trimIndent()
    }

    @Suppress("ktlint:standard:max-line-length")
    private fun buildChapterDivider(
        chapterId: Long,
        chapterName: String,
        chapterNumber: Float,
        chapterPath: String,
        input: DocumentInput,
    ): String {
        if (chapterId == -1L || !input.infiniteScrollEnabled) return ""
        val absoluteUrl = NovelWebViewChapterMeta
            .toAbsoluteChapterUrl(chapterPath, input.chapter?.chapter?.url)
            .htmlAttributeEscape()
        val name = chapterName.htmlAttributeEscape()
        val path = chapterPath.htmlAttributeEscape()
        // visibility:hidden (not display:none) so the first chapter's boundary marker still
        // generates a layout box: getBoundingClientRect().top on a display:none element is always 0,
        // which made updateChapterBoundaries record startOffset = scrollY (the scroll position at
        // requery time) instead of the chapter's true top, zeroing progress and misattributing
        // scroll to the wrong chapter whenever a reflow re-queried mid-scroll.
        return """<div class="$CHAPTER_DIVIDER_CLASS" $CHAPTER_ID_ATTR="$chapterId" $CHAPTER_TITLE_ATTR="$name" $CHAPTER_NUMBER_ATTR="$chapterNumber" $CHAPTER_PATH_ATTR="$path" $CHAPTER_URL_ATTR="$absoluteUrl" style="visibility:hidden;height:0;margin:0;padding:0;border:none;"></div>"""
    }

    private fun buildChapterWrapper(
        chapterId: Long,
        chapterName: String,
        chapterNumber: Float,
        chapterPath: String,
        input: DocumentInput,
    ): Pair<String, String> {
        if (chapterId == -1L) return "" to ""
        val absoluteUrl = NovelWebViewChapterMeta
            .toAbsoluteChapterUrl(chapterPath, input.chapter?.chapter?.url)
            .htmlAttributeEscape()
        val name = chapterName.htmlAttributeEscape()
        val path = chapterPath.htmlAttributeEscape()

        @Suppress("ktlint:standard:max-line-length")
        val start = """<$CHAPTER_TAG_NAME $CHAPTER_ID_ATTR="$chapterId" $CHAPTER_TITLE_ATTR="$name" $CHAPTER_NUMBER_ATTR="$chapterNumber" $CHAPTER_PATH_ATTR="$path" $CHAPTER_URL_ATTR="$absoluteUrl" $TSUNDOKU_CHAPTER_ATTR="1">"""
        val end = "</$CHAPTER_TAG_NAME>"
        return start to end
    }

    internal fun extractBodyOrFallback(html: String): String = try {
        val doc = org.jsoup.Jsoup.parse(html)
        val body = doc.body()
        when {
            body.hasText() -> body.html()
            body.children().isNotEmpty() -> body.html()
            else -> html
        }
    } catch (_: Exception) {
        html
    }

    internal fun String.escapeForStyleTag(): String =
        replace(Regex("</style>", RegexOption.IGNORE_CASE)) { "<\\/" + it.value.substring(2) }

    const val PLAIN_TEXT_CLASS = "tsundoku-plain-text"
    const val ATTR_DATA_PLAIN_TEXT = "data-tsundoku-plain-text"
}
