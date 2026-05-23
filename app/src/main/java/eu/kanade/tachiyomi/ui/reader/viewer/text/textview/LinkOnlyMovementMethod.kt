package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import android.text.Spannable
import android.text.method.MovementMethod
import android.text.style.ClickableSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView

// Unlike LinkMovementMethod, never calls Selection.setSelection — avoids "Selection cancelled" on non-selectable TextViews.
internal object LinkOnlyMovementMethod : MovementMethod {
    override fun initialize(widget: TextView, text: Spannable) {}
    override fun onKeyDown(widget: TextView, text: Spannable, keyCode: Int, event: KeyEvent) = false
    override fun onKeyUp(widget: TextView, text: Spannable, keyCode: Int, event: KeyEvent) = false
    override fun onKeyOther(view: TextView, text: Spannable, event: KeyEvent) = false
    override fun onTrackballEvent(widget: TextView, text: Spannable, event: MotionEvent) = false
    override fun onGenericMotionEvent(widget: TextView, text: Spannable, event: MotionEvent) = false
    override fun canSelectArbitrarily() = false
    override fun onTakeFocus(widget: TextView, text: Spannable, direction: Int) {}

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val action = event.action
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_DOWN) return false
        val layout = widget.layout ?: return false
        val x = (event.x.toInt() - widget.totalPaddingLeft + widget.scrollX)
        val y = (event.y.toInt() - widget.totalPaddingTop + widget.scrollY)
        val line = layout.getLineForVertical(y)
        val off = layout.getOffsetForHorizontal(line, x.toFloat())
        val links = buffer.getSpans(off, off, ClickableSpan::class.java)
        if (links.isNotEmpty()) {
            if (action == MotionEvent.ACTION_UP) links[0].onClick(widget)
            return true
        }
        return false
    }
}
