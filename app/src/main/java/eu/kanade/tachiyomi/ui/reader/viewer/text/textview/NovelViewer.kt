@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import android.graphics.Canvas
import android.graphics.text.LineBreaker
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
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
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.text.NovelConfig
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ChapterQueue
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ContentConfig
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ContentPipeline
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ErrorFormatter
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.NovelPageLoader
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ProcessedContent
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.RenderTarget
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.TtsController
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.TtsHandoffState
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.handleNovelFlingGesture
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.localized
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.injectLazy
import kotlin.math.roundToInt

class NovelViewer(val activity: ReaderActivity) : Viewer {

    private val container = FrameLayout(activity)
    private lateinit var scrollView: NestedScrollView
    private lateinit var contentContainer: LinearLayout
    private val inlineFeedback by lazy {
        NovelTextViewInlineFeedback(activity, contentContainer, scope)
    }
    private val textRenderer by lazy {
        NovelTextRenderer(activity, preferences, scope)
    }
    private val preferences: ReaderPreferences by injectLazy()
    private val libraryPreferences: tachiyomi.domain.library.service.LibraryPreferences by injectLazy()
    private val contentPipeline = ContentPipeline(preferences)
    private var isAutoScrolling = false
    private var autoScrollJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val config = NovelConfig(scope)
    private val navigator get() = config.navigator

    private var loadJob: Job? = null
    private var currentPage: ReaderPage? = null
    private var currentChapters: ViewerChapters? = null
    private var renderGeneration = 0L

    private data class LoadedChapter(
        val chapter: ReaderChapter,
        val block: ChapterTextBlock,
        val headerView: TextView,
        var separatorView: View? = null,
        var isLoaded: Boolean = false,
        // True once the chunk views received text — guards against false short-chapter
        // marks when the empty block has a smaller height than scrollView.
        var isTextSet: Boolean = false,
        // Holds pre-processed content to be rendered once the view is attached.
        var pendingContent: ProcessedContent? = null,
    )

    private val chapterQueue = ChapterQueue<LoadedChapter> { it.chapter.chapter.id }

    // Property accessors backed by chapterQueue so existing call sites keep
    // working. Mutations should go through chapterQueue's methods (append /
    // prepend / removeFirst / clear) so the cursor and id-set stay in sync.
    private val loadedChapters: List<LoadedChapter> get() = chapterQueue.all
    private var isLoadingNext: Boolean
        get() = chapterQueue.isLoadingNext
        set(value) {
            chapterQueue.isLoadingNext = value
        }
    private var isRestoringScroll = false
    private var currentChapterIndex: Int
        get() = chapterQueue.currentIndex
        set(value) {
            chapterQueue.currentIndex = value
        }
    private var disableScrollbarForSession = false

    private var lastSavedProgress = 0f

