package eu.kanade.tachiyomi.ui.reader.viewer.text

import android.annotation.SuppressLint
import android.content.Context
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.Keep
import eu.kanade.presentation.reader.settings.CodeSnippet
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import org.json.JSONObject
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * NovelWebViewViewer renders novel content using a WebView for more flexibility.
 * Supports custom CSS and JavaScript injection.
 */
class NovelWebViewViewer(val activity: ReaderActivity) : Viewer {

    private companion object {
        const val TSUNDOKU_CHAPTERS_CONTAINER_ID = "tsundoku-chapters-container"
        const val CHAPTER_DIVIDER_CLASS = "tsundoku-chapter-divider"
        const val CHAPTER_CONTENT_CLASS = "chapter-content"
        const val CHAPTER_ID_ATTR = "data-chapter-id"
        const val CHAPTER_TITLE_ATTR = "data-chapter-title"
        const val CHAPTER_NUMBER_ATTR = "data-chapter-number"
        const val CHAPTER_PATH_ATTR = "data-chapter-path"
        const val CHAPTER_URL_ATTR = "data-chapter-url"
        const val TSUNDOKU_CHAPTER_ATTR = "data-tsundoku-chapter"
        const val TSUNDOKU_OBJECT_NAME = "Tsundoku"
        const val TSUNDOKU_CURRENT_CHAPTER_KEY = "currentChapter"
        const val TSUNDOKU_CHAPTERS_KEY = "chapters"
        const val TSUNDOKU_NOVEL_URL_KEY = "novelUrl"
        const val TSUNDOKU_IS_EDIT_MODE_KEY = "isEditMode"
        const val TSUNDOKU_IS_INF_SCROLL_KEY = "isInfScroll"
        const val TSUNDOKU_TEXT_SELECTION_BLOCKED_KEY = "textSelectionBlocked"
        const val TSUNDOKU_FORCED_LOWERCASE_KEY = "forcedLowercase"
        const val REMEMBER_MENU_ITEM_ID = 0xBEEF // arbitrary unique ID

        const val CHAPTER_TAG_NAME = "tsundoku-chapter"
        const val PLAIN_TEXT_CLASS = "tsundoku-plain-text"
        const val ATTR_DATA_CHAPTER_ID = "data-chapter-id"
        const val ATTR_DATA_PLAIN_TEXT = "data-tsundoku-plain-text"
        const val ATTR_DATA_EDITABLE = "data-tsundoku-editable"
        const val STYLE_ID_CUSTOM = "tsundoku-custom-style"
        const val ID_NEXT_CHAPTER_BTN_CONTAINER = "next-chapter-btn-container"
        const val ID_INLINE_LOADING = "inline-loading"
        const val ID_INLINE_ERROR = "inline-error"
        const val ID_EDIT_MODE_STYLE = "edit-mode-style"
        const val URL_SCHEME_NOVEL_IMAGE = "tsundoku-novel-image://"
    }

    private val container = FrameLayout(activity)
    private lateinit var webView: WebView
    private var loadingIndicator: ReaderProgressIndicator? = null
    private val preferences: ReaderPreferences by injectLazy()
    private val translationPreferences: TranslationPreferences by injectLazy()
    private val libraryPreferences: tachiyomi.domain.library.service.LibraryPreferences by injectLazy()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var currentPage: ReaderPage? = null
    private var currentChapters: ViewerChapters? = null
    private val novelImageCache = ConcurrentHashMap<String, File>()
    private val novelImagePrefetchJobs = ConcurrentHashMap<String, Job>()

    // Track scroll progress
    private var lastSavedProgress = 0f

    // Infinite scroll state tracking
    private var isInfiniteScrollNavigation = false
    private var isInfiniteScrollPrepend = false
    private var loadedChapterIds = mutableListOf<Long>()
    private var loadedChapters = mutableListOf<ReaderChapter>()
    private var currentChapterIndex = 0
    private var isLoadingNext = false
    private var isDestroyed = false
    private var isEditingMode = false

    // Auto-scroll state
    private var isAutoScrolling = false
    private var autoScrollJob: Job? = null

    private var navigator: eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation = eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation()

    // TTS — delegates to TtsController for engine/state logic.
    @Volatile private var ttsIsPreparing = false
    @Volatile private var ttsIsStarting = false
    private var pendingTtsHandoffChapterId: Long? = null
    private var pendingTtsHandoffUseViewport: Boolean = false
    private var pendingTtsHandoffStarted: Boolean = false
    private var pendingTtsHandoffTimeoutJob: kotlinx.coroutines.Job? = null
    private var cachedNextChapterForTts: Pair<ReaderChapter, ReaderPage>? = null
    private var pendingTtsAppend = false
    // Survives ttsController.stop() — set when TTS triggers a non-inf-scroll chapter load.
    private var pendingTtsAutoStartOnLoad = false
    // True only while loadHtmlContent() has called loadDataWithBaseURL for real chapter content
    // (not the loading-indicator page). Lets onPageFinished distinguish real vs loading loads.
    private var isLoadingRealChapter = false

    private lateinit var ttsController: TtsController

    private data class CustomStylePayload(
        val css: String,
        val hideChapterTitle: Boolean,
        val backgroundColor: Int,
    )

    var pendingSelectedText: String? = null

