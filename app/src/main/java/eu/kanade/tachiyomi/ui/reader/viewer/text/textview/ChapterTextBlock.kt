package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Holds a chapter's text as a column of chunk TextViews instead of one giant view.
 * A single TextView makes StaticLayout size, span lookups and selection hit-testing
 * O(chapter); large txt/md chapters caused constant GC and multi-second touch handling.
 * Chunked views keep each layout small so all of that is O(chunk).
 */
internal class ChapterTextBlock(
    context: Context,
    private val createChunkView: () -> TextView,
) {

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    val chunkViews = mutableListOf<TextView>()

    /** Start offset of each chunk view's text within [fullText]. */
    var chunkStarts = IntArray(0)

    /** Concatenation of all chunk texts; the text TTS and offset mapping operate on. */
    var fullText: String? = null

    /**
     * Bumped each time a render of this block starts. A render coroutine captures the
     * value and bails if it changes, so an overlapping re-render (translation finish,
     * reload) never races view mutations against a stale one.
     */
    var renderToken: Int = 0

    private var placeholderView: TextView? = null

    fun ensureChunkCount(count: Int) {
        hidePlaceholder()
        while (chunkViews.size < count) {
            val view = createChunkView()
            chunkViews.add(view)
            container.addView(view)
        }
        while (chunkViews.size > count) {
            val view = chunkViews.removeAt(chunkViews.size - 1)
            container.removeView(view)
        }
    }

    fun showPlaceholder(text: CharSequence) {
        val view = placeholderView ?: createChunkView().apply {
            gravity = Gravity.CENTER
            placeholderView = this
            container.addView(this, 0)
        }
        view.text = text
    }

    fun hidePlaceholder() {
        placeholderView?.let { container.removeView(it) }
        placeholderView = null
    }

    fun hasSelection(): Boolean = chunkViews.any { it.hasSelection() }

    fun selectedText(): String? {
        val view = chunkViews.firstOrNull { it.hasSelection() } ?: return null
        val start = view.selectionStart
        val end = view.selectionEnd
        if (start < 0 || end < 0 || start >= end) return null
        return view.text.subSequence(start, end).toString()
    }

    fun clearSelections() {
        chunkViews.forEach { NovelTextRenderer.clearTextViewSelection(it) }
    }

    /** Chunk view whose text contains the ImageSpan backed by [drawable]. */
    fun chunkViewFor(drawable: Drawable): TextView? = chunkViews.firstOrNull { view ->
        (view.text as? Spanned)
            ?.getSpans(0, view.text.length, ImageSpan::class.java)
            ?.any { it.drawable === drawable } == true
    }
}
