package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.Html
import android.text.Spannable
import android.text.style.ImageSpan
import android.util.Base64
import android.widget.TextView
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat

/**
 * [Drawable] wrapper that delegates drawing to an inner drawable. Acts as a
 * placeholder while the real image loads asynchronously — the [innerDrawable]
 * is swapped in once loading completes.
 */
internal class DrawableWrapper : Drawable() {
    var innerDrawable: Drawable? = null

    override fun draw(canvas: Canvas) {
        innerDrawable?.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        innerDrawable?.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        innerDrawable?.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSPARENT", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int = innerDrawable?.opacity ?: PixelFormat.TRANSPARENT
}

/**
 * [Html.ImageGetter] for the native TextView reader. Returns a [DrawableWrapper]
 * placeholder immediately; the actual image load is deferred until [startLoading]
 * is called (after [androidx.core.text.PrecomputedTextCompat.create] completes on
 * a worker thread), which eliminates the data race where concurrent writes to
 * [DrawableWrapper.setBounds] from image-load coroutines and reads from the
 * `PrecomputedTextCompat` measurement pass would produce zero-bounds / blank images.
 *
 * Base64 inline images are decoded synchronously inside [getDrawable] because they
 * are sequential with the `Html.fromHtml` parse and never race with measurement.
 *
 * Must be constructed on the Main thread so [capturedContentWidth] reads
 * [TextView.getWidth] safely. [getDrawable] is called by [Html.fromHtml] on
 * a worker thread — it must not touch the view directly.
 */
internal class CoilImageGetter(
    private val textView: TextView,
    private val activity: ReaderActivity,
    private val scope: CoroutineScope,
) : Html.ImageGetter {

    // Captured at construction (Main thread) — never access textView.width
    // from the worker thread that Html.fromHtml runs on.
    private val capturedContentWidth: Int =
        (textView.width - textView.paddingLeft - textView.paddingRight)
            .takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels

    // Coalesces all concurrent image-load completions into one requestLayout pass.
    // Without this, N images finishing in the same frame causes N full measure cycles.
    private var layoutPending = false

    // Network and archive loads are registered here during Html.fromHtml (Default thread)
    // and launched only after startLoading() is called from the Main thread, once
    // PrecomputedTextCompat.create() has finished reading the Spannable's bounds.
    private data class PendingLoad(val source: String, val wrapper: DrawableWrapper)
    private val pendingLoads = mutableListOf<PendingLoad>()

    override fun getDrawable(source: String?): Drawable {
        val wrapper = DrawableWrapper()

        val placeholderHeight = (200 * activity.resources.displayMetrics.density).toInt()
        val placeholder = ColorDrawable(android.graphics.Color.LTGRAY)
        placeholder.setBounds(0, 0, capturedContentWidth, placeholderHeight)
        wrapper.innerDrawable = placeholder
        wrapper.setBounds(0, 0, capturedContentWidth, placeholderHeight)

        if (source.isNullOrBlank()) return wrapper

        // Inline base64 data URIs: decode synchronously — sequential with the parse,
        // so no race with PrecomputedTextCompat measurement.
        if (source.startsWith("data:")) {
            decodeBase64InlineImage(source, wrapper)
            return wrapper
        }

        // Defer all other loads until startLoading() is called from the Main thread.
        if (source.startsWith("tsundoku-novel-image://") ||
            source.startsWith("http://") || source.startsWith("https://") ||
            source.startsWith("//")
        ) {
            pendingLoads.add(PendingLoad(source, wrapper))
        } else {
            logcat(LogPriority.DEBUG) { "Skipping non-URL image source: $source" }
        }
        return wrapper
    }

    /**
     * Launch all deferred image loads. Must be called on the Main thread after
     * [androidx.core.widget.TextViewCompat.setPrecomputedText] has completed so
     * coroutine writes to [DrawableWrapper.setBounds] no longer race with
     * [androidx.core.text.PrecomputedTextCompat.create] reads.
     */
    fun startLoading() {
        for ((source, wrapper) in pendingLoads) {
            when {
                source.startsWith("tsundoku-novel-image://") -> loadFromPageLoader(source, wrapper)
                source.startsWith("http://") || source.startsWith("https://") -> loadFromNetwork(source, wrapper)
                source.startsWith("//") -> loadFromNetwork("https:$source", wrapper)
            }
        }
        pendingLoads.clear()
    }

    /**
     * Decodes a base64 data URI synchronously on the calling thread (already
     * [Dispatchers.Default] via [Html.fromHtml]). The Spannable is not yet set
     * on the view at this point so no layout invalidation is needed after decode.
     */
    private fun decodeBase64InlineImage(source: String, wrapper: DrawableWrapper) {
        try {
            val commaIndex = source.indexOf(',')
            if (commaIndex <= 0) return
            val base64Data = source.substring(commaIndex + 1)
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val drawable = BitmapDrawable(activity.resources, bitmap)
            fitToWidth(drawable, wrapper)
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "Failed to decode base64 image: ${e.message}" }
        }
    }

