package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toDrawable
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
    override fun getOpacity(): Int = PixelFormat.TRANSPARENT
}

// must be constructed on Main; getDrawable runs on worker thread — do not touch the view.
internal class CoilImageGetter(
    private val textView: TextView,
    private val activity: ReaderActivity,
    private val scope: CoroutineScope,
) : Html.ImageGetter {

    private val capturedContentWidth: Int =
        (textView.width - textView.paddingLeft - textView.paddingRight)
            .takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels

    private var recomputeJob: Job? = null

    private data class PendingLoad(val source: String, val wrapper: DrawableWrapper)
    private val pendingLoads = mutableListOf<PendingLoad>()

    override fun getDrawable(source: String?): Drawable {
        val wrapper = DrawableWrapper()

        val placeholderHeight = (200 * activity.resources.displayMetrics.density).toInt()
        val placeholder = android.graphics.Color.LTGRAY.toDrawable()
        placeholder.setBounds(0, 0, capturedContentWidth, placeholderHeight)
        wrapper.innerDrawable = placeholder
        wrapper.setBounds(0, 0, capturedContentWidth, placeholderHeight)

        if (source.isNullOrBlank()) return wrapper

        if (source.startsWith("data:")) {
            decodeBase64InlineImage(source, wrapper)
            return wrapper
        }

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

    // must run on Main
    fun startLoading() {
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

    private fun decodeBase64InlineImage(source: String, wrapper: DrawableWrapper) {
        try {
            val commaIndex = source.indexOf(',')
            if (commaIndex <= 0) return
            val base64Data = source.substring(commaIndex + 1)
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val drawable = bitmap.toDrawable(activity.resources)
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
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeForWidth(boundsOpts.outWidth)
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    ?: return@launch
                val drawable = bitmap.toDrawable(activity.resources)
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
        textView.invalidate()
        scheduleRecompute()
    }

    private fun scheduleRecompute() {
        recomputeJob?.cancel()
        recomputeJob = scope.launch(Dispatchers.Main) {
            delay(RECOMPUTE_DEBOUNCE_MS)
            if (!textView.isAttachedToWindow) return@launch
            val snapshot = textView.text ?: return@launch
            val params = TextViewCompat.getTextMetricsParams(textView)
            val precomputed = withContext(Dispatchers.Default) {
                PrecomputedTextCompat.create(SpannableStringBuilder(snapshot), params)
            }
            if (!textView.isAttachedToWindow) return@launch
            TextViewCompat.setPrecomputedText(textView, precomputed)
        }
    }

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
