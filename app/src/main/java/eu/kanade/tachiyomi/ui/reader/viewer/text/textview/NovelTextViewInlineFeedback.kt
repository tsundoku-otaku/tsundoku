package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource

/**
 * Inline UI feedback for [NovelViewer]: the "Loading…" banner shown while a
 * chapter loads, the tappable error banner shown when a load fails, and the
 * indeterminate progress bar at the bottom of the content container.
 *
 * Pulled out of NovelViewer so the viewer doesn't directly manage these
 * transient views. The helper does its own view bookkeeping (one banner at a
 * time, auto-dismiss timers, etc.) — callers only invoke `show*` / `hide*`.
 */
internal class NovelTextViewInlineFeedback(
    private val context: Context,
    private val contentContainer: LinearLayout,
    private val scope: CoroutineScope,
) {

    private var inlineLoadingView: TextView? = null
    private var inlineErrorView: TextView? = null
    private var bottomLoadingIndicator: ProgressBar? = null

    /**
     * Insert a "Loading…" text banner at the top ([isPrepend] = true) or
     * bottom of the content container. Idempotent — repeated calls without an
     * intervening [hideInlineLoading] are a no-op.
     */
    fun showInlineLoading(isPrepend: Boolean) {
        if (inlineLoadingView != null) return
        inlineLoadingView = TextView(context).apply {
            text = context.stringResource(tachiyomi.i18n.MR.strings.loading)
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            setPadding(16, 24, 16, 24)
        }
        val view = inlineLoadingView ?: return
        if (isPrepend) {
            contentContainer.addView(view, 0)
        } else {
            contentContainer.addView(view)
        }
    }

    fun hideInlineLoading() {
        inlineLoadingView?.let { contentContainer.removeView(it) }
        inlineLoadingView = null
    }

    /**
     * Show a tappable error banner with [message]. Auto-dismisses after 8 s.
     * Replaces any existing error banner — only one shows at a time.
     */
    fun showInlineError(message: String, isPrepend: Boolean) {
        inlineErrorView?.let { contentContainer.removeView(it) }

        inlineErrorView = TextView(context).apply {
            text = "$message (tap to dismiss)"
            textSize = 14f
            setTextColor(0xFFFF5252.toInt())
            setBackgroundColor(0x1AFF5252.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 48
                bottomMargin = 48
            }
            setPadding(16, 24, 16, 24)
            setOnClickListener {
                contentContainer.removeView(this)
                inlineErrorView = null
            }
        }

        val view = inlineErrorView ?: return
        if (isPrepend) {
            contentContainer.addView(view, 0)
        } else {
            contentContainer.addView(view)
        }

        scope.launch {
            delay(AUTO_DISMISS_MS)
            if (inlineErrorView == view) {
                contentContainer.removeView(view)
                inlineErrorView = null
            }
        }
    }

    fun showBottomLoadingIndicator() {
        if (bottomLoadingIndicator == null) {
            bottomLoadingIndicator = ProgressBar(context).apply { isIndeterminate = true }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, 16, 0, 16)
        }
        if (bottomLoadingIndicator?.parent == null) {
            contentContainer.addView(bottomLoadingIndicator, params)
        }
        bottomLoadingIndicator?.isVisible = true
    }

    fun hideBottomLoadingIndicator() {
        bottomLoadingIndicator?.isVisible = false
        (bottomLoadingIndicator?.parent as? ViewGroup)?.removeView(bottomLoadingIndicator)
    }

    companion object {
        private const val AUTO_DISMISS_MS = 8_000L
    }
}