    private fun loadFromPageLoader(source: String, wrapper: DrawableWrapper) {
        scope.launch(Dispatchers.IO) {
            try {
                val imagePath = android.net.Uri.decode(source.removePrefix("tsundoku-novel-image://"))
                val loader = activity.viewModel.state.value.viewerChapters?.currChapter?.pageLoader
                val stream = loader?.getPageDataStream(imagePath) ?: return@launch
                val bytes = stream.use { it.readBytes() }
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                val drawable = BitmapDrawable(activity.resources, bitmap)
                withContext(Dispatchers.Main) {
                    fitToWidthAndInvalidate(drawable, wrapper)
                }
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG) { "Failed to load custom novel image: ${e.message}" }
            }
        }
    }

    private fun loadFromNetwork(imageUrl: String, wrapper: DrawableWrapper) {
        scope.launch {
            try {
                val request = ImageRequest.Builder(activity).data(imageUrl).build()
                val result = activity.imageLoader.execute(request)
                val drawable = result.image?.asDrawable(activity.resources) ?: return@launch
                fitToWidthAndInvalidate(drawable, wrapper)
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG) { "Failed to load image in novel reader: $imageUrl - ${e.message}" }
            }
        }
    }

    private fun fitToWidth(drawable: Drawable, wrapper: DrawableWrapper) {
        val imgWidth = drawable.intrinsicWidth
        val imgHeight = drawable.intrinsicHeight
        if (imgWidth <= 0 || imgHeight <= 0) return
        val width = capturedContentWidth.coerceAtLeast(1)
        val ratio = width.toFloat() / imgWidth.toFloat()
        val height = (imgHeight * ratio).toInt().coerceAtLeast(1)
        drawable.setBounds(0, 0, width, height)
        wrapper.innerDrawable = drawable
        wrapper.setBounds(0, 0, width, height)
    }

    private fun fitToWidthAndInvalidate(drawable: Drawable, wrapper: DrawableWrapper) {
        fitToWidth(drawable, wrapper)
        // Remove and re-add the span so StaticLayout/DynamicLayout recomputes line heights.
        val text = textView.text
        if (text is Spannable) {
            val spans = text.getSpans(0, text.length, ImageSpan::class.java)
            val span = spans.firstOrNull { it.drawable === wrapper }
            if (span != null) {
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)
                val flags = text.getSpanFlags(span)
                text.removeSpan(span)
                text.setSpan(span, start, end, flags)
            }
        }
        textView.invalidate()
        // Post a single requestLayout for this frame — if multiple images finish
        // concurrently each one would otherwise trigger a separate full measure pass.
        if (!layoutPending) {
            layoutPending = true
            textView.post {
                layoutPending = false
                textView.requestLayout()
            }
        }
    }
}
