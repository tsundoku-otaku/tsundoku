package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.Html
import android.text.SpannableStringBuilder
import android.util.Base64
import android.widget.TextView
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Dimension as CoilDimension
import coil3.size.Size as CoilSize
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    // Debounced re-precompute job: cancelled and rescheduled each time an image finishes
    // loading, so N images completing within the debounce window trigger only one
    // background PrecomputedTextCompat pass instead of N synchronous StaticLayout rebuilds.
    private var recomputeJob: Job? = null

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
     *
     * The [PageLoader] is captured here (Main thread) rather than inside each
     * IO coroutine. This avoids a race where [ReaderViewModel.State.viewerChapters]
     * has advanced to the next chapter by the time the coroutine runs, causing
     * images from the previous chapter to be looked up via the wrong loader.
     */
    fun startLoading() {
        // Capture on Main thread so every IO coroutine uses the correct loader.
        val loader = activity.viewModel.state.value.viewerChapters?.currChapter?.pageLoader
        for ((source, wrapper) in pendingLoads) {
            when {
                source.startsWith("tsundoku-novel-image://") -> loadFromPageLoader(source, wrapper, loader)
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

    private fun loadFromPageLoader(source: String, wrapper: DrawableWrapper, loader: PageLoader?) {
        scope.launch(Dispatchers.IO) {
            try {
                val imagePath = android.net.Uri.decode(source.removePrefix("tsundoku-novel-image://"))
                val stream = loader?.getPageDataStream(imagePath) ?: return@launch
                val bytes = stream.use { it.readBytes() }
                // Two-pass decode: measure dimensions first, then decode at reduced sample
                // size so the in-memory bitmap is no larger than the display width.
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeForWidth(boundsOpts.outWidth)
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    ?: return@launch
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
                val request = ImageRequest.Builder(activity)
                    .data(imageUrl)
                    // Decode at display width; Coil scales proportionally (height = Undefined).
                    .size(CoilSize(CoilDimension.Pixels(capturedContentWidth), CoilDimension.Undefined))
                    .build()
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
        // Redraw immediately with the new image content. Line heights may still reflect
        // the old placeholder bounds until scheduleRecompute() finishes.
        textView.invalidate()
        scheduleRecompute()
    }

    /**
     * Debounced background re-precompute. Cancels any pending job and schedules a new
     * one that fires after a short delay, coalescing multiple concurrent image completions
     * into a single PrecomputedTextCompat pass off the main thread. This avoids the
     * synchronous StaticLayout rebuild that happens with a plain textView.text assignment,
     * while still giving the layout correct line heights once images settle.
     */
    private fun scheduleRecompute() {
        recomputeJob?.cancel()
        recomputeJob = scope.launch(Dispatchers.Main) {
            delay(RECOMPUTE_DEBOUNCE_MS)
            if (!textView.isAttachedToWindow) return@launch
            val snapshot = textView.text
            val params = TextViewCompat.getTextMetricsParams(textView)
            val precomputed = withContext(Dispatchers.Default) {
                PrecomputedTextCompat.create(SpannableStringBuilder(snapshot), params)
            }
            if (!textView.isAttachedToWindow) return@launch
            TextViewCompat.setPrecomputedText(textView, precomputed)
        }
    }

    // Returns the largest power-of-2 inSampleSize such that the decoded width is
    // still >= capturedContentWidth. Keeps bitmaps at display resolution or below.
    private fun sampleSizeForWidth(srcWidth: Int): Int {
        if (srcWidth <= 0 || capturedContentWidth <= 0) return 1
        var s = 1
        while (srcWidth / (s * 2) >= capturedContentWidth) s *= 2
        return s
    }

    companion object {
        private const val RECOMPUTE_DEBOUNCE_MS = 50L
    }
}
