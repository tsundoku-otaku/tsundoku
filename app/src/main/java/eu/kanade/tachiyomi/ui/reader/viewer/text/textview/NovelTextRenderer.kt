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
        block: ChapterTextBlock,
        processed: ProcessedContent,
        onTextSet: () -> Unit,
    ) {
        val rawHtmlMode = preferences.novelShowRawHtml.get()
        val plainTextMode = rawHtmlMode || processed.isPlainText

        var processedContent = if (!rawHtmlMode && processed.isPlainText &&
            TranslationHtmlUtils.hasSourceHashTag(processed.text)
        ) {
            TranslationHtmlUtils.extractTextFromHtml(processed.text)
        } else {
            processed.text
        }

        if (!plainTextMode) {
            processedContent = wrapParagraphs(processedContent)
        }

        val paragraphSpacing = if (rawHtmlMode) 0f else preferences.novelParagraphSpacing.get()
        val paragraphIndent = if (rawHtmlMode) 0f else preferences.novelParagraphIndent.get()
        val fontSize = preferences.novelFontSize.get()
        val density = activity.resources.displayMetrics.density
        val blockMedia = preferences.novelBlockMedia.get()

        val token = ++block.renderToken

        scope.launch {
            val imageGetter = if (!blockMedia && !plainTextMode) {
                val widthPx = block.chunkViews.firstOrNull()
                    ?.let { it.width - it.paddingLeft - it.paddingRight }
                    ?.takeIf { it > 0 }
                    ?: activity.resources.displayMetrics.widthPixels
                CoilImageGetter(activity, scope, widthPx, block::chunkViewFor)
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
                SpannableStringBuilder(spanned)
            }

            val spacingPx = (paragraphSpacing * fontSize * density).toInt()
            val indentPx = (paragraphIndent * fontSize * density).toInt()

            val chunks = withContext(Dispatchers.Default) {
                chunkRanges(spannable).map { (start, end) ->
                    SpannableStringBuilder(spannable.subSequence(start, end)).also {
                        if (spacingPx > 0 || indentPx > 0) {
                            applyParagraphSpans(it, spacingPx, indentPx)
                        }
                    }
                }
            }

            if (token != block.renderToken || !block.container.isAttachedToWindow) return@launch
            block.ensureChunkCount(chunks.size)

            if (chunks.isEmpty()) {
                block.chunkStarts = IntArray(0)
                block.fullText = ""
                onTextSet()
                return@launch
            }

            val selectable = preferences.novelTextSelectable.get()
            val params = if (selectable) null else TextViewCompat.getTextMetricsParams(block.chunkViews.first())
            val (precomputed, fullText) = withContext(Dispatchers.Default) {
                params?.let { p -> chunks.map { PrecomputedTextCompat.create(it, p) } } to spannable.toString()
            }

            if (token != block.renderToken || !block.container.isAttachedToWindow) return@launch

            val starts = IntArray(chunks.size)
            var offset = 0
            chunks.forEachIndexed { i, chunk ->
                starts[i] = offset
                offset += chunk.length
            }

            block.clearSelections()
            if (precomputed != null) {
                precomputed.forEachIndexed { i, text ->
                    TextViewCompat.setPrecomputedText(block.chunkViews[i], text)
                }
            } else {
                chunks.forEachIndexed { i, chunk -> block.chunkViews[i].text = chunk }
            }
            block.chunkStarts = starts
            block.fullText = fullText
            imageGetter?.startLoading()
            onTextSet()
        }
    }

    /**
     * Splits at the first paragraph boundary past every CHUNK_TARGET_CHARS so chunk
     * layouts stay small.
     */
    private fun chunkRanges(text: CharSequence): List<Pair<Int, Int>> {
        val length = text.length
        if (length == 0) return emptyList()
        val ranges = ArrayList<Pair<Int, Int>>(length / CHUNK_TARGET_CHARS + 1)
        var start = 0
        while (start < length) {
            var end = (start + CHUNK_TARGET_CHARS).coerceAtMost(length)
            if (end < length) {
                var newline = end
                while (newline < length && text[newline] != '\n') newline++
                end = if (newline < length) newline + 1 else length
            }
            ranges.add(start to end)
            start = end
        }
        return ranges
    }

    private fun wrapParagraphs(html: String): String {
        var content = html.replace(Regex("<p>(?: |&#160;|&nbsp;)+"), "<p>")
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
            val targetWidth = activity.resources.displayMetrics.widthPixels
            doc.select("img").forEach { img ->
                applySrcsetCandidate(img, targetWidth)
                if (img.parent()?.tagName() != "p" && img.parent()?.tagName() != "div") {
                    img.wrap("<p style=\"text-align:center;\"></p>")
                }
            }
            doc.body().html()
        } catch (_: Exception) {
            html
        }
    }

    private fun applySrcsetCandidate(img: org.jsoup.nodes.Element, targetWidth: Int) {
        val srcset = img.attr("srcset").takeIf { it.isNotBlank() } ?: return
        val candidates = srcset.split(',').mapNotNull { entry ->
            val parts = entry.trim().split(Regex("\\s+"), limit = 2)
            val url = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val width = parts.getOrNull(1)?.trim()
                ?.let { Regex("^(\\d+)w$").find(it) }
                ?.groupValues?.get(1)?.toIntOrNull()
            url to width
        }
        if (candidates.isEmpty()) return
        val withWidth = candidates.filter { it.second != null }
        val best = withWidth.filter { it.second!! >= targetWidth }.minByOrNull { it.second!! }
            ?: withWidth.maxByOrNull { it.second!! }
            ?: candidates.first()
        img.attr("src", best.first)
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
        private const val CHUNK_TARGET_CHARS = 6_000

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
