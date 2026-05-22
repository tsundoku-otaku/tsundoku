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

/**
 * Turns a [ProcessedContent] (already through [eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ContentPipeline])
 * into a spannable and sets it on the supplied [TextView].
 *
 * Pulled out of [NovelViewer.setTextViewContent] so the viewer no longer
 * owns the multi-stage render pipeline (raw-HTML mode bypass, plaintext
 * translator-marker extraction, paragraph-tag wrapping, Jsoup image
 * normalization, `Html.fromHtml`, paragraph-spacing/indent spans). The
 * renderer launches its own coroutine on [scope] for the heavy parsing
 * work and posts the final `setText` to the main thread.
 *
 * The viewer supplies two hooks:
 *  - [clearSelection] to dismiss any active text selection on the view
 *    before the `setText` call (without toggling `setTextIsSelectable`,
 *    which fires Android's "Selection cancelled" warning on API 34+).
 *  - [onTextSet] runs after the text is applied; the viewer uses it for
 *    `isTextSet` bookkeeping and the pending-TTS auto-start handoff.
 */
internal class NovelTextRenderer(
    private val activity: ReaderActivity,
    private val preferences: ReaderPreferences,
    private val scope: CoroutineScope,
) {

    /**
     * Render [processed] into [textView]. Returns immediately; the actual
     * `setText` happens on the main thread after the async parse completes.
     */
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

        // If a translator wrapped plain-text source in HTML markers, extract
        // the underlying text for the plain-text renderer.
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
            // CoilImageGetter must be constructed on Main to safely read textView.width.
            val imageGetter = if (!blockMedia) {
                CoilImageGetter(textView, activity, scope)
            } else {
                null
            }

            // All CPU-heavy work — Jsoup normalisation, Html.fromHtml, paragraph
            // span attachment — runs on Default so the Main thread stays free.
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

            // Pre-compute text metrics (word breaks, glyph shaping) off the Main
            // thread. setText with PrecomputedText skips the StaticLayout measurement
            // step on Main, which is the dominant source of UI-thread jank for long
            // chapters.
            val params = TextViewCompat.getTextMetricsParams(textView)
            val precomputed = withContext(Dispatchers.Default) {
                PrecomputedTextCompat.create(spannable, params)
            }

            if (!textView.isAttachedToWindow) return@launch

            clearSelection(textView)
            // The view's selectable/focusable state was already configured in
            // createSelectableTextView(). Do NOT toggle setTextIsSelectable()
            // here — calling setTextIsSelectable(false) on a view that has an
            // active Editor internally calls setText(mText, NORMAL) with
            // mTextIsSelectable = false, which fires Android's
            // "Selection cancelled" warning on API 34+.
            TextViewCompat.setPrecomputedText(textView, precomputed)
            imageGetter?.startLoading()
            onTextSet(textView)
        }
    }

    /**
     * Ensure the HTML body has paragraph tags so paragraph-spacing / indent
     * spans have boundaries. Strips leading non-breaking spaces inside `<p>`
     * to avoid double-spacing once the indent span is applied.
     */
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

    /**
     * Jsoup post-process for HTML chapter content: strip any leftover
     * `<style>` / `<script>` (defense in depth — pipeline should have done
     * this) and wrap unparented `<img>` tags so they render block-level.
     */
    private fun normalizeHtmlForRendering(html: String): String {
        return try {
            val doc = org.jsoup.Jsoup.parse(html)
            doc.select("style, script").remove()
            doc.select("img").forEach { img ->
                if (img.parent()?.tagName() != "p" && img.parent()?.tagName() != "div") {
                    img.wrap("<p style=\"text-align:center;\"></p>")
                }
            }
            doc.body()?.html() ?: html
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
        /**
         * Dismiss any active text selection (action mode / handles) on
         * [textView] without toggling `setTextIsSelectable`. Removes the
         * selection markers and clears focus when the view holds it —
         * no hidden API reflection needed.
         */
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
