package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.view.View
import android.webkit.WebView
import eu.kanade.presentation.reader.settings.CodeSnippet
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_DIVIDER_CLASS
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_ID_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TAG_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_OBJECT_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.quoteForJson
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import androidx.core.net.toUri
import java.io.File

internal class NovelWebViewStyler(
    private val activity: ReaderActivity,
    private val preferences: ReaderPreferences,
    private val webView: WebView,
    private val container: View,
    private val evaluateJs: (String) -> Unit,
) {

    data class CustomStylePayload(
        val css: String,
        val hideChapterTitle: Boolean,
        val backgroundColor: Int,
    )

    fun applyScrollbarSettings(target: WebView = webView) {
        target.isVerticalScrollBarEnabled = preferences.novelShowProgressSlider.get()
        target.isHorizontalScrollBarEnabled = false
        target.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        target.isScrollbarFadingEnabled = true
        target.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        target.layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    fun buildPayload(): CustomStylePayload {
        val fontSize = preferences.novelFontSize.get()
        val fontFamily = preferences.novelFontFamily.get()
        val lineHeight = preferences.novelLineHeight.get()
        val marginLeft = preferences.novelMarginLeft.get()
        val marginRight = preferences.novelMarginRight.get()
        val marginTop = preferences.novelMarginTop.get()
        val marginBottom = preferences.novelMarginBottom.get()
        val fontColor = preferences.novelFontColor.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val paragraphIndent = preferences.novelParagraphIndent.get()
        val paragraphSpacing = preferences.novelParagraphSpacing.get()
        val textAlign = preferences.novelTextAlign.get()
        val theme = preferences.novelTheme.get()
        val hideChapterTitle = preferences.novelHideChapterTitle.get()

        val (themeBgColor, themeTextColor) = ThemeUtils.getThemeColors(activity, preferences, theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        val finalTextColor = if (fontColor != 0) fontColor else themeTextColor

        val bgColorHex = ThemeUtils.colorToHex(finalBgColor)
        val textColorHex = ThemeUtils.colorToHex(finalTextColor)

        val customCss = preferences.novelCustomCss.get()
        val useOriginalFonts = preferences.novelUseOriginalFonts.get()

        val cssSnippetsJson = preferences.novelCustomCssSnippets.get()
        val enabledSnippetsCss = try {
            val snippets = Json.decodeFromString<List<CodeSnippet>>(cssSnippetsJson)
            snippets.filter { it.enabled }.joinToString("\n") { it.code }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to parse CSS snippets: ${e.message}" }
            ""
        }

        val (fontFaceDeclaration, effectiveFontFamily) = resolveFontFace(fontFamily, useOriginalFonts)


        val sourceCssPriority = preferences.novelSourceCssPriority.get()
        val styleImportance = if (sourceCssPriority) "" else " !important"
        val fontFamilyLine = if (useOriginalFonts) "" else "font-family: $effectiveFontFamily $styleImportance;"

        val textSelect = if (preferences.novelTextSelectable.get()) "text" else "none"

        val (fontInheritOverride, headingSizeRules) = fontOverrideCss(sourceCssPriority, useOriginalFonts)

        val hideChapterTitleCss = if (hideChapterTitle) {
            "$CHAPTER_TAG_NAME h1:first-of-type, $CHAPTER_TAG_NAME h2:first-of-type, " +
                "$CHAPTER_TAG_NAME h3:first-of-type, $CHAPTER_TAG_NAME h4:first-of-type, " +
                "$CHAPTER_TAG_NAME h5:first-of-type, $CHAPTER_TAG_NAME h6:first-of-type " +
                "{ display: none !important; }"
        } else {
            ""
        }

        val css = """
            $fontFaceDeclaration
            body {
                font-size: ${fontSize}px$styleImportance;
                $fontFamilyLine
                line-height: $lineHeight$styleImportance;
                margin: ${marginTop}px ${marginRight}px ${marginBottom}px ${marginLeft}px$styleImportance;
                color: $textColorHex$styleImportance;
                background-color: $bgColorHex$styleImportance;
                text-align: $textAlign$styleImportance;
                -webkit-user-select: $textSelect$styleImportance;
                user-select: $textSelect$styleImportance;
            }
            p {
                text-indent: ${paragraphIndent}em$styleImportance;
                margin-top: ${paragraphSpacing}em$styleImportance;
                margin-bottom: ${paragraphSpacing}em$styleImportance;
            }
            * {
                color: inherit$styleImportance;
                $fontInheritOverride
            }
            $headingSizeRules
            $hideChapterTitleCss
            $customCss
            $enabledSnippetsCss
        """.trimIndent().replace("\n", " ")

        return CustomStylePayload(
            css = css,
            hideChapterTitle = hideChapterTitle,
            backgroundColor = finalBgColor,
        )
    }

    private fun resolveFontFace(fontFamily: String, useOriginalFonts: Boolean): Pair<String, String> {
        if (useOriginalFonts) return "" to fontFamily
        if (!(fontFamily.startsWith("file://") || fontFamily.startsWith("content://"))) return "" to fontFamily
        return try {
            val fontUri = fontFamily.toUri()
            val inputStream = activity.contentResolver.openInputStream(fontUri)
                ?: return "" to fontFamily
            val fontFile = File(activity.cacheDir, "custom_font.ttf")
            inputStream.use { input -> fontFile.outputStream().use { output -> input.copyTo(output) } }
            val fontUrl = "file://" + fontFile.absolutePath
            val declaration = """
                @font-face {
                    font-family: 'CustomFont';
                    src: url('$fontUrl');
                }
            """.trimIndent()
            declaration to "'CustomFont', sans-serif"
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to load custom font: ${e.message}" }
            "" to fontFamily
        }
    }

    fun injectStyles() {
        val payload = buildPayload()
        webView.setBackgroundColor(payload.backgroundColor)
        container.setBackgroundColor(payload.backgroundColor)
        val js = NovelWebViewJsAssets.loadWith(
            activity,
            "inject-styles.js",
            mapOf(
                "STYLE_ID" to STYLE_ID_CUSTOM,
                "CSS" to quoteForJson(payload.css),
            ),
        )
        evaluateJs(js)
    }

    fun injectScript(buildTsundokuScript: () -> String) {
        evaluateJs(buildTsundokuScript())

        val customJs = preferences.novelCustomJs.get()
        if (customJs.isNotBlank()) evaluateJs(customJs)

        val jsSnippetsJson = preferences.novelCustomJsSnippets.get()
        val enabledSnippetsJs = try {
            val snippets = Json.decodeFromString<List<CodeSnippet>>(jsSnippetsJson)
            snippets.filter { it.enabled }.joinToString("\n") { it.code }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to parse JS snippets: ${e.message}" }
            ""
        }
        if (enabledSnippetsJs.isNotBlank()) evaluateJs(enabledSnippetsJs)
    }

    fun injectNextChapterButton(hasNextChapter: Boolean) {
        if (!hasNextChapter) return
        val js = NovelWebViewJsAssets.loadWith(
            activity,
            "next-chapter-button.js",
            mapOf("BTN_CONTAINER_ID" to ID_NEXT_CHAPTER_BTN_CONTAINER),
        )
        evaluateJs(js)
    }

    fun injectScrollTracking() {
        val autoLoadThreshold = preferences.novelAutoLoadNextChapterAt.get()
        val effectiveThreshold = if (autoLoadThreshold > 0) autoLoadThreshold / 100.0 else 0.95
        val js = NovelWebViewJsAssets.loadWith(
            activity,
            "scroll-tracking.js",
            mapOf(
                "TSUNDOKU_OBJECT_NAME" to TSUNDOKU_OBJECT_NAME,
                "CHAPTER_DIVIDER_CLASS" to CHAPTER_DIVIDER_CLASS,
                "CHAPTER_ID_ATTR" to CHAPTER_ID_ATTR,
                "INFINITE_SCROLL_ENABLED" to preferences.novelInfiniteScroll.get().toString(),
                "LOAD_THRESHOLD" to effectiveThreshold.toString(),
            ),
        )
        evaluateJs(js)
    }

    companion object {
        const val STYLE_ID_CUSTOM = "tsundoku-custom-style"
        const val ID_NEXT_CHAPTER_BTN_CONTAINER = "next-chapter-btn-container"

        internal fun fontOverrideCss(sourceCssPriority: Boolean, useOriginalFonts: Boolean): Pair<String, String> {
            if (sourceCssPriority) return "" to ""
            val ffInherit = if (useOriginalFonts) "" else " font-family: inherit !important;"
            val starOverride = "font-size: inherit !important;$ffInherit"
            val headings = "h1 { font-size: 2em !important; } " +
                "h2 { font-size: 1.5em !important; } " +
                "h3 { font-size: 1.17em !important; } " +
                "h4 { font-size: 1em !important; } " +
                "h5 { font-size: 0.83em !important; } " +
                "h6 { font-size: 0.67em !important; }"
            return starOverride to headings
        }
    }
}
