package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws an HTML table inline in a TextView as a real, column-aligned, bordered grid, measured with
 * the actual draw [Paint] (so it inherits the reader's font size and text color) and wrapped with
 * [StaticLayout] per cell. No bitmap and no separate view: everything is painted in [draw], so it
 * costs only what its own measure/draw take. Column widths come from each column's widest cell,
 * shrunk proportionally (and wrapped) when the natural width exceeds [maxWidth].
 */
class NovelTableSpan(
    private val rows: List<List<String>>,
    private val hasHeader: Boolean,
    private val maxWidth: Int,
    density: Float,
) : ReplacementSpan() {

    private val cellPadH = (8 * density).roundToInt()
    private val cellPadV = (5 * density).roundToInt()
    private val border = (1 * density).coerceAtLeast(1f)
    private val rowGap = (2 * density).roundToInt()

    private val colCount = rows.maxOf { it.size }

    private var measured = false
    private var colWidths = IntArray(colCount)
    private var cellLayouts: List<List<StaticLayout>> = emptyList()
    private var rowHeights = IntArray(rows.size)
    private var tableWidth = 0
    private var tableHeight = 0

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = false
    }
    private val headerBgPaint = Paint().apply { style = Paint.Style.FILL }

    // Shared cell paints. The StaticLayouts keep these by reference, so updating their color at
    // draw time (from the live, themed run paint) re-colors the already-built layouts. Colors are
    // never baked in at measure time (which would freeze whatever color the precompute pass used).
    private val bodyPaint = TextPaint()
    private val headerPaint = TextPaint()

    private fun cellText(r: Int, c: Int): String = rows.getOrNull(r)?.getOrNull(c).orEmpty()

    private fun measure(paint: Paint) {
        if (measured) return
        val tp = TextPaint(paint)
        bodyPaint.set(tp)
        headerPaint.set(tp)
        headerPaint.isFakeBoldText = true

        // Natural width each column would like = its widest cell line (cells may be multi-line via
        // <br>/<p>, so measure each line, not the whole string).
        val natural = IntArray(colCount)
        for (r in rows.indices) {
            for (c in 0 until colCount) {
                val w = cellText(r, c).lineSequence()
                    .maxOfOrNull { ceil(Layout.getDesiredWidth(it, tp)).toInt() } ?: 0
                if (w > natural[c]) natural[c] = w
            }
        }

        val fixedOverhead = (colCount + 1) * border.toInt() + colCount * 2 * cellPadH
        // Text budget after borders+padding. Always positive so columns stay drawable; the table is
        // hard-capped to maxWidth below, so it can never run off the right edge.
        val availForText = (maxWidth - fixedOverhead).coerceAtLeast(colCount)
        val naturalSum = natural.sum().coerceAtLeast(1)

        val textWidths: IntArray = if (naturalSum <= availForText) {
            natural
        } else {
            // Shrink proportionally to the budget (each column >= 1px), then shave any rounding
            // overflow off the widest columns so the total never exceeds availForText. Long
            // unbreakable cell tokens that still don't fit are clipped to the column at draw time.
            val w = IntArray(colCount) { c ->
                ((natural[c].toLong() * availForText) / naturalSum).toInt().coerceAtLeast(1)
            }
            var over = w.sum() - availForText
            while (over > 0) {
                val widest = w.indices.maxByOrNull { w[it] } ?: break
                if (w[widest] <= 1) break
                w[widest]--
                over--
            }
            w
        }

        colWidths = IntArray(colCount) { c -> textWidths[c] + 2 * cellPadH }

        cellLayouts = rows.indices.map { r ->
            val cellPaint = if (hasHeader && r == 0) headerPaint else bodyPaint
            (0 until colCount).map { c ->
                buildCellLayout(cellText(r, c), textWidths[c].coerceAtLeast(1), cellPaint)
            }
        }
        rowHeights = IntArray(rows.size) { r ->
            (cellLayouts[r].maxOfOrNull { it.height } ?: 0) + 2 * cellPadV
        }

        tableWidth = colWidths.sum() + (colCount + 1) * border.toInt()
        tableHeight = rowHeights.sum() + (rows.size + 1) * border.toInt() + rowGap
        measured = true
    }

    private fun buildCellLayout(text: String, width: Int, paint: TextPaint): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        measure(paint)
        if (fm != null) {
            // Occupy the whole table height on its line; sit on the baseline with no descent.
            fm.ascent = -tableHeight
            fm.top = fm.ascent
            fm.descent = 0
            fm.bottom = 0
        }
        return tableWidth.coerceAtMost(maxWidth)
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        measure(paint)

        // Re-color everything from the live (themed) run paint each draw: cell text via the shared
        // paints the StaticLayouts reference, borders/header-bg derived from the same color.
        val color = paint.color
        bodyPaint.color = color
        headerPaint.color = color
        borderPaint.color = withAlpha(color, 0x55)
        borderPaint.strokeWidth = border
        headerBgPaint.color = withAlpha(color, 0x14)

        val left = x
        val tableTop = top.toFloat()
        val b = border

        // Header row background.
        if (hasHeader && rows.isNotEmpty()) {
            canvas.drawRect(left, tableTop, left + tableWidth, tableTop + rowHeights[0] + b, headerBgPaint)
        }

        // Horizontal lines.
        var lineY = tableTop
        canvas.drawLine(left, lineY, left + tableWidth, lineY, borderPaint)
        for (r in rows.indices) {
            lineY += b + rowHeights[r]
            canvas.drawLine(left, lineY, left + tableWidth, lineY, borderPaint)
        }
        // Vertical lines.
        var lineX = left
        canvas.drawLine(lineX, tableTop, lineX, tableTop + (tableHeight - rowGap), borderPaint)
        for (c in 0 until colCount) {
            lineX += b + colWidths[c]
            canvas.drawLine(lineX, tableTop, lineX, tableTop + (tableHeight - rowGap), borderPaint)
        }

        // Cell text. Each cell is clipped to its box so an over-long unbreakable token can't spill
        // past the column border or off the screen.
        var cellTop = tableTop + b
        for (r in rows.indices) {
            var cellLeft = left + b
            for (c in 0 until colCount) {
                val layout = cellLayouts[r][c]
                canvas.save()
                canvas.clipRect(cellLeft, cellTop, cellLeft + colWidths[c], cellTop + rowHeights[r])
                canvas.translate(cellLeft + cellPadH, cellTop + cellPadV)
                layout.draw(canvas)
                canvas.restore()
                cellLeft += colWidths[c] + b
            }
            cellTop += rowHeights[r] + b
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
