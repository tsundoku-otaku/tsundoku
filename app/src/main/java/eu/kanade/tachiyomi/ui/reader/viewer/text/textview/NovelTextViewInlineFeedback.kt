package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource

internal class NovelTextViewInlineFeedback(
    private val context: Context,
    private val contentContainer: LinearLayout,
    private val scope: CoroutineScope,
) {

    private var inlineLoadingView: TextView? = null
    private var inlineErrorView: TextView? = null

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

    @android.annotation.SuppressLint("SetTextI18n")
    fun showInlineError(message: String, isPrepend: Boolean, onRetry: (() -> Unit)? = null) {
        inlineErrorView?.let { contentContainer.removeView(it) }

        inlineErrorView = TextView(context).apply {
            text = if (onRetry != null) "$message (tap to retry)" else "$message (tap to dismiss)"
            textSize = 14f
            setTextColor(0xFFFF5252.toInt())
            setBackgroundColor(0x1AFF5252)
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
                onRetry?.invoke()
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

    companion object {
        private const val AUTO_DISMISS_MS = 8_000L
    }
}
