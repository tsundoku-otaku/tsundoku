package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan

/**
 * Custom span for paragraph spacing — adds vertical space after paragraphs
 * that end with a newline.
 */
internal class ParagraphSpacingSpan(private val spacingPx: Int) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        if (end > 0 && end <= text.length && text[end - 1] == '\n') {
            fm.descent += spacingPx
            fm.bottom += spacingPx
        }
    }
}

/**
 * Custom span for paragraph indent — adds a leading margin to the first line
 * of each paragraph it covers.
 */
internal class ParagraphIndentSpan(private val indentPx: Int) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = if (first) indentPx else 0

    override fun drawLeadingMargin(
        c: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout,
    ) {
        // Margin only; no custom drawing.
    }
}

/**
 * Draws a rounded outline around the text covered by this span. Used for the
 * TTS highlight indicator on the active chunk.
 *
 * Rounds the corners only on the line where the span starts (top corners)
 * and the line where the span ends (bottom corners). Middle lines get
 * square corners so consecutive rects abut visually instead of pinching
 * apart at every line break.
 */
internal class RoundedOutlineSpan(
    private val color: Int,
    private val strokeWidthPx: Float = 3f,
    private val cornerRadiusPx: Float = 10f,
) : LineBackgroundSpan {

    private val path = android.graphics.Path()
    private val radii = FloatArray(8)

    override fun drawBackground(
        c: Canvas,
        p: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int,
    ) {
        val spanned = text as? Spanned
        val spanStart = spanned?.getSpanStart(this) ?: start
        val spanEnd = spanned?.getSpanEnd(this) ?: end
        val lineStart = maxOf(start, spanStart)
        val lineEnd = minOf(end, spanEnd)
        if (lineEnd <= lineStart) return

        val isFirstLine = spanStart in start until end
        val isLastLine = spanEnd in start..end
        val topR = if (isFirstLine) cornerRadiusPx else 0f
        val botR = if (isLastLine) cornerRadiusPx else 0f
        radii[0] = topR; radii[1] = topR // top-left
        radii[2] = topR; radii[3] = topR // top-right
        radii[4] = botR; radii[5] = botR // bottom-right
        radii[6] = botR; radii[7] = botR // bottom-left

        val originalStyle = p.style
        val originalColor = p.color
        val originalStroke = p.strokeWidth

        val prefixWidth = p.measureText(text, start, lineStart)
        val contentWidth = p.measureText(text, lineStart, lineEnd)
        val drawLeft = left + prefixWidth - strokeWidthPx
        val drawRight = drawLeft + contentWidth + (strokeWidthPx * 2f)

        path.rewind()
        path.addRoundRect(
            RectF(drawLeft, top.toFloat(), drawRight, bottom.toFloat()),
            radii,
            android.graphics.Path.Direction.CW,
        )

        p.style = Paint.Style.STROKE
        p.color = color
        p.strokeWidth = strokeWidthPx
        c.drawPath(path, p)

        p.style = originalStyle
        p.color = originalColor
        p.strokeWidth = originalStroke
    }
}
