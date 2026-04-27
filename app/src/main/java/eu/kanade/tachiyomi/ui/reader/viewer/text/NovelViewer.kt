package eu.kanade.tachiyomi.ui.reader.viewer.text

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.text.LineBreaker
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Html
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.injectLazy
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Custom span for paragraph spacing - adds vertical space after paragraphs
 */
private class ParagraphSpacingSpan(private val spacingPx: Int) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        // Only add spacing after the last line of a paragraph (ends with newline)
        if (end > 0 && end <= text.length && text[end - 1] == '\n') {
            fm.descent += spacingPx
            fm.bottom += spacingPx
        }
    }
}

/**
 * Custom span for paragraph indent - adds leading margin to first line
 */
private class ParagraphIndentSpan(private val indentPx: Int) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int {
        return if (first) indentPx else 0
    }

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
        // No custom drawing needed
    }
}

/**
 * Draws a rounded outline around highlighted text.
 */
private class RoundedOutlineSpan(
    private val color: Int,
    private val strokeWidthPx: Float = 3f,
    private val cornerRadiusPx: Float = 10f,
) : LineBackgroundSpan {
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
        val spanned = text as? android.text.Spanned
        val spanStart = spanned?.getSpanStart(this) ?: start
        val spanEnd = spanned?.getSpanEnd(this) ?: end
        val lineStart = maxOf(start, spanStart)
        val lineEnd = minOf(end, spanEnd)
        if (lineEnd <= lineStart) return

        val originalStyle = p.style
        val originalColor = p.color
        val originalStroke = p.strokeWidth

        val prefixWidth = p.measureText(text, start, lineStart)
        val contentWidth = p.measureText(text, lineStart, lineEnd)
        val drawLeft = left + prefixWidth - strokeWidthPx
        val drawRight = drawLeft + contentWidth + (strokeWidthPx * 2f)

        p.style = Paint.Style.STROKE
        p.color = color
        p.strokeWidth = strokeWidthPx
        c.drawRoundRect(android.graphics.RectF(drawLeft, top.toFloat(), drawRight, bottom.toFloat()), cornerRadiusPx, cornerRadiusPx, p)

        p.style = originalStyle
        p.color = originalColor
        p.strokeWidth = originalStroke
    }
}

/**
 * Drawable wrapper that delegates drawing to an inner drawable.
 * Used as a placeholder that can be updated asynchronously when images load.
 */
private class DrawableWrapper : Drawable() {
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
 * Html.ImageGetter implementation that loads images asynchronously using Coil 3.
 * Images are scaled to fit within the TextView width while maintaining aspect ratio.
 */
private class CoilImageGetter(
    private val textView: TextView,
    private val activity: ReaderActivity,
    private val scope: CoroutineScope,
) : Html.ImageGetter {

    override fun getDrawable(source: String?): Drawable {
        val wrapper = DrawableWrapper()

        val contentWidth = textView.width - textView.paddingLeft - textView.paddingRight
        val maxWidth = if (contentWidth > 0) {
            contentWidth
        } else {
            activity.resources.displayMetrics.widthPixels
        }

        // Add a temporary loading placeholder taking full width to prevent inline stacking
        val placeholder = android.graphics.drawable.ColorDrawable(android.graphics.Color.LTGRAY)
        val placeholderHeight = (200 * activity.resources.displayMetrics.density).toInt()

        // Use maxWidth to force the placeholder onto its own line and prevent images stacking side-by-side
        placeholder.setBounds(0, 0, maxWidth, placeholderHeight)
        wrapper.innerDrawable = placeholder
        wrapper.setBounds(0, 0, maxWidth, placeholderHeight)

        if (source.isNullOrBlank()) return wrapper

        // Handle base64 data URIs inline (EPUB downloads encode media as base64)
        if (source.startsWith("data:")) {
            try {
                val commaIndex = source.indexOf(',')
                if (commaIndex > 0) {
                    val base64Data = source.substring(commaIndex + 1)
                    val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        val drawable = android.graphics.drawable.BitmapDrawable(activity.resources, bitmap)
                        val contentWidth = textView.width - textView.paddingLeft - textView.paddingRight
                        // Fallback to display width when view not yet laid out (first render)
                        val maxWidth = if (contentWidth > 0) {
                            contentWidth
                        } else {
                            activity.resources.displayMetrics.widthPixels
                        }
                        val imgWidth = drawable.intrinsicWidth
                        val imgHeight = drawable.intrinsicHeight
                        if (imgWidth > 0 && imgHeight > 0) {
                            // Always scale images to fill available width
                            val width = maxWidth.coerceAtLeast(1)
                            val ratio = width.toFloat() / imgWidth.toFloat()
                            val height = (imgHeight * ratio).toInt().coerceAtLeast(1)
                            drawable.setBounds(0, 0, width, height)
                            wrapper.innerDrawable = drawable
                            wrapper.setBounds(0, 0, width, height)
                        }
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG) { "Failed to decode base64 image: ${e.message}" }
            }
            return wrapper
        }

        if (source.startsWith("tsundoku-novel-image://")) {
            scope.launch(Dispatchers.IO) {
                try {
                    val imagePath = android.net.Uri.decode(source.removePrefix("tsundoku-novel-image://"))
                    val loader = activity.viewModel.state.value.viewerChapters?.currChapter?.pageLoader
                    val stream = loader?.getPageDataStream(imagePath) ?: return@launch
                    val bytes = stream.readBytes()
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        val drawable = android.graphics.drawable.BitmapDrawable(activity.resources, bitmap)
                        withContext(Dispatchers.Main) {
                            val contentWidth = textView.width - textView.paddingLeft - textView.paddingRight
                            val maxWidth = if (contentWidth > 0) {
                                contentWidth
                            } else {
                                activity.resources.displayMetrics.widthPixels
                            }
                            val imgWidth = drawable.intrinsicWidth
                            val imgHeight = drawable.intrinsicHeight

                            if (imgWidth > 0 && imgHeight > 0) {
                                val width = maxWidth.coerceAtLeast(1)
                                val ratio = width.toFloat() / imgWidth.toFloat()
                                val height = (imgHeight * ratio).toInt().coerceAtLeast(1)

                                drawable.setBounds(0, 0, width, height)
                                wrapper.innerDrawable = drawable
                                wrapper.setBounds(0, 0, width, height)

                                val text = textView.text
                                if (text is android.text.Spannable) {
                                    val spans = text.getSpans(0, text.length, android.text.style.ImageSpan::class.java)
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
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.DEBUG) { "Failed to load custom novel image: ${e.message}" }
                }
            }
            return wrapper
        }

        // Skip EPUB-internal relative paths (they reference files inside the archive)
        if (!source.startsWith("http://") && !source.startsWith("https://") && !source.startsWith("//")) {
            logcat(LogPriority.DEBUG) { "Skipping non-URL image source: $source" }
            return wrapper
        }

        // Resolve protocol-relative URLs
        val imageUrl = if (source.startsWith("//")) "https:$source" else source

        scope.launch {
            try {
                val request = ImageRequest.Builder(activity)
                    .data(imageUrl)
                    .build()
                val result = activity.imageLoader.execute(request)
                val drawable = result.image?.asDrawable(activity.resources) ?: return@launch

                // Scale image to fill TextView width (or display width before first layout)
                val contentWidth = textView.width - textView.paddingLeft - textView.paddingRight
                val maxWidth = if (contentWidth > 0) {
                    contentWidth
                } else {
                    activity.resources.displayMetrics.widthPixels
                }
                val imgWidth = drawable.intrinsicWidth
                val imgHeight = drawable.intrinsicHeight

                if (imgWidth <= 0 || imgHeight <= 0) return@launch

                // Always fill available width
                val width = maxWidth.coerceAtLeast(1)
                val ratio = width.toFloat() / imgWidth.toFloat()
                val height = (imgHeight * ratio).toInt().coerceAtLeast(1)

                drawable.setBounds(0, 0, width, height)
                wrapper.innerDrawable = drawable
                wrapper.setBounds(0, 0, width, height)

                // Trigger TextView layout update without destroying selection
                // By removing and re-adding the span, we force the StaticLayout to re-calculate line heights
                val text = textView.text
                if (text is android.text.Spannable) {
                    val spans = text.getSpans(0, text.length, android.text.style.ImageSpan::class.java)
                    val span = spans.firstOrNull { it.drawable === wrapper }
                    if (span != null) {
                        val start = text.getSpanStart(span)
                        val end = text.getSpanEnd(span)
                        val flags = text.getSpanFlags(span)
                        text.removeSpan(span)
                        text.setSpan(span, start, end, flags)
                    }
                }

                // Force TextView to re-layout with the loaded image
                textView.invalidate()
                textView.requestLayout()
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG) { "Failed to load image in novel reader: $imageUrl - ${e.message}" }
            }
        }

        return wrapper
    }
}

/**
 * A [android.text.method.MovementMethod] that handles [android.text.style.ClickableSpan]
 * clicks without attempting text selection.
 *
 * Unlike [android.text.method.LinkMovementMethod], this method never calls
 * Selection.extendSelection / Selection.setSelection, so it never triggers the
 * "TextView does not support text selection. Selection cancelled." warning when
 * [TextView.isTextSelectable] is false.
 */
private object LinkOnlyMovementMethod : android.text.method.MovementMethod {
    override fun initialize(widget: TextView, text: android.text.Spannable) {}
    override fun onKeyDown(widget: TextView, text: android.text.Spannable, keyCode: Int, event: KeyEvent) = false
    override fun onKeyUp(widget: TextView, text: android.text.Spannable, keyCode: Int, event: KeyEvent) = false
    override fun onKeyOther(view: TextView, text: android.text.Spannable, event: KeyEvent) = false
    override fun onTrackballEvent(widget: TextView, text: android.text.Spannable, event: MotionEvent) = false
    override fun onGenericMotionEvent(widget: TextView, text: android.text.Spannable, event: MotionEvent) = false
    override fun canSelectArbitrarily() = false
    override fun onTakeFocus(widget: TextView, text: android.text.Spannable, direction: Int) {}
    override fun onTouchEvent(widget: TextView, buffer: android.text.Spannable, event: MotionEvent): Boolean {
        val action = event.action
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_DOWN) return false
        val layout = widget.layout ?: return false
        val x = (event.x.toInt() - widget.totalPaddingLeft + widget.scrollX)
        val y = (event.y.toInt() - widget.totalPaddingTop + widget.scrollY)
        val line = layout.getLineForVertical(y)
        val off = layout.getOffsetForHorizontal(line, x.toFloat())
        val links = buffer.getSpans(off, off, android.text.style.ClickableSpan::class.java)
        if (links.isNotEmpty()) {
            if (action == MotionEvent.ACTION_UP) links[0].onClick(widget)
            return true
        }
        return false
    }
}

/**
 * NovelViewer renders novel content using a native TextView.
 * It supports custom parsing, styling, and pagination.
 */
class NovelViewer(val activity: ReaderActivity) : Viewer, TextToSpeech.OnInitListener {