    // Cache the last resolved custom-font so content:// URIs are not re-copied on every style refresh.
    private var cachedFontUri: String? = null
    private var cachedTypeface: android.graphics.Typeface? = null

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
        const val NEXT_CHAPTER_BUTTON_TAG = "next_chapter_button"
    }

    private val gestureDetector = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (!preferences.novelSwipeNavigation.get()) return false
                return handleNovelFlingGesture(
                    e1,
                    e2,
                    velocityX,
                    velocityY,
                    onPrevious = { activity.loadPreviousChapter() },
                    onNext = { activity.loadNextChapter() },
                )
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // A hold ≥ long-press threshold means the user was selecting text, not tapping.
                // Without this guard, releasing after a long-press (which starts text selection)
                // also fires onSingleTapConfirmed and toggles the app bars.
                if (e.eventTime - e.downTime >= android.view.ViewConfiguration.getLongPressTimeout()) return true
                // Never toggle menu while text is selected
                if (loadedChapters.any { it.block.hasSelection() }) return false

                val pos = android.graphics.PointF(
                    e.x / container.width.toFloat(),
                    e.y / container.height.toFloat(),
                )

                if (preferences.navigationModeNovel.get() == ReaderPreferences.TapZones.size) {
                    val centerXStart = 0.4f
                    val centerXEnd = 0.6f
                    val centerYStart = 0.4f
                    val centerYEnd = 0.6f
                    if (pos.x in centerXStart..centerXEnd && pos.y in centerYStart..centerYEnd) {
                        activity.toggleMenu()
                        return true
                    }
                    return false
                }

                when (navigator.getAction(pos)) {
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.MENU -> {
                        activity.toggleMenu()
                    }
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.NEXT,
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.RIGHT,
                    -> {
                        scrollView.smoothScrollBy(0, (container.height * 0.8).toInt())
                    }
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.PREV,
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.LEFT,
                    -> {
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
        config.navigationModeChangedListener = {
            activity.binding.navigationOverlay.setNavigation(config.navigator, true)
        }
        if (config.forceNavigationOverlay && !activity.tapZonesShownInSession) {
            activity.tapZonesShownInSession = true
            activity.binding.navigationOverlay.setNavigation(config.navigator, true)
        }
        initViews()
        container.addView(scrollView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
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
                } catch (_: NullPointerException) {
                    disableScrollbarForSession = true
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
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

            val previousChapterIndex = currentChapterIndex

            // Suppress chapter detection and progress saves for a grace period after
            // chapter entry or view-hierarchy changes (displayChapter / cleanup).
            // Stale textView.bottom coordinates after addView/removeView would cause
            // updateCurrentChapterFromScroll to detect the wrong chapter.
            val inGracePeriod = System.currentTimeMillis() - chapterEntryTime < CHAPTER_ENTRY_GRACE_MS

            if (!isRestoringScroll && !inGracePeriod) {
                updateCurrentChapterFromScroll(scrollY)
            }

            val chapterChanged = previousChapterIndex != currentChapterIndex
            if (chapterChanged) return@setOnScrollChangeListener

            val chapterProgress = calculateCurrentChapterProgress(scrollY)

            if (!inGracePeriod) {
                scheduleProgressSave(chapterProgress)
                activity.onNovelProgressChanged(chapterProgress)
            }

            if (!inGracePeriod && preferences.novelInfiniteScroll.get()) {
                val autoLoadAt = preferences.novelAutoLoadNextChapterAt.get()
                val effectiveThreshold = if (autoLoadAt > 0) autoLoadAt / 100f else 0.95f

                val onLastLoaded = currentChapterIndex == (loadedChapters.size - 1).coerceAtLeast(0)
                // Use `isTtsAutoPlay` alone — `isSpeaking()` can briefly return false between
                // chunks and during pause/resume, causing the scroll listener to start a
                // visible chapter fetch while TTS still owns the chapter transition.
                val ttsIsDrivingChapterHandoff = ttsController.isTtsAutoPlay
                if (!isRestoringScroll && !ttsIsDrivingChapterHandoff && chapterProgress >= effectiveThreshold &&
                    !isLoadingNext &&
                    onLastLoaded
                ) {
                    logcat(LogPriority.DEBUG) {
                        "NovelViewer: scroll threshold hit (progress=$chapterProgress >= $effectiveThreshold, currentIdx=$currentChapterIndex, loadedCount=${loadedChapters.size})"
                    }
                    loadNextChapterIfAvailable()
                } else if (!isRestoringScroll && ttsIsDrivingChapterHandoff && chapterProgress >= effectiveThreshold &&
                    onLastLoaded
                ) {
                    preFetchNextChapterForTts()
                }
            }
        }
    }

    private fun calculateCurrentChapterProgress(scrollY: Int): Float {
        val loaded = loadedChapters.getOrNull(currentChapterIndex) ?: return 0f
        val textTop = loaded.block.container.top
        val textBottom = loaded.block.container.bottom
        val textHeight = textBottom - textTop

        // Guard: if the textView hasn't been laid out yet, its height will be 0.
        // Returning 1f (100%) here would cause a spurious progress jump; keep last known progress.
        if (textHeight <= 0) return lastSavedProgress.coerceIn(0f, 1f)

        // If the chapter text fits entirely within the viewport, it's 100% visible.
        // Guard isTextSet: an empty textView has a small but non-zero layout height that
        // would falsely satisfy textHeight <= scrollView.height before content arrives.
        if (textHeight <= scrollView.height) {
            val page = loaded.chapter.pages?.firstOrNull()
            if (!loaded.isTextSet) return lastSavedProgress.coerceIn(0f, 1f)
            return if (shouldAutoMarkShortChapter(page)) 1f else lastSavedProgress.coerceIn(0f, 1f)
        }

        val scrollableHeight = (textHeight - scrollView.height).coerceAtLeast(1)
        val scrollInText = (scrollY - textTop).coerceIn(0, scrollableHeight)
        return (scrollInText.toFloat() / scrollableHeight).coerceIn(0f, 1f)
    }

    private fun scheduleProgressSave(progress: Float) {
        val intPercent = (progress * 100f).roundToInt().coerceIn(0, 100)
        val lastIntPercent = (lastSavedProgress * 100f).roundToInt().coerceIn(0, 100)
        if (intPercent == lastIntPercent) return

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

    /**
     * Persists progress for the chapter currently being spoken by TTS based on
     * chunk index. The scroll-based save path does not fire when the activity
     * is in the background (no scroll events), so TTS sessions running under
     * the foreground service would lose progress until the user returns. This
     * hook runs on every chunk advance and is independent of scroll.
     */
    private var lastSavedTtsChunkIndex: Int = -1
    private fun saveTtsProgressForChunk(chunkIndex: Int) {
        if (chunkIndex == lastSavedTtsChunkIndex) return
        lastSavedTtsChunkIndex = chunkIndex
        val total = ttsController.ttsChunks.size
        if (total <= 0) return
        // Prefer the chapter id over the index: trimming read chapters shifts indices, so an id
        // lookup can't save progress against the wrong chapter if the index is momentarily stale.
        val loaded = (ttsController.ttsPlaybackChapterId?.let { id ->
            loadedChapters.firstOrNull { it.chapter.chapter.id == id }
        } ?: loadedChapters.getOrNull(ttsController.ttsPlaybackChapterIndex)) ?: return
        val page = loaded.chapter.pages?.firstOrNull() ?: return
        val percent = (((chunkIndex + 1) * 100f) / total).roundToInt().coerceIn(0, 100)
        activity.saveNovelProgress(page, percent)
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
        // Don't mark while the block still shows empty layout (text set hasn't completed yet).
        if (!loaded.isTextSet) return
        val textHeight = loaded.block.container.height
        if (textHeight <= 0 || textHeight > scrollView.height) return

        lastSavedProgress = 1f
        saveProgress(1f)
        activity.onNovelProgressChanged(1f)

        // Trigger infinite scroll append manually.
        val onLastLoaded = currentChapterIndex == (loadedChapters.size - 1).coerceAtLeast(0)
        if (preferences.novelInfiniteScroll.get() && !ttsController.isTtsAutoPlay && onLastLoaded) {
            loadNextChapterIfAvailable()
        }
    }

    private fun updateCurrentChapterFromScroll(scrollY: Int) {
        if (loadedChapters.size <= 1) return

        // Find the FIRST chapter whose text has not yet been completely scrolled past.
        // By using the block bottom (not headerView.top), the separator belongs to neither chapter:
        // while the separator is visible, scrollY is below prev chapter's text and above next
        // chapter's text → next chapter is detected (progress = 0% since scrollY < textTop).
        for ((index, loaded) in loadedChapters.withIndex()) {
            if (loaded.block.container.bottom > scrollY) {
                if (currentChapterIndex != index) {
                    onChapterChanged(oldIndex = currentChapterIndex, newIndex = index)
                }
                break
            }
        }
    }

    private fun onChapterChanged(oldIndex: Int, newIndex: Int) {
        val now = System.currentTimeMillis()
        if (now - lastChapterSwitchTime < 50L) return
        lastChapterSwitchTime = now

        currentChapterIndex = newIndex

        val initialProgress = if (newIndex > oldIndex) 0f else 1f
        lastSavedProgress = initialProgress
        chapterEntryTime = now

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
            logcat(LogPriority.ERROR) {
                "NovelViewer: loadNext failed, no anchor (loadedCount=${loadedChapters.size})"
            }
            inlineFeedback.showInlineError("No anchor chapter for infinite scroll", isPrepend = false)
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
                    inlineFeedback.showInlineError("No next chapter available", isPrepend = false)
                    return@launch
                }
                logcat(LogPriority.DEBUG) {
                    "NovelViewer: prepared next=${preparedChapter.chapter.id}/${preparedChapter.chapter.name}"
                }

                if (loadedChapters.any { it.chapter.chapter.id == preparedChapter.chapter.id }) {
                    logcat(LogPriority.DEBUG) {
                        "NovelViewer: next chapter ${preparedChapter.chapter.id} already loaded"
                    }
                    return@launch
                }
                val page = preparedChapter.pages?.firstOrNull() ?: run {
                    logcat(LogPriority.ERROR) { "NovelViewer: No page in prepared next chapter" }
                    inlineFeedback.showInlineError("No page in next chapter", isPrepend = false)
                    return@launch
                }
                val loader = page.chapter.pageLoader ?: run {
                    logcat(LogPriority.ERROR) { "NovelViewer: No loader for next chapter" }
                    inlineFeedback.showInlineError("No loader for next chapter", isPrepend = false)
                    return@launch
                }

                inlineFeedback.showInlineLoading(isPrepend = false)
                logcat(LogPriority.DEBUG) {
                    "NovelViewer: loading page for next ${preparedChapter.chapter.id}, state=${page.status}"
                }

                val loaded = try {
                    awaitPageText(page = page, loader = loader, timeoutMs = 30_000)
                } catch (_: TimeoutCancellationException) {
                    logcat(LogPriority.ERROR) { "NovelViewer: Timed out loading next chapter page after 30s" }
                    inlineFeedback.showInlineError("Timeout loading next chapter", isPrepend = false)
                    false
                } catch (_: CancellationException) {
                    // Reader was closed/navigated away; don't surface as an error.
                    logcat(LogPriority.DEBUG) { "NovelViewer: loadNext cancelled" }
                    false
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "NovelViewer: Error loading next chapter page: ${e.message}" }
                    inlineFeedback.showInlineError("Error: ${e.message ?: "Unknown error"}", isPrepend = false)
                    false
                }

                if (!loaded) return@launch

                logcat(LogPriority.DEBUG) { "NovelViewer: appending chapter ${preparedChapter.chapter.id}" }
                displayChapter(preparedChapter, page)
                logcat(LogPriority.INFO) {
                    "NovelViewer: Successfully appended next chapter ${preparedChapter.chapter.name}"
                }
            } finally {
                inlineFeedback.hideInlineLoading()
                isLoadingNext = false
            }
        }
    }

    private suspend fun awaitPageText(
        page: ReaderPage,
        loader: eu.kanade.tachiyomi.ui.reader.loader.PageLoader,
        timeoutMs: Long,
    ): Boolean = NovelPageLoader.awaitPageText("NovelViewer", page, loader, timeoutMs, scope)

    private fun reloadContent() {
        activity.runOnUiThread {
            currentChapters?.let {
                contentContainer.removeAllViews()
                chapterQueue.clear()
                setChapters(it)
            }
        }
    }

    private fun observePreferences() {
        NovelTextViewPreferenceObserver(
            preferences = preferences,
            scope = scope,
            onStylePrefChanged = ::refreshAllChapterStyles,
            onContentReloadRequested = ::reloadContent,
            onTtsSettingsChanged = {
                if (ttsController.ttsInitialized) ttsController.applySettings()
            },
            onInfiniteScrollChanged = { infiniteEnabled ->
                activity.runOnUiThread {
                    if (infiniteEnabled) {
                        contentContainer.findViewWithTag<View>(NEXT_CHAPTER_BUTTON_TAG)?.let {
                            contentContainer.removeView(it)
                        }
                    } else {
                        addNextChapterButton()
                    }
                }
            },
        ).observe()
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
                        val rememberItem = menu.add(
                            Menu.NONE,
                            1,
                            Menu.NONE,
                            activity.stringResource(TDMR.strings.action_remember),
                        )
                        rememberItem.setIcon(android.R.drawable.ic_menu_save)
                        rememberItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        return true
                    }

                    override fun onPrepareActionMode(mode: android.view.ActionMode, menu: Menu): Boolean {
                        return false
                    }

                    override fun onActionItemClicked(mode: android.view.ActionMode, item: MenuItem): Boolean {
                        return when (item.itemId) {
                            1 -> {
                                onRememberSelectedText()
                                mode.finish()
                                true
                            }
                            else -> false
                        }
                    }

                    override fun onDestroyActionMode(mode: android.view.ActionMode) {
                    }
                }
            }
        }
    }

    private fun createChapterBlock(): ChapterTextBlock =
        ChapterTextBlock(activity) { createSelectableTextView().also(::applyTextViewStyles) }

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
                loaded.block.chunkViews.forEach(::applyTextViewStyles)
            }
        }
        applyBackgroundColor()
    }

    private var pendingTtsAutoStart = false

    // Set when an inf-scroll TTS handoff found nothing prefetched and kicked a prefetch;
    // the prefetch completion retries the handoff.
    private var pendingTtsHandoffAfterPrefetch = false

    @Volatile private var handoffState: TtsHandoffState<LoadedChapter> = TtsHandoffState.Idle

    private val ttsController = TtsController(
        context = activity,
        preferences = preferences,
        scope = scope,
        callbacks = object : TtsController.Callbacks {
            override fun onInitialized(pendingRequest: TtsController.StartRequest?) {
                when (pendingRequest) {
                    TtsController.StartRequest.NORMAL -> startTts()
                    TtsController.StartRequest.VIEWPORT -> startTtsFromViewport()
                    null -> {}
                }
            }

            override fun getCurrentPage(): ReaderPage? = currentPage

            override fun onHighlightChunk(chunkIndex: Int, chunk: String, startOffset: Int, paragraphIndex: Int) {
                applyTtsHighlight(chunk, startOffset)
                saveTtsProgressForChunk(chunkIndex)
            }

            override fun onClearHighlights() {
                clearAllTtsHighlights()
            }

            override fun onLastChunkDone() {
                syncCurrentChapterIndexWithCurrentPage()
                loadNextChapterForTts(currentChapterIndex)
            }

            override fun runOnUiThread(action: () -> Unit) {
                activity.runOnUiThread(action)
            }
        },
    )

    /** Must be called on the UI thread. */
    private fun clearTtsSpansInLoadedChapters() {
        loadedChapters.forEach { loaded ->
            if (!loaded.isLoaded) return@forEach
            loaded.block.chunkViews.forEach { chunkView ->
                val spanned = chunkView.text as? android.text.Spannable ?: return@forEach
                val len = spanned.length
                spanned.getSpans(0, len, BackgroundColorSpan::class.java).forEach { spanned.removeSpan(it) }
                spanned.getSpans(0, len, ForegroundColorSpan::class.java).forEach { spanned.removeSpan(it) }
                spanned.getSpans(0, len, UnderlineSpan::class.java).forEach { spanned.removeSpan(it) }
                spanned.getSpans(0, len, RoundedOutlineSpan::class.java).forEach { spanned.removeSpan(it) }
            }
        }
    }

    private fun clearAllTtsHighlights() {
        activity.runOnUiThread { clearTtsSpansInLoadedChapters() }
    }

    private fun applyTtsHighlight(chunk: String, startOffset: Int) {
        activity.runOnUiThread {
            val highlightColor = preferences.novelTtsHighlightColor.get()
            val highlightTextColor = preferences.novelTtsHighlightTextColor.get()
            val highlightStyle = preferences.novelTtsHighlightStyle.get()
            val keepInView = preferences.novelTtsKeepHighlightInView.get()

            clearTtsSpansInLoadedChapters()

            val targetLoaded = (
                ttsController.ttsPlaybackChapterId?.let { id ->
                    loadedChapters.firstOrNull { it.chapter.chapter.id == id }
                } ?: loadedChapters.getOrNull(ttsController.ttsPlaybackChapterIndex)
                ) ?: return@runOnUiThread
            if (!targetLoaded.isLoaded || !targetLoaded.isTextSet) return@runOnUiThread
            val block = targetLoaded.block
            val text = block.fullText ?: return@runOnUiThread

            val startIndex = text.indexOf(chunk, startOffset.coerceAtLeast(0))
            if (startIndex < 0) return@runOnUiThread
            val endIndex = (startIndex + chunk.length).coerceAtMost(text.length)

            var firstChunkView: TextView? = null
            var firstChunkLocalStart = 0
            for ((i, chunkView) in block.chunkViews.withIndex()) {
                val chunkStart = block.chunkStarts.getOrNull(i) ?: break
                val chunkEnd = chunkStart + chunkView.text.length
                if (chunkEnd <= startIndex) continue
                if (chunkStart >= endIndex) break
                val spanned = chunkView.text as? android.text.Spannable ?: continue
                val localStart = (startIndex - chunkStart).coerceAtLeast(0)
                val localEnd = (endIndex - chunkStart).coerceAtMost(spanned.length)
                if (localEnd <= localStart) continue
                if (firstChunkView == null) {
                    firstChunkView = chunkView
                    firstChunkLocalStart = localStart
                }
                when (highlightStyle) {
                    "underline" -> spanned.setSpan(
                        UnderlineSpan(),
                        localStart,
                        localEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    "outline" -> {
                        spanned.setSpan(
                            RoundedOutlineSpan(highlightColor),
                            localStart,
                            localEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        spanned.setSpan(
                            ForegroundColorSpan(highlightTextColor),
                            localStart,
                            localEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    else -> {
                        spanned.setSpan(
                            BackgroundColorSpan(highlightColor),
                            localStart,
                            localEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        spanned.setSpan(
                            ForegroundColorSpan(highlightTextColor),
                            localStart,
                            localEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }

            if (keepInView) {
                val scrollTarget = firstChunkView ?: return@runOnUiThread
                val localStart = firstChunkLocalStart
                fun doScroll() {
                    val layout = scrollTarget.layout ?: return
                    val line = layout.getLineForOffset(localStart.coerceAtMost(layout.text.length - 1).coerceAtLeast(0))
                    val targetInChunk = layout.getLineTop(line)
                    val targetInScroll =
                        block.container.top + scrollTarget.top + targetInChunk - (scrollView.height / 3)
                    scrollView.smoothScrollTo(0, targetInScroll.coerceAtLeast(0))
                }
                if (scrollTarget.layout != null) doScroll() else scrollTarget.post { doScroll() }
            }
        }
    }

    private fun loadNextChapterForTts(anchorChapterIndex: Int = currentChapterIndex) {
        logcat(LogPriority.DEBUG) {
            "TTS: Auto-loading next chapter ts=${System.currentTimeMillis()} ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex} ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}"
        }

        if (preferences.novelInfiniteScroll.get()) {
            if (moveToLoadedNextChapterForTts(anchorChapterIndex)) {
                // Free chapters well behind the playback head so a long unattended autoplay run
                // doesn't keep every read chapter's views + precomputed text in memory.
                trimReadChaptersForTts()
                // For already-loaded chapters, text is ready — start immediately.
                // For pre-fetched chapters, moveToLoadedNextChapterForTts sets pendingTtsAutoStart
                // and fires setChapterContent; startTts() will be called once the chunks are ready.
                if (!pendingTtsAutoStart) {
                    startTts()
                }
            } else {
                // Nothing prefetched: TTS reads in place, so the scroll-driven prefetch never
                // fired. Fetch the next chapter now and retry the handoff once it's cached.
                logcat(LogPriority.DEBUG) { "TTS: next chapter not ready, prefetching for handoff" }
                pendingTtsHandoffAfterPrefetch = true
                preFetchNextChapterForTts()
            }
            return
        }

        val chapters = currentChapters ?: return
        if (chapters.nextChapter == null) {
            // End of novel: stop so the background service tears down instead of
            // lingering with isTtsAutoPlay stuck true.
            stopTts()
            return
        }

        pendingTtsAutoStart = true
        scope.launch {
            // Must NOT use activity.loadNextChapter(): it stops TTS (manual-nav)
            // and clears pendingTtsAutoStart, so playback never resumes.
            activity.loadNextChapterForTtsHandoff()
        }
    }

    private fun preFetchNextChapterForTts() {
        if (!handoffState.isIdle) return
        handoffState = TtsHandoffState.PreFetching(
            anchorChapterId = (loadedChapters.lastOrNull()?.chapter ?: currentChapters?.currChapter)?.chapter?.id,
        )

        scope.launch {
            try {
                val anchor = loadedChapters.lastOrNull()?.chapter ?: currentChapters?.currChapter ?: return@launch
                val preparedChapter = activity.viewModel.prepareNextChapterForInfiniteScroll(anchor) ?: return@launch
                val nextId = preparedChapter.chapter.id ?: return@launch
                if (loadedChapters.any { it.chapter.chapter.id == nextId }) return@launch
                val page = preparedChapter.pages?.firstOrNull() ?: return@launch
                val loader = page.chapter.pageLoader ?: return@launch

                logcat(LogPriority.DEBUG) { "TTS: Pre-fetching next chapter ${preparedChapter.chapter.name}" }

                val textLoaded = try {
                    awaitPageText(page = page, loader = loader, timeoutMs = 30_000)
                } catch (e: Exception) {
                    logcat(LogPriority.WARN) { "TTS: Pre-fetch failed: ${e.message}" }
                    return@launch
                }
                if (!textLoaded) return@launch

                val rawContent = page.text ?: return@launch
                val cfg = ContentConfig.from(
                    preferences,
                    RenderTarget.TEXT_VIEW,
                    preparedChapter.chapter.url,
                    preparedChapter.chapter.name,
                )
                val translator: (suspend (String) -> String)? =
                    if (activity.isTranslationEnabled() && !preferences.novelShowRawHtml.get()) {
                        { activity.translateContentIfEnabled(it) }
                    } else {
                        null
                    }
                val finalContent = withContext(Dispatchers.Default) {
                    contentPipeline.process(rawContent, cfg, translator)
                }

                withContext(Dispatchers.Main) {
                    if (handoffState.cachedOrNull != null) return@withContext
                    if (loadedChapters.any { it.chapter.chapter.id == nextId }) return@withContext

                    val headerView = TextView(activity).apply {
                        text = preparedChapter.chapter.name
                        textSize = 18f
                        setTextColor(0xFF888888.toInt())
                        gravity = Gravity.CENTER
                        setPadding(16, 32, 16, 16)
                        isVisible = false
                    }
                    val cachedChapter = LoadedChapter(
                        chapter = preparedChapter,
                        block = createChapterBlock(),
                        headerView = headerView,
                        isLoaded = true,
                        isTextSet = false,
                        pendingContent = finalContent,
                    )
                    handoffState = TtsHandoffState.Cached(cachedChapter)
                    logcat(LogPriority.DEBUG) { "TTS: Cached next chapter ${preparedChapter.chapter.name}" }

                    if (pendingTtsHandoffAfterPrefetch) {
                        pendingTtsHandoffAfterPrefetch = false
                        loadNextChapterForTts(currentChapterIndex)
                    }
                }
            } finally {
                if (handoffState.isPreFetching) handoffState = TtsHandoffState.Idle
                // Flag still set: prefetch ended without caching (fetch failed or no next
                // chapter). Stop instead of leaving isTtsAutoPlay stuck true.
                if (pendingTtsHandoffAfterPrefetch) {
                    pendingTtsHandoffAfterPrefetch = false
                    stopTts()
                }
            }
        }
    }

    private fun syncCurrentChapterIndexWithCurrentPage(): Boolean {
        val targetChapterId = currentPage?.chapter?.chapter?.id
            ?: currentChapters?.currChapter?.chapter?.id
            ?: return false
        val targetIndex = loadedChapters.indexOfFirst { it.chapter.chapter.id == targetChapterId }
        if (targetIndex < 0) return false
        currentChapterIndex = targetIndex
        return true
    }

    private fun moveToLoadedNextChapterForTts(anchorChapterIndex: Int = currentChapterIndex): Boolean {
        logcat(LogPriority.DEBUG) {
            "TTS: moveToLoadedNextChapterForTts anchor=$anchorChapterIndex ts=${System.currentTimeMillis()} ttsCurrentChunkIndex=${ttsController.ttsCurrentChunkIndex} ttsResumeChunkIndex=${ttsController.ttsResumeChunkIndex} ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex} ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}"
        }
        val nextIndex = anchorChapterIndex + 1
        val nextLoaded = loadedChapters.getOrNull(nextIndex)

        if (nextLoaded == null) {
            val cached = handoffState.cachedOrNull ?: return false
            handoffState = TtsHandoffState.Idle

            isRestoringScroll = true
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
            cached.separatorView = separator
            contentContainer.addView(cached.headerView)
            contentContainer.addView(cached.block.container)
            chapterQueue.append(cached)
            scrollView.post { isRestoringScroll = false }

            currentChapterIndex = loadedChapters.size - 1
            cached.chapter.pages?.firstOrNull()?.let { page ->
                currentPage = page
                activity.viewModel.setNovelVisibleChapter(cached.chapter.chapter)
                activity.onPageSelected(page)
                activity.onNovelProgressChanged(0f)
            }
            logcat(LogPriority.DEBUG) {
                "TTS: used pre-fetched chapter ${cached.chapter.chapter.name} ts=${System.currentTimeMillis()}"
            }

            // View is now attached — render the stored content. setChapterContent's coroutine
            // will set pendingTtsAutoStart → startTts() once the chunks are ready.
            val content = cached.pendingContent
            if (content != null) {
                cached.pendingContent = null
                pendingTtsAutoStart = true
                setChapterContent(cached, content)
            }
            return true
        }

        currentChapterIndex = nextIndex
        logcat(LogPriority.DEBUG) { "TTS: moved to loaded nextIndex=$nextIndex ts=${System.currentTimeMillis()}" }

        nextLoaded.chapter.pages?.firstOrNull()?.let { page ->
            currentPage = page
            activity.viewModel.setNovelVisibleChapter(nextLoaded.chapter.chapter)
            activity.onPageSelected(page)
            activity.onNovelProgressChanged(0f)
        }

        return true
    }

    // Read chapters kept behind the TTS head for back-scroll; older ones unloaded to bound memory.
    private val ttsKeepBehindChapters = 1

    // Drops chapters far behind the TTS head and scrolls back their height so the read chapter
    // doesn't jump. Autoplay only. Decrements currentChapterIndex and ttsPlaybackChapterIndex by
    // removeCount to match the now-shorter loadedChapters, else reads/progress saves point at the
    // wrong chapter.
    private fun trimReadChaptersForTts() {
        if (!ttsController.isTtsAutoPlay) return
        val removeCount = currentChapterIndex - ttsKeepBehindChapters
        if (removeCount <= 0) return
        val toRemove = loadedChapters.take(removeCount)
        if (toRemove.isEmpty()) return

        var removedHeight = 0
        isRestoringScroll = true
        toRemove.forEach { lc ->
            lc.separatorView?.let { sep ->
                removedHeight += sep.height
                contentContainer.removeView(sep)
            }
            removedHeight += lc.headerView.height
            contentContainer.removeView(lc.headerView)
            removedHeight += lc.block.container.height
            contentContainer.removeView(lc.block.container)
        }
        chapterQueue.removeFirstN(removeCount)
        currentChapterIndex = (currentChapterIndex - removeCount).coerceAtLeast(0)
        ttsController.shiftPlaybackChapterIndex(removeCount)
        // Removed views were above the viewport; compensate so the read chapter stays put.
        if (removedHeight > 0) scrollView.scrollBy(0, -removedHeight)
        scrollView.post { isRestoringScroll = false }
        logcat(LogPriority.DEBUG) {
            "TTS: trimmed $removeCount read chapter(s), now ${loadedChapters.size} loaded, currentIndex=$currentChapterIndex"
        }
    }

    fun startTts() {
        ttsController.ensureInitialized()
        if (!ttsController.ttsInitialized) {
            logcat(LogPriority.WARN) { "TTS: Not initialized yet, waiting..." }
            ttsController.pendingStartRequest = TtsController.StartRequest.NORMAL
            return
        }
        ttsController.pendingStartRequest = null
        ttsController.isTtsAutoPlay = true
        syncCurrentChapterIndexWithCurrentPage()
        val text = loadedChapters.getOrNull(currentChapterIndex)?.block?.fullText
            ?: loadedChapters.firstOrNull()?.block?.fullText
        if (text.isNullOrEmpty()) {
            // Chapter text hasn't rendered yet; defer so onChapterTextSet starts playback
            // once it's ready instead of failing the start silently.
            logcat(LogPriority.WARN) {
                "TTS: text not ready, deferring start. loadedChapters=${loadedChapters.size}, currentIndex=$currentChapterIndex"
            }
            pendingTtsAutoStart = true
            return
        }
        logcat(LogPriority.DEBUG) { "TTS: Starting to speak ${text.length} characters" }
        ttsController.speak(
            text,
            chapterIndex = currentChapterIndex,
            chapterId = currentPage?.chapter?.chapter?.id ?: currentChapters?.currChapter?.chapter?.id,
        )

        // Inf-scroll TTS reads in place, so the scroll listener that normally kicks the
        // prefetch may never reach the load threshold (keep-in-view off, or a chapter shorter
        // than the viewport). Start the prefetch when playback begins on the last loaded
        // chapter so the next chapter is cached before onLastChunkDone hands off; otherwise
        // the handoff stalls on a cold fetch and playback fails to advance. Idempotent: the
        // prefetch no-ops unless handoffState is Idle.
        if (preferences.novelInfiniteScroll.get() &&
            currentChapterIndex == (loadedChapters.size - 1).coerceAtLeast(0)
        ) {
            preFetchNextChapterForTts()
        }
    }

    fun stopTts() {
        logcat(LogPriority.DEBUG) {
            "TTS: stopTts called ts=${System.currentTimeMillis()} currentChapterIndex=$currentChapterIndex ttsCurrentChunkIndex=${ttsController.ttsCurrentChunkIndex} ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex} ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}"
        }
        pendingTtsAutoStart = false
        handoffState = TtsHandoffState.Idle
        ttsController.stop()
    }

    /**
     * A deferred TTS start ([pendingTtsAutoStart]) waits for [onChapterTextSet], which
     * never fires when the chapter fails to load. Clear the pending session so
     * isTtsAutoPlay doesn't stay stuck true (which would keep the background
     * notification/service alive with nothing playing).
     */
    private fun abortPendingTtsOnLoadFailure() {
        if (pendingTtsAutoStart || ttsController.isTtsAutoPlay) {
            stopTts()
        }
    }

    fun pauseTts() {
        logcat(LogPriority.DEBUG) {
            "TTS: pauseTts called ts=${System.currentTimeMillis()} ttsCurrentChunkIndex=${ttsController.ttsCurrentChunkIndex}"
        }
        ttsController.pause()
    }

    fun resumeTts() {
        ttsController.resume()
    }

    fun ttsNextParagraph() {
        ttsController.stepParagraph(1) { startTtsFromViewport() }
    }

    fun ttsPreviousParagraph() {
        ttsController.stepParagraph(-1) { startTtsFromViewport() }
    }

    fun isTtsPaused(): Boolean = ttsController.isPaused()
    fun isTtsSpeaking(): Boolean = ttsController.isSpeaking()

    /**
     * High-level "TTS session active" flag for the background-notification
     * sync. Stays `true` across the brief stop/restart gap inside
     * `stepParagraph` so the periodic sync doesn't tear down the foreground
     * service mid-step.
     */
    fun isTtsActive(): Boolean =
        ttsController.isTtsAutoPlay || ttsController.isSpeaking() ||
            ttsController.isPaused() || ttsController.isStarting()
    fun getTtsProgressPercent(): Int = ttsController.getProgressPercent()

    fun startTtsFromViewport() {
        ttsController.ensureInitialized()
        if (!ttsController.ttsInitialized) {
            logcat(LogPriority.WARN) { "TTS: Not initialized yet" }
            ttsController.pendingStartRequest = TtsController.StartRequest.VIEWPORT
            return
        }
        ttsController.pendingStartRequest = null
        ttsController.isTtsAutoPlay = true
        syncCurrentChapterIndexWithCurrentPage()
        val text = loadedChapters.getOrNull(currentChapterIndex)?.block?.fullText
            ?: loadedChapters.firstOrNull()?.block?.fullText
        if (text.isNullOrEmpty()) {
            // Chapter text not rendered yet; defer to onChapterTextSet rather than no-op.
            logcat(LogPriority.WARN) { "TTS: text not ready for viewport start, deferring" }
            pendingTtsAutoStart = true
            return
        }
        val firstVisibleParagraphIndex = findFirstVisibleParagraphIndex(text)
        logcat(LogPriority.DEBUG) { "TTS: Starting from viewport paragraph $firstVisibleParagraphIndex" }
        ttsController.ttsViewportParagraphIndex = firstVisibleParagraphIndex.coerceAtLeast(0)
        ttsController.hasViewportStartOverride = true
        ttsController.speak(
            text,
            chapterIndex = currentChapterIndex,
            chapterId = currentPage?.chapter?.chapter?.id ?: currentChapters?.currChapter?.chapter?.id,
        )
    }

    /**
     * Finds the index of the first paragraph visible in the current viewport.
     * Paragraph indexing MUST match speak()'s split (single \n, filter blank) so that
     * ttsViewportParagraphIndex aligns with ttsChunkParagraphIndexes.
     */
    private fun findFirstVisibleParagraphIndex(text: String): Int {
        val viewportTop = scrollView.scrollY
        val viewportBottom = viewportTop + scrollView.height

        // Same split as speak() so paragraph indexes are consistent.
        val paragraphLines = text.split("\n").filter { it.isNotBlank() }
        if (paragraphLines.isEmpty()) return 0

        // Build start-char offset for each paragraph within `text`.
        val paragraphOffsets = mutableListOf<Int>()
        var searchFrom = 0
        for (line in paragraphLines) {
            val idx = text.indexOf(line, searchFrom)
            if (idx >= 0) {
                paragraphOffsets.add(idx)
                searchFrom = idx + line.length
            } else {
                paragraphOffsets.add(searchFrom)
            }
        }

        loadedChapters.forEach { loaded ->
            if (!loaded.isLoaded) return@forEach
            val containerTop = loaded.block.container.top
            for ((i, chunkView) in loaded.block.chunkViews.withIndex()) {
                val chunkTop = containerTop + chunkView.top
                val chunkBottom = chunkTop + chunkView.height

                if (chunkBottom > viewportTop && chunkTop < viewportBottom) {
                    val layout = chunkView.layout ?: return 0
                    if (layout.lineCount > 0) {
                        val chunkRelativeY = (viewportTop - chunkTop).coerceAtLeast(0)
                        val lineAtViewportTop = layout.getLineForVertical(chunkRelativeY.coerceAtMost(layout.height))
                        val chunkStart = loaded.block.chunkStarts.getOrNull(i) ?: 0
                        val charOffset = (chunkStart + layout.getLineStart(lineAtViewportTop)).coerceAtLeast(0)
                        val pIdx = paragraphOffsets.indexOfLast { it <= charOffset }
                        return if (pIdx >= 0) pIdx else 0
                    }
                }
            }
        }

        return 0
    }

    override fun destroy() {
        getScrollProgress { progress ->
            saveProgress(progress)
        }

        ttsController.destroy()
        scope.cancel() // cancels loadJob and all other child coroutines
        chapterQueue.clear()
    }

    override fun getView(): View {
        return container
    }

    fun reloadWithTranslation() {
        val generation = ++renderGeneration
        loadedChapters.forEach { loadedChapter ->
            val page = loadedChapter.chapter.pages?.firstOrNull() ?: return@forEach
            val content = page.text ?: return@forEach
            val block = loadedChapter.block
            val cfg = ContentConfig.from(
                preferences,
                RenderTarget.TEXT_VIEW,
                loadedChapter.chapter.chapter.url,
                loadedChapter.chapter.chapter.name,
            )

            scope.launch {
                val pre = withContext(Dispatchers.Default) { contentPipeline.preTranslate(content, cfg) }
                val processed = if (activity.isTranslationEnabled()) {
                    val chapterId = loadedChapter.chapter.chapter.id
                    val labelRes = if (activity.hasCachedTranslation(chapterId)) {
                        TDMR.strings.novel_chapter_translating_from_cache
                    } else {
                        TDMR.strings.novel_chapter_translating_from_api
                    }
                    block.showPlaceholder(activity.stringResource(labelRes))
                    withContext(Dispatchers.Default) {
                        contentPipeline.finalize(pre, cfg) { activity.translateContentIfEnabled(it, chapterId) }
                    }
                } else {
                    withContext(Dispatchers.Default) { contentPipeline.finalize(pre, cfg) }
                }
                if (generation != renderGeneration) return@launch
                if (!block.container.isAttachedToWindow) return@launch
                setChapterContent(loadedChapter, processed)
            }
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        renderGeneration++
        val page = chapters.currChapter.pages?.firstOrNull() ?: return

        loadJob?.cancel()
        currentPage = page
        currentChapters = chapters

        val isAlreadyLoaded = loadedChapters.any { it.chapter.chapter.id == chapters.currChapter.chapter.id }

        if (preferences.novelInfiniteScroll.get() && loadedChapters.isNotEmpty() && isAlreadyLoaded) {
            return
        }

        if (!preferences.novelInfiniteScroll.get() || !isAlreadyLoaded) {
            contentContainer.removeAllViews()
            chapterQueue.clear()
        }

        if (page.status == Page.State.Ready && !page.text.isNullOrEmpty()) {
            hideLoadingIndicator()
            displayChapter(chapters.currChapter, page)
            restoreProgress(page)
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

            val state = try {
                withTimeout(30_000L) {
                    page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
                }
            } catch (e: TimeoutCancellationException) {
                hideLoadingIndicator()
                abortPendingTtsOnLoadFailure()
                displayError(e)
                return@launch
            }
            when (state) {
                Page.State.Ready -> {
                    hideLoadingIndicator()
                    displayChapter(chapters.currChapter, page)
                    restoreProgress(page)
                    activity.viewModel.setNovelVisibleChapter(page.chapter.chapter)
                }
                is Page.State.Error -> {
                    hideLoadingIndicator()
                    abortPendingTtsOnLoadFailure()
                    displayError(state.error)
                }
                else -> {}
            }
        }
    }

    private fun displayChapter(chapter: ReaderChapter, page: ReaderPage) {
        val rawContent = page.text
        if (rawContent.isNullOrBlank()) {
            logcat(LogPriority.ERROR) { "NovelViewer: Page text is null or blank for chapter ${chapter.chapter.name}" }
            abortPendingTtsOnLoadFailure()
            displayError(Exception(activity.stringResource(TDMR.strings.novel_error_empty_chapter)))
            return
        }

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

        val headerView = TextView(activity).apply {
            text = chapter.chapter.name
            textSize = 18f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            setPadding(16, 32, 16, 16)
            // Never show a chapter boundary indicator during infinite scroll.
            isVisible = false
        }

        val block = createChapterBlock()

        val loadedChapter = LoadedChapter(
            chapter = chapter,
            block = block,
            headerView = headerView,
            isLoaded = true,
        )

        val isAppend = loadedChapters.isNotEmpty() && preferences.novelInfiniteScroll.get()

        chapterQueue.append(loadedChapter)

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

        contentContainer.addView(headerView)
        contentContainer.addView(block.container)

        val cfg = ContentConfig.from(
            preferences,
            RenderTarget.TEXT_VIEW,
            chapter.chapter.url,
            chapter.chapter.name,
        )
        scope.launch {
            val pre = withContext(Dispatchers.Default) { contentPipeline.preTranslate(rawContent, cfg) }
            if (activity.isTranslationEnabled() && !preferences.novelShowRawHtml.get()) {
                val chapterId = chapter.chapter.id
                val labelRes = if (activity.hasCachedTranslation(chapterId)) {
                    TDMR.strings.novel_chapter_translating_from_cache
                } else {
                    TDMR.strings.novel_chapter_translating_from_api
                }
                block.showPlaceholder(activity.stringResource(labelRes))
                val translated = withContext(Dispatchers.Default) {
                    contentPipeline.finalize(pre, cfg) { activity.translateContentIfEnabled(it, chapterId) }
                }
                setChapterContent(loadedChapter, translated)
            } else {
                val processed = withContext(Dispatchers.Default) { contentPipeline.finalize(pre, cfg) }
                setChapterContent(loadedChapter, processed)
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
            text = activity.stringResource(TDMR.strings.novel_chapter_next)
            isAllCaps = false
            textSize = 16f
            setTextColor(android.graphics.Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor("#ADD8E6".toColorInt())
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
        val maxChapters = 3
        while (loadedChapters.size > maxChapters && currentChapterIndex > 0) {
            val toRemove = loadedChapters.first()
            var removedHeight = toRemove.headerView.height + toRemove.block.container.height

            toRemove.separatorView?.let { sep ->
                removedHeight += sep.height
                contentContainer.removeView(sep)
            }

            contentContainer.removeView(toRemove.headerView)
            contentContainer.removeView(toRemove.block.container)
            chapterQueue.removeFirst()

            if (loadedChapters.isNotEmpty()) {
                loadedChapters.first().separatorView?.let { sep ->
                    removedHeight += sep.height
                    contentContainer.removeView(sep)
                    loadedChapters.first().separatorView = null
                }
            }

            scrollView.scrollBy(0, -removedHeight)

            logcat(LogPriority.DEBUG) { "NovelViewer: Removed distant chapter, adjusted scroll by -$removedHeight" }
        }
    }

    private fun restoreProgress(page: ReaderPage) {
        val savedProgress = page.chapter.chapter.last_page_read
        val isRead = page.chapter.chapter.read
        logcat(LogPriority.DEBUG) {
            "NovelViewer: Restoring progress, savedProgress=$savedProgress, isRead=$isRead for ${page.chapter.chapter.name}"
        }

        val shouldRestore = if (!isRead) {
            savedProgress in 1..100
        } else {
            libraryPreferences.novelReadProgress100.get() && savedProgress in 1..100
        }
        if (shouldRestore) {
            val progress = savedProgress / 100f
            lastSavedProgress = progress

            scrollView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    scrollView.viewTreeObserver.removeOnGlobalLayoutListener(this)

                    // Double-check the content is loaded
                    val child = scrollView.getChildAt(0) ?: return
                    val totalHeight = child.height - scrollView.height
                    if (totalHeight <= 0) {
                        scrollView.postDelayed({
                            isRestoringScroll = true
                            setScrollProgress(progress.coerceIn(0f, 1f))
                            logcat(LogPriority.DEBUG) {
                                "NovelViewer: Scroll restored (delayed) to ${(progress * 100).toInt()}%"
                            }
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

        textView.typeface = when {
            fontFamily.startsWith("file://") || fontFamily.startsWith("content://") -> {
                if (fontFamily == cachedFontUri && cachedTypeface != null) {
                    cachedTypeface!!
                } else {
                    try {
                        val fontUri = fontFamily.toUri()
                        val fontFile = if (fontFamily.startsWith("content://")) {
                            val tempFile = java.io.File(activity.cacheDir, "custom_font.ttf")
                            activity.contentResolver.openInputStream(fontUri)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            tempFile
                        } else {
                            java.io.File(fontUri.path ?: fontFamily.removePrefix("file://"))
                        }
                        android.graphics.Typeface.createFromFile(fontFile).also {
                            cachedFontUri = fontFamily
                            cachedTypeface = it
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR) { "Failed to load custom font: ${e.message}" }
                        android.graphics.Typeface.SANS_SERIF
                    }
                }
            }
            fontFamily.contains("serif", ignoreCase = true) && !fontFamily.contains("sans", ignoreCase = true) ->
                android.graphics.Typeface.SERIF
            fontFamily.contains("monospace", ignoreCase = true) ->
                android.graphics.Typeface.MONOSPACE
            else -> android.graphics.Typeface.SANS_SERIF
        }

        textView.gravity = when (textAlign) {
            "center" -> Gravity.CENTER_HORIZONTAL
            "right" -> Gravity.END
            "justify" -> Gravity.START // Android doesn't have justify, fallback to start
            else -> Gravity.START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            textView.justificationMode = if (textAlign == "justify") {
                LineBreaker.JUSTIFICATION_MODE_INTER_WORD
            } else {
                LineBreaker.JUSTIFICATION_MODE_NONE
            }
        }

        val (_, themeTextColor) = getThemeColors(theme)
        val finalTextColor = if (fontColor != 0) fontColor else themeTextColor
        textView.setTextColor(finalTextColor)
    }

    private fun applyBackgroundColor() {
        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()

        val (themeBgColor, _) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        scrollView.setBackgroundColor(finalBgColor)
    }

    private fun getThemeColors(theme: String): Pair<Int, Int> =
        ThemeUtils.getThemeColors(activity, preferences, theme)

    private fun setChapterContent(loaded: LoadedChapter, processed: ProcessedContent) {
        textRenderer.render(
            block = loaded.block,
            processed = processed,
            onTextSet = { onChapterTextSet(loaded) },
        )
    }

    private fun onChapterTextSet(loaded: LoadedChapter) {
        loaded.isTextSet = true
        if (pendingTtsAutoStart) {
            pendingTtsAutoStart = false
            val loadedIdx = loadedChapters.indexOf(loaded)
            if (loadedIdx >= 0) {
                currentChapterIndex = loadedIdx
                loaded.chapter.pages?.firstOrNull()?.let { page ->
                    currentPage = page
                    activity.viewModel.setNovelVisibleChapter(loaded.chapter.chapter)
                    activity.onPageSelected(page)
                    activity.onNovelProgressChanged(0f)
                }
            }
            startTts()
        }
    }

    private var initialLoadingView: TextView? = null

    private fun showLoadingIndicator() {
        contentContainer.removeAllViews()

        initialLoadingView = TextView(activity).apply {
            text = activity.stringResource(TDMR.strings.novel_chapter_loading)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 64, 32, 64)
            setTextColor(0xFF888888.toInt())
        }

        contentContainer.addView(initialLoadingView)
        applyBackgroundColor()
    }

    private fun hideLoadingIndicator() {
        initialLoadingView?.let { view ->
            contentContainer.removeView(view)
        }
        initialLoadingView = null
    }

    private fun displayError(error: Throwable) {
        val fmt = ErrorFormatter.format(error)
        logcat(LogPriority.ERROR) { "NovelViewer: Chapter load failed\n${fmt.stackTrace}" }

        val errorContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            )
            setPadding(48, 64, 48, 64)
        }

        val categoryView = TextView(activity).apply {
            text = fmt.category.localized(activity)
            textSize = 16f
            setTextColor(0xFFFF5555.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 12 }
        }

        val summaryView = TextView(activity).apply {
            text = fmt.summary
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 32 }
        }

        val copyButton = android.widget.Button(activity).apply {
            text = activity.stringResource(TDMR.strings.novel_error_copy_details)
            isAllCaps = false
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener {
                val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("error", fmt.stackTrace))
                activity.toast(activity.stringResource(TDMR.strings.novel_error_copied))
            }
        }

        errorContainer.addView(categoryView)
        errorContainer.addView(summaryView)
        errorContainer.addView(copyButton)

        contentContainer.removeAllViews()
        contentContainer.addView(errorContainer)
    }

    fun startAutoScroll() {
        val speed = preferences.novelAutoScrollSpeed.get().coerceIn(1, 10)
        isAutoScrolling = true

        autoScrollJob?.cancel()
        autoScrollJob = scope.launch {
            while (isActive && isAutoScrolling) {
                val scrollAmount = speed
                scrollView.smoothScrollBy(0, scrollAmount)
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

    fun scrollToTop() {
        scrollView.scrollTo(0, 0)
    }

    fun getScrollProgress(callback: (Float) -> Unit) {
        val scrollY = scrollView.scrollY
        val child = scrollView.getChildAt(0)
        val totalHeight = if (child != null) child.height - scrollView.height else 0
        val progress = if (totalHeight > 0) scrollY.toFloat() / totalHeight else 0f
        callback(progress)
    }

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
        val percent = if (progress >= 0.98f) 100 else (progress * 100).toInt()
        return percent.coerceIn(0, 100)
    }

    fun setScrollProgress(progress: Float) {
        if (loadedChapters.size <= 1 || !preferences.novelInfiniteScroll.get()) {
            val child = scrollView.getChildAt(0) ?: return
            val totalHeight = child.height - scrollView.height
            val scrollY = (totalHeight * progress).toInt()
            scrollView.scrollTo(0, scrollY)
            return
        }

        var accumulatedHeight = 0
        for ((index, loadedChapter) in loadedChapters.withIndex()) {
            val separatorHeight = loadedChapter.separatorView?.height ?: 0
            if (index == currentChapterIndex) {
                val chapterHeight =
                    loadedChapter.headerView.height + loadedChapter.block.container.height + separatorHeight
                val visibleHeight = scrollView.height
                val effectiveChapterHeight = (chapterHeight - visibleHeight).coerceAtLeast(1)
                val chapterScrollY = accumulatedHeight + (effectiveChapterHeight * progress).toInt()
                scrollView.scrollTo(0, chapterScrollY)
                return
            }
            accumulatedHeight +=
                loadedChapter.headerView.height + loadedChapter.block.container.height + separatorHeight
        }
    }

    fun setProgressPercent(percent: Int) {
        val progress = percent.coerceIn(0, 100) / 100f
        setScrollProgress(progress)
        // The scroll listener that drives infinite scroll can swallow a slider jump (grace
        // period after chapter entry, or the chapter-changed early-return), so a drag to the
        // end of the last loaded chapter would not load the next one. Re-check explicitly.
        maybeLoadNextChapterFromSlider()
    }

    private fun maybeLoadNextChapterFromSlider() {
        if (!preferences.novelInfiniteScroll.get()) return
        if (ttsController.isTtsAutoPlay || isLoadingNext || isRestoringScroll) return
        scrollView.post {
            val onLastLoaded = currentChapterIndex == (loadedChapters.size - 1).coerceAtLeast(0)
            if (!onLastLoaded) return@post
            val autoLoadAt = preferences.novelAutoLoadNextChapterAt.get()
            val effectiveThreshold = if (autoLoadAt > 0) autoLoadAt / 100f else 0.95f
            val chapterProgress = calculateCurrentChapterProgress(scrollView.scrollY)
            if (chapterProgress >= effectiveThreshold && !isLoadingNext) {
                logcat(LogPriority.DEBUG) { "NovelViewer: slider jump hit threshold ($chapterProgress), loading next" }
                loadNextChapterIfAvailable()
            }
        }
    }

    override fun moveToPage(page: ReaderPage) {
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

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    fun getSelectedText(): String? {
        val loaded = loadedChapters.getOrNull(currentChapterIndex) ?: return null
        return loaded.block.selectedText()
    }

    fun getCurrentChapterName(): String? {
        val loaded = loadedChapters.getOrNull(currentChapterIndex) ?: return null
        return loaded.chapter.chapter.name
    }

    fun clearTextSelection() {
        val loaded = loadedChapters.getOrNull(currentChapterIndex) ?: return
        loaded.block.clearSelections()
    }

    private fun onRememberSelectedText() {
        val selectedText = getSelectedText()
        val chapterName = getCurrentChapterName()

        if (selectedText != null && chapterName != null) {
            activity.onRememberSelectedText()
            clearTextSelection()
        } else {
            activity.toast("No text selected")
        }
    }
}
