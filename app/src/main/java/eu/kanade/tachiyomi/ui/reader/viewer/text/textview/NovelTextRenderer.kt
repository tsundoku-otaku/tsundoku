package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import android.text.Html
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.widget.TextView
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import eu.kanade.tachiyomi.data.translation.TranslationHtmlUtils
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ProcessedContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class NovelTextRenderer(
    private val activity: ReaderActivity,
    private val preferences: ReaderPreferences,
    private val scope: CoroutineScope,
) {

    fun render(
        textView: TextView,
        processed: ProcessedContent,
        clearSelection: (TextView) -> Unit,
        onTextSet: (TextView) -> Unit,
    ) {
        if (preferences.novelShowRawHtml.get()) {
            if (!textView.isAttachedToWindow) return
            clearSelection(textView)
            textView.text = processed.text
            return
        }

        val plainTextMode = processed.isPlainText

        var processedContent = if (plainTextMode && TranslationHtmlUtils.hasSourceHashTag(processed.text)) {
            TranslationHtmlUtils.extractTextFromHtml(processed.text)
        } else {
            processed.text
        }

        if (!plainTextMode) {
            processedContent = wrapParagraphs(processedContent)
        }

        val paragraphSpacing = preferences.novelParagraphSpacing.get()
        val paragraphIndent = preferences.novelParagraphIndent.get()
        val fontSize = preferences.novelFontSize.get()
        val density = activity.resources.displayMetrics.density
        val blockMedia = preferences.novelBlockMedia.get()

        scope.launch {
            val imageGetter = if (!blockMedia) {
                CoilImageGetter(textView, activity, scope)
            } else {
                null
            }

            val spannable = withContext(Dispatchers.Default) {
                val spanned: CharSequence = if (plainTextMode) {
                    SpannableStringBuilder(processedContent)
                } else {
                    val cleanHtmlContent = normalizeHtmlForRendering(processedContent)
                    Html.fromHtml(cleanHtmlContent, Html.FROM_HTML_MODE_LEGACY, imageGetter, null)
                }
                val result = SpannableStringBuilder(spanned)
                val spacingPx = (paragraphSpacing * fontSize * density).toInt()
                val indentPx = (paragraphIndent * fontSize * density).toInt()
                if (spacingPx > 0 || indentPx > 0) {
                    applyParagraphSpans(result, spacingPx, indentPx)
                }
                result
            }

            if (!textView.isAttachedToWindow) return@launch

            val params = TextViewCompat.getTextMetricsParams(textView)
            val precomputed = withContext(Dispatchers.Default) {
                PrecomputedTextCompat.create(spannable, params)
            }

            if (!textView.isAttachedToWindow) return@launch

            clearSelection(textView)
            TextViewCompat.setPrecomputedText(textView, precomputed)
            imageGetter?.startLoading()
            onTextSet(textView)
        }
    }

    private fun wrapParagraphs(html: String): String {
        var content = html.replace(Regex("<p>(?: |&#160;|&nbsp;)+"), "<p>")
        if (!content.contains("<p>", ignoreCase = true)) {
            content = content
                .replace("\n\n", "</p><p>")
                .replace("\r\n\r\n", "</p><p>")
            content = "<p>$content</p>"
        }
        return content
    }

    private fun normalizeHtmlForRendering(html: String): String {
        return try {
            val doc = org.jsoup.Jsoup.parse(html)
            doc.select("style, script").remove()
            doc.select("img").forEach { img ->
                if (img.parent()?.tagName() != "p" && img.parent()?.tagName() != "div") {
                    img.wrap("<p style=\"text-align:center;\"></p>")
                }
            }
            doc.body().html()
        } catch (_: Exception) {
            html
        }
    }

    private fun applyParagraphSpans(spannable: Spannable, spacingPx: Int, indentPx: Int) {
        var i = 0
        var paragraphStart = 0
        while (i < spannable.length) {
            if (spannable[i] == '\n' || i == spannable.length - 1) {
                val paragraphEnd = i + 1
                if (spacingPx > 0 && paragraphEnd <= spannable.length) {
                    spannable.setSpan(
                        ParagraphSpacingSpan(spacingPx),
                        paragraphStart,
                        paragraphEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (indentPx > 0 && paragraphEnd <= spannable.length) {
                    spannable.setSpan(
                        ParagraphIndentSpan(indentPx),
                        paragraphStart,
                        paragraphEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                paragraphStart = paragraphEnd
            }
            i++
        }
    }

    companion object {
        fun clearTextViewSelection(textView: TextView) {
            val text = textView.text
            if (text is Spannable && text.isNotEmpty() &&
                Selection.getSelectionStart(text) >= 0
            ) {
                Selection.removeSelection(text)
            }
            if (textView.isFocused) {
                textView.clearFocus()
            }
        }
    }
}