    private val container = FrameLayout(activity)
    private lateinit var scrollView: NestedScrollView
    private lateinit var contentContainer: LinearLayout
    private var bottomLoadingIndicator: ProgressBar? = null
    private val preferences: ReaderPreferences by injectLazy()
    private val libraryPreferences: tachiyomi.domain.library.service.LibraryPreferences by injectLazy()
    private var tts: TextToSpeech? = null
    private var isAutoScrolling = false
    private var autoScrollJob: Job? = null

    private var navigator: eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation = eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation()



    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var currentPage: ReaderPage? = null
    private var currentChapters: ViewerChapters? = null

    // Track loaded chapters for infinite scroll
    private data class LoadedChapter(
        val chapter: ReaderChapter,
        val textView: TextView,
        val headerView: TextView,
        var separatorView: View? = null,
        var isLoaded: Boolean = false,
    )

    private val loadedChapters = mutableListOf<LoadedChapter>()
    private var isLoadingNext = false
    private var isRestoringScroll = false
    private var currentChapterIndex = 0
    private var disableScrollbarForSession = false

    // Flag to track if next chapter load is from infinite scroll (vs manual navigation)
    private var isInfiniteScrollNavigation = false

    // For tracking scroll position and progress
    private var lastSavedProgress = 0f
    private var progressSaveJob: Job? = null

    // Debounce chapter transitions: require at least 350 ms between chapter index changes
    // to prevent oscillation when the scroll center hovers at a chapter boundary.
    private var lastChapterSwitchTime = 0L

    // Timestamp of the last chapter entry OR cleanup scroll adjustment.
    // Progress and threshold checks are suppressed for CHAPTER_ENTRY_GRACE_MS after this.
    // This handles two cases:
    //   1. Chapter boundary scroll positions compute ~50% before the user has scrolled in.
    //   2. cleanupDistantChapters() adjusts scrollY and fires a scroll event with stale
    //      layout coordinates, causing a ~50% reading.
    private var chapterEntryTime = 0L
    private companion object {
        const val CHAPTER_ENTRY_GRACE_MS = 800L
    }

    private val gestureDetector = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {
            // Increased thresholds for less sensitive swipe detection
            private val SWIPE_THRESHOLD = 150
            private val SWIPE_VELOCITY_THRESHOLD = 200

            // Require horizontal swipe to be significantly more horizontal than vertical
            private val DIRECTION_RATIO = 1.5f

            override fun onDown(e: MotionEvent): Boolean {
                // Return true so we continue receiving gesture events
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (!preferences.novelSwipeNavigation.get()) return false
                if (e1 == null) return false

                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                // Require horizontal swipe to be significantly more horizontal than vertical
                val absDiffX = kotlin.math.abs(diffX)
                val absDiffY = kotlin.math.abs(diffY)

                if (absDiffX > absDiffY * DIRECTION_RATIO) {
                    if (absDiffX > SWIPE_THRESHOLD && kotlin.math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            // Swipe right - go to previous chapter
                            activity.loadPreviousChapter()
                        } else {
                            // Swipe left - go to next chapter
                            activity.loadNextChapter()
                        }
                        return true
                    }
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Never toggle menu while the user has text selected
                if (loadedChapters.any { it.textView.hasSelection() }) return false

                val pos = android.graphics.PointF(
                    e.x / container.width.toFloat(),
                    e.y / container.height.toFloat(),
                )

                when (navigator.getAction(pos)) {
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.MENU -> {
                        activity.toggleMenu()
                    }
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.NEXT,
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.RIGHT -> {
                        scrollView.smoothScrollBy(0, (container.height * 0.8).toInt())
                    }
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.PREV,
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.LEFT -> {
                        scrollView.smoothScrollBy(0, -(container.height * 0.8).toInt())
                    }
                }

                return true
            }
        },
    ).apply {
        // Disable long press handling so TextView can handle text selection
        setIsLongpressEnabled(false)
    }

    init {
        initViews()
        container.addView(scrollView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        // Defer TTS initialization until actually needed to avoid "not bound" errors
        // TTS will be initialized lazily when startTts() is called
        observePreferences()
        setupScrollListener()
    }

    private fun initViews() {
        scrollView = object : NestedScrollView(activity) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(ev)
                return super.dispatchTouchEvent(ev)
            }

            override fun draw(canvas: Canvas) {
                try {
                    super.draw(canvas)
                } catch (e: NullPointerException) {

                        disableScrollbarForSession = true
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                         runCatching { super.draw(canvas) }
                }
            }
        }.apply {
            isFillViewport = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            isScrollbarFadingEnabled = false
            isHorizontalScrollBarEnabled = false
        }

        contentContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        scrollView.addView(contentContainer)
        applyNovelScrollbarSettings()

        // Allow descendants to receive focus so TextView text selection works.
        // The reader container typically sets FOCUS_BLOCK_DESCENDANTS which prevents
        // the TextView's Editor from initializing properly for selection.
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                (container.parent as? ViewGroup)?.descendantFocusability =
                    ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    private fun setupScrollListener() {
        scrollView.setOnScrollChangeListener { _: NestedScrollView, _: Int, scrollY: Int, _: Int, _: Int ->
            val child = scrollView.getChildAt(0) ?: return@setOnScrollChangeListener
            val totalHeight = child.height - scrollView.height
            if (totalHeight <= 0) return@setOnScrollChangeListener

            // Track chapter index before update to detect chapter change
            val previousChapterIndex = currentChapterIndex

            // Suppress chapter detection and progress saves for a grace period after
            // chapter entry or view-hierarchy changes (displayChapter / cleanup).
            // Stale textView.bottom coordinates after addView/removeView would cause
            // updateCurrentChapterFromScroll to detect the wrong chapter.
            val inGracePeriod = System.currentTimeMillis() - chapterEntryTime < CHAPTER_ENTRY_GRACE_MS

            // Update current chapter based on scroll position first.
            // Skip when isRestoringScroll (view hierarchy is being modified) or during
            // the grace period (layout coordinates may still be stale).
            if (!isRestoringScroll && !inGracePeriod) {
                updateCurrentChapterFromScroll(scrollY)
            }

            // If chapter changed, skip progress update (onChapterChanged already sent initialProgress)
            val chapterChanged = previousChapterIndex != currentChapterIndex
            if (chapterChanged) return@setOnScrollChangeListener

            // Calculate progress within the current chapter's text only
            val chapterProgress = calculateCurrentChapterProgress(scrollY)

            if (!inGracePeriod) {
                scheduleProgressSave(chapterProgress)
                activity.onNovelProgressChanged(chapterProgress)
            }

            // Check for infinite scroll
            if (!inGracePeriod && preferences.novelInfiniteScroll.get()) {
                val autoLoadAt = preferences.novelAutoLoadNextChapterAt.get()
                val effectiveThreshold = if (autoLoadAt > 0) autoLoadAt / 100f else 0.95f

                val onLastLoaded = currentChapterIndex == (loadedChapters.size - 1).coerceAtLeast(0)
                if (!isRestoringScroll && chapterProgress >= effectiveThreshold && !isLoadingNext && onLastLoaded) {
                    logcat(LogPriority.DEBUG) {
                        "NovelViewer: scroll threshold hit (progress=$chapterProgress >= $effectiveThreshold, currentIdx=$currentChapterIndex, loadedCount=${loadedChapters.size})"
                    }
                    loadNextChapterIfAvailable()
                }
            }
        }
    }

    /**
     * Calculates progress within the current chapter using only the text view bounds.
     * The header and separator are excluded so that entering a chapter always reads 0%
     *
     * When the chapter text fits entirely within the viewport, returns 1.0 immediately
     * since the user can see all content without scrolling.
     *
     * When scrollY < textTop  → 0%  (separator / heading still visible above text)
     * When scrollY = textTop  → 0%
     * When scrollY = textTop + scrollableHeight → 100%
     */
    private fun calculateCurrentChapterProgress(scrollY: Int): Float {
        val loaded = loadedChapters.getOrNull(currentChapterIndex) ?: return 0f
        val textTop = loaded.textView.top
        val textBottom = loaded.textView.bottom
        val textHeight = textBottom - textTop

        // Guard: if the textView hasn't been laid out yet, its height will be 0.
        // Returning 1f (100%) here would cause a spurious progress jump; keep last known progress.
        if (textHeight <= 0) return lastSavedProgress.coerceIn(0f, 1f)

        // If the chapter text fits entirely within the viewport, it's 100% visible
        if (textHeight <= scrollView.height) {
            val page = loaded.chapter.pages?.firstOrNull() as? ReaderPage
            return if (shouldAutoMarkShortChapter(page)) 1f else lastSavedProgress.coerceIn(0f, 1f)
        }

        val scrollableHeight = (textHeight - scrollView.height).coerceAtLeast(1)
        val scrollInText = (scrollY - textTop).coerceIn(0, scrollableHeight)
        return (scrollInText.toFloat() / scrollableHeight).coerceIn(0f, 1f)
    }

    private fun scheduleProgressSave(progress: Float) {
        // Convert to integer percentage to avoid excessive saves for sub-percent jitter.
        val intPercent = (progress * 100f).roundToInt().coerceIn(0, 100)
        val lastIntPercent = (lastSavedProgress * 100f).roundToInt().coerceIn(0, 100)
        if (intPercent == lastIntPercent) return

        // Save immediately - every integer-percent change is persisted without debounce
        // so the reader bar stays accurate and chapters are reliably marked as read.
        progressSaveJob?.cancel()
        saveProgress(progress)
        lastSavedProgress = progress
    }

    private fun saveProgress(progress: Float) {
        currentPage?.let { page ->
            val progressValue = (progress * 100f).roundToInt().coerceIn(0, 100)
            activity.saveNovelProgress(page, progressValue)
            logcat(LogPriority.DEBUG) { "NovelViewer: Saving progress $progressValue% for chapter" }
        }
    }

