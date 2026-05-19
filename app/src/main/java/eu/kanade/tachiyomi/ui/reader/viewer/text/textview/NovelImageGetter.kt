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
 * placeholder immediately and loads the real image asynchronously via Coil 3,
 * base64 decoding, or the [PageLoader] (for downloaded EPUB images served via
 * the `tsundoku-novel-image://` scheme). Loaded images are scaled to fill the
 * TextView's content width.
 */
internal class CoilImageGetter(
    private val textView: TextView,
    private val activity: ReaderActivity,
    private val scope: CoroutineScope,
) : Html.ImageGetter {

    override fun getDrawable(source: String?): Drawable {
        val wrapper = DrawableWrapper()

        val maxWidth = textView.contentWidth()
        val placeholderHeight = (200 * activity.resources.displayMetrics.density).toInt()

        // Force the placeholder onto its own line and prevent images stacking side-by-side.
        val placeholder = ColorDrawable(android.graphics.Color.LTGRAY)
        placeholder.setBounds(0, 0, maxWidth, placeholderHeight)
        wrapper.innerDrawable = placeholder
        wrapper.setBounds(0, 0, maxWidth, placeholderHeight)

        if (source.isNullOrBlank()) return wrapper

        // Base64 data URIs (EPUB downloads encode media inline)
        if (source.startsWith("data:")) {
            decodeBase64InlineImage(source, wrapper)
            return wrapper
        }

        // Custom scheme for chapter-archive-backed images
        if (source.startsWith("tsundoku-novel-image://")) {
            loadFromPageLoader(source, wrapper)
            return wrapper
        }

        // Skip EPUB-internal relative paths (they reference files inside the archive)
        if (!source.startsWith("http://") && !source.startsWith("https://") && !source.startsWith("//")) {
            logcat(LogPriority.DEBUG) { "Skipping non-URL image source: $source" }
            return wrapper
        }

        val imageUrl = if (source.startsWith("//")) "https:$source" else source
        loadFromNetwork(imageUrl, wrapper)
        return wrapper
    }

    private fun textViewContentWidth(): Int {
        val contentWidth = textView.width - textView.paddingLeft - textView.paddingRight
        return if (contentWidth > 0) contentWidth else activity.resources.displayMetrics.widthPixels
    }

    private fun TextView.contentWidth(): Int = textViewContentWidth()

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
        val maxWidth = textView.contentWidth()
        val imgWidth = drawable.intrinsicWidth
        val imgHeight = drawable.intrinsicHeight
        if (imgWidth <= 0 || imgHeight <= 0) return
        val width = maxWidth.coerceAtLeast(1)
        val ratio = width.toFloat() / imgWidth.toFloat()
        val height = (imgHeight * ratio).toInt().coerceAtLeast(1)
        drawable.setBounds(0, 0, width, height)
        wrapper.innerDrawable = drawable
        wrapper.setBounds(0, 0, width, height)
    }

    private fun fitToWidthAndInvalidate(drawable: Drawable, wrapper: DrawableWrapper) {
        fitToWidth(drawable, wrapper)
        // Remove and re-add the span so StaticLayout recomputes line heights without
        // destroying the current text selection.
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
        textView.requestLayout()
    }
}