    private val gestureDetector = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {
            // Increased thresholds for less sensitive swipe detection
            private val SWIPE_THRESHOLD = 150
            private val SWIPE_VELOCITY_THRESHOLD = 200

            // Require horizontal swipe to be significantly more horizontal than vertical
            private val DIRECTION_RATIO = 1.5f

            override fun onDown(e: MotionEvent): Boolean = false

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (isEditingMode) return false
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
                if (isEditingMode) return false

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
                        webView.evaluateJavascript("window.scrollBy(0, ${(container.height * 0.8).toInt()});", null)
                    }
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.PREV,
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.LEFT -> {
                        webView.evaluateJavascript("window.scrollBy(0, -${(container.height * 0.8).toInt()});", null)
                    }
                }

                return true
            }
        },
    ).apply {
        // Disable long press handling so WebView can handle text selection
        setIsLongpressEnabled(false)
    }

    init {
        ttsController = TtsController(
            context = activity,
            preferences = preferences,
            scope = scope,
            callbacks = object : TtsController.Callbacks {
                override fun onInitialized(pendingRequest: TtsController.StartRequest?) {
                    ttsIsStarting = true
                    when (pendingRequest) {
                        TtsController.StartRequest.NORMAL -> startTts()
                        TtsController.StartRequest.VIEWPORT -> startTtsFromViewport()
                        null -> {}
                    }
                }

                override fun getCurrentPage(): ReaderPage? = currentPage

                override fun onHighlightChunk(chunkIndex: Int, chunk: String, startOffset: Int, paragraphIndex: Int) {
                    applyTtsHighlight(chunkIndex, paragraphIndex)
                }

                override fun onClearHighlights() {
                    clearWebViewTtsHighlight()
                }

                override fun onLastChunkDone() {
                    val nextAlreadyLoaded = preferences.novelInfiniteScroll.get() &&
                        loadedChapters.getOrNull(ttsController.ttsPlaybackChapterIndex + 1) != null
                    if (nextAlreadyLoaded) {
                        unloadReadChaptersAndStartNextTts()
                    } else {
                        loadNextChapterForTts(ttsController.ttsPlaybackChapterIndex)
                    }
                }

                override fun runOnUiThread(action: () -> Unit) {
                    activity.runOnUiThread(action)
                }
            },
        )
        initWebView()
        observePreferences()
    }


    /**
     * Applies visual highlighting to the chunk currently being read by TTS using JavaScript.
     */
    private fun applyTtsHighlight(chunkIndex: Int, paragraphIndex: Int) {
        if (chunkIndex < 0 || chunkIndex >= ttsController.ttsChunks.size) return

        val highlightColor = String.format("#%06X", 0xFFFFFF and preferences.novelTtsHighlightColor.get())
        val highlightTextColor = String.format("#%06X", 0xFFFFFF and preferences.novelTtsHighlightTextColor.get())
        val highlightStyle = preferences.novelTtsHighlightStyle.get()
        val keepInView = preferences.novelTtsKeepHighlightInView.get()

        val jsCode = """
            (function() {
                var state = window.__tdTtsState || (window.__tdTtsState = {});
                if (!state.styleEl) {
                    state.styleEl = document.createElement('style');
                    state.styleEl.id = 'td-tts-highlight-style';
                    state.styleEl.textContent =
                        '.td-tts-highlight-bg{background:var(--td-tts-highlight-bg)!important;color:var(--td-tts-highlight-text)!important;border-radius:6px;padding:0 .2em;}' +
                        '.td-tts-highlight-underline{text-decoration:underline 2px var(--td-tts-highlight-bg)!important;text-underline-offset:0.2em;}' +
                        '.td-tts-highlight-outline{outline:2px solid var(--td-tts-highlight-bg)!important;outline-offset:2px;border-radius:8px;padding:0 .2em;}' ;
                    document.head.appendChild(state.styleEl);
                }

                document.documentElement.style.setProperty('--td-tts-highlight-bg', '$highlightColor');
                document.documentElement.style.setProperty('--td-tts-highlight-text', '$highlightTextColor');

                var selectors = 'p, li, blockquote, h1, h2, h3, h4, h5, h6, pre';
                var paragraphs = Array.from(document.querySelectorAll(selectors)).filter(function(el) {
                    return !!el && !!el.innerText && el.innerText.trim().length > 0;
                });
                if (!paragraphs.length) {
                    paragraphs = Array.from(document.body.children).filter(function(el) {
                        return !!el && !!el.innerText && el.innerText.trim().length > 0;
                    });
                }

                if (state.currentEl) {
                    state.currentEl.classList.remove('td-tts-highlight-bg', 'td-tts-highlight-underline', 'td-tts-highlight-outline');
                }

                var targetIndex = Math.min(Math.max($paragraphIndex, 0), Math.max(paragraphs.length - 1, 0));
                var target = paragraphs[targetIndex];
                if (!target) {
                    state.currentEl = null;
                    return;
                }

                if ('$highlightStyle' === 'underline') {
                    target.classList.add('td-tts-highlight-underline');
                } else if ('$highlightStyle' === 'outline') {
                    target.classList.add('td-tts-highlight-outline');
                } else {
                    target.classList.add('td-tts-highlight-bg');
                }

                state.currentEl = target;
                if ($keepInView) {
                    target.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
                }
            })();
        """.trimIndent()

        evaluateJavascriptSafe(jsCode)
    }

    private fun clearWebViewTtsHighlight() {
        evaluateJavascriptSafe(
            """
            (function() {
                var state = window.__tdTtsState;
                if (state && state.currentEl) {
                    state.currentEl.classList.remove('td-tts-highlight-bg', 'td-tts-highlight-underline', 'td-tts-highlight-outline');
                    state.currentEl = null;
                }
            })();
            """.trimIndent(),
        )
    }

    private fun loadNextChapterForTts(anchorChapterIndex: Int = ttsController.ttsPlaybackChapterIndex) {
        logcat(LogPriority.DEBUG) { "TTS (WebView): Auto-loading next chapter ts=${System.currentTimeMillis()} ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex} ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}" }

        scope.launch {
            if (preferences.novelInfiniteScroll.get()) {
                pendingTtsAppend = true
                appendNextChapterIfAvailable()
                setPendingTtsHandoffTimeout(5000L)
            } else {
                val chapters = currentChapters ?: return@launch
                chapters.nextChapter ?: return@launch
                // Use a viewer-owned flag so ttsController.stop() (called from setChapters)
                // cannot clear it before onPageFinished fires.
                pendingTtsAutoStartOnLoad = true
                activity.loadNextChapter()
            }
        }
    }

    /**
     * Remove all chapters already read (0..ttsPlaybackChapterIndex) from the DOM and Kotlin state,
     * then start TTS fresh from the beginning of the next chapter. Used in inf-scroll mode when
     * TTS finishes the last chunk and the next chapter is already appended to the DOM — avoids the
     * unreliable scroll-based viewport handoff entirely.
     */
    private fun unloadReadChaptersAndStartNextTts() {
        val currentIdx = ttsController.ttsPlaybackChapterIndex
        val nextIdx = currentIdx + 1
        val nextChapter = loadedChapters.getOrNull(nextIdx) ?: return
        val nextChapterId = nextChapter.chapter.id ?: return

        // Collect IDs of all chapters up to and including the current one.
        val idsToRemove = loadedChapterIds.take(nextIdx)

        logcat(LogPriority.DEBUG) {
            "TTS (WebView): Unloading ${idsToRemove.size} chapter(s) from DOM before starting next ($nextChapterId)"
        }

        val idsJsonArray = idsToRemove.joinToString(",") { "\"$it\"" }
        val js = """
            (function() {
                var ids = [$idsJsonArray];
                ids.forEach(function(id) {
                    var el = document.querySelector('$CHAPTER_TAG_NAME[$CHAPTER_ID_ATTR="' + id + '"]');
                    var div = document.querySelector('.$CHAPTER_DIVIDER_CLASS[$CHAPTER_ID_ATTR="' + id + '"]');
                    if (el) el.remove();
                    if (div) div.remove();
                });
                // scrollTo(0,0) BEFORE updateChapterBoundaries so boundary callbacks
                // report 0% progress instead of a stale scroll position from the old content.
                window.scrollTo(0, 0);
                if (typeof window.updateChapterBoundaries === 'function') window.updateChapterBoundaries();
            })();
        """.trimIndent()

        // evaluateJavascriptSafe posts to main thread; callback is also on main thread.
        evaluateJavascriptSafe(js) {
            repeat(nextIdx) {
                if (loadedChapterIds.isNotEmpty()) loadedChapterIds.removeAt(0)
                if (loadedChapters.isNotEmpty()) loadedChapters.removeAt(0)
            }
            currentChapterIndex = 0

            nextChapter.pages?.firstOrNull()?.let { page ->
                currentPage = page
                activity.viewModel.setNovelVisibleChapter(nextChapter.chapter)
                activity.onPageSelected(page)
                activity.onNovelProgressChanged(0f)
            }

            clearWebViewTtsHighlight()
            startTts()
        }
    }

    /**
     * Set a timeout for pending TTS handoff. If the handoff hasn't completed within [timeoutMs],
     * clear the pending state and log a warning to prevent TTS from hanging indefinitely.
     */
    private fun setPendingTtsHandoffTimeout(timeoutMs: Long) {
        pendingTtsHandoffTimeoutJob?.cancel()

        pendingTtsHandoffTimeoutJob = scope.launch {
            delay(timeoutMs)
            if (pendingTtsHandoffChapterId != null || pendingTtsAppend) {
                logcat(LogPriority.WARN) {
                    "TTS (WebView): Handoff timeout after ${timeoutMs}ms for chapter $pendingTtsHandoffChapterId (pendingAppend=$pendingTtsAppend); clearing pending state and resuming playback"
                }
                clearPendingTtsHandoff()
                if (ttsController.isTtsAutoPlay && !ttsController.isSpeaking()) {
                    startTts()
                }
            }
        }
    }

    /**
     * Clear pending TTS handoff state to prevent stale handoff attempts.
     */
    private fun clearPendingTtsHandoff() {
        pendingTtsHandoffChapterId = null
        pendingTtsHandoffUseViewport = false
        pendingTtsHandoffStarted = false
        pendingTtsHandoffTimeoutJob?.cancel()
        pendingTtsHandoffTimeoutJob = null
        pendingTtsAppend = false
        cachedNextChapterForTts = null
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun initWebView() {
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // Remove blocksDescendants from reader_activity.xml's viewer_container parent
                // so the WebView can actually receive text input focus.
                (container.parent as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })

        webView = object : WebView(activity) {
            override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
                if (!preferences.novelTextSelectable.get() || callback == null) {
                    return super.startActionMode(callback, type)
                }
                // Preserve Callback2 so the floating toolbar anchors correctly to the selection
                val wrapped = if (callback is ActionMode.Callback2) {
                    object : ActionMode.Callback2() {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            val result = callback.onCreateActionMode(mode, menu)
                            menu.add(
                                Menu.NONE,
                                REMEMBER_MENU_ITEM_ID,
                                Menu.NONE,
                                activity.stringResource(TDMR.strings.action_remember),
                            )
                                .setIcon(android.R.drawable.ic_menu_save)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                            return result
                        }
                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
                            callback.onPrepareActionMode(mode, menu)
                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                            if (item.itemId == REMEMBER_MENU_ITEM_ID) {
                                onRememberSelectedText(mode) // pass mode in
                                return true
                            }
                            return callback.onActionItemClicked(mode, item)
                        }
                        override fun onDestroyActionMode(mode: ActionMode) =
                            callback.onDestroyActionMode(mode)

                        // Forward the content rect so the toolbar floats near the selection
                        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) =
                            callback.onGetContentRect(mode, view, outRect)
                    }
                } else {
                    object : ActionMode.Callback {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            val result = callback.onCreateActionMode(mode, menu)
                            menu.add(
                                Menu.NONE,
                                REMEMBER_MENU_ITEM_ID,
                                Menu.NONE,
                                activity.stringResource(TDMR.strings.action_remember),
                            )
                                .setIcon(android.R.drawable.ic_menu_save)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                            return result
                        }
                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
                            callback.onPrepareActionMode(mode, menu)
                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                            if (item.itemId == REMEMBER_MENU_ITEM_ID) {
                                onRememberSelectedText()
                                mode.finish()
                                return true
                            }
                            return callback.onActionItemClicked(mode, item)
                        }
                        override fun onDestroyActionMode(mode: ActionMode) =
                            callback.onDestroyActionMode(mode)
                    }
                }
                return super.startActionMode(wrapped, type)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            applyWebViewScrollbarSettings(this)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                // Block images/videos if preference is set
                val shouldBlock = preferences.novelBlockMedia.get()
                blockNetworkImage = shouldBlock
                loadsImagesAutomatically = !shouldBlock
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): android.webkit.WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (url.startsWith(URL_SCHEME_NOVEL_IMAGE)) {
                        val imagePath = android.net.Uri.decode(url.removePrefix(URL_SCHEME_NOVEL_IMAGE))
                        val chapterId = currentPage?.chapter?.chapter?.id ?: currentChapters?.currChapter?.chapter?.id
                        val cacheKey = buildNovelImageCacheKey(chapterId, imagePath)
                        novelImageCache[cacheKey]?.takeIf { it.exists() }?.let { cachedFile ->
                            return android.webkit.WebResourceResponse(
                                guessNovelImageMimeType(imagePath),
                                "UTF-8",
                                cachedFile.inputStream(),
                            )
                        }

                        val loader = activity.viewModel.state.value.viewerChapters?.currChapter?.pageLoader
                        if (loader != null) {
                            chapterId?.let { prefetchNovelImage(it, imagePath, loader) }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    hideLoadingIndicator()
                    injectCustomScript()
                    injectScrollTracking()
                    restoreScrollPosition()
                    syncShortChapterProgressIfNeeded()
                    if (!preferences.novelInfiniteScroll.get()) {
                        injectNextChapterButton()
                    }
                    if (isEditingMode) {
                        toggleEditMode(true)
                    }
                    // isLoadingRealChapter distinguishes real chapter loads from
                    // showLoadingIndicator() loads — both fire onPageFinished(about:blank)
                    // when the chapter URL is relative, making the URL useless as a guard.
                    if (isLoadingRealChapter) {
                        isLoadingRealChapter = false
                        if (pendingTtsAutoStartOnLoad) {
                            pendingTtsAutoStartOnLoad = false
                            startTts()
                        }
                        ttsController.pendingStartRequest?.let { request ->
                            ttsController.pendingStartRequest = null
                            when (request) {
                                TtsController.StartRequest.NORMAL -> startTts()
                                TtsController.StartRequest.VIEWPORT -> startTtsFromViewport()
                            }
                        }
                    }
                }
            }

            // Add JavaScript interface for progress saving
            addJavascriptInterface(this@NovelWebViewViewer.WebViewInterface(), "Android")

            // Enable text selection via long press
            isLongClickable = true

            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
        }

        // Initial setup for background to avoid white flashes
        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val (themeBgColor, _) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        webView.setBackgroundColor(finalBgColor)
        container.setBackgroundColor(finalBgColor)

        container.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    private fun observePreferences() {

        scope.launch {
            merge(
                preferences.novelFontSize.changes().drop(1),
                preferences.novelFontFamily.changes().drop(1),
                preferences.novelTheme.changes().drop(1),
                preferences.novelLineHeight.changes().drop(1),
                preferences.novelTextAlign.changes().drop(1),
                preferences.novelMarginLeft.changes().drop(1),
                preferences.novelMarginRight.changes().drop(1),
                preferences.novelMarginTop.changes().drop(1),
                preferences.novelMarginBottom.changes().drop(1),
                preferences.novelFontColor.changes().drop(1),
                preferences.novelBackgroundColor.changes().drop(1),
                preferences.novelParagraphIndent.changes().drop(1),
                preferences.novelParagraphSpacing.changes().drop(1),
                preferences.novelCustomCss.changes().drop(1),
                preferences.novelSourceCssPriority.changes().drop(1),
                preferences.novelCustomCssSnippets.changes().drop(1),
                preferences.novelUseOriginalFonts.changes().drop(1),
                preferences.novelHideChapterTitle.changes().drop(1),
                preferences.novelTextSelectable.changes().drop(1),
            )
                .collect {
                    injectCustomStyles()
                }
        }

        // Observe JS changes separately to re-inject scripts
        scope.launch {
            merge(
                preferences.novelCustomJs.changes().drop(1),
                preferences.novelCustomJsSnippets.changes().drop(1),
            )
                .collect {
                    injectCustomScript()
                }
        }

        // When embedded CSS/JS toggles change we need to reload the current chapter
        // because sanitization (strip/keep) happens during initial HTML normalization.
        scope.launch {
            merge(
                preferences.enableEpubStyles.changes().drop(1),
                preferences.enableEpubJs.changes().drop(1),
            ).collect {
                // Reload current chapters so sanitizeHtmlForWebView runs with updated flags
                currentChapters?.let { setChapters(it) }
            }
        }

        scope.launch {
            preferences.novelForceTextLowercase.changes()
                .drop(1)
                .collect {
                    currentChapters?.let {
                        // Reload the current chapter to reapply string transformations
                        setChapters(it)
                    }
                }
        }

        // Observe block media preference
        scope.launch {
            preferences.novelBlockMedia.changes()
                .drop(1)
                .collect { blockMedia ->
                    webView.settings.apply {
                        blockNetworkImage = blockMedia
                        loadsImagesAutomatically = !blockMedia
                    }
                    // Reload the page to apply media blocking
                    webView.reload()
                }
        }

        // Observe regex replacements — requires full content reload
        scope.launch {
            preferences.novelRegexReplacements.changes()
                .drop(1)
                .collect {
                    currentChapters?.let { setChapters(it) }
                }
        }

        scope.launch {
            merge(
                preferences.novelTtsVoice.changes(),
                preferences.novelTtsSpeed.changes(),
                preferences.novelTtsPitch.changes(),
            ).drop(3)
                .collect {
                    if (ttsController.ttsInitialized) {
                        ttsController.applySettings()
                    }
                }
        }

    }

    private fun applyWebViewScrollbarSettings(target: WebView = webView) {
        target.isVerticalScrollBarEnabled = true
        target.isHorizontalScrollBarEnabled = false
        target.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        target.isScrollbarFadingEnabled = true
        target.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        target.layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    private fun buildCustomStylePayload(): CustomStylePayload {
        val fontSize = preferences.novelFontSize.get()
        val fontFamily = preferences.novelFontFamily.get()
        val lineHeight = preferences.novelLineHeight.get()
        val marginLeft = preferences.novelMarginLeft.get()
        val marginRight = preferences.novelMarginRight.get()
        val marginTop = preferences.novelMarginTop.get()
        val marginBottom = preferences.novelMarginBottom.get()
        val fontColor = preferences.novelFontColor.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val paragraphIndent = preferences.novelParagraphIndent.get()
        val paragraphSpacing = preferences.novelParagraphSpacing.get()
        val textAlign = preferences.novelTextAlign.get()
        val theme = preferences.novelTheme.get()
        val hideChapterTitle = preferences.novelHideChapterTitle.get()

        val (themeBgColor, themeTextColor) = getThemeColors(theme)
        // Use 0 as default marker (not -1, since white = -1 as signed int)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        val finalTextColor = if (fontColor != 0) fontColor else themeTextColor

        val bgColorHex = String.format("#%06X", 0xFFFFFF and finalBgColor)
        val textColorHex = String.format("#%06X", 0xFFFFFF and finalTextColor)

        val customCss = preferences.novelCustomCss.get()
        val useOriginalFonts = preferences.novelUseOriginalFonts.get()

        // Collect enabled CSS snippets
        val cssSnippetsJson = preferences.novelCustomCssSnippets.get()
        val enabledSnippetsCss = try {
            val snippets = Json.decodeFromString<List<CodeSnippet>>(cssSnippetsJson)
            snippets.filter { it.enabled }.joinToString("\n") { it.code }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to parse CSS snippets: ${e.message}" }
            ""
        }

        // Generate font-face declaration for custom fonts
        // For custom fonts (URIs), copy to cache and use file:// URL
        val (fontFaceDeclaration, effectiveFontFamily) = if (!useOriginalFonts &&
            (fontFamily.startsWith("file://") || fontFamily.startsWith("content://"))
        ) {
            try {
                // Copy font to cache directory for WebView access
                val fontUri = android.net.Uri.parse(fontFamily)
                val inputStream = activity.contentResolver.openInputStream(fontUri)
                val fontFile = java.io.File(activity.cacheDir, "custom_font.ttf")
                inputStream?.use { input ->
                    fontFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val fontUrl = "file://" + fontFile.absolutePath
                val declaration = """
                @font-face {
                    font-family: 'CustomFont';
                    src: url('$fontUrl');
                }
                """.trimIndent()
                declaration to "'CustomFont', sans-serif"
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Failed to load custom font: ${e.message}" }
                "" to fontFamily
            }
        } else {
            "" to fontFamily
        }

        // Only include font-family if not using original fonts
        val fontFamilyCss = if (useOriginalFonts) {
            ""
        } else {
            "font-family: $effectiveFontFamily;"
        }

        val sourceCssPriority = preferences.novelSourceCssPriority.get()
        val styleImportance = if (sourceCssPriority) "" else " !important"

        val css = """
            $fontFaceDeclaration
            body {
                font-size: ${fontSize}px$styleImportance;
                $fontFamilyCss$styleImportance
                line-height: $lineHeight$styleImportance;
                margin: ${marginTop}px ${marginRight}px ${marginBottom}px ${marginLeft}px$styleImportance;
                color: $textColorHex$styleImportance;
                background-color: $bgColorHex$styleImportance;
                text-align: $textAlign$styleImportance;
                -webkit-user-select: ${if (preferences.novelTextSelectable.get()) "text" else "none"}$styleImportance;
                user-select: ${if (preferences.novelTextSelectable.get()) "text" else "none"}$styleImportance;
            }
            p {
                text-indent: ${paragraphIndent}em$styleImportance;
                margin-top: ${paragraphSpacing}em$styleImportance;
                margin-bottom: ${paragraphSpacing}em$styleImportance;
            }
            * {
                color: inherit$styleImportance;
            }
            $customCss
            $enabledSnippetsCss
        """.trimIndent().replace("\n", " ")

        return CustomStylePayload(
            css = css,
            hideChapterTitle = hideChapterTitle,
            backgroundColor = finalBgColor,
        )
    }

    private fun injectCustomStyles() {
        val payload = buildCustomStylePayload()
        webView.setBackgroundColor(payload.backgroundColor)
        container.setBackgroundColor(payload.backgroundColor)

        val js = """
            (function() {
                var style = document.getElementById('${STYLE_ID_CUSTOM}');
                if (!style) {
                    style = document.createElement('style');
                    style.id = '${STYLE_ID_CUSTOM}';
                    document.head.appendChild(style);
                }
                style.textContent = `${payload.css}`;

                // Hide chapter title if enabled
                if (${payload.hideChapterTitle}) {
                    var headings = document.querySelectorAll('h1, h2, h3, h4, h5, h6');
                    if (headings.length > 0) {
                        headings[0].style.display = 'none';
                    }
                }
            })();
        """

        evaluateJavascriptSafe(js, null)
    }

    private fun injectCustomScript() {
        val script = buildTsundokuScript()
        evaluateJavascriptSafe(script, null)

        val customJs = preferences.novelCustomJs.get()
        if (customJs.isNotBlank()) {
            evaluateJavascriptSafe(customJs, null)
        }

        val jsSnippetsJson = preferences.novelCustomJsSnippets.get()
        val enabledSnippetsJs = try {
            val snippets = Json.decodeFromString<List<CodeSnippet>>(jsSnippetsJson)
            snippets.filter { it.enabled }.joinToString("\n") { it.code }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to parse JS snippets: ${e.message}" }
            ""
        }
        if (enabledSnippetsJs.isNotBlank()) {
            evaluateJavascriptSafe(enabledSnippetsJs, null)
        }
    }

    /**
     * Injects a "Next Chapter" button at the bottom of the WebView content
     * when infinite scroll is disabled.
     */
    private fun injectNextChapterButton() {
        val hasNext = currentChapters?.nextChapter != null
        if (!hasNext) return

        val js = """
            (function() {
                // Remove existing button if any
                var existing = document.getElementById('${ID_NEXT_CHAPTER_BTN_CONTAINER}');
                if (existing) existing.remove();

                var container = document.createElement('div');
                container.id = '${ID_NEXT_CHAPTER_BTN_CONTAINER}';
                container.style.cssText = 'padding: 32px 16px; text-align: center;';

                var btn = document.createElement('button');
                btn.textContent = 'Next Chapter →';
                btn.style.cssText = 'width: 100%; padding: 12px 24px; font-size: 16px; ' +
                    'background-color: #ADD8E6; color: #000000; ' +
                    'border: 2px solid #000000; border-radius: 8px; ' +
                    'cursor: pointer; text-transform: none;';
                btn.onclick = function() {
                    Android.loadNextChapter();
                };
                container.appendChild(btn);
                document.body.appendChild(container);
            })();
        """.trimIndent()

        evaluateJavascriptSafe(js, null)
    }

    private fun injectScrollTracking() {
        // Add scroll tracking script with infinite scroll support
        val infiniteScrollEnabled = preferences.novelInfiniteScroll.get()
        val autoLoadThreshold = preferences.novelAutoLoadNextChapterAt.get()
        // Treat stored 0 as legacy/unset and use a sensible default.
        val infiniteScrollActuallyEnabled = infiniteScrollEnabled
        val effectiveThreshold = if (autoLoadThreshold > 0) autoLoadThreshold / 100.0 else 0.95
        val scrollTrackingScript = """
            (function() {
                window.$TSUNDOKU_OBJECT_NAME = window.$TSUNDOKU_OBJECT_NAME || {};
                window.$TSUNDOKU_OBJECT_NAME.runtime = window.$TSUNDOKU_OBJECT_NAME.runtime || {};
                var runtime = window.$TSUNDOKU_OBJECT_NAME.runtime;

                if (runtime.infiniteScrollInstalled) {
                    return;
                }
                runtime.infiniteScrollInstalled = true;

                var lastProgress = 0;
                var saveTimeout = null;
                runtime.loadingNext = runtime.loadingNext || false;
                runtime.setLoadingNext = function(v) { runtime.loadingNext = !!v; };
                var infiniteScrollEnabled = $infiniteScrollActuallyEnabled;
                var loadThreshold = $effectiveThreshold;

                // Track chapter boundaries for multi-chapter infinite scroll
                window.chapterBoundaries = window.chapterBoundaries || [];
                runtime.lastBoundaryUpdate = runtime.lastBoundaryUpdate || 0;

                window.addEventListener('scroll', function() {
                    // Keep chapter boundaries in sync with actual DOM markers.
                    // This is important after appends/prepends.
                    if (infiniteScrollEnabled && typeof window.updateChapterBoundaries === 'function') {
                        var dividers = document.querySelectorAll('.${CHAPTER_DIVIDER_CLASS}');
                        if (!window.chapterBoundaries || window.chapterBoundaries.length !== dividers.length) {
                            window.updateChapterBoundaries();
                        } else if (Date.now() - runtime.lastBoundaryUpdate > 1000) {
                            runtime.lastBoundaryUpdate = Date.now();
                            window.updateChapterBoundaries();
                        }
                    }

                    var scrollTop = document.documentElement.scrollTop || document.body.scrollTop;
                    var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                    var progress = scrollHeight > 0 ? scrollTop / scrollHeight : 1;
                    // Round up if very close to 100% (within 2%) to allow reaching 100%
                    if (progress >= 0.98) progress = 1.0;

                    // For infinite scroll with multiple chapters, calculate per-chapter progress
                    var currentChapterProgress = progress;
                    var currentChapterIdx = 0;
                    if (infiniteScrollEnabled && window.chapterBoundaries.length > 1) {
                        var accumulatedHeight = 0;
                        var docHeight = document.documentElement.scrollHeight;
                        for (var i = 0; i < window.chapterBoundaries.length; i++) {
                            var boundary = window.chapterBoundaries[i];
                            var chapterEnd = boundary.startOffset + boundary.height;
                            if (scrollTop >= boundary.startOffset && scrollTop < chapterEnd) {
                                currentChapterIdx = i;
                                var chapterScrollY = scrollTop - boundary.startOffset;
                                var effectiveHeight = Math.max(boundary.height - window.innerHeight, 1);
                                currentChapterProgress = Math.min(chapterScrollY / effectiveHeight, 1.0);
                                break;
                            }
                        }
                        // Notify Android of chapter change
                        Android.onChapterScrollUpdate(currentChapterIdx, currentChapterProgress);
                    }

                    if (Math.abs(currentChapterProgress - lastProgress) > 0.01) {
                        lastProgress = currentChapterProgress;

                        // Immediate update for UI (throttled)
                        if (!window.lastScrollUpdate || Date.now() - window.lastScrollUpdate > 50) {
                            window.lastScrollUpdate = Date.now();
                            Android.onScrollUpdate(currentChapterProgress);
                        }

                        clearTimeout(saveTimeout);
                        saveTimeout = setTimeout(function() {
                            Android.onScrollProgress(currentChapterProgress);
                        }, 500);
                    }

                    // Infinite scroll: load next chapter when reaching threshold
                    var shouldLoadNext = false;
                    if (infiniteScrollEnabled) {
                        if (window.chapterBoundaries.length > 1) {
                            shouldLoadNext = (currentChapterIdx === (window.chapterBoundaries.length - 1)) && (currentChapterProgress >= loadThreshold);
                        } else {
                            shouldLoadNext = progress >= loadThreshold;
                        }
                    }
                    if (shouldLoadNext && !runtime.loadingNext) {
                        console.log('Infinite scroll: Loading next chapter at progress ' + currentChapterProgress + ' (threshold: ' + loadThreshold + ')');
                        runtime.loadingNext = true;
                        try {
                            Android.loadNextChapter();
                            console.log('Infinite scroll: Successfully called loadNextChapter()');
                        } catch(e) {
                            console.error('Infinite scroll: Error calling loadNextChapter:', e);
                            runtime.loadingNext = false; // Reset immediately on error
                        }
                    }
                });

                // Function to add a chapter boundary
                window.addChapterBoundary = function(chapterId, startOffset, height) {
                    window.chapterBoundaries.push({
                        chapterId: chapterId,
                        startOffset: startOffset,
                        height: height
                    });
                };

                // Function to update chapter boundary heights after content load
                window.updateChapterBoundaries = function() {
                    var dividers = document.querySelectorAll('.${CHAPTER_DIVIDER_CLASS}');
                    var boundaries = [];
                    var lastEnd = 0;
                    dividers.forEach(function(divider, index) {
                        var chapterId = divider.getAttribute('${ATTR_DATA_CHAPTER_ID}');
                        var nextDivider = dividers[index + 1];
                        var endPos = nextDivider ? nextDivider.offsetTop : document.body.scrollHeight;
                        boundaries.push({
                            chapterId: chapterId,
                            startOffset: divider.offsetTop,
                            height: endPos - divider.offsetTop
                        });
                    });
                    window.chapterBoundaries = boundaries;
                    runtime.lastBoundaryUpdate = Date.now();
                };

                // Initialize boundaries on first load.
                setTimeout(function() {
                    if (typeof window.updateChapterBoundaries === 'function') {
                        window.updateChapterBoundaries();
                    }
                }, 0);
            })();
        """
        evaluateJavascriptSafe(scrollTrackingScript, null)
    }

    private fun restoreScrollPosition() {
        currentPage?.let { page ->
            val savedProgress = page.chapter.chapter.last_page_read
            val isRead = page.chapter.chapter.read

            logcat(LogPriority.DEBUG) {
                "NovelWebViewViewer: Restoring progress, savedProgress=$savedProgress, isRead=$isRead for ${page.chapter.chapter.name}"
            }

            // If chapter is marked as read, start from top (0%) to avoid infinite scroll issues
            val shouldRestore = if (!isRead) {
                savedProgress > 0 && savedProgress <= 100
            } else {
                libraryPreferences.novelReadProgress100.get() && savedProgress > 0 && savedProgress <= 100
            }
            if (shouldRestore) {
                val progress = savedProgress / 100f
                lastSavedProgress = progress

                // Wait a bit for content to be fully rendered before scrolling
                webView.postDelayed({
                    val js = """
                        (function() {
                            var scrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                            if (scrollHeight > 0) {
                                window.scrollTo(0, scrollHeight * $progress);
                                console.log('Restored scroll to ' + Math.round($progress * 100) + '% (' + Math.round(scrollHeight * $progress) + 'px)');
                            } else {
                                // Content not ready, retry in 200ms
                                setTimeout(function() {
                                    var newScrollHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                                    window.scrollTo(0, newScrollHeight * $progress);
                                }, 200);
                            }
                        })();
                    """
                    webView.evaluateJavascript(js, null)
                }, 100)
            } else {
                // Ensure we are at top for read chapters
                webView.scrollTo(0, 0)
                lastSavedProgress = 0f
            }
        }
    }

    private fun getThemeColors(theme: String): Pair<Int, Int> =
        NovelViewerTextUtils.getThemeColors(activity, preferences, theme)

    override fun destroy() {
        // Save progress before destroying
        saveProgress()

        ttsController.destroy()
        ttsIsPreparing = false
        ttsIsStarting = false

        clearNovelImageCache()

        // Mark destroyed first so coroutine finally-blocks won't touch WebView.
        isDestroyed = true

        scope.cancel()
        loadJob?.cancel()
        webView.destroy()
    }

    private fun evaluateJavascriptSafe(js: String, callback: ((String) -> Unit)? = null) {
        if (isDestroyed) return
        activity.runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            try {
                webView.evaluateJavascript(js, callback)
            } catch (t: Throwable) {
                // WebView may already be destroyed; avoid crashing.
                logcat(LogPriority.WARN) { "NovelWebViewViewer: evaluateJavascript ignored (${t.message})" }
            }
        }
    }

    private fun saveProgress() {
        currentPage?.let { page ->
            val progressValue = (lastSavedProgress * 100).toInt().coerceIn(0, 100)
            activity.saveNovelProgress(page, progressValue)
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Saving progress $progressValue%" }
        }
    }

    private fun clearNovelImageCache() {
        novelImagePrefetchJobs.values.forEach { it.cancel() }
        novelImagePrefetchJobs.clear()
        novelImageCache.values.forEach { cachedFile ->
            runCatching { cachedFile.delete() }
        }
        novelImageCache.clear()
    }

    private fun buildNovelImageCacheKey(chapterId: Long?, imagePath: String): String {
        return "${chapterId ?: -1L}:$imagePath"
    }

    private fun guessNovelImageMimeType(imagePath: String): String {
        return when (imagePath.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            else -> "image/jpeg"
        }
    }

    private fun scheduleNovelImagePrefetch(content: String, chapterId: Long?, loader: PageLoader?) {
        if (chapterId == null || loader == null) return

        val imageUrlPattern = Regex("${Regex.escape(URL_SCHEME_NOVEL_IMAGE)}([^\"'<>\\s]+)")
        imageUrlPattern.findAll(content)
            .mapNotNull { match -> runCatching { android.net.Uri.decode(match.groupValues[1]) }.getOrNull() }
            .distinct()
            .forEach { imagePath ->
                prefetchNovelImage(chapterId, imagePath, loader)
            }
    }

    private fun prefetchNovelImage(chapterId: Long, imagePath: String, loader: PageLoader) {
        val cacheKey = buildNovelImageCacheKey(chapterId, imagePath)
        if (novelImageCache[cacheKey]?.exists() == true) return

        novelImagePrefetchJobs[cacheKey]?.let { existing ->
            if (existing.isActive) return
        }

        val job = scope.launch(Dispatchers.IO) {
            try {
                val stream = loader.getPageDataStream(imagePath) ?: return@launch
                val fileSuffix = imagePath.substringAfterLast('.', "bin").ifBlank { "bin" }
                val cachedFile = File(activity.cacheDir, "novel-image-${cacheKey.hashCode().toString(16)}.$fileSuffix")
                stream.use { input ->
                    cachedFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                novelImageCache[cacheKey] = cachedFile
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Failed to prefetch image $imagePath: ${e.message}" }
            } finally {
                novelImagePrefetchJobs.remove(cacheKey)
            }
        }

        val previous = novelImagePrefetchJobs.putIfAbsent(cacheKey, job)
        if (previous != null) {
            job.cancel()
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

        // Use ResizeObserver to wait for layout to stabilize before checking viewport height
        evaluateJavascriptSafe(
            """
            (function() {
                // Helper function to check if content needs scrolling
                function checkIfShortChapter() {
                    var maxScroll = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                    return maxScroll <= 0;
                }

                // Set up a ResizeObserver to wait for content to stabilize
                var resizeObserver = new ResizeObserver(function() {
                    if (checkIfShortChapter()) {
                        // Content fits in viewport - call Android interface
                        Android.markChapterAsShort();
                        resizeObserver.disconnect();
                    }
                });

                // Observe document body for size changes
                resizeObserver.observe(document.body);

                // Also check after a small delay to catch static content
                setTimeout(function() {
                    if (checkIfShortChapter()) {
                        Android.markChapterAsShort();
                    }
                    resizeObserver.disconnect();
                }, 500);
            })();
            """.trimIndent(),
            null
        )
    }

    override fun getView(): View = container

    /**
     * Reload content with current translation state.
     * Re-renders the WebView with or without translation.
     */
    fun reloadWithTranslation() {
        val page = currentPage ?: return
        val chapter = currentChapters?.currChapter ?: return
        val chapterId = chapter.chapter.id
        var content = page.text ?: return

        // Optionally strip chapter title from content
        if (preferences.novelHideChapterTitle.get()) {
            content = stripChapterTitle(content, chapter.chapter.name)
        }

        // Apply translation if enabled (async)
        val finalContent = content
        if (activity.isTranslationEnabled()) {
            // Show loading indicator while translating
            loadingIndicator?.show()
            scope.launch {
                val translatedContent = activity.translateContentIfEnabled(finalContent)
                withContext(Dispatchers.Main) {
                    loadingIndicator?.hide()
                    loadHtmlContent(translatedContent, chapter)
                }
            }
        } else {
            loadHtmlContent(finalContent, chapter)
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        val page = chapters.currChapter.pages?.firstOrNull() as? ReaderPage ?: return
        val chapterId = chapters.currChapter.chapter.id ?: return

        loadJob?.cancel()

        currentPage = page
        currentChapters = chapters

        // setChapters() is for loading a single chapter (manual navigation or initial load).
        // Infinite scroll appends/prepends are handled explicitly via WebViewInterface.loadNext/PrevChapter().
        val isInfiniteScrollAppend = false

        val isPrepend = isInfiniteScrollPrepend
        isInfiniteScrollPrepend = false

        // Reset the flag after checking
        isInfiniteScrollNavigation = false

        // Check if chapter is already loaded — do NOT stop TTS for already-loaded chapters
        // (scroll-triggered chapter detection fires setChapters for loaded chapters in inf-scroll).
        if (loadedChapterIds.contains(chapterId)) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Chapter $chapterId already loaded, skipping" }
            // Find and scroll to this chapter
            val index = loadedChapterIds.indexOf(chapterId)
            if (index >= 0) {
                currentChapterIndex = index
            }
            return
        }

        // Stop TTS only when actually navigating to a genuinely new (not-yet-loaded) chapter.
        ttsController.stop()

        // Clear previous chapters for manual navigation / initial load.
        if (!preferences.novelInfiniteScroll.get() || loadedChapterIds.isEmpty()) {
            loadedChapterIds.clear()
            loadedChapters.clear()
            currentChapterIndex = 0
        }

        if (page.status == Page.State.Ready && !page.text.isNullOrEmpty()) {
            if (!isInfiniteScrollAppend && !isPrepend) {
                hideLoadingIndicator()
            }
            displayContent(chapters.currChapter, page, isInfiniteScrollAppend || isPrepend, isPrepend)
            // Trigger download of next chapters (needed for non-infinite-scroll mode)
            if (!isInfiniteScrollAppend && !isPrepend) {
                activity.viewModel.setNovelVisibleChapter(page.chapter.chapter)
            }
            return
        }

        // Only show loading for manual navigation, NEVER for infinite scroll (seamless append)
        if (!isInfiniteScrollAppend && !isPrepend) {
            showLoadingIndicator()
        }
        // No loading indicator at all for infinite scroll - should be completely seamless

        loadJob = scope.launch {
            val loader = page.chapter.pageLoader
            if (loader == null) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page loader available" }
                if (!isInfiniteScrollAppend && !isPrepend) {
                    hideLoadingIndicator()
                }
                return@launch
            }

            launch(Dispatchers.IO) {
                loader.loadPage(page)
            }

            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue, Page.State.LoadPage -> {
                        if (!isInfiniteScrollAppend && !isPrepend) {
                            showLoadingIndicator()
                        }
                    }
                    Page.State.Ready -> {
                        // Only hide loading for manual navigation
                        if (!isInfiniteScrollAppend && !isPrepend) {
                            hideLoadingIndicator()
                        }
                        // Infinite scroll is seamless - no loading indicators to hide
                        displayContent(chapters.currChapter, page, isInfiniteScrollAppend || isPrepend, isPrepend)
                        // Trigger download of next chapters (needed for non-infinite-scroll mode)
                        if (!isInfiniteScrollAppend && !isPrepend) {
                            activity.viewModel.setNovelVisibleChapter(page.chapter.chapter)
                        }
                    }
                    is Page.State.Error -> {
                        // Only hide loading for manual navigation
                        if (!isInfiniteScrollAppend && !isPrepend) {
                            hideLoadingIndicator()
                        }
                        // Infinite scroll is seamless - no loading indicators to hide
                        displayError(state.error)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun showInlineLoading(isPrepend: Boolean) {
        val js = """
            (function() {
                var loadingDiv = document.getElementById('${ID_INLINE_LOADING}');
                if (!loadingDiv) {
                    loadingDiv = document.createElement('div');
                    loadingDiv.id = '${ID_INLINE_LOADING}';
                    loadingDiv.style.textAlign = 'center';
                    loadingDiv.style.padding = '20px';
                    loadingDiv.style.color = '#888';
                    loadingDiv.innerHTML = 'Loading...';
                }

                if ($isPrepend) {
                    document.body.insertBefore(loadingDiv, document.body.firstChild);
                } else {
                    document.body.appendChild(loadingDiv);
                }
            })();
        """.trimIndent()
        evaluateJavascriptSafe(js, null)
    }

    private fun hideInlineLoading(isPrepend: Boolean) {
        val js = """
            (function() {
                var loadingDiv = document.getElementById('${ID_INLINE_LOADING}');
                if (loadingDiv) {
                    loadingDiv.remove();
                }
            })();
        """.trimIndent()
        evaluateJavascriptSafe(js, null)
    }

    private fun showInlineError(message: String, isPrepend: Boolean) {
        scope.launch(Dispatchers.Main) {
            val escapedMessage = message.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")

            val js = """
                (function() {
                    var errorDiv = document.getElementById('${ID_INLINE_ERROR}');
                    if (errorDiv) {
                        errorDiv.remove();
                    }
                    errorDiv = document.createElement('div');
                    errorDiv.id = '${ID_INLINE_ERROR}';
                    errorDiv.style.textAlign = 'center';
                    errorDiv.style.padding = '16px';
                    errorDiv.style.color = '#FF5252';
                    errorDiv.style.backgroundColor = 'rgba(255, 82, 82, 0.1)';
                    errorDiv.style.cursor = 'pointer';
                    errorDiv.innerHTML = '$escapedMessage (tap to dismiss)';
                    errorDiv.onclick = function() { errorDiv.remove(); };

                    if ($isPrepend) {
                        document.body.insertBefore(errorDiv, document.body.firstChild);
                    } else {
                        document.body.appendChild(errorDiv);
                    }
                })();
            """.trimIndent()
            evaluateJavascriptSafe(js, null)

            // Auto-dismiss after 8 seconds
            delay(8000)
            evaluateJavascriptSafe(
                "document.getElementById('${ID_INLINE_ERROR}')?.remove();",
                null,
            )
        }
    }

    private fun scrollToChapterIndex(index: Int, onDone: (() -> Unit)? = null) {
        val js = """
            (function() {
                var dividers = document.querySelectorAll('.${CHAPTER_DIVIDER_CLASS}');
                if (dividers[$index]) {
                    dividers[$index].scrollIntoView({ behavior: 'auto', block: 'start' });
                }
            })();
        """.trimIndent()
        evaluateJavascriptSafe(js) { _ -> onDone?.invoke() }
    }

    private fun displayContent(
        chapter: ReaderChapter,
        page: ReaderPage,
        isAppendOrPrepend: Boolean = false,
        isPrepend: Boolean = false,
    ) {
        var content = page.text
        if (content.isNullOrBlank()) {
            displayError(Exception("No text content available"))
            return
        }

        val chapterId = chapter.chapter.id ?: return

        // Optionally strip chapter title from content
        if (preferences.novelHideChapterTitle.get()) {
            content = stripChapterTitle(content, chapter.chapter.name)
        }

        // Keep preprocessing consistent with normal WebView loads.
        content = applyRegexReplacements(content)

        // Optionally force lowercase
        if (preferences.novelForceTextLowercase.get()) {
            content = content.lowercase()
        }

        val finalContent = content
        scope.launch {
            // Check if we should translate based on context
            val shouldTranslate = if (isAppendOrPrepend) {
                // For infinite scroll, only translate if real-time translation is enabled
                translationPreferences.realTimeTranslation().get()
            } else {
                // For manual navigation, always allow translation if enabled
                true
            }

            // Apply translation logic if allowed
            val processedContent = if (shouldTranslate) {
                activity.translateContentIfEnabled(finalContent)
            } else {
                finalContent
            }

            scheduleNovelImagePrefetch(processedContent, chapter.chapter.id, page.chapter.pageLoader)
            val plainTextMode = NovelViewerTextUtils.isPlainTextChapter(chapter.chapter.url)
            val renderableContent = if (plainTextMode) {
                NovelViewerTextUtils.normalizePlainTextContent(processedContent)
            } else {
                NovelViewerTextUtils.normalizeContentForHtml(
                    processedContent,
                    chapter.chapter.url,
                )
            }

            withContext(Dispatchers.Main) {
                if (isAppendOrPrepend && preferences.novelInfiniteScroll.get()) {
                    if (!loadedChapterIds.contains(chapterId)) {
                        if (isPrepend) {
                            loadedChapterIds.add(0, chapterId)
                            loadedChapters.add(0, chapter)
                            // Keep the user's current chapter index stable after a prepend.
                            currentChapterIndex += 1
                        } else {
                            loadedChapterIds.add(chapterId)
                            loadedChapters.add(chapter)
                        }
                    }
                    if (isPrepend) {
                        prependHtmlContent(renderableContent, chapterId, chapter.chapter.name, chapter.chapter.chapter_number, chapter.chapter.url)
                    } else {
                        appendHtmlContent(renderableContent, chapterId, chapter.chapter.name, chapter.chapter.chapter_number, chapter.chapter.url)
                    }
                } else {
                    loadHtmlContent(renderableContent, chapter)

                    // Fresh load: reset tracking to this single chapter.
                    loadedChapterIds.clear()
                    loadedChapters.clear()
                    loadedChapterIds.add(chapterId)
                    loadedChapters.add(chapter)
                    currentChapterIndex = 0
                }
            }
        }
    }

    /**
     * Prepend content to the existing WebView for infinite scroll (loading previous chapter)
     */
    private fun prependHtmlContent(content: String, chapterId: Long, chapterName: String, chapterNumber: Float, chapterUrl: String?) {
        val plainTextMode = NovelViewerTextUtils.isPlainTextChapter(chapterUrl)

        // Get user preferences for embedded CSS/JS
        val keepEmbeddedCss = preferences.enableEpubStyles.get()
        val keepEmbeddedJs = preferences.enableEpubJs.get()
        val blockMedia = preferences.novelBlockMedia.get()

        // Normalize and sanitize content
        var cleanContent = if (plainTextMode) {
            NovelViewerTextUtils.normalizePlainTextContent(content)
        } else {
            val normalized = normalizeContentForHtml(content, chapterUrl)
            sanitizeHtmlForWebView(normalized, keepEmbeddedCss, keepEmbeddedJs, blockMedia)
        }
        val escapedContent = quoteForJson(cleanContent)

        val js = """
            (function() {
                var oldHeight = document.body.scrollHeight;
                var oldScrollY = window.scrollY || window.pageYOffset;

                var chapterElement = document.createElement('${CHAPTER_TAG_NAME}');
                chapterElement.setAttribute('${ATTR_DATA_CHAPTER_ID}', '$chapterId');
                chapterElement.setAttribute('${TSUNDOKU_CHAPTER_ATTR}', '1');
                chapterElement.setAttribute('$CHAPTER_TITLE_ATTR', ${quoteForJson(chapterName)});
                chapterElement.setAttribute('$CHAPTER_NUMBER_ATTR', '$chapterNumber');
                chapterElement.setAttribute('$CHAPTER_PATH_ATTR', ${quoteForJson(chapterUrl.orEmpty())});
                chapterElement.setAttribute('$CHAPTER_URL_ATTR', ${quoteForJson(toAbsoluteChapterUrl(chapterUrl))});
                ${if (plainTextMode) "chapterElement.textContent = $escapedContent;" else "chapterElement.innerHTML = $escapedContent;"}

                var divider = document.createElement('div');
                divider.className = '$CHAPTER_DIVIDER_CLASS';
                divider.setAttribute('${ATTR_DATA_CHAPTER_ID}', '$chapterId');
                divider.setAttribute('${TSUNDOKU_CHAPTER_ATTR}', '1');
                divider.setAttribute('$CHAPTER_TITLE_ATTR', ${quoteForJson(chapterName)});
                divider.setAttribute('$CHAPTER_NUMBER_ATTR', '$chapterNumber');
                divider.setAttribute('$CHAPTER_PATH_ATTR', ${quoteForJson(chapterUrl.orEmpty())});
                divider.setAttribute('$CHAPTER_URL_ATTR', ${quoteForJson(toAbsoluteChapterUrl(chapterUrl))});

                var firstChild = document.body.firstChild;
                document.body.insertBefore(chapterElement, firstChild);
                document.body.insertBefore(divider, chapterElement);

                // Restore scroll position
                // Use setTimeout to ensure layout is updated
                setTimeout(function() {
                    var newHeight = document.body.scrollHeight;
                    var diff = newHeight - oldHeight;
                    if (diff > 0) {
                        window.scrollTo(0, oldScrollY + diff);
                    }

                    // Update chapter boundaries after DOM update
                    if (typeof window.updateChapterBoundaries === 'function') {
                        window.updateChapterBoundaries();
                    }
                }, 10);
            })();
        """.trimIndent()

        evaluateJavascriptSafe(js, null)

        // Re-run custom JS for newly prepended DOM so selector-based scripts apply consistently.
        webView.postDelayed({ injectCustomScript() }, 120)

        logcat(LogPriority.DEBUG) {
            "NovelWebViewViewer: Prepended chapter $chapterId (${loadedChapterIds.size} total)"
        }
    }

    /**
     * Append content to the existing WebView for infinite scroll
     */
    private fun appendHtmlContent(content: String, chapterId: Long, chapterName: String, chapterNumber: Float, chapterUrl: String?) {
        val plainTextMode = NovelViewerTextUtils.isPlainTextChapter(chapterUrl)

        // Get user preferences for embedded CSS/JS
        val keepEmbeddedCss = preferences.enableEpubStyles.get()
        val keepEmbeddedJs = preferences.enableEpubJs.get()
        val blockMedia = preferences.novelBlockMedia.get()

        // Normalize and sanitize content
        var cleanContent = if (plainTextMode) {
            NovelViewerTextUtils.normalizePlainTextContent(content)
        } else {
            val normalized = normalizeContentForHtml(content, chapterUrl)
            sanitizeHtmlForWebView(normalized, keepEmbeddedCss, keepEmbeddedJs, blockMedia)
        }
        val escapedContent = quoteForJson(cleanContent)

        val js = """
            (function() {
                // Ensure chapters container exists
                var chaptersContainer = document.getElementById('$TSUNDOKU_CHAPTERS_CONTAINER_ID');
                if (!chaptersContainer) {
                    chaptersContainer = document.createElement('div');
                    chaptersContainer.id = '$TSUNDOKU_CHAPTERS_CONTAINER_ID';
                    while (document.body.firstChild) {
                        chaptersContainer.appendChild(document.body.firstChild);
                    }
                    document.body.appendChild(chaptersContainer);
                }

                var divider = document.createElement('div');
                divider.className = '$CHAPTER_DIVIDER_CLASS';
                divider.setAttribute('$CHAPTER_ID_ATTR', '$chapterId');
                divider.setAttribute('$CHAPTER_TITLE_ATTR', ${quoteForJson(chapterName)});
                divider.setAttribute('$CHAPTER_NUMBER_ATTR', '$chapterNumber');
                divider.setAttribute('$CHAPTER_PATH_ATTR', ${quoteForJson(chapterUrl.orEmpty())});
                divider.setAttribute('$CHAPTER_URL_ATTR', ${quoteForJson(toAbsoluteChapterUrl(chapterUrl))});
                chaptersContainer.appendChild(divider);

                var chapterElement = document.createElement('${CHAPTER_TAG_NAME}');
                chapterElement.setAttribute('${ATTR_DATA_CHAPTER_ID}', '$chapterId');
                chapterElement.setAttribute('$CHAPTER_TITLE_ATTR', ${quoteForJson(chapterName)});
                chapterElement.setAttribute('$CHAPTER_NUMBER_ATTR', '$chapterNumber');
                chapterElement.setAttribute('$CHAPTER_PATH_ATTR', ${quoteForJson(chapterUrl.orEmpty())});
                chapterElement.setAttribute('$CHAPTER_URL_ATTR', ${quoteForJson(toAbsoluteChapterUrl(chapterUrl))});
                chapterElement.setAttribute('${TSUNDOKU_CHAPTER_ATTR}', '1');
                ${if (plainTextMode) "chapterElement.textContent = $escapedContent;" else "chapterElement.innerHTML = $escapedContent;"}
                chaptersContainer.appendChild(chapterElement);

                // Update chapter boundaries after DOM update
                if (typeof window.updateChapterBoundaries === 'function') {
                    window.updateChapterBoundaries();
                }

                if (window.Android && window.Android.onInfiniteScrollAppendComplete) {
                    window.Android.onInfiniteScrollAppendComplete($chapterId);
                }
            })();
        """.trimIndent()

        evaluateJavascriptSafe(js, null)

        // Re-run custom JS for newly appended DOM so selector-based scripts apply consistently.
        webView.postDelayed({ injectCustomScript() }, 120)

        logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Appended chapter $chapterId (${loadedChapterIds.size} total)" }
    }

    private fun loadHtmlContent(
        content: String,
        chapter: ReaderChapter? = null,
    ) {
        val chapterModel = chapter?.chapter
        val chapterId = chapterModel?.id ?: -1L
        val chapterName = chapterModel?.name.orEmpty()
        val chapterNumber = chapterModel?.chapter_number ?: -1f
        val chapterPath = chapterModel?.url.orEmpty()
        val normalizedChapterUrl = normalizeUrl(chapterPath)
        val plainTextMode = NovelViewerTextUtils.isPlainTextChapter(normalizedChapterUrl)

        // Get user preferences for embedded CSS/JS
        val keepEmbeddedCss = preferences.enableEpubStyles.get()
        val keepEmbeddedJs = preferences.enableEpubJs.get()
        val blockMedia = preferences.novelBlockMedia.get()

        // Normalize and sanitize content
        var cleanContent = if (plainTextMode) {
            NovelViewerTextUtils.normalizePlainTextContent(content)
        } else {
            val normalized = NovelViewerTextUtils.normalizeContentForHtml(content, normalizedChapterUrl)
            sanitizeHtmlForWebView(normalized, keepEmbeddedCss, keepEmbeddedJs, blockMedia)
        }

        scheduleNovelImagePrefetch(cleanContent, chapterId.takeIf { it != -1L }, currentPage?.chapter?.pageLoader)

        // Apply user's regex find & replace rules
        cleanContent = applyRegexReplacements(cleanContent)

        val stylePayload = buildCustomStylePayload()
        webView.setBackgroundColor(stylePayload.backgroundColor)
        container.setBackgroundColor(stylePayload.backgroundColor)

        // Clear loaded chapters for fresh start
        loadedChapterIds.clear()
        loadedChapters.clear()
        currentChapterIndex = 0

        // Always wrap chapter content with tsundoku chapter metadata so injected CSS/JS
        // can target chapter attributes in both single and infinite-scroll modes.
        val infiniteScrollEnabled = preferences.novelInfiniteScroll.get()
        val chapterDivider = if (chapterId != -1L && infiniteScrollEnabled) {
            val absoluteChapterUrl = toAbsoluteChapterUrl(chapterPath).htmlAttributeEscape()
            val escapedName = chapterName.htmlAttributeEscape()
            val escapedPath = chapterPath.htmlAttributeEscape()
            """<div class="$CHAPTER_DIVIDER_CLASS" $CHAPTER_ID_ATTR="$chapterId" $CHAPTER_TITLE_ATTR="$escapedName" $CHAPTER_NUMBER_ATTR="$chapterNumber" $CHAPTER_PATH_ATTR="$escapedPath" $CHAPTER_URL_ATTR="$absoluteChapterUrl" style="display:none;height:0;margin:0;padding:0;"></div>"""
        } else {
            ""
        }
        val chapterWrapperStart = if (chapterId != -1L) {
            val absoluteChapterUrl = toAbsoluteChapterUrl(chapterPath).htmlAttributeEscape()
            val escapedName = chapterName.htmlAttributeEscape()
            val escapedPath = chapterPath.htmlAttributeEscape()
            """<$CHAPTER_TAG_NAME $CHAPTER_ID_ATTR="$chapterId" $CHAPTER_TITLE_ATTR="$escapedName" $CHAPTER_NUMBER_ATTR="$chapterNumber" $CHAPTER_PATH_ATTR="$escapedPath" $CHAPTER_URL_ATTR="$absoluteChapterUrl" $TSUNDOKU_CHAPTER_ATTR="1">"""
        } else {
            ""
        }
        val chapterWrapperEnd = if (chapterId != -1L) "</$CHAPTER_TAG_NAME>" else ""

        val mediaBlockCss = if (blockMedia) {
            "img, video, audio, source, svg, image { display: none !important; }"
        } else {
            ""
        }

        // Build global JS variables for custom scripts
        val chapterMetaScript = buildTsundokuScript()

        // Get theme tokens for CSS variables and JS exposure
        val theme = preferences.novelTheme.get()
        val themeTokens = NovelViewerTextUtils.getThemeTokens(activity, preferences, theme)

        var finalContent = cleanContent
        var embeddedHead = ""

        if (plainTextMode) {
            finalContent = """
                <pre class="${PLAIN_TEXT_CLASS}" ${ATTR_DATA_PLAIN_TEXT}="1" style="white-space: pre-wrap; word-break: break-word; overflow-wrap: anywhere; margin: 0;"></pre>
                <script>
                    document.querySelector('.${PLAIN_TEXT_CLASS}').textContent = ${JSONObject.quote(cleanContent)};
                </script>
            """.trimIndent()
        } else {
            try {
                val doc = org.jsoup.Jsoup.parse(finalContent)

                // Note: If keepEmbeddedCss is true, styles were already kept by sanitizeHtmlForWebView
                // If keepEmbeddedCss is false, styles were already stripped
                // We don't need to do anything here since sanitization was already done

                // Note: If keepEmbeddedJs is true, scripts were already kept by sanitizeHtmlForWebView
                // If keepEmbeddedJs is false, scripts were already stripped
                // We don't need to do anything here since sanitization was already done

                val bodyNode = doc.body()
                if (bodyNode != null && bodyNode.hasText()) {
                    finalContent = bodyNode.html()
                } else if (bodyNode != null && bodyNode.children().isNotEmpty()) {
                    finalContent = bodyNode.html()
                }
            } catch (_: Exception) {}
        }

        val escapedInitialStyle = stylePayload.css
            .replace("</style>", "<\\/style>")
            .replace("</Style>", "<\\/Style>")
            .replace("</STYLE>", "<\\/STYLE>")

        val hideHeadingCss = if (stylePayload.hideChapterTitle) {
            "h1:first-of-type, h2:first-of-type, h3:first-of-type, h4:first-of-type, h5:first-of-type, h6:first-of-type { display: none !important; }"
        } else {
            ""
        }

        // Escape theme token CSS variables for safe embedding in HTML
        val escapedThemeCss = themeTokens.cssVariables
            .replace("</style>", "<\\/style>")
            .replace("</Style>", "<\\/Style>")
            .replace("</STYLE>", "<\\/STYLE>")

        // Build theme exposure script - escape for safe embedding
        val escapedThemeJson = themeTokens.jsObject
            .replace("\\", "\\\\")
            .replace("</script>", "<\\/script>")
            .replace("</Script>", "<\\/Script>")
            .replace("</SCRIPT>", "<\\/SCRIPT>")
        val themeExposureScript = """window.TsundokuTheme = $escapedThemeJson;"""

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    $escapedThemeCss
                    .tsundoku-chapter-divider {
                        height: 1px;
                        margin: 32px auto;
                        padding: 0;
                        border: none;
                        border-top: 1px solid currentColor;
                        opacity: 0.4;
                        width: 60%;
                    }
                    tsundoku-chapter {
                        display: block;
                    }
                    img {
                        max-width: 100%;
                        height: auto;
                        display: block;
                        margin: 8px auto;
                        min-height: 100px;
                        background: rgba(150, 150, 150, 0.2) url('data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 50 50"><circle cx="25" cy="25" r="20" fill="none" stroke="%23888" stroke-width="5" stroke-dasharray="31.4 31.4"><animateTransform attributeName="transform" type="rotate" from="0 25 25" to="360 25 25" dur="1s" repeatCount="indefinite"/></circle></svg>') no-repeat center center;
                    }
                    video {
                        max-width: 100%;
                        height: auto;
                    }
                    $hideHeadingCss
                    $mediaBlockCss
                </style>
                <style id="tsundoku-custom-style">$escapedInitialStyle</style>
                $embeddedHead
                <script>$chapterMetaScript</script>
                <script>$themeExposureScript</script>
            </head>
            <body>
                $chapterDivider
                $chapterWrapperStart
                $finalContent
                $chapterWrapperEnd
            </body>
            </html>
        """.trimIndent()

        // Signal to onPageFinished that the next callback is for real chapter content, not
        // the loading-indicator page (which also fires onPageFinished with url="about:blank").
        isLoadingRealChapter = true
        webView.loadDataWithBaseURL(resolveWebViewBaseUrl(normalizedChapterUrl), html, "text/html", "UTF-8", null)
    }



    private fun resolveWebViewBaseUrl(chapterUrl: String?): String? {
        val repairedChapterUrl = normalizeUrl(chapterUrl)
        val absoluteChapterUrl = repairedChapterUrl?.trim().takeUnless { it.isNullOrBlank() }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (absoluteChapterUrl != null) return absoluteChapterUrl

        val novelUrl = normalizeUrl(activity.viewModel.manga?.url)?.trim().takeUnless { it.isNullOrBlank() }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        return novelUrl
    }

    private fun normalizeUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isBlank()) return url
        return when {
            value.startsWith("https//") -> "https://" + value.removePrefix("https//")
            value.startsWith("http//") -> "http://" + value.removePrefix("http//")
            else -> value
        }
    }

    private fun toAbsoluteChapterUrl(chapterPath: String?): String {
        val normalized = normalizeUrl(chapterPath).orEmpty().trim()
        if (normalized.isBlank()) return ""
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) return normalized

        val novelUrl = normalizeUrl(activity.viewModel.manga?.url).orEmpty().trim()
        if (!(novelUrl.startsWith("http://") || novelUrl.startsWith("https://"))) return normalized

        return try {
            URI(novelUrl).resolve(normalized).toString()
        } catch (_: Exception) {
            normalized
        }
    }

    private fun stripMediaTags(content: String): String {
        return content
            .replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<image[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</image>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<svg[^>]*>.*?</svg>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<video[^>]*>.*?</video>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<audio[^>]*>.*?</audio>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<source[^>]*>", RegexOption.IGNORE_CASE), "")
    }

    /**
     * Sanitize HTML content for WebView rendering based on user preferences.
     * Handles conditional stripping of scripts, styles, and media based on user toggles.
     */
    private fun sanitizeHtmlForWebView(
        content: String,
        keepEmbeddedCss: Boolean,
        keepEmbeddedJs: Boolean,
        blockMedia: Boolean,
    ): String {
        var result = content

        if (!keepEmbeddedJs) {
            result = result.replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            result = result.replace(Regex("<script[^>]*/>", RegexOption.IGNORE_CASE), "")
        }

        if (!keepEmbeddedCss) {
            result = result.replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            result = result.replace(Regex("<link[^>]*rel[^>]*stylesheet[^>]*>", RegexOption.IGNORE_CASE), "")
            result = result.replace(Regex("<link[^>]*stylesheet[^>]*rel[^>]*>", RegexOption.IGNORE_CASE), "")

            try {
                val doc = org.jsoup.Jsoup.parse(result)
                doc.select("*").removeAttr("style")
                result = doc.html()
            } catch (_: Exception) {
                // Keep the partially sanitized result if parsing fails.
            }
        }

        result = result.replace(Regex("<noscript[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        // Strip HTML comments — can render as visible text in WebView
        result = result.replace(Regex("<!--.*?-->", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        // Strip entity-encoded comment markers used as ad injection points (e.g. &lt;!--bg--&gt;)
        result = result.replace(Regex("&lt;!--.*?--&gt;", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        if (blockMedia) {
            result = stripMediaTags(result)
        }

        return result
    }

    /**
     * Build the global Tsundoku JS object for the current chapter context.
     * Includes the active chapter plus an array of all loaded chapters.
     */
    private fun buildTsundokuScript(): String {
        val currentChapter = getCurrentTsundokuChapter()
        val currentChapterJson = buildTsundokuChapterJson(currentChapter)
        val chaptersJson = buildTsundokuChaptersJson()
        val novelUrl = quoteForJson(normalizeUrl(activity.viewModel.manga?.url).orEmpty())

        return """
            window.$TSUNDOKU_OBJECT_NAME = window.$TSUNDOKU_OBJECT_NAME || {};
            window.$TSUNDOKU_OBJECT_NAME.$TSUNDOKU_NOVEL_URL_KEY = $novelUrl;
            window.$TSUNDOKU_OBJECT_NAME.$TSUNDOKU_CURRENT_CHAPTER_KEY = $currentChapterJson;
            window.$TSUNDOKU_OBJECT_NAME.$TSUNDOKU_CHAPTERS_KEY = $chaptersJson;
            window.$TSUNDOKU_OBJECT_NAME.runtime = window.$TSUNDOKU_OBJECT_NAME.runtime || {};
            window.$TSUNDOKU_OBJECT_NAME.runtime.$TSUNDOKU_IS_EDIT_MODE_KEY = $isEditingMode;
            window.$TSUNDOKU_OBJECT_NAME.runtime.$TSUNDOKU_IS_INF_SCROLL_KEY = ${preferences.novelInfiniteScroll.get()};
            window.$TSUNDOKU_OBJECT_NAME.runtime.$TSUNDOKU_TEXT_SELECTION_BLOCKED_KEY = ${!preferences.novelTextSelectable.get()};
            window.$TSUNDOKU_OBJECT_NAME.runtime.$TSUNDOKU_FORCED_LOWERCASE_KEY = ${preferences.novelForceTextLowercase.get()};
        """.trimIndent()
    }

    private fun getCurrentTsundokuChapter(): ReaderChapter? =
        loadedChapters.getOrNull(currentChapterIndex) ?: currentChapters?.currChapter

    private fun buildTsundokuChaptersJson(): String {
        val chapters = if (loadedChapters.isNotEmpty()) {
            loadedChapters
        } else {
            currentChapters?.currChapter?.let { listOf(it) }.orEmpty()
        }
        return chapters.joinToString(prefix = "[", postfix = "]") { buildTsundokuChapterJson(it) }
    }

    private fun buildTsundokuChapterJson(chapter: ReaderChapter?): String {
        val chapterModel = chapter?.chapter
        val chapterId = chapterModel?.id ?: -1L
        val chapterTitle = quoteForJson(chapterModel?.name.orEmpty())
        val chapterNumber = chapterModel?.chapter_number ?: -1f
        val chapterPath = quoteForJson(chapterModel?.url.orEmpty())
        val chapterUrl = quoteForJson(toAbsoluteChapterUrl(chapterModel?.url))

        return buildString {
            append('{')
            append("\"id\": ").append(chapterId).append(',')
            append("\"title\": ").append(chapterTitle).append(',')
            append("\"number\": ").append(chapterNumber).append(',')
            append("\"path\": ").append(chapterPath).append(',')
            append("\"url\": ").append(chapterUrl)
            append('}')
        }
    }

    /**
     * Update global JS chapter metadata variables after an infinite-scroll
     * boundary change (when the user scrolls into a different chapter).
     */
    private fun updateChapterMetaJs() {
        val js = buildTsundokuScript()
        evaluateJavascriptSafe("(function(){$js})();", null)
    }

    /**
     * Escape a string for safe embedding inside a JS double-quoted literal.
     * Also escapes `</script>` sequences which would prematurely close the script tag.
     */
    private fun String.jsEscape(): String =
        this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("</script>", "<\\/script>")
            .replace("</Script>", "<\\/Script>")
            .replace("</SCRIPT>", "<\\/SCRIPT>")

    /**
     * Apply user-configured find & replace rules to content.
     * Rules are stored as JSON in the novelRegexReplacements preference.
     * Each enabled rule is applied in order — supports both plain text and regex patterns.
     */
    private fun applyRegexReplacements(content: String): String =
        NovelViewerTextUtils.applyRegexReplacements(content, preferences)

    private fun normalizeContentForHtml(content: String, chapterUrl: String?): String =
        NovelViewerTextUtils.normalizeContentForHtml(content, chapterUrl)

    private fun String.htmlAttributeEscape(): String =
        this.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /**
     * Helper to safely quote strings for JSON/JavaScript literals.
     * Centralizes JSON quoting logic.
     */
    private fun quoteForJson(value: String): String =
        JSONObject.quote(value)

    /**
     * Strips the chapter title from the beginning of the content.
     * Removes the first H1-H6 heading element, first paragraph, div, span, or plain text if it matches the chapter name.
     */
    private fun stripChapterTitle(content: String, chapterName: String): String =
        NovelViewerTextUtils.stripChapterTitle(content, chapterName)

    private fun isTitleMatch(text: String, chapterName: String): Boolean =
        NovelViewerTextUtils.isTitleMatch(text, chapterName)

    private fun showLoadingIndicator() {
        // Inject loading HTML instead of showing popup
        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val fontColor = preferences.novelFontColor.get()
        val (themeBgColor, themeTextColor) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        val finalTextColor = if (fontColor != 0) fontColor else themeTextColor

        val bgColorHex = String.format("#%06X", 0xFFFFFF and finalBgColor)
        val textColorHex = String.format("#%06X", 0xFFFFFF and finalTextColor)

        val loadingHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        margin: 0;
                        padding: 16px;
                        background-color: $bgColorHex;
                        color: $textColorHex;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        height: 100vh;
                        font-family: sans-serif;
                    }
                </style>
            </head>
            <body>
                <div>Loading...</div>
            </body>
            </html>
        """.trimIndent()

        isLoadingRealChapter = false
        webView.loadDataWithBaseURL(null, loadingHtml, "text/html", "UTF-8", null)
    }

    private fun hideLoadingIndicator() {
        // No-op, content will replace loading HTML
    }

    private fun displayError(error: Throwable) {
        android.util.Log.e("NovelWebViewViewer", "displayError: ${error.javaClass.simpleName}: ${error.message}", error)
        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val fontColor = preferences.novelFontColor.get()
        val (themeBgColor, themeTextColor) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        val finalTextColor = if (fontColor != 0) fontColor else themeTextColor
        val bgColorHex = String.format("#%06X", 0xFFFFFF and finalBgColor)
        val textColorHex = String.format("#%06X", 0xFFFFFF and finalTextColor)

        val escapedMessage = "${error.javaClass.simpleName}: ${error.message ?: "(null)"}"
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
        val errorHtml = """
            <!DOCTYPE html>
            <html>
            <body style="display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; background-color: $bgColorHex; color: $textColorHex;">
                <div style="text-align: center; color: #ff5555;">
                    <h2>Error loading chapter</h2>
                    <p>$escapedMessage</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
    }

    override fun moveToPage(page: ReaderPage) {
        // For novels, navigation is by chapter not page
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        // Use a smaller scroll amount (30% of viewport) for volume keys
        val scrollAmount = (container.height * 0.30).toInt()

        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (isUp) activity.toggleMenu()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (preferences.novelVolumeKeysScroll.get()) {
                    if (!isUp) webView.evaluateJavascript("window.scrollBy(0, $scrollAmount);", null)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (preferences.novelVolumeKeysScroll.get()) {
                    if (!isUp) webView.evaluateJavascript("window.scrollBy(0, -$scrollAmount);", null)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_SPACE -> {
                if (!isUp) {
                    if (event.isShiftPressed) {
                        webView.pageUp(false)
                    } else {
                        webView.pageDown(false)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (!isUp) webView.pageUp(false)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (!isUp) webView.pageDown(false)
                return true
            }
        }
        return false
    }

    fun toggleEditMode(isEditing: Boolean, save: Boolean = true) {
        if (!isEditing && !save) {
            this.isEditingMode = false
            webView.evaluateJavascript(
                "(function() { window.getSelection().removeAllRanges(); document.activeElement.blur(); })();",
                null,
            )
            webView.clearFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)

            // Reload chapter to discard edits
            loadedChapterIds.clear()
            loadedChapters.clear()
            activity.viewModel.reloadChapter(fromSource = false)
            return
        }

        this.isEditingMode = isEditing
        injectCustomScript()
        updateChapterMetaJs()

        if (isEditing) {
            webView.post {
                activity.window.decorView.clearFocus()
                webView.requestFocus()
                webView.requestFocusFromTouch()
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(webView, 0)
                webView.postDelayed({
                    imm?.showSoftInput(webView, 0)
                }, 120)
            }
        } else {
            webView.evaluateJavascript(
                "(function() { window.getSelection().removeAllRanges(); document.activeElement.blur(); })();",
                null,
            )
            webView.clearFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)
        }

        val script = """
            (function() {
                function enableEdit() {
                    document.designMode = 'off';
                    var styleId = '${ID_EDIT_MODE_STYLE}';
                    if ('$isEditing' === 'true') {
                        if (!document.getElementById(styleId)) {
                            var style = document.createElement('style');
                            style.id = styleId;
                            style.innerHTML = '${CHAPTER_TAG_NAME}, [${ATTR_DATA_EDITABLE}="1"], body { -webkit-user-select: text !important; user-select: text !important; pointer-events: auto !important; -webkit-tap-highlight-color: transparent; outline: none; } ' +
                                'body { padding-bottom: max(220px, 38vh) !important; }';
                            document.head.appendChild(style);
                        }

                        var editTargets = document.querySelectorAll('${CHAPTER_TAG_NAME}');
                        if (editTargets.length === 0 && document.body) {
                            document.body.setAttribute('contenteditable', 'true');
                            document.body.setAttribute('${ATTR_DATA_EDITABLE}', '1');
                            document.body.setAttribute('tabindex', '0');
                        } else {
                            for (var i = 0; i < editTargets.length; i++) {
                                editTargets[i].setAttribute('contenteditable', 'true');
                                editTargets[i].setAttribute('${ATTR_DATA_EDITABLE}', '1');
                                editTargets[i].setAttribute('tabindex', '0');
                            }
                        }

                        window.$TSUNDOKU_OBJECT_NAME = window.$TSUNDOKU_OBJECT_NAME || {};
                        window.$TSUNDOKU_OBJECT_NAME.runtime = window.$TSUNDOKU_OBJECT_NAME.runtime || {};
                        // Guard against multiple registrations of input listener
                        if (!window.$TSUNDOKU_OBJECT_NAME.runtime.editInputBound) {
                            window.$TSUNDOKU_OBJECT_NAME.runtime.editInputBound = true;
                            // Remove existing listeners first to avoid duplicates
                            var existingListener = window.$TSUNDOKU_OBJECT_NAME.runtime.inputListener;
                            if (existingListener) {
                                document.removeEventListener('input', existingListener);
                            }
                            var inputListener = function(e) {
                                if (window.Android && window.Android.onContentEdited) {
                                    window.Android.onContentEdited();
                                }
                            };
                            document.addEventListener('input', inputListener);
                            window.$TSUNDOKU_OBJECT_NAME.runtime.inputListener = inputListener;
                        }
                    } else {
                        var style = document.getElementById(styleId);
                        if (style) {
                            style.parentNode.removeChild(style);
                        }

                        var editableNodes = document.querySelectorAll('[data-tsundoku-editable="1"]');
                        for (var j = 0; j < editableNodes.length; j++) {
                            editableNodes[j].removeAttribute('contenteditable');
                            editableNodes[j].removeAttribute('${ATTR_DATA_EDITABLE}');
                            editableNodes[j].removeAttribute('tabindex');
                        }

                        var contents = [];
                        var nodes = document.querySelectorAll('${CHAPTER_TAG_NAME}');
                        if (nodes.length > 0) {
                            for (var i = 0; i < nodes.length; i++) {
                                var html = nodes[i].innerHTML;
                                var chapterId = nodes[i].getAttribute('${ATTR_DATA_CHAPTER_ID}');
                                contents.push({id: chapterId, content: html});
                            }
                        } else if (document.body) {
                            var currentId = '${currentChapters?.currChapter?.chapter?.id ?: -1}';
                            contents.push({id: currentId, content: document.body.innerHTML});
                        }
                        if (window.Android && window.Android.onSaveEditedContent) {
                            window.Android.onSaveEditedContent(JSON.stringify(contents));
                        }
                    }
                }

                if (document.readyState === 'complete') {
                    enableEdit();
                } else {
                    window.addEventListener('load', enableEdit);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    /**
     * JavaScript interface for communication from WebView
     */
    @Keep
    inner class WebViewInterface {
        @JavascriptInterface
        fun onContentEdited() {
            activity.viewModel.setHasUnsavedChanges(true)
        }

        @JavascriptInterface
        fun onSaveEditedContent(json: String) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: onSaveEditedContent(length=${json.length})" }
            activity.viewModel.saveEditedChapterContent(json)
        }

        @JavascriptInterface
        fun onScrollProgress(progress: Float) {
            activity.runOnUiThread {
                lastSavedProgress = progress
                saveProgress()
            }
        }

        @JavascriptInterface
        fun onScrollUpdate(progress: Float) {
            activity.runOnUiThread {
                lastSavedProgress = progress
                activity.onNovelProgressChanged(progress)
            }
        }

        @JavascriptInterface
        fun onChapterScrollUpdate(chapterIndex: Int, progress: Float) {
            activity.runOnUiThread {
                val chapterId = loadedChapters.getOrNull(chapterIndex)?.chapter?.id
                val pendingChapterId = pendingTtsHandoffChapterId
                if (pendingChapterId != null && chapterId != pendingChapterId) {
                    return@runOnUiThread
                }

                if (chapterIndex != currentChapterIndex && chapterIndex >= 0 && chapterIndex < loadedChapters.size) {
                    val oldIndex = currentChapterIndex
                    currentChapterIndex = chapterIndex
                    logcat(LogPriority.DEBUG) {
                        "NovelWebViewViewer: onChapterScrollUpdate chapterIndex=$chapterIndex progress=$progress ts=${System.currentTimeMillis()} ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex} (changed from $oldIndex)"
                    }

                    activity.viewModel.setNovelVisibleChapter(loadedChapters.getOrNull(chapterIndex)?.chapter)

                    loadedChapters.getOrNull(chapterIndex)?.pages?.firstOrNull()?.let { page ->
                        currentPage = page
                        activity.onPageSelected(page)
                    }

                    // Reset progress for new chapter - use 0f, not the incoming progress
                    lastSavedProgress = 0f
                    activity.onNovelProgressChanged(0f)

                    // Update global JS variables for the new chapter
                    updateChapterMetaJs()
                }

                if (pendingChapterId != null && chapterId == pendingChapterId) {
                    if (!pendingTtsHandoffStarted) {
                        // For viewport-based handoff, wait until the scroll has actually
                        // entered the new chapter (progress > 0) so the viewport doesn't
                        // accidentally show the last paragraph of the previous chapter.
                        if (pendingTtsHandoffUseViewport && progress < 0.01f) {
                            // Not settled yet — wait for next scroll update.
                        } else {
                            pendingTtsHandoffStarted = true
                            if (pendingTtsHandoffUseViewport) {
                                startTtsFromViewport()
                            } else {
                                startTts()
                            }
                        }
                    } else if (pendingTtsHandoffStarted && progress > 0.01f) {
                        clearPendingTtsHandoff()
                    }
                }
            }
        }

        @JavascriptInterface
        fun onInfiniteScrollAppendComplete(chapterId: Long) {
            activity.runOnUiThread {
                if (ttsController.isTtsAutoPlay && pendingTtsAppend) {
                    // Next chapter is now in DOM — cancel timeout, unload current chapter,
                    // and start TTS from the beginning of the newly appended chapter.
                    pendingTtsAppend = false
                    pendingTtsHandoffTimeoutJob?.cancel()
                    pendingTtsHandoffTimeoutJob = null
                    clearPendingTtsHandoff()
                    unloadReadChaptersAndStartNextTts()
                    return@runOnUiThread
                }

                val pendingChapterId = pendingTtsHandoffChapterId ?: return@runOnUiThread
                if (pendingChapterId != chapterId) return@runOnUiThread

                val chapterIndex = loadedChapterIds.indexOf(chapterId)
                if (chapterIndex < 0) return@runOnUiThread

                currentChapterIndex = chapterIndex
                loadedChapters.getOrNull(chapterIndex)?.pages?.firstOrNull()?.let { page ->
                    currentPage = page
                    activity.viewModel.setNovelVisibleChapter(page.chapter.chapter)
                    activity.onPageSelected(page)
                    activity.onNovelProgressChanged(0f)
                }

                scrollToChapterIndex(chapterIndex)
                pendingTtsHandoffTimeoutJob?.cancel()
                pendingTtsHandoffTimeoutJob = null
            }
        }

        @JavascriptInterface
        fun loadNextChapter() {
            activity.runOnUiThread {
                logcat(LogPriority.DEBUG) {
                    "NovelWebViewViewer: loadNextChapter triggered, infiniteScroll=${preferences.novelInfiniteScroll.get()}, isLoadingNext=$isLoadingNext, loadedCount=${loadedChapterIds.size}"
                }
                if (!preferences.novelInfiniteScroll.get()) {
                    activity.loadNextChapter()
                } else if (ttsController.isTtsAutoPlay) {
                    // Don't append to DOM while TTS is active — TTS drives its own appends.
                    // Instead, pre-fetch and cache the next chapter so it's ready instantly
                    // when TTS finishes the current chapter and calls appendNextChapterIfAvailable.
                    if (cachedNextChapterForTts == null) {
                        logcat(LogPriority.DEBUG) { "NovelWebViewViewer: loadNextChapter — TTS active, pre-fetching next chapter" }
                        scope.launch { preFetchNextChapterForTts() }
                    }
                } else if (!isLoadingNext) {
                    isLoadingNext = true
                    scope.launch {
                        try {
                            appendNextChapterIfAvailable()
                        } finally {
                            isLoadingNext = false
                            setJsLoadingNext(false)
                        }
                    }
                } else {
                    logcat(LogPriority.WARN) {
                        "NovelWebViewViewer: loadNextChapter ignored (infiniteScroll=${preferences.novelInfiniteScroll.get()}, isLoadingNext=$isLoadingNext)"
                    }
                }
            }
        }

        @JavascriptInterface
        fun markChapterAsShort() {
            activity.runOnUiThread {
                // If the whole chapter fits in the viewport, treat it as fully read.
                lastSavedProgress = 1f
                saveProgress()
                activity.onNovelProgressChanged(1f)
                logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Chapter marked as short (fits in viewport)" }
            }
        }
    }

    private fun setJsLoadingNext(isLoading: Boolean) {
        val flag = if (isLoading) "true" else "false"
        evaluateJavascriptSafe(
            "(function(){ if (window.$TSUNDOKU_OBJECT_NAME && window.$TSUNDOKU_OBJECT_NAME.runtime && window.$TSUNDOKU_OBJECT_NAME.runtime.setLoadingNext) window.$TSUNDOKU_OBJECT_NAME.runtime.setLoadingNext($flag); })();",
            null,
        )
    }

    private suspend fun awaitPageText(page: ReaderPage, loader: PageLoader, timeoutMs: Long): Boolean =
        NovelViewerTextUtils.awaitPageText("NovelWebViewViewer", page, loader, timeoutMs, scope)

    private suspend fun displayContentImmediate(
        chapter: ReaderChapter,
        page: ReaderPage,
        isAppendOrPrepend: Boolean,
        isPrepend: Boolean,
    ) {
        if (isDestroyed) return

        var content = page.text
        if (content.isNullOrBlank()) {
            displayError(Exception("No text content available"))
            return
        }

        val chapterId = chapter.chapter.id ?: return

        if (preferences.novelHideChapterTitle.get()) {
            content = stripChapterTitle(content, chapter.chapter.name)
        }

        // Keep preprocessing consistent with normal WebView loads.
        content = applyRegexReplacements(content)

        // Optionally force lowercase
        if (preferences.novelForceTextLowercase.get()) {
            content = content.lowercase()
        }

        val processedContent = activity.translateContentIfEnabled(content)
        scheduleNovelImagePrefetch(processedContent, chapterId, page.chapter.pageLoader)
        val plainTextMode = NovelViewerTextUtils.isPlainTextChapter(chapter.chapter.url)
        val renderableContent = if (plainTextMode) {
            NovelViewerTextUtils.normalizePlainTextContent(processedContent)
        } else {
            NovelViewerTextUtils.normalizeContentForHtml(
                processedContent,
                chapter.chapter.url,
            )
        }

        withContext(Dispatchers.Main) {
            if (isDestroyed) return@withContext

            if (isAppendOrPrepend && preferences.novelInfiniteScroll.get()) {
                if (!loadedChapterIds.contains(chapterId)) {
                    if (isPrepend) {
                        // Backward prepend is disabled; keep behavior forward-only.
                        return@withContext
                    }
                    loadedChapterIds.add(chapterId)
                    loadedChapters.add(chapter)
                }
                appendHtmlContent(renderableContent, chapterId, chapter.chapter.name, chapter.chapter.chapter_number, chapter.chapter.url)
            } else {
                loadHtmlContent(renderableContent, chapter)
                loadedChapterIds.clear()
                loadedChapters.clear()
                loadedChapterIds.add(chapterId)
                loadedChapters.add(chapter)
                currentChapterIndex = 0
            }
        }
    }

    /**
     * Fetch and cache the next chapter without appending to the DOM.
     * Called when the JS scroll threshold fires during TTS auto-play so the chapter is
     * immediately available when TTS finishes the current one.
     */
    private suspend fun preFetchNextChapterForTts() {
        if (cachedNextChapterForTts != null) return
        val anchor = loadedChapters.lastOrNull() ?: currentChapters?.currChapter ?: return
        val preparedChapter = activity.viewModel.prepareNextChapterForInfiniteScroll(anchor) ?: return
        val nextId = preparedChapter.chapter.id ?: return
        if (loadedChapterIds.contains(nextId)) return

        val page = preparedChapter.pages?.firstOrNull() as? ReaderPage ?: return
        val loader = page.chapter.pageLoader ?: return

        logcat(LogPriority.DEBUG) { "TTS (WebView): Pre-fetching next chapter ${preparedChapter.chapter.name}" }
        try {
            val loaded = awaitPageText(page = page, loader = loader, timeoutMs = 30_000)
            if (loaded) {
                withContext(Dispatchers.Main) {
                    if (cachedNextChapterForTts == null) {
                        cachedNextChapterForTts = Pair(preparedChapter, page)
                        logcat(LogPriority.DEBUG) { "TTS (WebView): Cached next chapter ${preparedChapter.chapter.name}" }
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "TTS (WebView): Pre-fetch failed: ${e.message}" }
        }
    }

    private suspend fun appendNextChapterIfAvailable() {
        // Use pre-fetched cache when available — avoids redundant network request.
        val cached = cachedNextChapterForTts
        if (cached != null) {
            cachedNextChapterForTts = null
            val (preparedChapter, page) = cached
            val nextId = preparedChapter.chapter.id ?: return
            if (!loadedChapterIds.contains(nextId)) {
                logcat(LogPriority.DEBUG) { "NovelWebViewViewer: using pre-fetched chapter $nextId (${preparedChapter.chapter.name})" }
                try {
                    displayContentImmediate(preparedChapter, page, isAppendOrPrepend = true, isPrepend = false)
                    logcat(LogPriority.INFO) { "NovelWebViewViewer: Successfully appended pre-fetched chapter ${preparedChapter.chapter.name}" }
                } finally {
                    hideInlineLoading(isPrepend = false)
                    setJsLoadingNext(false)
                }
            }
            return
        }

        val anchor = loadedChapters.lastOrNull() ?: currentChapters?.currChapter ?: run {
            logcat(LogPriority.ERROR) {
                "NovelWebViewViewer: appendNext failed, no anchor chapter (loadedCount=${loadedChapters.size})"
            }
            showInlineError("No anchor chapter for infinite scroll", isPrepend = false)
            return
        }
        logcat(LogPriority.DEBUG) {
            "NovelWebViewViewer: appendNext starting from anchor=${anchor.chapter.id}/${anchor.chapter.name}"
        }

        val preparedChapter = activity.viewModel.prepareNextChapterForInfiniteScroll(anchor) ?: run {
            logcat(LogPriority.WARN) { "NovelWebViewViewer: No next chapter available after ${anchor.chapter.name}" }
            showInlineError("No next chapter available", isPrepend = false)
            return
        }
        val nextId = preparedChapter.chapter.id ?: return
        logcat(LogPriority.DEBUG) { "NovelWebViewViewer: prepared next=$nextId/${preparedChapter.chapter.name}" }

        if (loadedChapterIds.contains(nextId)) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: next chapter $nextId already loaded, skipping" }
            return
        }

        val page = preparedChapter.pages?.firstOrNull() as? ReaderPage ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page in prepared next chapter" }
            showInlineError("No page in next chapter", isPrepend = false)
            return
        }
        val loader = page.chapter.pageLoader ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page loader for next chapter" }
            showInlineError("No loader for next chapter", isPrepend = false)
            return
        }

        showInlineLoading(isPrepend = false)
        try {
            logcat(LogPriority.DEBUG) {
                "NovelWebViewViewer: loading page for next chapter $nextId, state=${page.status}"
            }
            val loaded = try {
                awaitPageText(page = page, loader = loader, timeoutMs = 30_000)
            } catch (e: TimeoutCancellationException) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: Timed out loading next chapter page after 30s" }
                showInlineError("Timeout loading next chapter", isPrepend = false)
                false
            } catch (e: CancellationException) {
                logcat(LogPriority.DEBUG) { "NovelWebViewViewer: appendNext cancelled" }
                false
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: Error loading next chapter page: ${e.message}" }
                showInlineError("Error: ${e.message ?: "Unknown error"}", isPrepend = false)
                false
            }

            if (!loaded) return

            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: appending content for chapter $nextId ts=${System.currentTimeMillis()} ttsCurrentChunkIndex=${ttsController.ttsCurrentChunkIndex} ttsResumeChunkIndex=${ttsController.ttsResumeChunkIndex} ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex} ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}" }
            displayContentImmediate(preparedChapter, page, isAppendOrPrepend = true, isPrepend = false)
            logcat(LogPriority.INFO) {
                "NovelWebViewViewer: Successfully appended next chapter ${preparedChapter.chapter.name}"
            }
        } finally {
            hideInlineLoading(isPrepend = false)
            setJsLoadingNext(false)
        }
    }

    private suspend fun prependPreviousChapterIfAvailable() {
        val anchor = loadedChapters.firstOrNull() ?: currentChapters?.currChapter ?: return
        val preparedChapter = activity.viewModel.preparePreviousChapterForInfiniteScroll(anchor) ?: return
        val prevId = preparedChapter.chapter.id ?: return
        if (loadedChapterIds.contains(prevId)) return

        val page = preparedChapter.pages?.firstOrNull() as? ReaderPage ?: return
        val loader = page.chapter.pageLoader ?: return

        showInlineLoading(isPrepend = true)
        try {
            val loaded = awaitPageText(page, loader, 30_000)

            if (!loaded) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: Failed to load previous chapter page" }
                return
            }

            withContext(Dispatchers.Main) {
                displayContent(preparedChapter, page, isAppendOrPrepend = true, isPrepend = true)
            }
        } finally {
            hideInlineLoading(isPrepend = true)
        }
    }

    /**
     * Scroll to the top of the content
     */
    fun scrollToTop() {
        webView.scrollTo(0, 0)
    }

    /**
     * Toggle auto-scroll for WebView using JavaScript-based smooth scrolling
     */
    fun toggleAutoScroll() {
        isAutoScrolling = !isAutoScrolling

        if (isAutoScrolling) {
            startAutoScroll()
        } else {
            stopAutoScroll()
        }
    }

    private fun startAutoScroll() {
        val speed = preferences.novelAutoScrollSpeed.get().coerceIn(1, 10)
        isAutoScrolling = true

        autoScrollJob?.cancel()
        autoScrollJob = scope.launch {
            while (isActive && isAutoScrolling) {
                // Scroll amount per tick - higher speed = more scroll
                val scrollAmount = speed // 1-10 pixels per tick
                evaluateJavascriptSafe(
                    """
                    (function() {
                        window.scrollBy(0, $scrollAmount);
                    })();
                    """.trimIndent(),
                    null,
                )
                // Fixed delay between scroll ticks
                // At speed 1: scroll 1px every 50ms = ~20px/sec
                // At speed 10: scroll 10px every 50ms = ~200px/sec
                delay(50L)
            }
        }
    }

    private fun stopAutoScroll() {
        isAutoScrolling = false
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    /**
     * Check if auto-scroll is currently active
     */
    fun isAutoScrollActive(): Boolean = isAutoScrolling

    /**
     * Gets the current scroll progress as percentage (0 to 100)
     */
    fun getProgressPercent(): Int {
        // Return last saved progress since WebView scroll can't be accessed synchronously
        return (lastSavedProgress * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Sets the scroll position by progress percentage (0 to 100)
     */
    fun setProgressPercent(percent: Int) {
        val progress = percent.coerceIn(0, 100)
        // Update local state immediately for consistent UI
        lastSavedProgress = progress / 100f

        evaluateJavascriptSafe(
            """
            (function() {
                var scrollHeight = document.documentElement.scrollHeight - window.innerHeight;
                var targetScroll = scrollHeight * $progress / 100;
                window.scrollTo(0, targetScroll);
                // Update the tracking variable to prevent immediate re-report
                if (typeof lastProgress !== 'undefined') {
                    lastProgress = $progress / 100.0;
                }
            })();
            """.trimIndent(),
            null,
        )
    }

    /**
     * Reload the current chapter
     */
    fun reloadChapter() {
        currentChapters?.let { setChapters(it) }
    }

    // TTS Methods

    private fun ensureTtsInitialized() {
        ttsIsPreparing = true
        ttsController.ensureInitialized()
    }

    fun startTts() {
        ensureTtsInitialized()

        if (!ttsController.ttsInitialized) {
            logcat(LogPriority.WARN) { "TTS (WebView): Not initialized yet, waiting..." }
            ttsController.pendingStartRequest = TtsController.StartRequest.NORMAL
            return
        }

        ttsController.pendingStartRequest = null
        ttsController.isTtsAutoPlay = true
        val (chapterIdx, chapterId) = getTtsChapterContext()
        evaluateJavascriptSafe(
            """
            (function() {
                var body = document.body;
                return body ? body.innerText || body.textContent : '';
            })();
            """.trimIndent(),
        ) { result ->
            val text = result?.let {
                if (it.startsWith("\"") && it.endsWith("\"")) {
                    it.substring(1, it.length - 1)
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                } else {
                    it
                }
            }

            if (!text.isNullOrBlank() && text != "null") {
                logcat(LogPriority.DEBUG) { "TTS (WebView): Starting to speak ${text.length} characters" }
                ttsController.speak(text, chapterIdx, chapterId)
            } else {
                logcat(LogPriority.WARN) { "TTS (WebView): No text to speak" }
            }
        }
    }

    fun stopTts() {
        logcat(LogPriority.DEBUG) {
            "TTS (WebView): stopTts called ts=${System.currentTimeMillis()} currentChapterIndex=$currentChapterIndex, ttsCurrentChunkIndex=${ttsController.ttsCurrentChunkIndex}, ttsResumeChunkIndex=${ttsController.ttsResumeChunkIndex}, ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex}, ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}"
        }
        pendingTtsAutoStartOnLoad = false
        isLoadingRealChapter = false
        ttsController.stop()
        clearPendingTtsHandoff()
    }

    fun pauseTts() {
        logcat(LogPriority.DEBUG) {
            "TTS (WebView): pauseTts called ts=${System.currentTimeMillis()} currentChapterIndex=$currentChapterIndex, ttsCurrentChunkIndex=${ttsController.ttsCurrentChunkIndex}, ttsResumeChunkIndex=${ttsController.ttsResumeChunkIndex}, ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex}, ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}"
        }
        ttsController.pause()
    }

    fun resumeTts() {
        ttsController.resume()
    }

    fun ttsNextParagraph() {
        stepTtsParagraph(1)
    }

    fun ttsPreviousParagraph() {
        stepTtsParagraph(-1)
    }

    private fun stepTtsParagraph(delta: Int) {
        ttsController.stepParagraph(delta) { startTtsFromViewport() }
    }

    private fun getTtsChapterContext(): Pair<Int, Long?> {
        val activeChapter = getCurrentTsundokuChapter()
            ?: currentPage?.chapter
            ?: return Pair(currentChapterIndex, null)
        return Pair(
            currentChapterIndex,
            activeChapter.chapter.id ?: currentPage?.chapter?.chapter?.id,
        )
    }

    fun isTtsPaused(): Boolean = ttsController.isPaused()

    fun isTtsSpeaking(): Boolean = ttsController.isSpeaking()

    fun isTtsStarting(): Boolean = ttsController.isStarting()

    fun getTtsProgressPercent(): Int = ttsController.getProgressPercent()

    fun getAvailableVoices(): List<Pair<String, String>> = ttsController.getAvailableVoices()

    fun getCurrentVoiceName(): String = ttsController.getCurrentVoiceName()

    fun startTtsFromViewport() {
        ensureTtsInitialized()

        if (!ttsController.ttsInitialized) {
            logcat(LogPriority.WARN) { "TTS (WebView): Not initialized yet" }
            ttsController.pendingStartRequest = TtsController.StartRequest.VIEWPORT
            return
        }

        ttsController.pendingStartRequest = null
        ttsController.isTtsAutoPlay = true
        val (chapterIdx, chapterId) = getTtsChapterContext()
        evaluateJavascriptSafe(
            """
            (function() {
                var selectors = 'p, li, blockquote, h1, h2, h3, h4, h5, h6, pre';
                var elements = Array.from(document.querySelectorAll(selectors)).filter(function(el) {
                    return !!el && !!el.innerText && el.innerText.trim().length > 0;
                });
                if (!elements.length) {
                    elements = Array.from(document.body.children).filter(function(el) {
                        return !!el && !!el.innerText && el.innerText.trim().length > 0;
                    });
                }
                var viewportHeight = window.innerHeight || document.documentElement.clientHeight;
                for (var i = 0; i < elements.length; i++) {
                    var rect = elements[i].getBoundingClientRect();
                    if (rect.bottom > 0 && rect.top < viewportHeight) {
                        return i;
                    }
                }
                return 0;
            })();
            """.trimIndent(),
        ) { rawIndex ->
            val firstVisibleParagraphIndex = rawIndex?.trim('"')?.toIntOrNull() ?: 0
            evaluateJavascriptSafe(
                """
                (function() {
                    var body = document.body;
                    return body ? body.innerText || body.textContent : '';
                })();
                """.trimIndent(),
            ) { result ->
                val text = result?.let {
                    if (it.startsWith("\"") && it.endsWith("\"")) {
                        it.substring(1, it.length - 1)
                            .replace("\\n", "\n")
                            .replace("\\t", "\t")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                    } else {
                        it
                    }
                }

                if (!text.isNullOrBlank() && text != "null") {
                    ttsController.ttsViewportParagraphIndex = firstVisibleParagraphIndex.coerceAtLeast(0)
                    ttsController.hasViewportStartOverride = true
                    ttsController.speak(text, chapterIdx, chapterId)
                } else {
                    logcat(LogPriority.WARN) { "TTS (WebView): No text available for viewport start" }
                }
            }
        }
    }

    /**
     * Get the currently selected text from the WebView
     */
    fun getSelectedText(): String? {
        var selectedText: String? = null
        evaluateJavascriptSafe(
            """
            (function() {
                var selection = window.getSelection();
                if (selection && selection.toString().trim()) {
                    return selection.toString().trim();
                }
                return null;
            })();
            """.trimIndent(),
        ) { result ->
            // JavaScript returns quoted string, need to unquote and unescape
            selectedText = result?.let {
                if (it.startsWith("\"") && it.endsWith("\"")) {
                    it.substring(1, it.length - 1)
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                } else {
                    it
                }
            }
        }
        return selectedText
    }

    /**
     * Get the current chapter name for quote context
     */
    fun getCurrentChapterName(): String? {
        val loaded = loadedChapters.getOrNull(currentChapterIndex) ?: return null
        return loaded.chapter.name
    }

    /**
     * Check if text is currently selected in the WebView
     */
    fun hasTextSelection(): Boolean {
        var hasSelection = false
        evaluateJavascriptSafe(
            """
            (function() {
                var selection = window.getSelection();
                return selection && selection.toString().trim().length > 0;
            })();
            """.trimIndent(),
        ) { result ->
            hasSelection = result == "true"
        }
        return hasSelection
    }

    /**
     * Clear text selection in the WebView
     */
    fun clearTextSelection() {
        evaluateJavascriptSafe(
            """
            (function() {
                var selection = window.getSelection();
                if (selection) {
                    selection.removeAllRanges();
                }
            })();
            """.trimIndent(),
            null,
        )
    }

    /**
     * Handle the "Remember" action from text selection menu
     */
    private fun onRememberSelectedText(actionMode: ActionMode? = null) {
        evaluateJavascriptSafe(
            """
        (function() {
            var selection = window.getSelection();
            if (selection && selection.toString().trim()) {
                return selection.toString().trim();
            }
            return null;
        })();
            """.trimIndent(),
        ) { result ->
            activity.runOnUiThread {
                actionMode?.finish() // finish AFTER JS has read the selection
                val selectedText = if (result != null && result != "null" &&
                    result.startsWith("\"") && result.endsWith("\"")
                ) {
                    result.substring(1, result.length - 1)
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                } else {
                    null
                }

                if (!selectedText.isNullOrBlank()) {
                    pendingSelectedText = selectedText
                    activity.onRememberSelectedText()
                    clearTextSelection()
                } else {
                    activity.toast("No text selected")
                }
            }
        }
    }
}