    private fun shouldAutoMarkShortChapter(page: ReaderPage?): Boolean {
        if (!preferences.novelMarkShortChapterAsRead.get()) return false
        val chapter = page?.chapter?.chapter ?: return false
        return !chapter.read && chapter.last_page_read <= 0
    }

    private fun syncShortChapterProgressIfNeeded() {
        val page = currentPage ?: return
        if (!shouldAutoMarkShortChapter(page)) return
        if (page.status != Page.State.Ready || page.text.isNullOrBlank()) return

        val loaded = loadedChapters.getOrNull(currentChapterIndex) ?: return
        val textHeight = loaded.textView.height
        if (textHeight <= 0 || textHeight > scrollView.height) return

        lastSavedProgress = 1f
        saveProgress(1f)
        activity.onNovelProgressChanged(1f)
    }

    private fun updateCurrentChapterFromScroll(scrollY: Int) {
        if (loadedChapters.size <= 1) return

        // Find the FIRST chapter whose text has not yet been completely scrolled past.
        // By using textView.bottom (not headerView.top), the separator belongs to neither chapter:
        // while the separator is visible, scrollY is below prev chapter's text and above next
        // chapter's text → next chapter is detected (progress = 0% since scrollY < textTop).
        for ((index, loaded) in loadedChapters.withIndex()) {
            if (loaded.textView.bottom > scrollY) {
                if (currentChapterIndex != index) {
                    onChapterChanged(oldIndex = currentChapterIndex, newIndex = index)
                }
                break
            }
        }
    }

    private fun onChapterChanged(oldIndex: Int, newIndex: Int) {
        // Minimal guard to prevent re-entrant within the same scroll frame
        val now = System.currentTimeMillis()
        if (now - lastChapterSwitchTime < 50L) return
        lastChapterSwitchTime = now

        currentChapterIndex = newIndex

        val initialProgress = if (newIndex > oldIndex) 0f else 1f
        lastSavedProgress = initialProgress
        chapterEntryTime = System.currentTimeMillis()

        // When moving forward, mark the previous chapter as complete
        if (newIndex > oldIndex) {
            loadedChapters.getOrNull(oldIndex)?.chapter?.pages?.firstOrNull()?.let { page ->
                activity.saveNovelProgress(page, 100)
                logcat(LogPriority.DEBUG) {
                    "NovelViewer: Marking chapter $oldIndex as 100% (moved forward)"
                }
            }
        }

        if (newIndex > oldIndex + 1) {
            for (skipped in (oldIndex + 1) until newIndex) {
                loadedChapters.getOrNull(skipped)?.chapter?.pages?.firstOrNull()?.let { page ->
                    activity.saveNovelProgress(page, 100)
                    logcat(LogPriority.DEBUG) {
                        "NovelViewer: Marking skipped chapter $skipped as 100% (fast scroll)"
                    }
                }
            }
        }

        val loadedChapter = loadedChapters.getOrNull(newIndex) ?: return
        loadedChapter.chapter.pages?.firstOrNull()?.let { page ->
            currentPage = page
            activity.viewModel.setNovelVisibleChapter(loadedChapter.chapter.chapter)
            activity.onPageSelected(page)
            logcat(LogPriority.DEBUG) {
                "NovelViewer: Chapter changed from index $oldIndex to $newIndex (${loadedChapter.chapter.chapter.name})"
            }
            activity.onNovelProgressChanged(initialProgress)
        }
    }

    private fun loadNextChapterIfAvailable() {
        val anchor = loadedChapters.getOrNull(currentChapterIndex)?.chapter
            ?: currentChapters?.currChapter ?: run {
                logcat(LogPriority.ERROR) { "NovelViewer: loadNext failed, no anchor (loadedCount=${loadedChapters.size})" }
                showInlineError("No anchor chapter for infinite scroll", isPrepend = false)
                return
            }

        if (isLoadingNext) {
            logcat(LogPriority.DEBUG) { "NovelViewer: loadNext ignored, already loading" }
            return
        }
        isLoadingNext = true
        logcat(LogPriority.DEBUG) {
            "NovelViewer: loadNext starting from anchor=${anchor.chapter.id}/${anchor.chapter.name}"
        }

        scope.launch {
            try {
                val preparedChapter = activity.viewModel.prepareNextChapterForInfiniteScroll(anchor) ?: run {
                    logcat(LogPriority.WARN) { "NovelViewer: No next chapter after ${anchor.chapter.name}" }
                    showInlineError("No next chapter available", isPrepend = false)
                    return@launch
                }
                logcat(LogPriority.DEBUG) {
                    "NovelViewer: prepared next=${preparedChapter.chapter.id}/${preparedChapter.chapter.name}"
                }

                // Prevent duplicates (e.g., if anchor gets out of sync)
                if (loadedChapters.any { it.chapter.chapter.id == preparedChapter.chapter.id }) {
                    logcat(LogPriority.DEBUG) {
                        "NovelViewer: next chapter ${preparedChapter.chapter.id} already loaded"
                    }
                    return@launch
                }
                val page = preparedChapter.pages?.firstOrNull() as? ReaderPage ?: run {
                    logcat(LogPriority.ERROR) { "NovelViewer: No page in prepared next chapter" }
                    showInlineError("No page in next chapter", isPrepend = false)
                    return@launch
                }
                val loader = page.chapter.pageLoader ?: run {
                    logcat(LogPriority.ERROR) { "NovelViewer: No loader for next chapter" }
                    showInlineError("No loader for next chapter", isPrepend = false)
                    return@launch
                }

                showInlineLoading(isPrepend = false)
                logcat(LogPriority.DEBUG) {
                    "NovelViewer: loading page for next ${preparedChapter.chapter.id}, state=${page.status}"
                }

                val loaded = try {
                    awaitPageText(page = page, loader = loader, timeoutMs = 30_000)
                } catch (e: TimeoutCancellationException) {
                    logcat(LogPriority.ERROR) { "NovelViewer: Timed out loading next chapter page after 30s" }
                    showInlineError("Timeout loading next chapter", isPrepend = false)
                    false
                } catch (e: CancellationException) {
                    // Reader was closed/navigated away; don't surface as an error.
                    logcat(LogPriority.DEBUG) { "NovelViewer: loadNext cancelled" }
                    false
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "NovelViewer: Error loading next chapter page: ${e.message}" }
                    showInlineError("Error: ${e.message ?: "Unknown error"}", isPrepend = false)
                    false
                }

                if (!loaded) return@launch

                // Append seamlessly with no UI interruption.
                logcat(LogPriority.DEBUG) { "NovelViewer: appending chapter ${preparedChapter.chapter.id}" }
                displayChapter(preparedChapter, page)
                logcat(LogPriority.INFO) {
                    "NovelViewer: Successfully appended next chapter ${preparedChapter.chapter.name}"
                }
            } finally {
                hideInlineLoading()
                isLoadingNext = false
            }
        }
    }

    private suspend fun awaitPageText(
        page: ReaderPage,
        loader: eu.kanade.tachiyomi.ui.reader.loader.PageLoader,
        timeoutMs: Long,
    ): Boolean = NovelViewerTextUtils.awaitPageText("NovelViewer", page, loader, timeoutMs, scope)

    private var inlineLoadingView: TextView? = null

    private fun showInlineLoading(isPrepend: Boolean) {
        if (inlineLoadingView != null) return
        inlineLoadingView = TextView(activity).apply {
            text = activity.stringResource(tachiyomi.i18n.MR.strings.loading)
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

    private fun hideInlineLoading() {
        inlineLoadingView?.let { view ->
            contentContainer.removeView(view)
        }
        inlineLoadingView = null
    }

    private var inlineErrorView: android.widget.TextView? = null

    private fun showInlineError(message: String, isPrepend: Boolean) {
        // Remove any existing error
        inlineErrorView?.let { view ->
            contentContainer.removeView(view)
        }

        inlineErrorView = android.widget.TextView(activity).apply {
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

        // Auto-dismiss after 8 seconds
        scope.launch {
            delay(8000)
            if (inlineErrorView == view) {
                contentContainer.removeView(view)
                inlineErrorView = null
            }
        }
    }

    private fun showBottomLoadingIndicator() {
        if (bottomLoadingIndicator == null) {
            bottomLoadingIndicator = ProgressBar(activity).apply {
                isIndeterminate = true
            }
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

    private fun hideBottomLoadingIndicator() {
        bottomLoadingIndicator?.isVisible = false
        (bottomLoadingIndicator?.parent as? ViewGroup)?.removeView(bottomLoadingIndicator)
    }

    private fun reloadContent() {
        activity.runOnUiThread {
            currentChapters?.let {
                contentContainer.removeAllViews()
                loadedChapters.clear()
                currentChapterIndex = 0
                setChapters(it)
            }
        }
    }

    private fun observePreferences() {


        // Observe preference changes and refresh content
        scope.launch {
            merge(
                preferences.novelFontSize.changes(),
                preferences.novelFontFamily.changes(),
                preferences.novelTheme.changes(),
                preferences.novelLineHeight.changes(),
                preferences.novelTextAlign.changes(),
                preferences.novelMarginLeft.changes(),
                preferences.novelMarginRight.changes(),
                preferences.novelMarginTop.changes(),
                preferences.novelMarginBottom.changes(),
                preferences.novelFontColor.changes(),
                preferences.novelBackgroundColor.changes(),
            ).drop(11) // Drop initial emissions from all 11 preferences
                .collect {
                    // Re-display text when preferences change
                    refreshAllChapterStyles()
                }
        }

        scope.launch {
            merge(
                preferences.novelParagraphIndent.changes(),
                preferences.novelParagraphSpacing.changes(),
                preferences.novelShowRawHtml.changes(),
                preferences.novelRegexReplacements.changes(),
                preferences.novelAutoSplitText.changes(),
                preferences.novelAutoSplitWordCount.changes(),
                preferences.novelBlockMedia.changes(),
            ).drop(7)
                .collect {
                    // Reload content to apply new formatting
                    reloadContent()
                }
        }

        // Observe force lowercase preference - reload content to reapply transformation
        scope.launch {
            merge(
                preferences.novelForceTextLowercase.changes(),
                preferences.novelHideChapterTitle.changes(),
            ).drop(2) // Drop initial emissions from both preferences
                .collectLatest {
                    reloadContent()
                }
        }

        scope.launch {
            merge(
                preferences.novelTtsVoice.changes(),
                preferences.novelTtsSpeed.changes(),
                preferences.novelTtsPitch.changes(),
            ).drop(3)
                .collectLatest {
                    if (ttsInitialized) {
                        applyTtsSettings()
                    }
                }
        }

        // Observe infinite scroll toggle - add/remove next chapter button
        scope.launch {
            preferences.novelInfiniteScroll.changes()
                .drop(1)
                .collectLatest { infiniteEnabled ->
                    activity.runOnUiThread {
                        if (infiniteEnabled) {
                            // Remove the next chapter button when switching to infinite scroll
                            contentContainer.findViewWithTag<View>(NEXT_CHAPTER_BUTTON_TAG)?.let {
                                contentContainer.removeView(it)
                            }
                        } else {
                            // Add the next chapter button when switching away from infinite scroll
                            addNextChapterButton()
                        }
                    }
                }
        }

        scope.launch {
            preferences.novelTextSelectable.changes()
                .drop(1)
                .collectLatest {
                    reloadContent()
                }
        }

    }

    private fun applyNovelScrollbarSettings() {
        scrollView.isVerticalScrollBarEnabled = false
        scrollView.isHorizontalScrollBarEnabled = false
        scrollView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        scrollView.isScrollbarFadingEnabled = false
        scrollView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        contentContainer.layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    private fun createSelectableTextView(): TextView {
        return TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            applyTextSelectionPreference(this)

            // Set custom action mode callback for text selection
            if (preferences.novelTextSelectable.get()) {
                customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
                    override fun onCreateActionMode(mode: android.view.ActionMode, menu: Menu): Boolean {
                        // Add "Remember" action
                        val rememberItem = menu.add(Menu.NONE, 1, Menu.NONE, activity.stringResource(TDMR.strings.action_remember))
                        rememberItem.setIcon(android.R.drawable.ic_menu_save)
                        rememberItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        return true
                    }

                    override fun onPrepareActionMode(mode: android.view.ActionMode, menu: Menu): Boolean {
                        return false
                    }

                    override fun onActionItemClicked(mode: android.view.ActionMode, item: MenuItem): Boolean {
                        return when (item.itemId) {
                            1 -> { // Remember action
                                onRememberSelectedText()
                                mode.finish()
                                true
                            }
                            else -> false
                        }
                    }

                    override fun onDestroyActionMode(mode: android.view.ActionMode) {
                        // Action mode finished, selection cleared
                    }
                }
            }
        }
    }

    private fun applyTextSelectionPreference(textView: TextView) {
        val selectable = preferences.novelTextSelectable.get()
        textView.setTextIsSelectable(selectable)
        textView.linksClickable = false
        if (!selectable) {
            textView.movementMethod = LinkOnlyMovementMethod
        } else {
            // Explicitly set the movement method required for text selection
            textView.movementMethod = android.text.method.ArrowKeyMovementMethod.getInstance()
        }
    }

    private fun refreshAllChapterStyles() {
        loadedChapters.forEach { loaded ->
            if (loaded.isLoaded) {
                applyTextViewStyles(loaded.textView)
            }
        }
        applyBackgroundColor()
    }

    private var ttsInitialized = false
    private var isTtsAutoPlay = false // Track if TTS should auto-continue to next chapter
    private var ttsPaused = false
    private var ttsChunks: List<String> = emptyList()
    private var ttsChunkParagraphIndexes: List<Int> = emptyList()
    private var ttsCurrentParagraphs: List<NovelViewerTextUtils.ParagraphInfo> = emptyList()

    private enum class TtsStartRequest {
        NORMAL,
        VIEWPORT,
    }

    private var pendingTtsStartRequest: TtsStartRequest? = null

    @Volatile private var ttsCurrentChunkIndex = 0
    private var ttsResumeChunkIndex: Int = 0 // Track which chunk to resume from after pause
    private var ttsViewportParagraphIndex: Int = 0
    private var hasViewportStartOverride: Boolean = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsInitialized = true
            // Apply TTS settings from preferences
            applyTtsSettings()
            // Set up utterance progress listener for auto-continue
            setupTtsListener()
            pendingTtsStartRequest?.let { request ->
                pendingTtsStartRequest = null
                activity.runOnUiThread {
                    when (request) {
                        TtsStartRequest.NORMAL -> startTts()
                        TtsStartRequest.VIEWPORT -> startTtsFromViewport()
                    }
                }
            }
        } else {
            logcat(LogPriority.ERROR) { "TTS initialization failed with status: $status" }
            ttsInitialized = false
        }
    }

    private fun setupTtsListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // Track which chunk is currently speaking
                utteranceId?.removePrefix("tts_utterance_")?.toIntOrNull()?.let { chunkIndex ->
                    ttsCurrentChunkIndex = chunkIndex
                    // Apply highlighting to current chunk if enabled
                    if (preferences.novelTtsEnableHighlight.get()) {
                        applyTtsHighlight(chunkIndex)
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                // Advance chunk index
                val finishedIndex = utteranceId?.removePrefix("tts_utterance_")?.toIntOrNull() ?: -1
                val isLastChunk = finishedIndex >= ttsChunks.size - 1

                // Check if this was the last chunk and auto-play is enabled
                if (isLastChunk && isTtsAutoPlay && preferences.novelTtsAutoNextChapter.get()) {
                    // Check if we've finished all chunks for current chapter
                    activity.runOnUiThread {
                        // Small delay to ensure speech is fully done
                        scope.launch {
                            delay(500)
                            if (!isTtsSpeaking()) {
                                // Save progress before moving to next chapter
                                saveTtsProgress()
                                // Load and play next chapter
                                loadNextChapterForTts()
                            }
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                logcat(LogPriority.ERROR) { "TTS error on utterance: $utteranceId" }
            }
        })
    }

    /**
     * Applies visual highlighting to the chunk currently being read by TTS.
     */
    private fun applyTtsHighlight(chunkIndex: Int) {
        if (chunkIndex < 0 || chunkIndex >= ttsChunks.size) return

        activity.runOnUiThread {
            val chunk = ttsChunks[chunkIndex]
            val highlightColor = preferences.novelTtsHighlightColor.get()
            val highlightTextColor = preferences.novelTtsHighlightTextColor.get()
            val highlightStyle = preferences.novelTtsHighlightStyle.get()
            val keepInView = preferences.novelTtsKeepHighlightInView.get()

            loadedChapters.forEach { loaded ->
                if (loaded.isLoaded) {
                    val textView = loaded.textView
                    val text = textView.text.toString()

                    val spanned = textView.text as? android.text.Spannable
                    // Always clear old TTS highlight spans before applying a new one.
                    spanned?.getSpans(0, text.length, android.text.style.BackgroundColorSpan::class.java)?.forEach { spanned.removeSpan(it) }
                    spanned?.getSpans(0, text.length, android.text.style.ForegroundColorSpan::class.java)?.forEach { spanned.removeSpan(it) }
                    spanned?.getSpans(0, text.length, android.text.style.UnderlineSpan::class.java)?.forEach { spanned.removeSpan(it) }
                    spanned?.getSpans(0, text.length, RoundedOutlineSpan::class.java)?.forEach { spanned.removeSpan(it) }

                    if (text.contains(chunk, ignoreCase = true)) {
                        // Find and highlight the chunk in the text
                        val startIndex = text.indexOf(chunk, ignoreCase = true)
                        if (startIndex >= 0) {
                            val endIndex = startIndex + chunk.length

                            // Apply new highlighting
                            when (highlightStyle) {
                                "underline" -> {
                                    spanned?.setSpan(
                                        android.text.style.UnderlineSpan(),
                                        startIndex,
                                        endIndex,
                                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                    )
                                }
                                "outline" -> {
                                    spanned?.setSpan(
                                        RoundedOutlineSpan(highlightColor),
                                        startIndex,
                                        endIndex,
                                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                    )
                                    spanned?.setSpan(
                                        android.text.style.ForegroundColorSpan(highlightTextColor),
                                        startIndex,
                                        endIndex,
                                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                    )
                                }
                                else -> { // background (default)
                                    spanned?.setSpan(
                                        android.text.style.BackgroundColorSpan(highlightColor),
                                        startIndex,
                                        endIndex,
                                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                    )
                                    spanned?.setSpan(
                                        android.text.style.ForegroundColorSpan(highlightTextColor),
                                        startIndex,
                                        endIndex,
                                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                    )
                                }
                            }

                            if (keepInView) {
                                val layout = textView.layout
                                if (layout != null) {
                                    val line = layout.getLineForOffset(startIndex)
                                    val targetInTextView = layout.getLineTop(line)
                                    val targetInScroll = textView.top + targetInTextView - (scrollView.height / 3)
                                    scrollView.smoothScrollTo(0, targetInScroll.coerceAtLeast(0))
                                }
                            }

                            return@forEach
                        }
                    }
                }
            }
        }
    }

    private fun loadNextChapterForTts() {
        val chapters = currentChapters ?: return
        val nextChapter = chapters.nextChapter ?: return

        logcat(LogPriority.DEBUG) { "TTS: Auto-loading next chapter for playback" }

        // Load next chapter and start TTS when ready
        scope.launch {
            activity.loadNextChapter()
            // Wait for the chapter to load
            delay(1000)
            // Start TTS on new chapter
            startTts()
        }
    }

    private fun applyTtsSettings() {
        tts?.let { textToSpeech ->
            // Set voice/locale
            val voicePref = preferences.novelTtsVoice.get()
            if (voicePref.isNotEmpty()) {
                // Try to find matching voice by name
                val voices = textToSpeech.voices
                val selectedVoice = voices?.find { it.name == voicePref }
                if (selectedVoice != null) {
                    textToSpeech.voice = selectedVoice
                } else {
                    // Fallback to locale matching
                    try {
                        val locale = Locale.forLanguageTag(voicePref)
                        textToSpeech.language = locale
                    } catch (e: Exception) {
                        textToSpeech.language = Locale.getDefault()
                    }
                }
            } else {
                textToSpeech.language = Locale.getDefault()
            }

            // Set speech rate (speed)
            val speed = preferences.novelTtsSpeed.get()
            textToSpeech.setSpeechRate(speed)

            // Set pitch
            val pitch = preferences.novelTtsPitch.get()
            textToSpeech.setPitch(pitch)
        }
    }

    fun speak(text: String) {
        if (!ttsInitialized || tts == null) {
            logcat(LogPriority.WARN) { "TTS not initialized, cannot speak" }
            return
        }

        // Re-apply settings before speaking in case they changed
        applyTtsSettings()

        ttsPaused = false

        val maxLength = TextToSpeech.getMaxSpeechInputLength()
        val paragraphs = text.split("\n").filter { it.isNotBlank() }

        val chunkParagraphIndexes = mutableListOf<Int>()
        ttsChunks = if (paragraphs.size > 1) {
            paragraphs.flatMapIndexed { paragraphIndex, para ->
                val chunks = if (para.length <= maxLength) {
                    listOf(para)
                } else {
                    splitTextForTts(para, maxLength)
                }
                repeat(chunks.size) { chunkParagraphIndexes.add(paragraphIndex) }
                chunks
            }
        } else if (text.length <= maxLength) {
            chunkParagraphIndexes.add(0)
            listOf(text)
        } else {
            val chunks = splitTextForTts(text, maxLength)
            repeat(chunks.size) { chunkParagraphIndexes.add(0) }
            chunks
        }
        ttsChunkParagraphIndexes = chunkParagraphIndexes

        // Extract paragraph info for highlighting support
        ttsCurrentParagraphs = NovelViewerTextUtils.findParagraphs(text)

        val savedChunkIndex = restoreTtsProgress()
        ttsCurrentChunkIndex = 0
        val startIndex = if (hasViewportStartOverride) {
            val viewportChunkIndex = ttsChunkParagraphIndexes.indexOfFirst { it >= ttsViewportParagraphIndex }
            if (viewportChunkIndex >= 0) viewportChunkIndex else savedChunkIndex
        } else {
            savedChunkIndex
        }
        hasViewportStartOverride = false

        speakChunksFrom(startIndex.coerceIn(0, (ttsChunks.size - 1).coerceAtLeast(0)))
    }

    private fun splitTextForTts(text: String, maxLength: Int): List<String> =
        NovelViewerTextUtils.splitTextForTts(text, maxLength)

    private fun ensureTtsInitialized() {
        if (tts == null) {
            try {
                tts = TextToSpeech(activity, this)
                logcat(LogPriority.DEBUG) { "TTS: Initialization started" }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "TTS: Failed to create TextToSpeech instance: ${e.message}" }
            }
        }
    }

    fun startTts() {
        ensureTtsInitialized()

        if (!ttsInitialized) {
            logcat(LogPriority.WARN) { "TTS: Not initialized yet, waiting..." }
            pendingTtsStartRequest = TtsStartRequest.NORMAL
            return
        }

        pendingTtsStartRequest = null
        isTtsAutoPlay = true // Enable auto-continue
        val text = loadedChapters.getOrNull(currentChapterIndex)?.textView?.text?.toString()
            ?: loadedChapters.firstOrNull()?.textView?.text?.toString()

        if (text.isNullOrEmpty()) {
            logcat(LogPriority.WARN) {
                "TTS: No text to speak. loadedChapters=${loadedChapters.size}, currentIndex=$currentChapterIndex"
            }
            return
        }

        logcat(LogPriority.DEBUG) { "TTS: Starting to speak ${text.length} characters" }
        speak(text)
    }

    fun stopTts() {
        isTtsAutoPlay = false // Disable auto-continue when manually stopped
        ttsPaused = false
        pendingTtsStartRequest = null
        ttsChunks = emptyList()
        ttsChunkParagraphIndexes = emptyList()
        ttsCurrentChunkIndex = 0
        hasViewportStartOverride = false
        if (ttsInitialized) {
            tts?.stop()
        }

        activity.runOnUiThread {
            loadedChapters.forEach { loaded ->
                if (!loaded.isLoaded) return@forEach
                val textView = loaded.textView
                val spanned = textView.text as? android.text.Spannable ?: return@forEach
                val text = textView.text.toString()
                spanned.getSpans(0, text.length, android.text.style.BackgroundColorSpan::class.java).forEach { spanned.removeSpan(it) }
                spanned.getSpans(0, text.length, android.text.style.ForegroundColorSpan::class.java).forEach { spanned.removeSpan(it) }
                spanned.getSpans(0, text.length, android.text.style.UnderlineSpan::class.java).forEach { spanned.removeSpan(it) }
                spanned.getSpans(0, text.length, RoundedOutlineSpan::class.java).forEach { spanned.removeSpan(it) }
            }
        }
    }

    fun pauseTts() {
        if (ttsInitialized && tts?.isSpeaking == true) {
            ttsPaused = true
            ttsResumeChunkIndex = ttsCurrentChunkIndex
            tts?.stop()
            saveTtsProgress()
        }
    }

    fun resumeTts() {
        if (ttsPaused && ttsChunks.isNotEmpty()) {
            ttsPaused = false
            // Resume from the chunk that was interrupted
            speakChunksFrom(ttsResumeChunkIndex)
        }
    }

    fun isTtsPaused(): Boolean = ttsPaused

    fun isTtsSpeaking(): Boolean = ttsInitialized && tts?.isSpeaking == true

    fun isTtsStarting(): Boolean = pendingTtsStartRequest != null || (!ttsInitialized && tts != null) || (ttsChunks.isEmpty() && isTtsAutoPlay)

    fun getTtsProgressPercent(): Int {
        if (ttsChunks.isEmpty()) return 0
        val chunkCount = ttsChunks.size
        val currentChunk = if (ttsPaused) ttsResumeChunkIndex else ttsCurrentChunkIndex
        val clampedChunk = currentChunk.coerceIn(0, chunkCount - 1)
        return (((clampedChunk + 1) * 100f) / chunkCount).roundToInt().coerceIn(0, 100)
    }

    /**
     * Saves the current TTS playback progress to preferences.
     */
    private fun saveTtsProgress() {
        val currentChapter = currentPage
        if (currentChapter == null || ttsCurrentChunkIndex < 0) return

        val chapterId = currentChapter.index.toLong()
        val paragraphIndex = ttsCurrentChunkIndex.coerceAtLeast(0)

        // Load existing progress map
        val progressJson = preferences.novelTtsLastReadParagraph.get()
        val progressMap = try {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, Int>>(progressJson)
                .toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }

        // Update with current chapter progress
        progressMap[chapterId.toString()] = paragraphIndex

        // Save back to preferences
        try {
            val json = kotlinx.serialization.json.Json.encodeToString(progressMap)
            preferences.novelTtsLastReadParagraph.set(json)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to save TTS progress: ${e.message}" }
        }
    }

    /**
     * Restores the last-read paragraph position for the current chapter.
     * @return The chunk index to resume from, or 0 if no progress found.
     */
    private fun restoreTtsProgress(): Int {
        val currentChapter = currentPage
        if (currentChapter == null) return 0

        val chapterId = currentChapter.index.toString()
        val progressJson = preferences.novelTtsLastReadParagraph.get()

        return try {
            val progressMap = kotlinx.serialization.json.Json.decodeFromString<Map<String, Int>>(progressJson)
            progressMap[chapterId]?.coerceAtLeast(0) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Starts TTS from the first visible paragraph in the viewport.
     */
    fun startTtsFromViewport() {
        ensureTtsInitialized()

        if (!ttsInitialized) {
            logcat(LogPriority.WARN) { "TTS: Not initialized yet" }
            pendingTtsStartRequest = TtsStartRequest.VIEWPORT
            return
        }

        pendingTtsStartRequest = null
        isTtsAutoPlay = true
        val text = loadedChapters.getOrNull(currentChapterIndex)?.textView?.text?.toString()
            ?: loadedChapters.firstOrNull()?.textView?.text?.toString()

        if (text.isNullOrEmpty()) {
            logcat(LogPriority.WARN) { "TTS: No text available for viewport start" }
            return
        }

        // Find first paragraph visible in viewport
        val firstVisibleParagraphIndex = findFirstVisibleParagraphIndex(text)
        logcat(LogPriority.DEBUG) { "TTS: Starting from viewport paragraph $firstVisibleParagraphIndex" }

        ttsViewportParagraphIndex = firstVisibleParagraphIndex.coerceAtLeast(0)
        hasViewportStartOverride = true
        speak(text)
    }

    /**
     * Finds the index of the first paragraph visible in the current viewport.
     */
    private fun findFirstVisibleParagraphIndex(text: String): Int {
        val viewportTop = scrollView.scrollY
        val viewportBottom = viewportTop + scrollView.height
        val paragraphs = NovelViewerTextUtils.findParagraphs(text)

        if (paragraphs.isEmpty()) return 0

        loadedChapters.forEachIndexed { _, loaded ->
            if (loaded.isLoaded) {
                val textView = loaded.textView
                val textViewTop = textView.top + scrollView.paddingTop
                val textViewBottom = textViewTop + textView.height

                // Check if any part of this textView is visible
                if (textViewBottom > viewportTop && textViewTop < viewportBottom) {
                    // Calculate how far into this textView the viewport starts
                    val layout = textView.layout ?: return 0
                    if (layout.lineCount > 0) {
                        // Get the line at viewport top within this textView
                        val textViewRelativeY = (viewportTop - textViewTop).coerceAtLeast(0)
                        val lineAtViewportTop = layout.getLineForVertical(textViewRelativeY.coerceAtMost(layout.height))
                        val charOffset = layout.getLineStart(lineAtViewportTop).coerceAtLeast(0)
                        val containingParagraph = paragraphs.indexOfFirst { charOffset >= it.startChar && charOffset < it.endChar }
                        if (containingParagraph >= 0) return containingParagraph

                        val nextParagraph = paragraphs.indexOfFirst { it.startChar >= charOffset }
                        return if (nextParagraph >= 0) nextParagraph else (paragraphs.size - 1)
                    }
                }
            }
        }

        return 0
    }

    private fun speakChunksFrom(startIndex: Int) {
        if (ttsChunks.isEmpty() || startIndex >= ttsChunks.size) return
        ttsChunks.drop(startIndex).forEachIndexed { i, chunk ->
            val actualIndex = startIndex + i
            val queueMode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(chunk, queueMode, null, "tts_utterance_$actualIndex")
        }
    }

    /**
     * Get list of available TTS voices for the settings UI
     */
    fun getAvailableVoices(): List<Pair<String, String>> {
        val voices = tts?.voices ?: return emptyList()
        return voices.map { voice ->
            val displayName = "${voice.locale.displayLanguage} (${voice.name})"
            Pair(voice.name, displayName)
        }.sortedBy { it.second }
    }

    /**
     * Get the current TTS voice name
     */
    fun getCurrentVoiceName(): String {
        return tts?.voice?.name ?: preferences.novelTtsVoice.get()
    }

    override fun destroy() {
        // Save progress before destroying
        progressSaveJob?.cancel()
        getScrollProgress { progress ->
            saveProgress(progress)
        }

        // Cleanup TTS - only if initialized
        if (ttsInitialized) {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null

        scope.cancel()
        loadJob?.cancel()
        loadedChapters.clear()
    }

    override fun getView(): View {
        return container
    }

    /**
     * Reload content with current translation state.
     * Re-renders all loaded chapters with or without translation.
     */
    fun reloadWithTranslation() {
        // Re-render all loaded chapters with new translation state
        loadedChapters.forEach { loadedChapter ->
            val page = loadedChapter.chapter.pages?.firstOrNull() as? ReaderPage ?: return@forEach
            val content = page.text ?: return@forEach
            val textView = loadedChapter.textView

            // Apply translation if enabled (async)
            if (activity.isTranslationEnabled()) {
                textView.text = "Translating..."
                scope.launch {
                    val translatedContent = activity.translateContentIfEnabled(content)
                    withContext(Dispatchers.Main) {
                        if (!textView.isAttachedToWindow) return@withContext
                        setTextViewContent(textView, translatedContent, loadedChapter.chapter.chapter.url)
                    }
                }
            } else {
                // Show original content
                setTextViewContent(textView, content, loadedChapter.chapter.chapter.url)
            }
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        val page = chapters.currChapter.pages?.firstOrNull() as? ReaderPage ?: return

        loadJob?.cancel()
        currentPage = page
        currentChapters = chapters

        // Check if chapter is already loaded to prevent a redundant clear+redraw.
        val isAlreadyLoaded = loadedChapters.any { it.chapter.chapter.id == chapters.currChapter.chapter.id }

        // If already loaded in infinite scroll mode, nothing to do.
        if (preferences.novelInfiniteScroll.get() && loadedChapters.isNotEmpty() && isAlreadyLoaded) {
            return
        }

        // Clear for manual navigation or initial load; preserve in infinite-scroll if already present.
        if (!preferences.novelInfiniteScroll.get() || !isAlreadyLoaded) {
            contentContainer.removeAllViews()
            loadedChapters.clear()
            currentChapterIndex = 0
        }

        // If page is already ready (downloaded chapter), display immediately.
        if (page.status == Page.State.Ready && !page.text.isNullOrEmpty()) {
            hideLoadingIndicator()
            displayChapter(chapters.currChapter, page)
            restoreProgress(page)
            // Trigger download of next chapters (needed for non-infinite-scroll mode)
            activity.viewModel.setNovelVisibleChapter(page.chapter.chapter)
            return
        }

        showLoadingIndicator()

        loadJob = scope.launch {
            val loader = page.chapter.pageLoader
            if (loader == null) {
                logcat(LogPriority.ERROR) { "NovelViewer: No page loader available" }
                hideLoadingIndicator()
                return@launch
            }

            launch(Dispatchers.IO) { loader.loadPage(page) }

            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Ready -> {
                        hideLoadingIndicator()
                        displayChapter(chapters.currChapter, page)
                        restoreProgress(page)
                        // Trigger download of next chapters (needed for non-infinite-scroll mode)
                        activity.viewModel.setNovelVisibleChapter(page.chapter.chapter)
                    }
                    is Page.State.Error -> {
                        hideLoadingIndicator()
                        displayError(state.error)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun displayChapter(chapter: ReaderChapter, page: ReaderPage) {
        var content = page.text
        if (content.isNullOrBlank()) {
            logcat(LogPriority.ERROR) { "NovelViewer: Page text is null or blank" }
            displayError(Exception("No text content available"))
            return
        }

        // Optionally strip chapter title from content
        if (preferences.novelHideChapterTitle.get()) {
            content = stripChapterTitle(content, chapter.chapter.name)
        }

        val plainTextMode = NovelViewerTextUtils.isPlainTextChapter(chapter.chapter.url)
        content = if (plainTextMode) {
            NovelViewerTextUtils.normalizePlainTextContent(content)
        } else {
            normalizeContentForHtml(content, chapter.chapter.url)
        }

        // Optionally force lowercase
        if (preferences.novelForceTextLowercase.get()) {
            content = content.lowercase()
        }

        // Check if chapter is already loaded - return early to prevent duplicate adds
        val existingIndex = loadedChapters.indexOfFirst { it.chapter.chapter.id == chapter.chapter.id }
        if (existingIndex >= 0) {
            logcat(LogPriority.DEBUG) {
                "NovelViewer: Chapter ${chapter.chapter.id} already in loadedChapters at index $existingIndex, skipping display"
            }
            currentChapterIndex = existingIndex
            return
        }

        logcat(LogPriority.DEBUG) {
            "NovelViewer: Displaying chapter ${chapter.chapter.id}, infinite scroll enabled: ${preferences.novelInfiniteScroll.get()}, loaded count: ${loadedChapters.size}"
        }

        // Create header for chapter (for infinite scroll mode)
        val headerView = TextView(activity).apply {
            text = chapter.chapter.name
            textSize = 18f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            setPadding(16, 32, 16, 16)
            // Never show a chapter boundary indicator during infinite scroll.
            isVisible = false
        }

        // Create text view for content
        val textView = createSelectableTextView()

        applyTextViewStyles(textView)

        val loadedChapter = LoadedChapter(
            chapter = chapter,
            textView = textView,
            headerView = headerView,
            isLoaded = true,
        )

        // Check if this is an append (infinite scroll) or new chapter (manual nav)
        val isAppend = loadedChapters.isNotEmpty() && preferences.novelInfiniteScroll.get()
        val previousIndex = currentChapterIndex

        // Add to end for infinite scroll
        loadedChapters.add(loadedChapter)

        // Only update currentChapterIndex if this is not an append (manual navigation)
        // For infinite scroll appends, keep reading the current chapter
        if (!isAppend) {
            currentChapterIndex = loadedChapters.size - 1
        }

        // Suppress scroll events for the entire view-hierarchy modification.
        // addView/removeView trigger layout recalculation whose scroll events would see
        // stale textView.bottom coordinates → wrong chapter detection.
        // Reset is deferred to scrollView.post{} so it happens AFTER the layout pass.
        isRestoringScroll = true

        if (isAppend) {
            val separator = TextView(activity).apply {
                text = "──────────"
                textSize = 16f
                setTextColor(0xFF888888.toInt())
                gravity = Gravity.CENTER
                setPadding(16, 48, 16, 48)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            contentContainer.addView(separator)
            loadedChapter.separatorView = separator
        }

        // Add views to container
        contentContainer.addView(headerView)
        contentContainer.addView(textView)

        // Apply translation if enabled (async)
        val finalContent = content
        // Always show content immediately; translation (if enabled) replaces it asynchronously.
        setTextViewContent(textView, finalContent, chapter.chapter.url)
        if (activity.isTranslationEnabled() && !preferences.novelShowRawHtml.get()) {
            scope.launch {
                val translatedContent = activity.translateContentIfEnabled(finalContent)
                withContext(Dispatchers.Main) {
                    setTextViewContent(textView, translatedContent, chapter.chapter.url)
                }
            }
        }

        applyBackgroundColor()

        if (!preferences.novelInfiniteScroll.get()) {
            addNextChapterButton()
        }

        cleanupDistantChapters()

        // Defer re-enabling scroll events until AFTER the layout pass completes.
        // This ensures textView.bottom coordinates are up-to-date before
        // updateCurrentChapterFromScroll can run again.
        scrollView.post {
            isRestoringScroll = false
            chapterEntryTime = System.currentTimeMillis()
            syncShortChapterProgressIfNeeded()
        }
    }

    private val NEXT_CHAPTER_BUTTON_TAG = "next_chapter_button"

    /**
     * Adds a "Next Chapter" navigation button at the bottom of the content
     * when infinite scroll is off.
     */
    private fun addNextChapterButton() {
        contentContainer.findViewWithTag<View>(NEXT_CHAPTER_BUTTON_TAG)?.let {
            contentContainer.removeView(it)
        }

        val chapters = currentChapters ?: return
        val hasNext = chapters.nextChapter != null

        if (!hasNext) return

        val buttonContainer = LinearLayout(activity).apply {
            tag = NEXT_CHAPTER_BUTTON_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val nextButton = android.widget.Button(activity).apply {
            text = "Next Chapter →"
            isAllCaps = false
            textSize = 16f
            setTextColor(android.graphics.Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#ADD8E6"))
                setStroke(2, android.graphics.Color.BLACK)
                cornerRadius = 12f
            }
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { activity.loadNextChapter() }
        }
        buttonContainer.addView(nextButton)

        contentContainer.addView(buttonContainer)
    }

    private fun cleanupDistantChapters() {
        // Keep at most 3 chapters loaded (current + 1 next+1prev)
        // When at chapter x, unload chapter x-2
        val maxChapters = 3
        while (loadedChapters.size > maxChapters && currentChapterIndex > 0) {
            val toRemove = loadedChapters.first()
            var removedHeight = toRemove.headerView.height + toRemove.textView.height

            toRemove.separatorView?.let { sep ->
                removedHeight += sep.height
                contentContainer.removeView(sep)
            }

            contentContainer.removeView(toRemove.headerView)
            contentContainer.removeView(toRemove.textView)
            loadedChapters.removeAt(0)
            currentChapterIndex--

            if (loadedChapters.isNotEmpty()) {
                loadedChapters.first().separatorView?.let { sep ->
                    removedHeight += sep.height
                    contentContainer.removeView(sep)
                    loadedChapters.first().separatorView = null
                }
            }

            // Adjust scroll position to prevent visual jump.
            // Content shifted UP by removedHeight, so we must scroll UP by the same amount
            // to keep the viewport on the same content.
            // isRestoringScroll is already true (set by displayChapter before addView calls)
            // and will be reset by displayChapter's scrollView.post{} after the layout pass.
            scrollView.scrollBy(0, -removedHeight)

            logcat(LogPriority.DEBUG) { "NovelViewer: Removed distant chapter, adjusted scroll by -$removedHeight" }
        }
    }

    private fun restoreProgress(page: ReaderPage) {
        // Restore scroll position from lastPageRead (stored as percentage)
        val savedProgress = page.chapter.chapter.last_page_read
        val isRead = page.chapter.chapter.read
        logcat(LogPriority.DEBUG) {
            "NovelViewer: Restoring progress, savedProgress=$savedProgress, isRead=$isRead for ${page.chapter.chapter.name}"
        }

        // If chapter is marked as read, start from top (0%) to avoid infinite scroll issues
        val shouldRestore = if (!isRead) {
            savedProgress > 0 && savedProgress <= 100
        } else {
            libraryPreferences.novelReadProgress100.get() && savedProgress > 0 && savedProgress <= 100
        }
        if (shouldRestore) {
            val progress = savedProgress / 100f
            // Set lastSavedProgress BEFORE posting to prevent race condition with scroll listener
            lastSavedProgress = progress

            // Wait for layout to complete before scrolling
            scrollView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    scrollView.viewTreeObserver.removeOnGlobalLayoutListener(this)

                    // Double-check the content is loaded
                    val child = scrollView.getChildAt(0) ?: return
                    val totalHeight = child.height - scrollView.height
                    if (totalHeight <= 0) {
                        // Content not ready yet, schedule a retry
                        scrollView.postDelayed({
                            isRestoringScroll = true
                            setScrollProgress(progress.coerceIn(0f, 1f))
                            logcat(LogPriority.DEBUG) { "NovelViewer: Scroll restored (delayed) to ${(progress * 100).toInt()}%" }
                            isRestoringScroll = false
                        }, 200)
                        return
                    }

                    isRestoringScroll = true
                    setScrollProgress(progress.coerceIn(0f, 1f))
                    logcat(LogPriority.DEBUG) { "NovelViewer: Scroll restored to ${(progress * 100).toInt()}%" }
                    isRestoringScroll = false
                }
            })
        } else {
            // Scroll to top for new chapters or already read chapters
            lastSavedProgress = 0f
            scrollView.post {
                isRestoringScroll = true
                scrollView.scrollTo(0, 0)
                isRestoringScroll = false
            }
        }
    }

    private fun applyTextViewStyles(textView: TextView) {
        val fontSize = preferences.novelFontSize.get()
        val lineHeight = preferences.novelLineHeight.get()
        val marginLeft = preferences.novelMarginLeft.get()
        val marginRight = preferences.novelMarginRight.get()
        val marginTop = preferences.novelMarginTop.get()
        val marginBottom = preferences.novelMarginBottom.get()
        val fontColor = preferences.novelFontColor.get()
        val theme = preferences.novelTheme.get()
        val textAlign = preferences.novelTextAlign.get()
        val fontFamily = preferences.novelFontFamily.get()

        val density = activity.resources.displayMetrics.density
        val leftPx = (marginLeft * density).toInt()
        val rightPx = (marginRight * density).toInt()
        val topPx = (marginTop * density).toInt()
        val bottomPx = (marginBottom * density).toInt()
        textView.setPadding(leftPx, topPx, rightPx, bottomPx)

        textView.textSize = fontSize.toFloat()
        textView.setLineSpacing(0f, lineHeight)

        // Apply font family
        // For custom fonts (file:// or content:// URIs), load the Typeface from file
        textView.typeface = when {
            fontFamily.startsWith("file://") || fontFamily.startsWith("content://") -> {
                try {
                    val fontUri = android.net.Uri.parse(fontFamily)
                    // For content:// URIs, copy to cache first
                    val fontFile = if (fontFamily.startsWith("content://")) {
                        val tempFile = java.io.File(activity.cacheDir, "custom_font.ttf")
                        activity.contentResolver.openInputStream(fontUri)?.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        tempFile
                    } else {
                        // file:// URI - extract path
                        java.io.File(fontUri.path ?: fontFamily.removePrefix("file://"))
                    }
                    android.graphics.Typeface.createFromFile(fontFile)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Failed to load custom font: ${e.message}" }
                    android.graphics.Typeface.SANS_SERIF
                }
            }
            fontFamily.contains("serif", ignoreCase = true) && !fontFamily.contains("sans", ignoreCase = true) ->
                android.graphics.Typeface.SERIF
            fontFamily.contains("monospace", ignoreCase = true) ->
                android.graphics.Typeface.MONOSPACE
            else -> android.graphics.Typeface.SANS_SERIF
        }

        // Apply text alignment
        textView.gravity = when (textAlign) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "right" -> Gravity.END
            "justify" -> Gravity.START // Android doesn't have justify, fallback to start
            else -> Gravity.START
        }
        // For justify on API 26+, use justification mode
        if (textAlign == "justify" && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            textView.justificationMode = LineBreaker.JUSTIFICATION_MODE_INTER_WORD
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            textView.justificationMode = android.text.Layout.JUSTIFICATION_MODE_NONE
        }

        val (_, themeTextColor) = getThemeColors(theme)
        // 0 = default (use theme color)
        val finalTextColor = if (fontColor != 0) fontColor else themeTextColor
        textView.setTextColor(finalTextColor)
    }

    private fun applyBackgroundColor() {
        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()

        val (themeBgColor, _) = getThemeColors(theme)
        // 0 = default (use theme color)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        scrollView.setBackgroundColor(finalBgColor)
    }

    private fun getThemeColors(theme: String): Pair<Int, Int> =
        NovelViewerTextUtils.getThemeColors(activity, preferences, theme)

    private fun setTextViewContent(textView: TextView, content: String, chapterUrl: String?) {
        // Check if raw HTML mode is enabled (for debugging)
        val showRawHtml = preferences.novelShowRawHtml.get()
        if (showRawHtml) {
            // Display raw HTML tags without parsing
            if (!textView.isAttachedToWindow) return
            clearTextViewSelection(textView)
            textView.text = content
            return
        }

        val plainTextMode = NovelViewerTextUtils.isPlainTextChapter(chapterUrl)

        // Process content to ensure paragraph tags exist for styling
        var processedContent = if (plainTextMode) {
            NovelViewerTextUtils.normalizePlainTextContent(content)
        } else {
            NovelViewerTextUtils.normalizeContentForHtml(content, chapterUrl)
        }

        // Strip script tags and their content — they would render as visible text
        processedContent = processedContent.replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        processedContent = processedContent.replace(Regex("<script[^>]*/>", RegexOption.IGNORE_CASE), "")
        // Strip style tags too — their CSS rules show up as text in TextView
        processedContent = processedContent.replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        processedContent = processedContent.replace(Regex("<style[^>]*/>", RegexOption.IGNORE_CASE), "")
        // Strip noscript tags
        processedContent = processedContent.replace(Regex("<noscript[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        processedContent = applyRegexReplacements(processedContent)

        // Optionally strip media tags entirely when blocking media
        if (preferences.novelBlockMedia.get()) {
            processedContent = processedContent
                .replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("</?image[^>]*>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<video[^>]*>.*?</video>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("<audio[^>]*>.*?</audio>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("<source[^>]*>", RegexOption.IGNORE_CASE), "")
        }

        if (!plainTextMode) {
            // First, strip any existing leading non-breaking spaces from paragraphs
            // This prevents double-spacing when indent is applied
            processedContent = processedContent.replace(Regex("<p>(?:\u00A0|&#160;|&nbsp;)+"), "<p>")

            // If content doesn't have <p> tags, wrap paragraphs (double newlines or single <br> followed by text)
            if (!processedContent.contains("<p>", ignoreCase = true)) {
                // Replace double line breaks with paragraph markers
                processedContent = processedContent
                    .replace("\n\n", "</p><p>")
                    .replace("\r\n\r\n", "</p><p>")
                // Wrap in paragraph tags
                processedContent = "<p>$processedContent</p>"
            }
        }

        // Get paragraph spacing preference (em units, default 0.5)
        val paragraphSpacing = preferences.novelParagraphSpacing.get()
        // Get paragraph indent preference (em units, default 0)
        val paragraphIndent = preferences.novelParagraphIndent.get()
        val fontSize = preferences.novelFontSize.get()
        val density = activity.resources.displayMetrics.density

        scope.launch {
            // Create image getter if media is not blocked
            val blockMedia = preferences.novelBlockMedia.get()
            val imageGetter = if (!blockMedia) {
                CoilImageGetter(textView, activity, scope)
            } else {
                null
            }

            val spanned = if (plainTextMode) {
                android.text.SpannableStringBuilder(processedContent)
            } else {
                // Strip style and script tags entirely before rendering
                var cleanHtmlContent = processedContent
                try {
                    val doc = org.jsoup.Jsoup.parse(cleanHtmlContent)
                    doc.select("style, script").remove()
                    // Force all images to be block-level by wrapping them in generic paragraphs
                    // This prevents `TextView` overlapping them if they appear inline without spaces
                    doc.select("img").forEach { img ->
                        if (img.parent()?.tagName() != "p" && img.parent()?.tagName() != "div") {
                            img.wrap("<p style=\"text-align:center;\"></p>")
                        }
                    }
                    cleanHtmlContent = doc.body()?.html() ?: cleanHtmlContent
                } catch (_: Exception) {}

                withContext(Dispatchers.Default) {
                    Html.fromHtml(cleanHtmlContent, Html.FROM_HTML_MODE_LEGACY, imageGetter, null)
                }
            }

            // Apply custom paragraph spacing and indent using spans
            val spannable = android.text.SpannableStringBuilder(spanned)

            // Calculate pixel values from em units
            val spacingPx = (paragraphSpacing * fontSize * density).toInt()
            val indentPx = (paragraphIndent * fontSize * density).toInt()

            if (spacingPx > 0 || indentPx > 0) {
                // Find paragraph boundaries (newline characters)
                var i = 0
                var paragraphStart = 0
                while (i < spannable.length) {
                    if (spannable[i] == '\n' || i == spannable.length - 1) {
                        val paragraphEnd = if (spannable[i] == '\n') i + 1 else i + 1

                        // Apply spacing span to this paragraph
                        if (spacingPx > 0 && paragraphEnd <= spannable.length) {
                            spannable.setSpan(
                                ParagraphSpacingSpan(spacingPx),
                                paragraphStart,
                                paragraphEnd,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }

                        // Apply indent span to this paragraph
                        if (indentPx > 0 && paragraphEnd <= spannable.length) {
                            spannable.setSpan(
                                ParagraphIndentSpan(indentPx),
                                paragraphStart,
                                paragraphEnd,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }

                        paragraphStart = paragraphEnd
                    }
                    i++
                }
            }

            withContext(Dispatchers.Main) {
                // Skip if the view was detached (e.g. preference change triggered
                // a full reload while this coroutine was in-flight).
                if (!textView.isAttachedToWindow) return@withContext

                // Clear any existing selection state before replacing text.
                clearTextViewSelection(textView)

                // The view's selectable/focusable state was already configured in
                // createSelectableTextView().  Do NOT toggle setTextIsSelectable()
                // here — calling setTextIsSelectable(false) on a view that has an
                // active Editor internally calls setText(mText, NORMAL) with
                // mTextIsSelectable=false, which fires Android's
                // "Selection cancelled" warning on API 34+.
                textView.setText(spannable, TextView.BufferType.SPANNABLE)
            }
        }
    }

    /**
     * Apply user-configured find & replace rules to content.
     * Rules are stored as JSON in the novelRegexReplacements preference.
     * Each enabled rule is applied in order — supports both plain text and regex patterns.
     */
    private fun applyRegexReplacements(content: String): String =
        NovelViewerTextUtils.applyRegexReplacements(content, preferences)

    private fun normalizeContentForHtml(content: String, chapterUrl: String?): String =
        NovelViewerTextUtils.normalizeContentForHtml(content, chapterUrl)

    /**
     * Dismiss any active text selection (action mode / handles) without toggling
     * [TextView.setTextIsSelectable].  Only removes the selection markers and clears focus —
     * no hidden API reflection needed.
     */
    private fun clearTextViewSelection(textView: TextView) {
        val text = textView.text
        if (text is android.text.Spannable && text.isNotEmpty() &&
            android.text.Selection.getSelectionStart(text) >= 0
        ) {
            android.text.Selection.removeSelection(text)
        }
        // Only clearFocus when the view actually has focus, to avoid cascading
        // focus-change events on other TextViews that could trigger selection warnings.
        if (textView.isFocused) {
            textView.clearFocus()
        }
    }

    /**
     * Strips the chapter title from the beginning of the content.
     * Searches within the first ~500 characters for chapter title or name matches.
     */
    private fun stripChapterTitle(content: String, chapterName: String): String =
        NovelViewerTextUtils.stripChapterTitle(content, chapterName)

    private fun isTitleMatch(text: String, chapterName: String): Boolean =
        NovelViewerTextUtils.isTitleMatch(text, chapterName)

    /**
     * Reloads the current chapter content.
     */
    fun reloadChapter() {
        // Clear loaded chapters and reload
        contentContainer.removeAllViews()
        loadedChapters.clear()
        currentChapterIndex = 0
        currentChapters?.let { setChapters(it) }
    }

    private var initialLoadingView: TextView? = null

    private fun showLoadingIndicator() {
        // Use inline loading text instead of progress bar
        contentContainer.removeAllViews()

        initialLoadingView = TextView(activity).apply {
            text = "Loading..."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 64, 32, 64)
            setTextColor(0xFF888888.toInt())
        }

        contentContainer.addView(initialLoadingView)
        applyBackgroundColor()
    }

    private fun hideLoadingIndicator() {
        // Remove the initial loading view if still present
        initialLoadingView?.let { view ->
            contentContainer.removeView(view)
        }
        initialLoadingView = null
    }

    private fun displayError(error: Throwable) {
        val errorView = TextView(activity).apply {
            text = "Error loading chapter: ${error.message}"
            setTextColor(0xFFFF5555.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            )
            setPadding(32, 32, 32, 32)
        }
        contentContainer.removeAllViews()
        contentContainer.addView(errorView)
    }

    fun startAutoScroll() {
        val speed = preferences.novelAutoScrollSpeed.get().coerceIn(1, 10)
        isAutoScrolling = true

        autoScrollJob?.cancel()
        autoScrollJob = scope.launch {
            while (isActive && isAutoScrolling) {
                // Scroll amount per tick - higher speed = more scroll
                val scrollAmount = speed // 1-10 pixels per tick
                scrollView.smoothScrollBy(0, scrollAmount)

                // Fixed delay between scroll ticks
                // At speed 1: scroll 1px every 50ms = ~20px/sec
                // At speed 10: scroll 10px every 50ms = ~200px/sec
                delay(50L)
            }
        }
    }

    fun stopAutoScroll() {
        isAutoScrolling = false
        autoScrollJob?.cancel()
    }

    fun toggleAutoScroll() {
        if (isAutoScrolling) {
            stopAutoScroll()
        } else {
            startAutoScroll()
        }
    }

    fun isAutoScrollActive(): Boolean = isAutoScrolling

    /**
     * Scrolls to the top of the page
     */
    fun scrollToTop() {
        scrollView.scrollTo(0, 0)
    }

    /**
     * Scrolls to the bottom of the page
     */
    fun scrollToBottom() {
        scrollView.fullScroll(View.FOCUS_DOWN)
    }

    /**
     * Gets the current scroll progress (0.0 to 1.0)
     */
    fun getScrollProgress(callback: (Float) -> Unit) {
        val scrollY = scrollView.scrollY
        val child = scrollView.getChildAt(0)
        val totalHeight = if (child != null) child.height - scrollView.height else 0
        val progress = if (totalHeight > 0) scrollY.toFloat() / totalHeight else 0f
        callback(progress)
    }

    /**
     * Gets the current scroll progress as percentage (0 to 100)
     * In infinite scroll mode, returns progress within the current chapter.
     */
    fun getProgressPercent(): Int {
        val scrollY = scrollView.scrollY
        if (loadedChapters.size > 1 && preferences.novelInfiniteScroll.get()) {
            val progress = calculateCurrentChapterProgress(scrollY)
            val percent = if (progress >= 0.98f) 100 else (progress * 100).toInt()
            return percent.coerceIn(0, 100)
        }
        val child = scrollView.getChildAt(0)
        val totalHeight = if (child != null) child.height - scrollView.height else 0
        if (totalHeight <= 0) {
            return if (shouldAutoMarkShortChapter(currentPage)) {
                100
            } else {
                (lastSavedProgress * 100).roundToInt().coerceIn(0, 100)
            }
        }
        val progress = scrollY.toFloat() / totalHeight
        // Round up if very close to 100% (within 2%) to allow reaching 100%
        val percent = if (progress >= 0.98f) 100 else (progress * 100).toInt()
        return percent.coerceIn(0, 100)
    }

    /**
     * Sets the scroll position within the current chapter by progress (0.0 to 1.0)
     */
    fun setScrollProgress(progress: Float) {
        // For single chapter or no infinite scroll, use overall scroll
        if (loadedChapters.size <= 1 || !preferences.novelInfiniteScroll.get()) {
            val child = scrollView.getChildAt(0) ?: return
            val totalHeight = child.height - scrollView.height
            val scrollY = (totalHeight * progress).toInt()
            scrollView.scrollTo(0, scrollY)
            return
        }

        // For infinite scroll with multiple chapters, scroll within current chapter
        var accumulatedHeight = 0
        for ((index, loadedChapter) in loadedChapters.withIndex()) {
            val separatorHeight = loadedChapter.separatorView?.height ?: 0
            if (index == currentChapterIndex) {
                val chapterHeight = loadedChapter.headerView.height + loadedChapter.textView.height + separatorHeight
                val visibleHeight = scrollView.height
                val effectiveChapterHeight = (chapterHeight - visibleHeight).coerceAtLeast(1)
                val chapterScrollY = accumulatedHeight + (effectiveChapterHeight * progress).toInt()
                scrollView.scrollTo(0, chapterScrollY)
                return
            }
            accumulatedHeight += loadedChapter.headerView.height + loadedChapter.textView.height + separatorHeight
        }
    }

    /**
     * Sets the scroll position by progress percentage (0 to 100)
     */
    fun setProgressPercent(percent: Int) {
        val progress = percent.coerceIn(0, 100) / 100f
        setScrollProgress(progress)
    }

    override fun moveToPage(page: ReaderPage) {
        // For novels, each chapter is one "page"
        // If we need to support multi-page novels, implement navigation here
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (isUp) activity.toggleMenu()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (preferences.novelVolumeKeysScroll.get()) {
                    if (!isUp) scrollView.pageScroll(View.FOCUS_DOWN)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (preferences.novelVolumeKeysScroll.get()) {
                    if (!isUp) scrollView.pageScroll(View.FOCUS_UP)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_SPACE -> {
                if (!isUp) {
                    if (event.isShiftPressed) {
                        scrollView.pageScroll(View.FOCUS_UP)
                    } else {
                        scrollView.pageScroll(View.FOCUS_DOWN)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (!isUp) scrollView.pageScroll(View.FOCUS_UP)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (!isUp) scrollView.pageScroll(View.FOCUS_DOWN)
                return true
            }
        }
        return false
    }

    fun toggleEditMode(isEditing: Boolean) {
        if (isEditing) {
            activity.runOnUiThread {
                android.widget.Toast.makeText(activity, "Edit mode is only supported in WebView mode", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Get the currently selected text from the active chapter's TextView
     */
    fun getSelectedText(): String? {
        val loaded = loadedChapters.getOrNull(currentChapterIndex) ?: return null
        val textView = loaded.textView
        if (!textView.hasSelection()) return null

        val start = textView.selectionStart
        val end = textView.selectionEnd
        if (start < 0 || end < 0 || start >= end) return null

        val text = textView.text.toString()
        return text.substring(start, end)
    }

    /**
     * Get the current chapter name for quote context
     */
    fun getCurrentChapterName(): String? {
        val loaded = loadedChapters.getOrNull(currentChapterIndex) ?: return null
        return loaded.chapter.chapter.name
    }

    /**
     * Check if text is currently selected
     */
    fun hasTextSelection(): Boolean {
        val loaded = loadedChapters.getOrNull(currentChapterIndex) ?: return false
        return loaded.textView.hasSelection()
    }

    /**
     * Clear text selection
     */
    fun clearTextSelection() {
        val loaded = loadedChapters.getOrNull(currentChapterIndex) ?: return
        val textView = loaded.textView
        if (textView.hasSelection()) {
            textView.clearFocus()
        }
    }

    /**
     * Handle the "Remember" action from text selection menu
     */
    private fun onRememberSelectedText() {
        val selectedText = getSelectedText()
        val chapterName = getCurrentChapterName()

        if (selectedText != null && chapterName != null) {
            activity.onRememberSelectedText()
            // Clear selection after adding quote
            clearTextSelection()
        } else {
            activity.toast("No text selected")
        }
    }
}
