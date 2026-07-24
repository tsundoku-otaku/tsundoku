@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.lifecycle.Lifecycle
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.text.NovelConfig
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ChapterQueue
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ContentConfig
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ContentPipeline
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ErrorFormatter
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.HtmlUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.NovelPageLoader
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.NovelProgress
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ProcessedContent
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.RenderTarget
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.TtsController
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.TtsHandoffState
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.handleNovelFlingGesture
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.localized
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_DIVIDER_CLASS
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_ID_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_NUMBER_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_PATH_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TAG_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TITLE_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_URL_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_CHAPTERS_CONTAINER_ID
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_CHAPTER_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_OBJECT_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.quoteForJson
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.injectLazy

class NovelWebViewViewer(val activity: ReaderActivity) : Viewer {

    private companion object {
        const val REMEMBER_MENU_ITEM_ID = 0xBEEF // arbitrary unique ID
        const val ATTR_DATA_EDITABLE = "data-tsundoku-editable"
        const val ID_EDIT_MODE_STYLE = "edit-mode-style"
        const val SEEK_ECHO_SUPPRESS_MS = 350L
        const val AUTO_SCROLL_START_VERIFY_MS = 400L
        const val AUTO_SCROLL_MAX_START_ATTEMPTS = 3

        const val TTS_TEXT_EXTRACTION_JS = """
            (function() {
                var selectors = 'p, li, blockquote, h1, h2, h3, h4, h5, h6, pre';
                var els = Array.from(document.querySelectorAll(selectors)).filter(function(el) {
                    return !!el && !!el.innerText && el.innerText.trim().length > 0;
                });
                if (!els.length) {
                    els = Array.from(document.body.children).filter(function(el) {
                        return !!el && !!el.innerText && el.innerText.trim().length > 0;
                    });
                }
                if (!els.length) {
                    var body = document.body;
                    return body ? body.innerText || body.textContent : '';
                }
                return els.map(function(el) {
                    return el.innerText.trim().replace(/\s*\n\s*/g, ' ');
                }).join('\n');
            })();
        """

        fun unescapeJsResult(result: String): String =
            if (result.startsWith("\"") && result.endsWith("\"")) {
                // \\ must come first so \\n stays as backslash+n rather than becoming a newline.
                result.substring(1, result.length - 1)
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
            } else {
                result
            }
    }

    private val container = FrameLayout(activity)
    private lateinit var webView: WebView
    private var loadingIndicator: ReaderProgressIndicator? = null
    private val preferences: ReaderPreferences by injectLazy()
    private val libraryPreferences: tachiyomi.domain.library.service.LibraryPreferences by injectLazy()
    private val contentPipeline = ContentPipeline(preferences)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var contentJob: Job? = null
    private var appendJob: Job? = null
    private var attachListener: View.OnAttachStateChangeListener? = null
    private var currentPage: ReaderPage? = null
    private var currentChapters: ViewerChapters? = null
    private val imageCache = NovelWebViewImageCache(activity.cacheDir, scope)

    private var lastSavedProgress = 0f

    private var isInfiniteScrollNavigation = false
    private var isInfiniteScrollPrepend = false
    private val chapterQueue = ChapterQueue<ReaderChapter> { it.chapter.id }

    // Suppresses JS scroll callbacks while a full-document load + scroll restore is in flight, so a
    // stale event or the programmatic restore scroll can't persist against the new chapter's page.
    private var isRestoringScroll = false
    private var scrollRestoreToken = 0

    // Blocks flushing the backward-entry 1f baseline until a real scroll sample replaces it.
    private var awaitingFirstScrollSample = false

    // Timestamp of the last slider seek; scroll->slider echoes are ignored briefly after so a stale
    // async onScrollUpdate can't overwrite the value the user is dragging to.
    private var lastUserSeekAt = 0L

    // Latched once the novel has no further chapter to append.
    private var reachedNovelEnd = false

    // Suppresses auto-append for NEXT_LOAD_RETRY_COOLDOWN_MS after a failure; the JS load guard
    // clears each finally, so without this a chapter that keeps timing out re-fires every frame.
    private var lastNextLoadFailedAt = 0L

    // True while a delayed JS-latch release is queued for the current cooldown, so the JS load latch
    // is held (not re-fired every scroll frame) and released exactly once when the cooldown ends.
    private var cooldownReleaseScheduled = false

    // Lightweight property accessors so existing call sites keep working.
    // Mutations should go through chapterQueue's methods (append / prepend /
    // removeFirst / clear) - they keep the cursor and id-set in sync.
    private val loadedChapters: List<ReaderChapter> get() = chapterQueue.all
    private val loadedChapterIds: Set<Long> get() = chapterQueue.loadedIds
    private var currentChapterIndex: Int
        get() = chapterQueue.currentIndex
        set(value) {
            chapterQueue.currentIndex = value
        }
    private var isLoadingNext: Boolean
        get() = chapterQueue.isLoadingNext
        set(value) {
            chapterQueue.isLoadingNext = value
        }
    private var isDestroyed = false
    private var isEditingMode = false

    private var isAutoScrolling = false
    private var autoScrollStartAttempt = 0

    // The error page is a fresh document that drops the autoscroll rAF loop; re-arm it once its
    // onPageFinished lands, since that load never enters DocState.LOADING_REAL so the re-arm path
    // in the real-chapter gate is skipped.
    private var rearmAutoScrollOnErrorPage = false

    // Tracked so a JS dialog still on screen at teardown is dismissed instead of leaking the window.
    private var activeJsDialog: AlertDialog? = null

    // Reader-chrome obstruction pushed from ReaderActivity: the transient reader menu bars (shown
    // only with the menu) plus system bars. The novel status bar is NOT here - it gets real layout
    // space via viewer_container padding. Exposed as --tsundoku-safe-top/bottom +
    // Tsundoku.runtime.menuVisible so fixed elements clear the menu. Re-applied on each fresh-DOM load.
    private var chromeMenuVisible = false
    private var chromeSafeTopDp = 0f
    private var chromeSafeBottomDp = 0f

    private val config = NovelConfig(scope)
    private val navigator get() = config.navigator

    private var handoffState: TtsHandoffState<Pair<ReaderChapter, ReaderPage>> = TtsHandoffState.Idle

    private val prefetchCompletedSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Survives ttsController.stop() - set when TTS triggers a non-inf-scroll chapter load.
    private var pendingTtsAutoStartOnLoad = false

    // Single source of truth for the loaded document's lifecycle, replacing three hand-synced
    // booleans (isLoadingRealChapter/webChapterContentReady/webChapterIsError) that had to be flipped
    // together on every load/error/finish. Only these four combinations were ever legal; the enum
    // makes the illegal ones unrepresentable.
    //   LOADING       loading-indicator page up, or a base load queued but not yet issued
    //   LOADING_REAL  real-chapter loadDataWithBaseURL issued, awaiting its onPageFinished
    //   READY         real content committed; TTS may read the body and appends may splice onto it
    //   ERROR         error placeholder committed; body counts as ready but has no DOM to append onto
    private enum class DocState { LOADING, LOADING_REAL, READY, ERROR }
    private var docState = DocState.LOADING

    // Real content or an error placeholder is committed. Error counts as ready so a failed load
    // can't block infinite-scroll appends forever; webChapterIsError then suppresses the append.
    private val webChapterContentReady get() = docState == DocState.READY || docState == DocState.ERROR

    // Current DOM is an error placeholder, so there is no valid base to append the next chapter onto.
    private val webChapterIsError get() = docState == DocState.ERROR

    private val ttsController: TtsController

    // Initialized in [initWebView] after the WebView lateinit is assigned.
    // Was previously `by lazy { ... }` but the lazy initializer ran from
    // inside the WebView's `.apply { }` block (before `webView = …` had
    // completed assignment), causing "lateinit property webView has not been
    // initialized" when toggling rendering mode mid-session.
    private lateinit var styler: NovelWebViewStyler

    private val inlineFeedback by lazy {
        NovelWebViewInlineFeedback(
            scope = scope,
            evaluateJs = { js -> evaluateJavascriptSafe(js, null) },
        )
    }

    var pendingSelectedText: String? = null
    var pendingParagraphIndex: Int? = null

    private val gestureDetector = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean = false

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (isEditingMode) return false
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
                if (isEditingMode) return false
                if (e.eventTime - e.downTime >= android.view.ViewConfiguration.getLongPressTimeout()) return true

                val pos = android.graphics.PointF(
                    e.x / container.width.toFloat(),
                    e.y / container.height.toFloat(),
                )

                // Center-only mode: navigator.getAction defaults every unmatched tap to MENU, so
                // gate the toggle on the center rect ourselves (parity with the TextView viewer).
                if (preferences.navigationModeNovel.get() == ReaderPreferences.TapZones.size) {
                    if (pos.x in 0.4f..0.6f && pos.y in 0.4f..0.6f) {
                        activity.toggleMenu()
                    }
                    return true
                }

                when (navigator.getAction(pos)) {
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.MENU -> {
                        activity.toggleMenu()
                    }
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.NEXT,
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.RIGHT,
                    -> {
                        pageScrollBy(1)
                    }
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.PREV,
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.LEFT,
                    -> {
                        pageScrollBy(-1)
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
                    when (pendingRequest) {
                        TtsController.StartRequest.NORMAL -> startTts()
                        TtsController.StartRequest.VIEWPORT -> startTtsFromViewport()
                        null -> {}
                    }
                }

                override fun getCurrentPage(): ReaderPage? = currentPage

                override fun onHighlightChunk(chunkIndex: Int, chunk: String, startOffset: Int, paragraphIndex: Int) {
                    applyTtsHighlight(chunkIndex, paragraphIndex)
                    saveTtsProgressForChunk(chunkIndex)
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

        // NovelConfig swallows the initial navigationMode emit, so this
        // listener now fires only when the user actually changes the nav-mode
        // preference. Always show the preview in that case - opening the
        // reader plainly should NOT re-pop the overlay.
        config.navigationModeChangedListener = {
            activity.binding.navigationOverlay.setNavigation(config.navigator, true)
        }
        // Initial publish so overlay reflects the configured navigator from the
        // start instead of staying on whatever the previous viewer set, but
        // without the show-on-start preview.
        activity.binding.navigationOverlay.setNavigation(config.navigator, false)
        // Brand-new-user one-shot: surface the nav layout on first reader open.
        if (config.forceNavigationOverlay && !activity.tapZonesShownInSession) {
            activity.tapZonesShownInSession = true
            activity.binding.navigationOverlay.setNavigation(config.navigator, true)
        }
    }

    private fun applyTtsHighlight(chunkIndex: Int, paragraphIndex: Int) {
        if (chunkIndex < 0 || chunkIndex >= ttsController.ttsChunks.size) return

        val highlightColor = ThemeUtils.colorToHex(preferences.novelTtsHighlightColor.get())
        val highlightTextColor = ThemeUtils.colorToHex(preferences.novelTtsHighlightTextColor.get())
        val highlightStyle = quoteForJson(preferences.novelTtsHighlightStyle.get())
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

                var style = $highlightStyle;
                if (style === 'underline') {
                    target.classList.add('td-tts-highlight-underline');
                } else if (style === 'outline') {
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

    private fun loadNextChapterForTts(_anchorChapterIndex: Int = ttsController.ttsPlaybackChapterIndex) {
        logcat(LogPriority.DEBUG) {
            "TTS (WebView): Auto-loading next chapter ts=${System.currentTimeMillis()} ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex} ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}"
        }

        scope.launch {
            if (preferences.novelInfiniteScroll.get()) {
                // TTS owns the chapter transition here; suppress the visible "Loading…"
                // banner so it doesn't flash while the cache hits (or the fresh fetch
                // runs in the background). Errors still surface via showInlineError.
                // 30 s hard cap: if the fetch stalls (e.g. no-timeout HTTP client),
                // stop TTS rather than leaving isTtsAutoPlay stuck true indefinitely.
                val appended = withTimeoutOrNull(30_000L) { appendNextChapterIfAvailable(silent = true) }
                if (appended == true) {
                    // Drive the handoff directly instead of waiting on a JS callback +
                    // watchdog timer. The DOM append and the unload-and-start JS are queued
                    // on the WebView in order, and evaluateJavascript completion fires even
                    // when the activity is backgrounded (requestAnimationFrame does not), so
                    // the next chapter starts reliably during background TTS.
                    unloadReadChaptersAndStartNextTts()
                } else {
                    // Nothing appended (end of novel or fetch failure): no callback will ever
                    // come, so stop here instead of hanging with isTtsAutoPlay stuck true.
                    stopTts()
                }
            } else {
                val chapters = currentChapters ?: return@launch
                if (chapters.nextChapter == null) {
                    // End of novel: stop so the background service tears down instead of
                    // lingering with isTtsAutoPlay stuck true.
                    stopTts()
                    return@launch
                }
                // Use a viewer-owned flag so ttsController.stop() (called from setChapters)
                // cannot clear it before onPageFinished fires.
                pendingTtsAutoStartOnLoad = true
                // Must NOT use activity.loadNextChapter(): stopNovelTtsForManualNav() →
                // stopTts() clears pendingTtsAutoStartOnLoad, so playback never resumes.
                activity.loadNextChapterForTtsHandoff()
            }
        }
    }

    /**
     * Remove all chapters already read (0..ttsPlaybackChapterIndex) from the DOM and Kotlin state,
     * then start TTS fresh from the beginning of the next chapter. Used in inf-scroll mode when
     * TTS finishes the last chunk and the next chapter is already appended to the DOM - avoids the
     * unreliable scroll-based viewport handoff entirely.
     */
    private fun unloadReadChaptersAndStartNextTts() {
        val currentIdx = ttsController.ttsPlaybackChapterIndex
        val nextIdx = currentIdx + 1
        val nextChapter = loadedChapters.getOrNull(nextIdx) ?: return
        val nextChapterId = nextChapter.chapter.id ?: return

        // Collect IDs of all chapters up to the current one (using the ordered
        // list, not the id set, to guarantee declaration order).
        val idsToRemove = loadedChapters.take(nextIdx).mapNotNull { it.chapter.id }

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

        evaluateJavascriptSafe(js) {
            chapterQueue.removeFirstN(nextIdx)
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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun initWebView() {
        attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // Remove blocksDescendants from reader_activity.xml's viewer_container parent
                // so the WebView can actually receive text input focus.
                (container.parent as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            override fun onViewDetachedFromWindow(v: View) {}
        }.also(container::addOnAttachStateChangeListener)

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
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
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
                    styler.interceptFont(url)?.let { return it }
                    val fallbackChapterId =
                        currentPage?.chapter?.chapter?.id ?: currentChapters?.currChapter?.chapter?.id
                    val fallbackLoader = activity.viewModel.state.value.viewerChapters?.currChapter?.pageLoader
                    imageCache.intercept(url, fallbackChapterId, fallbackLoader)?.let { return it }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    hideLoadingIndicator()

                    // The error page is a fresh document; re-arm autoscroll before the real-chapter
                    // gate below, which the error load (docState=ERROR, not LOADING_REAL) would skip.
                    if (rearmAutoScrollOnErrorPage) {
                        rearmAutoScrollOnErrorPage = false
                        if (isAutoScrolling) startAutoScroll()
                    }

                    // Loading/error loads also fire onPageFinished(about:blank), and some WebView
                    // builds fire twice per navigation; gate the whole body so scripts/snippets
                    // inject only on the first genuine chapter load.
                    if (docState != DocState.LOADING_REAL) return
                    docState = DocState.READY

                    styler.injectScript { buildTsundokuScript() }
                    styler.injectScrollTracking()
                    // Fresh DOM lost the --tsundoku-safe-* vars and menuVisible flag; re-apply them.
                    pushReaderChrome()
                    restoreScrollPosition()
                    syncShortChapterProgressIfNeeded()
                    if (!preferences.novelInfiniteScroll.get()) {
                        styler.injectNextChapterButton(currentChapters?.nextChapter != null)
                    }
                    if (isEditingMode) {
                        toggleEditMode(true)
                    }
                    // Real content rendered (docState = READY above); TTS may now read the body.
                    dispatchLoadingChapter(false)
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
                    // A full reload replaces window, dropping the autoscroll rAF loop; re-arm it
                    // on the new document so autoscroll survives a non-inf-scroll chapter change.
                    if (isAutoScrolling) startAutoScroll()
                }
            }

            if (preferences.novelWebViewDevTools.get()) {
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        val level = consoleMessage.messageLevel()
                        val shouldToast = level == ConsoleMessage.MessageLevel.LOG ||
                            level == ConsoleMessage.MessageLevel.WARNING ||
                            level == ConsoleMessage.MessageLevel.ERROR
                        if (shouldToast && preferences.novelConsoleErrorToast.get()) {
                            activity.toast(consoleMessage.message().take(120))
                        }
                        return true
                    }

                    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                        if (activity.isFinishing || activity.isDestroyed) {
                            result.cancel()
                            return true
                        }
                        activeJsDialog = AlertDialog.Builder(activity)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                            .setOnCancelListener { result.cancel() }
                            .setOnDismissListener { activeJsDialog = null }
                            .show()
                        return true
                    }

                    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                        if (activity.isFinishing || activity.isDestroyed) {
                            result.cancel()
                            return true
                        }
                        activeJsDialog = AlertDialog.Builder(activity)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                            .setOnCancelListener { result.cancel() }
                            .setOnDismissListener { activeJsDialog = null }
                            .show()
                        return true
                    }

                    override fun onJsPrompt(
                        view: WebView?,
                        url: String?,
                        message: String?,
                        defaultValue: String?,
                        result: JsPromptResult,
                    ): Boolean {
                        if (activity.isFinishing || activity.isDestroyed) {
                            result.cancel()
                            return true
                        }
                        val input = EditText(activity).apply { setText(defaultValue.orEmpty()) }
                        activeJsDialog = AlertDialog.Builder(activity)
                            .setMessage(message)
                            .setView(input)
                            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                            .setOnCancelListener { result.cancel() }
                            .setOnDismissListener { activeJsDialog = null }
                            .show()
                        return true
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?,
                    ): Boolean {
                        if (filePathCallback == null || fileChooserParams == null) return false
                        return activity.launchWebViewFileChooser(filePathCallback, fileChooserParams)
                    }
                }
            }

            addJavascriptInterface(this@NovelWebViewViewer.WebViewInterface(), "Android")

            isLongClickable = true

            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
        }

        // Construct the styler now that `webView` has been assigned. Doing this
        // here (instead of as a `by lazy { … }` initializer that referenced
        // `webView`) avoids the "lateinit property webView has not been
        // initialized" crash that fired when the lazy initializer ran from
        // inside the WebView's `.apply { }` block during construction.
        styler = NovelWebViewStyler(
            activity = activity,
            preferences = preferences,
            webView = webView,
            container = container,
            evaluateJs = { js -> evaluateJavascriptSafe(js, null) },
        )
        styler.applyScrollbarSettings()

        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val (themeBgColor, _) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        webView.setBackgroundColor(finalBgColor)
        container.setBackgroundColor(finalBgColor)

        container.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    private fun observePreferences() {
        NovelWebViewPreferenceObserver(
            preferences = preferences,
            scope = scope,
            onStyleChanged = { styler.injectStyles() },
            onScriptChanged = { styler.injectScript { buildTsundokuScript() } },
            onChapterReloadRequested = {
                // Force a full pipeline re-run so the new prefs take effect.
                // Plain setChapters() would no-op on an already-loaded chapter.
                reloadChapter()
            },
            onBlockMediaChanged = { blockMedia ->
                webView.settings.apply {
                    blockNetworkImage = blockMedia
                    loadsImagesAutomatically = !blockMedia
                }
                webView.reload()
            },
            onTtsSettingsChanged = {
                if (ttsController.ttsInitialized) ttsController.applySettings()
            },
        ).observe()
    }

    private fun restoreScrollPosition() {
        val page = currentPage ?: run {
            isRestoringScroll = false
            return
        }
        val savedProgress = page.chapter.chapter.last_page_read
        val isRead = page.chapter.chapter.read

        val shouldRestore = if (!isRead) {
            savedProgress > 0 && savedProgress <= 100
        } else {
            libraryPreferences.novelReadProgress100.get() && savedProgress > 0 && savedProgress <= 100
        }
        if (shouldRestore) {
            val progress = savedProgress / 100f
            lastSavedProgress = progress
            activity.onNovelProgressChanged(progress)
            isRestoringScroll = true
            val token = ++scrollRestoreToken

            // Apply the saved ratio once the content has a scrollable range: immediately if laid
            // out, else a ResizeObserver waits for the body height. onScrollRestoreComplete lifts
            // the guard when done.
            val js = """
                (function() {
                    var target = $progress;
                    var token = $token;
                    function range() {
                        var docHeight = Math.max(
                            document.documentElement.scrollHeight,
                            document.body ? document.body.scrollHeight : 0
                        );
                        var viewport = window.innerHeight || document.documentElement.clientHeight;
                        return docHeight - viewport;
                    }
                    function done() {
                        if (window.Android && window.Android.onScrollRestoreComplete) {
                            window.Android.onScrollRestoreComplete(token);
                        }
                    }
                    function apply() {
                        var r = range();
                        if (r > 0) { window.scrollTo(0, r * target); return true; }
                        return false;
                    }
                    if (apply()) {
                        requestAnimationFrame(function() { apply(); done(); });
                        return;
                    }
                    // Short chapter fits the viewport: finish now, keep observing for a late reflow.
                    if (typeof ResizeObserver === 'function' && document.body) {
                        var ro = new ResizeObserver(function() {
                            if (apply()) {
                                ro.disconnect();
                                requestAnimationFrame(function() { apply(); });
                            }
                        });
                        ro.observe(document.body);
                    }
                    requestAnimationFrame(function() { done(); });
                })();
            """
            evaluateJavascriptSafe(js)
            webView.postDelayed({ liftRestoreGuard(token) }, 3000)
        } else {
            isRestoringScroll = true
            val token = ++scrollRestoreToken
            webView.scrollTo(0, 0)
            lastSavedProgress = 0f
            activity.onNovelProgressChanged(0f)
            // Hold the guard past the scrollTo(0,0) settle so it can't persist 0 over a read chapter.
            webView.postDelayed({ liftRestoreGuard(token) }, 300)
        }
    }

    private fun getThemeColors(theme: String): Pair<Int, Int> =
        ThemeUtils.getThemeColors(activity, preferences, theme)

    override fun destroy() {
        if (isDestroyed) return
        // Only persist if real progress exists. lastSavedProgress starts at 0 and stays 0
        // until onPageFinished restores or the user scrolls. Saving 0 here on an early
        // teardown (orientation lock recreates the activity before restore runs) would
        // wipe the chapter's saved progress.
        if (lastSavedProgress > 0f && !awaitingFirstScrollSample) saveProgress()

        ttsController.destroy()
        imageCache.clear()

        isDestroyed = true

        scope.cancel()

        attachListener?.let(container::removeOnAttachStateChangeListener)
        attachListener = null

        // cancel(), not dismiss(): dismiss() skips the OnCancelListener, so the pending
        // JsResult/JsPromptResult would never resolve and the WebView's JS thread stays blocked.
        try {
            activeJsDialog?.cancel()
        } catch (e: Throwable) {
            logcat(LogPriority.WARN) { "Failed to cancel active JS dialog during destroy (${e.message})" }
        }
        activeJsDialog = null

        container.removeView(webView)
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.removeJavascriptInterface("Android")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
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

    /**
     * Persist the latest live progress immediately. The JS debounce timer can be throttled while
     * the WebView is backgrounded, so the activity's onPause calls this to avoid losing the tail.
     */
    fun flushProgress() {
        if (awaitingFirstScrollSample) return
        if (lastSavedProgress > 0f && NovelProgress.progressToPercent(lastSavedProgress) != lastPersistedPercent) {
            saveProgress()
        }
    }

    private var lastPersistedPercent = -1
    private fun saveProgress() {
        currentPage?.let { page ->
            val progressValue = NovelProgress.progressToPercent(lastSavedProgress)
            lastPersistedPercent = progressValue
            activity.saveNovelProgress(page, progressValue)
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Saving progress $progressValue%" }
        }
    }

    /**
     * Persists progress for the chapter currently being spoken by TTS based on
     * chunk index. The scroll-based save path does not fire when the activity
     * is in the background (no JS scroll events make it back through the
     * bridge while paused), so TTS sessions running under the foreground
     * service would lose progress until the user returns.
     */
    private var lastSavedTtsChunkIndex: Int = -1
    private fun saveTtsProgressForChunk(chunkIndex: Int) {
        // Foreground: the per-chapter scroll bridge (onScrollProgress) owns progress and the slider.
        // WebView TTS extracts the whole loaded (multi-chapter) body, so its chunk% is document-wide,
        // not per-chapter, it would misattribute the visible chapter's position. Persist from TTS
        // only when backgrounded, where the JS scroll bridge is paused and this is the sole source.
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (chunkIndex == lastSavedTtsChunkIndex) return
        lastSavedTtsChunkIndex = chunkIndex
        val total = ttsController.ttsChunks.size
        if (total <= 0) return
        val chapterIdx = ttsController.ttsPlaybackChapterIndex
        val chapter = loadedChapters.getOrNull(chapterIdx) ?: return
        val page = chapter.pages?.firstOrNull() ?: return
        val percent = (((chunkIndex + 1) * 100f) / total).toInt().coerceIn(0, 100)
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

        evaluateJavascriptSafe(
            """
            (function() {
                function checkIfShortChapter() {
                    var docHeight = Math.max(
                        document.documentElement.scrollHeight,
                        document.body ? document.body.scrollHeight : 0
                    );
                    var viewport = window.innerHeight || document.documentElement.clientHeight;
                    return docHeight - viewport <= 0;
                }
                var called = false;
                function tryMarkShort() {
                    if (!called && checkIfShortChapter()) {
                        called = true;
                        Android.markChapterAsShort();
                    }
                }
                var resizeObserver = new ResizeObserver(function() {
                    tryMarkShort();
                    if (called) resizeObserver.disconnect();
                });
                resizeObserver.observe(document.body);
                setTimeout(function() {
                    tryMarkShort();
                    resizeObserver.disconnect();
                }, 500);
            })();
            """.trimIndent(),
            null,
        )
    }

    override fun getView(): View = container

    fun reloadWithTranslation() {
        val page = currentPage ?: return
        val chapter = currentChapters?.currChapter ?: return
        val content = page.text ?: return
        val cfg = ContentConfig.from(
            preferences,
            RenderTarget.WEB_VIEW,
            chapter.chapter.url,
            chapter.chapter.name,
        )

        contentJob?.cancel()
        contentJob = if (activity.isTranslationEnabled()) {
            loadingIndicator?.show()
            scope.launch {
                val processed = withContext(Dispatchers.Default) {
                    contentPipeline.process(content, cfg) { activity.translateContentIfEnabled(it) }
                }
                loadingIndicator?.hide()
                loadHtmlContent(processed, chapter)
            }
        } else {
            scope.launch {
                val processed = withContext(Dispatchers.Default) { contentPipeline.process(content, cfg) }
                loadHtmlContent(processed, chapter)
            }
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        val page = chapters.currChapter.pages?.firstOrNull() ?: return
        val chapterId = chapters.currChapter.chapter.id ?: return

        loadJob?.cancel()

        currentPage = page
        currentChapters = chapters

        val isPrepend = isInfiniteScrollPrepend
        isInfiniteScrollPrepend = false
        isInfiniteScrollNavigation = false

        if (loadedChapterIds.contains(chapterId)) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Chapter $chapterId already loaded, skipping" }
            val index = chapterQueue.indexOf(chapterId)
            if (index >= 0) {
                currentChapterIndex = index
            }
            return
        }

        ttsController.stop()

        if (!preferences.novelInfiniteScroll.get() || loadedChapterIds.isEmpty()) {
            chapterQueue.clear()
            currentChapterIndex = 0
        }

        if (page.status == Page.State.Ready && !page.text.isNullOrEmpty()) {
            if (!isPrepend) hideLoadingIndicator()
            displayContent(chapters.currChapter, page, isPrepend, isPrepend)
            if (!isPrepend) activity.viewModel.setNovelVisibleChapter(page.chapter.chapter)
            return
        }

        if (!isPrepend) showLoadingIndicator()

        loadJob = scope.launch {
            val loader = page.chapter.pageLoader
            if (loader == null) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page loader available" }
                if (!isPrepend) hideLoadingIndicator()
                return@launch
            }

            launch(Dispatchers.IO) {
                loader.loadPage(page)
            }

            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue, Page.State.LoadPage -> {
                        if (!isPrepend) showLoadingIndicator()
                    }
                    Page.State.Ready -> {
                        if (!isPrepend) hideLoadingIndicator()
                        displayContent(chapters.currChapter, page, isPrepend, isPrepend)
                        if (!isPrepend) activity.viewModel.setNovelVisibleChapter(page.chapter.chapter)
                    }
                    is Page.State.Error -> {
                        if (isPrepend) {
                            // A prepend fetch failing must not replace the whole multi-chapter DOM
                            // (and scroll position) with a full-page error; surface it inline and
                            // leave the document (and docState) intact.
                            inlineFeedback.hideInlineLoading(isPrepend = true)
                            inlineFeedback.showInlineError(
                                ErrorFormatter.format(state.error).summary,
                                isPrepend = true,
                            )
                        } else {
                            hideLoadingIndicator()
                            displayError(state.error)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun displayContent(
        chapter: ReaderChapter,
        page: ReaderPage,
        isAppendOrPrepend: Boolean = false,
        isPrepend: Boolean = false,
    ) {
        val rawContent = page.text
        if (rawContent.isNullOrBlank()) {
            displayError(Exception(activity.stringResource(TDMR.strings.novel_error_empty_chapter)))
            return
        }

        val chapterId = chapter.chapter.id ?: return

        val translator: (suspend (String) -> String)? =
            if (activity.isTranslationEnabled()) { c -> activity.translateContentIfEnabled(c, chapterId) } else null
        val cfg = ContentConfig.from(
            preferences,
            RenderTarget.WEB_VIEW,
            chapter.chapter.url,
            chapter.chapter.name,
        )

        if (!isAppendOrPrepend) {
            contentJob?.cancel()
            // An in-flight append targets the DOM this base load is about to replace; cancelling it
            // avoids splicing a stale chapter's content onto the newly loaded one when it resumes.
            appendJob?.cancel()
            // Gate infinite-scroll appends until this base chapter's DOM is committed (onPageFinished
            // sets READY). Otherwise an early append (JS scroll threshold) is wiped by the
            // clear()+loadHtmlContent this job runs, which re-appends and duplicates the chapter.
            docState = DocState.LOADING
        }
        val job = scope.launch {
            if (!isAppendOrPrepend && translator != null) {
                val labelRes = if (activity.hasCachedTranslation(chapterId)) {
                    TDMR.strings.novel_chapter_translating_from_cache
                } else {
                    TDMR.strings.novel_chapter_translating_from_api
                }
                showLoadingIndicator(activity.stringResource(labelRes))
            }

            val processed = withContext(Dispatchers.Default) {
                contentPipeline.process(rawContent, cfg, translator)
            }

            // For infinite-scroll appends/prepends, prefix every tsundoku-novel-image://
            // URL with the chapter ID so that shouldInterceptRequest can resolve the
            // correct loader even when multiple chapters share identical image filenames
            // (e.g. image_0.jpg in both chapter 3 and chapter 4).
            val finalProcessed = if (isAppendOrPrepend &&
                processed.text.contains(NovelWebViewImageCache.URL_SCHEME_NOVEL_IMAGE)
            ) {
                processed.copy(
                    text = processed.text.replace(
                        NovelWebViewImageCache.URL_SCHEME_NOVEL_IMAGE,
                        "${NovelWebViewImageCache.URL_SCHEME_NOVEL_IMAGE}$chapterId/",
                    ),
                )
            } else {
                processed
            }
            imageCache.schedulePrefetch(finalProcessed.text, chapter.chapter.id, page.chapter.pageLoader)

            withContext(Dispatchers.Main) {
                if (isAppendOrPrepend && preferences.novelInfiniteScroll.get()) {
                    // Queue add and DOM insert share one guard: a redundant displayContent() for the
                    // same chapter would otherwise skip the queue add but still re-insert the DOM copy,
                    // corrupting chapterBoundaries.
                    if (!loadedChapterIds.contains(chapterId)) {
                        if (isPrepend) {
                            chapterQueue.prepend(chapter)
                            prependHtmlContent(
                                finalProcessed,
                                chapterId,
                                chapter.chapter.name,
                                chapter.chapter.chapter_number,
                                chapter.chapter.url,
                            )
                        } else {
                            chapterQueue.append(chapter)
                            appendHtmlContent(
                                finalProcessed,
                                chapterId,
                                chapter.chapter.name,
                                chapter.chapter.chapter_number,
                                chapter.chapter.url,
                            )
                        }
                    }
                } else {
                    loadHtmlContent(finalProcessed, chapter)

                    chapterQueue.clear()
                    chapterQueue.append(chapter)
                    currentChapterIndex = 0
                }
            }
        }
        if (!isAppendOrPrepend) contentJob = job
    }

    /**
     * Prepend [processed] (already through [ContentPipeline]) to the WebView DOM.
     * No preprocessing is performed here; the content is injected as-is.
     */
    private fun prependHtmlContent(
        processed: ProcessedContent,
        chapterId: Long,
        chapterName: String,
        chapterNumber: Float,
        chapterUrl: String?,
    ) {
        val plainTextMode = processed.isPlainText
        val escapedContent = quoteForJson(processed.text)
        val token = ++scrollRestoreToken

        val js = """
            (function() {
                var oldHeight = document.body.scrollHeight;
                var oldScrollY = window.scrollY || window.pageYOffset;

                var chapterElement = document.createElement('${CHAPTER_TAG_NAME}');
                chapterElement.setAttribute('${CHAPTER_ID_ATTR}', '$chapterId');
                chapterElement.setAttribute('${TSUNDOKU_CHAPTER_ATTR}', '1');
                chapterElement.setAttribute('$CHAPTER_TITLE_ATTR', ${quoteForJson(chapterName)});
                chapterElement.setAttribute('$CHAPTER_NUMBER_ATTR', '$chapterNumber');
                chapterElement.setAttribute('$CHAPTER_PATH_ATTR', ${quoteForJson(chapterUrl.orEmpty())});
                chapterElement.setAttribute('$CHAPTER_URL_ATTR', ${quoteForJson(toAbsoluteChapterUrl(chapterUrl))});
                ${if (plainTextMode) "chapterElement.textContent = $escapedContent;" else "chapterElement.innerHTML = $escapedContent;"}

                var divider = document.createElement('div');
                divider.className = '$CHAPTER_DIVIDER_CLASS';
                divider.setAttribute('${CHAPTER_ID_ATTR}', '$chapterId');
                divider.setAttribute('${TSUNDOKU_CHAPTER_ATTR}', '1');
                divider.setAttribute('$CHAPTER_TITLE_ATTR', ${quoteForJson(chapterName)});
                divider.setAttribute('$CHAPTER_NUMBER_ATTR', '$chapterNumber');
                divider.setAttribute('$CHAPTER_PATH_ATTR', ${quoteForJson(chapterUrl.orEmpty())});
                divider.setAttribute('$CHAPTER_URL_ATTR', ${quoteForJson(toAbsoluteChapterUrl(chapterUrl))});

                var firstChild = document.body.firstChild;
                document.body.insertBefore(chapterElement, firstChild);
                document.body.insertBefore(divider, chapterElement);

                // Reading scrollHeight inside rAF forces the pending layout so the delta is exact.
                // Pin the reading position, rebuild boundaries, then lift the guard.
                requestAnimationFrame(function() {
                    var newHeight = document.body.scrollHeight;
                    var diff = newHeight - oldHeight;
                    if (diff > 0) {
                        window.scrollTo(0, oldScrollY + diff);
                    }
                    if (typeof window.updateChapterBoundaries === 'function') {
                        window.updateChapterBoundaries();
                    }
                    if (window.Android && window.Android.onScrollRestoreComplete) {
                        window.Android.onScrollRestoreComplete($token);
                    }
                });
            })();
        """.trimIndent()

        // Guard scroll callbacks while the prepend shifts every startOffset, JS lifts it when done.
        isRestoringScroll = true
        evaluateJavascriptSafe(js) {
            styler.injectScript(isAppend = true) { buildTsundokuScript() }
        }
        // JS lift runs in rAF (paused while backgrounded); fallback so the guard can't stick forever.
        webView.postDelayed({ liftRestoreGuard(token) }, 3000)

        logcat(LogPriority.DEBUG) {
            "NovelWebViewViewer: Prepended chapter $chapterId (${loadedChapterIds.size} total)"
        }
    }

    private fun appendHtmlContent(processed: ProcessedContent, chapterId: Long, chapterName: String, chapterNumber: Float, chapterUrl: String?) {
        val plainTextMode = processed.isPlainText
        val escapedContent = quoteForJson(processed.text)

        val js = """
            (function() {
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
                chapterElement.setAttribute('${CHAPTER_ID_ATTR}', '$chapterId');
                chapterElement.setAttribute('$CHAPTER_TITLE_ATTR', ${quoteForJson(chapterName)});
                chapterElement.setAttribute('$CHAPTER_NUMBER_ATTR', '$chapterNumber');
                chapterElement.setAttribute('$CHAPTER_PATH_ATTR', ${quoteForJson(chapterUrl.orEmpty())});
                chapterElement.setAttribute('$CHAPTER_URL_ATTR', ${quoteForJson(toAbsoluteChapterUrl(chapterUrl))});
                chapterElement.setAttribute('${TSUNDOKU_CHAPTER_ATTR}', '1');
                ${if (plainTextMode) "chapterElement.textContent = $escapedContent;" else "chapterElement.innerHTML = $escapedContent;"}
                chaptersContainer.appendChild(chapterElement);

                // Rebuild boundaries synchronously (getBoundingClientRect forces layout) BEFORE the
                // browser can fire a scroll frame. Otherwise the DOM has the new chapter (doubled
                // scrollHeight) while chapterBoundaries still has one entry, so computeState falls into
                // the whole-document branch and reports the current chapter's position as a fraction of
                // both chapters (e.g. 70% -> 50%) until the rAF rebuild catches up. The rAF rebuild
                // below still runs to pick up late reflow (image/font load).
                if (typeof window.updateChapterBoundaries === 'function') {
                    window.updateChapterBoundaries();
                }

                requestAnimationFrame(function() {
                    if (typeof window.updateChapterBoundaries === 'function') {
                        window.updateChapterBoundaries();
                    }
                    if (window.Android && window.Android.onInfiniteScrollAppendComplete) {
                        window.Android.onInfiniteScrollAppendComplete($chapterId);
                    }
                });
            })();
        """.trimIndent()

        dispatchLoadingChapter(true)
        evaluateJavascriptSafe(js) {
            styler.injectScript(isAppend = true) { buildTsundokuScript() }
            dispatchLoadingChapter(false)
        }

        // A chapter was appended, so the end-of-novel verdict is stale.
        reachedNovelEnd = false
        setJsNoMoreChapters(false)

        logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Appended chapter $chapterId (${loadedChapterIds.size} total)" }
    }

    private suspend fun loadHtmlContent(
        processed: ProcessedContent,
        chapter: ReaderChapter? = null,
    ) {
        val chapterModel = chapter?.chapter
        val chapterId = chapterModel?.id ?: -1L
        val chapterPath = chapterModel?.url.orEmpty()

        val stylePayload = styler.buildPayload()
        webView.setBackgroundColor(stylePayload.backgroundColor)
        container.setBackgroundColor(stylePayload.backgroundColor)

        chapterQueue.clear()
        currentChapterIndex = 0

        // Inputs are gathered on Main (touch viewer state), but the heavy work - the image-URL
        // regex scan and the Jsoup parse + full-document string build - runs off the main thread.
        // For large chapters this was a multi-MB alloc + DOM parse on the UI thread (frame skips).
        val input = NovelWebViewDocumentBuilder.DocumentInput(
            processed = processed,
            chapter = chapter,
            style = stylePayload,
            themeTokens = ThemeUtils.getThemeTokens(activity, preferences, preferences.novelTheme.get()),
            tsundokuScript = buildTsundokuScript(),
            infiniteScrollEnabled = preferences.novelInfiniteScroll.get(),
            blockMedia = preferences.novelBlockMedia.get(),
        )
        val pageLoader = currentPage?.chapter?.pageLoader
        val html = withContext(Dispatchers.Default) {
            imageCache.schedulePrefetch(processed.text, chapterId.takeIf { it != -1L }, pageLoader)
            NovelWebViewDocumentBuilder.assemble(input)
        }

        // Signal to onPageFinished that the next callback is for real chapter content, not
        // the loading-indicator page (which also fires onPageFinished with url="about:blank").
        docState = DocState.LOADING_REAL
        dispatchLoadingChapter(true)
        // New document: hold scroll callbacks and clear the baseline so a stale flush can't write
        // the previous chapter's percent here, restoreScrollPosition seeds the real value.
        isRestoringScroll = true
        lastSavedProgress = 0f
        lastPersistedPercent = -1
        reachedNovelEnd = false
        webView.loadDataWithBaseURL(resolveWebViewBaseUrl(chapterPath), html, "text/html", "UTF-8", null)
    }

    // Memoized: the manga URL is fixed for the viewer's lifetime, and getMangaUrl() does a source
    // lookup + toSManga() + getMangaUrlOrNull() that ran twice per chapter load/append otherwise.
    private var cachedMangaUrl: String? = null

    private fun resolvedMangaUrl(): String? {
        cachedMangaUrl?.let { return it }
        return (activity.viewModel.getMangaUrl() ?: activity.viewModel.manga?.url)
            ?.also { cachedMangaUrl = it }
    }

    private fun resolveWebViewBaseUrl(chapterUrl: String?): String? =
        NovelWebViewChapterMeta.resolveWebViewBaseUrl(chapterUrl, resolvedMangaUrl(), sourceBaseUrl())

    // Site url of the current source, so relative asset paths in chapter HTML resolve like a browser.
    private fun sourceBaseUrl(): String? = when (val source = activity.viewModel.getSource()) {
        is eu.kanade.tachiyomi.jsplugin.source.JsSource -> source.baseUrl.takeIf { it.isNotBlank() }
        is eu.kanade.tachiyomi.source.online.HttpSource -> source.baseUrl.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun toAbsoluteChapterUrl(chapterPath: String?): String =
        NovelWebViewChapterMeta.toAbsoluteChapterUrl(chapterPath, activity.viewModel.manga?.url)

    private fun buildTsundokuScript(): String {
        val context = NovelWebViewChapterMeta.TsundokuScriptContext(
            novelUrl = resolvedMangaUrl(),
            currentChapter = getCurrentTsundokuChapter(),
            chaptersInOrder = if (loadedChapters.isNotEmpty()) {
                loadedChapters
            } else {
                currentChapters?.currChapter?.let { listOf(it) }.orEmpty()
            },
            isEditingMode = isEditingMode,
            isInfiniteScroll = preferences.novelInfiniteScroll.get(),
            textSelectionBlocked = !preferences.novelTextSelectable.get(),
            forcedLowercase = preferences.novelForceTextLowercase.get(),
            menuVisible = activity.viewModel.state.value.menuVisible,
            immersive = !activity.viewModel.state.value.menuVisible,
            ttsState = currentTtsState(),
            loadingChapter = !webChapterContentReady,
        )
        return NovelWebViewChapterMeta.buildTsundokuScript(context)
    }

    private fun getCurrentTsundokuChapter(): ReaderChapter? =
        loadedChapters.getOrNull(currentChapterIndex) ?: currentChapters?.currChapter

    private fun updateChapterMetaJs() {
        val js = buildTsundokuScript()
        evaluateJavascriptSafe("(function(){$js})();", null)
    }

    private fun currentTtsState(): String = when {
        ttsController.isPaused() -> "paused"
        ttsController.isTtsAutoPlay || ttsController.isSpeaking() || ttsController.isStarting() -> "playing"
        else -> "stopped"
    }

    // Updates the runtime state via [assignments] (JS statements against `t.runtime`) and fires a
    // CustomEvent on `window` so novel-source plugins and user snippets can react. See the EVENT_*
    // and *_KEY constants in NovelWebViewChapterMeta. No-ops safely when the WebView isn't ready.
    private fun dispatchTsundokuEvent(eventName: String, assignments: String, detailJson: String) {
        val obj = NovelWebViewChapterMeta.TSUNDOKU_OBJECT_NAME
        evaluateJavascriptSafe(
            """
            (function(){
              var t = window.$obj = window.$obj || {};
              t.runtime = t.runtime || {};
              $assignments
              try { window.dispatchEvent(new CustomEvent('$eventName', { detail: $detailJson })); } catch (e) {}
            })();
            """.trimIndent(),
            null,
        )
    }

    fun onMenuVisibilityChanged(visible: Boolean) {
        dispatchTsundokuEvent(
            NovelWebViewChapterMeta.EVENT_MENU_VISIBILITY,
            "t.runtime.${NovelWebViewChapterMeta.TSUNDOKU_MENU_VISIBLE_KEY} = $visible; " +
                "t.runtime.${NovelWebViewChapterMeta.TSUNDOKU_IMMERSIVE_KEY} = ${!visible};",
            "{ menuVisible: $visible, immersive: ${!visible} }",
        )
    }

    fun onChapterNavigate(direction: String) {
        dispatchTsundokuEvent(
            NovelWebViewChapterMeta.EVENT_CHAPTER_NAVIGATE,
            "",
            "{ direction: '$direction' }",
        )
    }

    private fun dispatchLoadingChapter(loading: Boolean) {
        dispatchTsundokuEvent(
            NovelWebViewChapterMeta.EVENT_CHAPTER_LOADING,
            "t.runtime.${NovelWebViewChapterMeta.TSUNDOKU_LOADING_CHAPTER_KEY} = $loading;",
            "{ loading: $loading }",
        )
    }

    private fun dispatchTtsState() {
        val state = currentTtsState()
        dispatchTsundokuEvent(
            NovelWebViewChapterMeta.EVENT_TTS_STATE,
            "t.runtime.${NovelWebViewChapterMeta.TSUNDOKU_TTS_STATE_KEY} = '$state';",
            "{ state: '$state' }",
        )
    }

    /** Called from ReaderActivity when the menu or reader-chrome insets change. */
    fun onReaderChromeChanged(menuVisible: Boolean, safeTopDp: Float, safeBottomDp: Float) {
        chromeMenuVisible = menuVisible
        chromeSafeTopDp = safeTopDp
        chromeSafeBottomDp = safeBottomDp
        pushReaderChrome()
    }

    // Sets the safe-area CSS vars and Tsundoku.runtime.menuVisible via the reader-chrome.js asset
    // (same token-substitution path as the other injected scripts), firing the menu-visibility event
    // only when the flag actually flips so a load/inset re-apply is silent.
    private fun pushReaderChrome() {
        val js = NovelWebViewJsAssets.loadWith(
            activity,
            "reader-chrome.js",
            mapOf(
                "SAFE_TOP_VAR" to NovelWebViewChapterMeta.CSS_VAR_SAFE_TOP,
                "SAFE_BOTTOM_VAR" to NovelWebViewChapterMeta.CSS_VAR_SAFE_BOTTOM,
                "SAFE_TOP" to chromeSafeTopDp.toString(),
                "SAFE_BOTTOM" to chromeSafeBottomDp.toString(),
                "OBJECT" to NovelWebViewChapterMeta.TSUNDOKU_OBJECT_NAME,
                "MENU_KEY" to NovelWebViewChapterMeta.TSUNDOKU_MENU_VISIBLE_KEY,
                "MENU_VISIBLE" to chromeMenuVisible.toString(),
                "EVENT" to NovelWebViewChapterMeta.EVENT_MENU_VISIBILITY,
            ),
        )
        evaluateJavascriptSafe(js, null)
    }

    private fun showLoadingIndicator(message: String = "Loading...") {
        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val fontColor = preferences.novelFontColor.get()
        val (themeBgColor, themeTextColor) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        val finalTextColor = if (fontColor != 0) fontColor else themeTextColor

        val bgColorHex = ThemeUtils.colorToHex(finalBgColor)
        val textColorHex = ThemeUtils.colorToHex(finalTextColor)

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
                <div>${message.replace("<", "&lt;").replace(">", "&gt;")}</div>
            </body>
            </html>
        """.trimIndent()

        docState = DocState.LOADING
        webView.loadDataWithBaseURL(null, loadingHtml, "text/html", "UTF-8", null)
    }

    private fun hideLoadingIndicator() {
    }

    private fun displayError(error: Throwable) {
        val fmt = ErrorFormatter.format(error)
        logcat(LogPriority.ERROR) { "NovelWebViewViewer: Chapter load failed\n${fmt.stackTrace}" }

        // No real chapter DOM is coming for this load, so onPageFinished won't reach the READY
        // transition. ERROR keeps webChapterContentReady true (so a failed load can't block
        // infinite-scroll appends forever) while marking the body un-appendable.
        docState = DocState.ERROR

        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val fontColor = preferences.novelFontColor.get()
        val (themeBgColor, themeTextColor) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        val finalTextColor = if (fontColor != 0) fontColor else themeTextColor
        val bgColorHex = ThemeUtils.colorToHex(finalBgColor)
        val textColorHex = ThemeUtils.colorToHex(finalTextColor)

        val escapedCategory = HtmlUtils.escapeHtml(fmt.category.localized(activity))
        val escapedSummary = HtmlUtils.escapeHtml(fmt.summary)
        val escapedTrace = HtmlUtils.escapeHtml(fmt.stackTrace)
        // Base64-encode the trace so it can be safely passed to the Android JS bridge
        // without worrying about special characters breaking the JS string literal.
        val base64Trace = android.util.Base64.encodeToString(
            fmt.stackTrace.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )

        val errorHtml = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { margin: 0; padding: 24px 16px; background: $bgColorHex; color: $textColorHex; font-family: sans-serif; }
              .err { max-width: 600px; margin: 0 auto; text-align: center; padding-top: 10vh; }
              .category { color: #ff5555; font-size: 18px; font-weight: bold; margin-bottom: 12px; }
              .summary { color: #888; font-size: 14px; margin-bottom: 24px; word-break: break-word; }
              .copy-btn { background: transparent; color: $textColorHex; border: 1px solid #555; border-radius: 8px; padding: 10px 20px; font-size: 14px; cursor: pointer; margin-bottom: 20px; }
              details { text-align: left; margin-top: 4px; }
              summary { cursor: pointer; color: #777; font-size: 13px; padding: 8px 0; user-select: none; }
              pre { background: rgba(0,0,0,0.25); color: #bbb; padding: 12px; border-radius: 6px; font-size: 11px; white-space: pre-wrap; word-break: break-all; max-height: 280px; overflow-y: auto; margin: 0; }
            </style>
            </head>
            <body>
            <div class="err">
              <div class="category">$escapedCategory</div>
              <div class="summary">$escapedSummary</div>
              <button class="copy-btn" onclick="copyErr()">Copy error details</button>
              <details>
                <summary>Technical details</summary>
                <pre>$escapedTrace</pre>
              </details>
            </div>
            <script>
            function copyErr() {
              if (window.Android && window.Android.copyToClipboard) {
                window.Android.copyToClipboard('$base64Trace');
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        rearmAutoScrollOnErrorPage = isAutoScrolling
        webView.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
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
                if (preferences.novelVolumeKeysScroll.get() && !activity.viewModel.state.value.menuVisible) {
                    if (!isUp) pageScrollBy(1, 0.3)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (preferences.novelVolumeKeysScroll.get() && !activity.viewModel.state.value.menuVisible) {
                    if (!isUp) pageScrollBy(-1, 0.3)
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
            chapterQueue.clear()
            activity.viewModel.reloadChapter(fromSource = false)
            return
        }

        this.isEditingMode = isEditing
        styler.injectScript { buildTsundokuScript() }
        updateChapterMetaJs()

        if (isEditing) {
            // Focusing a contenteditable for the keyboard scrolls Chromium to the top; snapshot the
            // ratio, restore it after focus settles, and gate onScrollProgress meanwhile.
            val restoreRatio = lastSavedProgress
            isRestoringScroll = true
            val token = ++scrollRestoreToken
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
            webView.postDelayed({
                evaluateJavascriptSafe(
                    """
                    (function() {
                        var target = $restoreRatio;
                        var docHeight = Math.max(
                            document.documentElement.scrollHeight,
                            document.body ? document.body.scrollHeight : 0
                        );
                        var viewport = window.innerHeight || document.documentElement.clientHeight;
                        var range = docHeight - viewport;
                        if (range > 0) window.scrollTo(0, range * target);
                    })();
                    """.trimIndent(),
                )
                isRestoringScroll = false
            }, 220)
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
                        if (!window.$TSUNDOKU_OBJECT_NAME.runtime.editInputBound) {
                            window.$TSUNDOKU_OBJECT_NAME.runtime.editInputBound = true;
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
                                var chapterId = nodes[i].getAttribute('${CHAPTER_ID_ATTR}');
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

    @Keep
    @Suppress("unused")
    inner class WebViewInterface {
        @JavascriptInterface
        fun onContentEdited() {
            activity.runOnUiThread {
                activity.viewModel.setHasUnsavedChanges(true)
            }
        }

        @JavascriptInterface
        fun onSaveEditedContent(json: String) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: onSaveEditedContent(length=${json.length})" }
            activity.runOnUiThread {
                activity.viewModel.saveEditedChapterContent(json)
            }
        }

        @JavascriptInterface
        fun onScrollProgress(progress: Float) {
            activity.runOnUiThread {
                if (isRestoringScroll) return@runOnUiThread
                awaitingFirstScrollSample = false
                lastSavedProgress = progress
                if (NovelProgress.progressToPercent(progress) == lastPersistedPercent) return@runOnUiThread
                saveProgress()
            }
        }

        @JavascriptInterface
        fun onScrollUpdate(progress: Float) {
            activity.runOnUiThread {
                if (isRestoringScroll) return@runOnUiThread
                // Don't let a delayed echo from a slider seek overwrite the finger's live position,
                // nor let it clobber lastSavedProgress with a stale in-flight value.
                if (System.currentTimeMillis() - lastUserSeekAt < SEEK_ECHO_SUPPRESS_MS) return@runOnUiThread
                awaitingFirstScrollSample = false
                lastSavedProgress = progress
                activity.onNovelProgressChanged(progress)
            }
        }

        @JavascriptInterface
        fun onChapterScrollUpdate(chapterId: String, @Suppress("UNUSED_PARAMETER") progress: Float) {
            activity.runOnUiThread {
                if (isRestoringScroll) return@runOnUiThread
                // Fired edge-triggered by the JS (only on a real change). Resolve by stable chapter
                // id, not index: DOM boundaries and chapterQueue briefly disagree after a prepend.
                val id = chapterId.toLongOrNull() ?: return@runOnUiThread
                val chapterIndex = loadedChapters.indexOfFirst { it.chapter.id == id }
                if (chapterIndex != currentChapterIndex && chapterIndex >= 0) {
                    // Skip if the target has no page yet: advancing the index without currentPage
                    // would persist the new chapter's progress against the old page.
                    val newPage = loadedChapters.getOrNull(chapterIndex)?.pages?.firstOrNull() ?: return@runOnUiThread
                    val oldIndex = currentChapterIndex
                    currentChapterIndex = chapterIndex

                    // Moving forward marks the outgoing chapter (and any skipped) read, per-chapter
                    // progress never snaps to 100 in the DOM so onScrollProgress can't.
                    NovelProgress.forwardChaptersToMarkRead(oldIndex, chapterIndex, loadedChapters.size)
                        .forEach { idx ->
                            loadedChapters.getOrNull(idx)?.pages?.firstOrNull()?.let { page ->
                                activity.saveNovelProgress(page, 100)
                                logcat(LogPriority.DEBUG) {
                                    "NovelWebViewViewer: Marking chapter index $idx as 100% (moved forward)"
                                }
                            }
                        }

                    activity.viewModel.setNovelVisibleChapter(loadedChapters.getOrNull(chapterIndex)?.chapter)

                    currentPage = newPage
                    activity.onPageSelected(newPage)

                    // Directional baseline so a flush before the next scroll event isn't wrong.
                    // The next onScrollUpdate replaces it with the real position.
                    lastSavedProgress = if (chapterIndex > oldIndex) 0f else 1f
                    lastPersistedPercent = -1
                    awaitingFirstScrollSample = true

                    updateChapterMetaJs()
                }
            }
        }

        @JavascriptInterface
        fun onScrollRestoreComplete(token: Int) {
            // Only the latest restore may lift the guard; ignore stale completions.
            activity.runOnUiThread { liftRestoreGuard(token) }
        }

        @JavascriptInterface
        fun onInfiniteScrollAppendComplete(@Suppress("UNUSED_PARAMETER") chapterId: Long) {
            // No-op: the TTS handoff is now driven directly from loadNextChapterForTts after the
            // append completes, instead of waiting on this requestAnimationFrame-fired callback
            // (which is paused while the activity is backgrounded, stalling background TTS).
        }

        @JavascriptInterface
        fun loadNextChapter() {
            activity.runOnUiThread {
                logcat(LogPriority.DEBUG) {
                    "NovelWebViewViewer: loadNextChapter triggered, infiniteScroll=${preferences.novelInfiniteScroll.get()}, isLoadingNext=$isLoadingNext, loadedCount=${loadedChapterIds.size}"
                }
                if (preferences.novelInfiniteScroll.get() && !webChapterContentReady) {
                    // Base chapter DOM not committed yet. Appending now races the base load's
                    // loadHtmlContent (replaces the body) and its chapterQueue.clear(), which would
                    // wipe the just-appended chapter and cause a duplicate re-append. Release the JS
                    // latch so the page retries once the base chapter has finished rendering.
                    setJsLoadingNext()
                    return@runOnUiThread
                }
                if (preferences.novelInfiniteScroll.get() && webChapterIsError) {
                    // Current DOM is an error placeholder, not a real chapter; appending the next
                    // chapter onto it would stack content below the error and race a reload. Release
                    // the latch and wait for a successful reload to clear webChapterIsError.
                    setJsLoadingNext()
                    return@runOnUiThread
                }
                if (!preferences.novelInfiniteScroll.get()) {
                    activity.loadNextChapter()
                } else if (reachedNovelEnd) {
                    setJsNoMoreChapters(true)
                } else if (ttsController.isTtsAutoPlay) {
                    // Don't append while TTS is active, pre-fetch so the next chapter is ready when
                    // TTS calls appendNextChapterIfAvailable.
                    if (handoffState.isIdle) {
                        scope.launch { preFetchNextChapterForTts() }
                    }
                } else if (System.currentTimeMillis() - lastNextLoadFailedAt <
                    NovelProgress.NEXT_LOAD_RETRY_COOLDOWN_MS
                ) {
                    // Keep the JS load latch held (JS set it before this call) so it stops re-firing
                    // loadNextChapter every scroll frame during the cooldown; schedule a single
                    // release for when the cooldown expires so it can't become permanent.
                    if (!cooldownReleaseScheduled) {
                        cooldownReleaseScheduled = true
                        val remaining = NovelProgress.NEXT_LOAD_RETRY_COOLDOWN_MS -
                            (System.currentTimeMillis() - lastNextLoadFailedAt)
                        webView.postDelayed({
                            cooldownReleaseScheduled = false
                            setJsLoadingNext()
                        }, remaining.coerceAtLeast(0))
                    }
                    logcat(LogPriority.DEBUG) { "NovelWebViewViewer: loadNextChapter ignored, in failure cooldown" }
                } else if (!isLoadingNext) {
                    isLoadingNext = true
                    appendJob = scope.launch {
                        try {
                            val ok = appendNextChapterIfAvailable()
                            lastNextLoadFailedAt = if (ok) 0L else System.currentTimeMillis()
                        } finally {
                            isLoadingNext = false
                            setJsLoadingNext()
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
                lastSavedProgress = 1f
                saveProgress()
                activity.onNovelProgressChanged(1f)
                logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Chapter marked as short (fits in viewport)" }

                // Chapter fits in viewport → no scroll events fire → threshold never reached.
                // Trigger infinite scroll append manually.
                if (preferences.novelInfiniteScroll.get() && !isLoadingNext && !ttsController.isTtsAutoPlay &&
                    !webChapterIsError
                ) {
                    isLoadingNext = true
                    appendJob = scope.launch {
                        try {
                            appendNextChapterIfAvailable()
                        } finally {
                            isLoadingNext = false
                            setJsLoadingNext()
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun copyToClipboard(base64Text: String) {
            activity.runOnUiThread {
                val text = try {
                    android.util.Base64.decode(base64Text, android.util.Base64.DEFAULT)
                        .toString(Charsets.UTF_8)
                } catch (_: Exception) {
                    base64Text
                }
                val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("error", text))
                activity.toast(activity.stringResource(TDMR.strings.novel_error_copied))
            }
        }
    }

    private fun setJsLoadingNext() {
        evaluateJavascriptSafe(
            "(function(){ if (window.$TSUNDOKU_OBJECT_NAME && window.$TSUNDOKU_OBJECT_NAME.runtime && window.$TSUNDOKU_OBJECT_NAME.runtime.setLoadingNext) window.$TSUNDOKU_OBJECT_NAME.runtime.setLoadingNext(false); })();",
            null,
        )
    }

    private fun setJsNoMoreChapters(value: Boolean) {
        evaluateJavascriptSafe(
            "(function(){ if (window.$TSUNDOKU_OBJECT_NAME && window.$TSUNDOKU_OBJECT_NAME.runtime && window.$TSUNDOKU_OBJECT_NAME.runtime.setNoMoreChapters) window.$TSUNDOKU_OBJECT_NAME.runtime.setNoMoreChapters($value); })();",
            null,
        )
    }

    // Lift the scroll-restore guard for [token] only if it's still the latest restore, and tell the
    // page to re-emit onChapterScrollUpdate so a chapter switch dropped while the guard was up isn't
    // lost (the JS callback is edge-triggered and won't re-fire for the same idx on its own).
    private fun liftRestoreGuard(token: Int) {
        if (token != scrollRestoreToken) return
        isRestoringScroll = false
        evaluateJavascriptSafe(
            "(function(){ if (window.$TSUNDOKU_OBJECT_NAME && window.$TSUNDOKU_OBJECT_NAME.runtime && window.$TSUNDOKU_OBJECT_NAME.runtime.resetChapterTracking) window.$TSUNDOKU_OBJECT_NAME.runtime.resetChapterTracking(); })();",
            null,
        )
    }

    private suspend fun awaitPageText(page: ReaderPage, loader: PageLoader, timeoutMs: Long): Boolean =
        NovelPageLoader.awaitPageText("NovelWebViewViewer", page, loader, timeoutMs, scope)

    private suspend fun displayContentImmediate(
        chapter: ReaderChapter,
        page: ReaderPage,
        isAppendOrPrepend: Boolean,
        isPrepend: Boolean,
    ) {
        if (isDestroyed) return

        val rawContent = page.text
        if (rawContent.isNullOrBlank()) {
            displayError(Exception(activity.stringResource(TDMR.strings.novel_error_empty_chapter)))
            return
        }

        val chapterId = chapter.chapter.id ?: return

        val cfg = ContentConfig.from(
            preferences,
            RenderTarget.WEB_VIEW,
            chapter.chapter.url,
            chapter.chapter.name,
        )
        val translator: (suspend (String) -> String)? =
            if (activity.isTranslationEnabled()) { c -> activity.translateContentIfEnabled(c, chapterId) } else null
        val processed = withContext(Dispatchers.Default) {
            contentPipeline.process(rawContent, cfg, translator)
        }
        imageCache.schedulePrefetch(processed.text, chapterId, page.chapter.pageLoader)

        withContext(Dispatchers.Main) {
            if (isDestroyed) return@withContext

            if (isAppendOrPrepend && preferences.novelInfiniteScroll.get()) {
                // Queue add and DOM insert share one guard: a redundant append for the same
                // chapter would otherwise skip the queue add but still re-insert the DOM copy,
                // corrupting chapterBoundaries.
                if (!loadedChapterIds.contains(chapterId)) {
                    if (isPrepend) {
                        return@withContext
                    }
                    chapterQueue.append(chapter)
                    appendHtmlContent(
                        processed,
                        chapterId,
                        chapter.chapter.name,
                        chapter.chapter.chapter_number,
                        chapter.chapter.url,
                    )
                }
            } else {
                loadHtmlContent(processed, chapter)
                chapterQueue.reset(chapter)
            }
        }
    }

    /**
     * Fetch and cache the next chapter without appending to the DOM.
     * Called when the JS scroll threshold fires during TTS auto-play so the chapter is
     * immediately available when TTS finishes the current one.
     */
    private suspend fun preFetchNextChapterForTts() {
        if (!handoffState.isIdle) return
        val anchor = loadedChapters.lastOrNull() ?: currentChapters?.currChapter ?: return
        val preparedChapter = activity.viewModel.prepareNextChapterForInfiniteScroll(anchor) ?: return
        val nextId = preparedChapter.chapter.id ?: return
        if (loadedChapterIds.contains(nextId)) return

        val page = preparedChapter.pages?.firstOrNull() ?: return
        val loader = page.chapter.pageLoader ?: return

        handoffState = TtsHandoffState.PreFetching(anchorChapterId = anchor.chapter.id)
        logcat(LogPriority.DEBUG) { "TTS (WebView): Pre-fetching next chapter ${preparedChapter.chapter.name}" }
        try {
            val loaded = awaitPageText(page = page, loader = loader, timeoutMs = 30_000)
            if (loaded) {
                withContext(Dispatchers.Main) {
                    if (handoffState.isPreFetching) {
                        handoffState = TtsHandoffState.Cached(Pair(preparedChapter, page))
                        prefetchCompletedSignal.tryEmit(Unit)
                        logcat(LogPriority.DEBUG) {
                            "TTS (WebView): Cached next chapter ${preparedChapter.chapter.name}"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "TTS (WebView): Pre-fetch failed: ${e.message}" }
        } finally {
            // Drop back to Idle if we never reached Cached (e.g. load failed).
            if (handoffState.isPreFetching) handoffState = TtsHandoffState.Idle
        }
    }

    /**
     * Append the next chapter to the WebView, using the pre-fetched cache if
     * available. [silent] suppresses the inline "Loading…" banner - set it
     * when this is invoked from the TTS auto-advance path so the user doesn't
     * see the banner flash during TTS chapter handoff (errors still surface
     * via `showInlineError`). The JS-driven scroll trigger path uses the
     * default (`silent = false`) so the user gets the loading hint when they
     * scroll to the threshold themselves.
     */
    private suspend fun appendNextChapterIfAvailable(silent: Boolean = false): Boolean {
        val cached = handoffState.cachedOrNull
        if (cached != null) {
            handoffState = TtsHandoffState.Idle
            val (preparedChapter, page) = cached
            val nextId = preparedChapter.chapter.id ?: return false
            if (!loadedChapterIds.contains(nextId)) {
                logcat(LogPriority.DEBUG) {
                    "NovelWebViewViewer: using pre-fetched chapter $nextId (${preparedChapter.chapter.name})"
                }
                try {
                    displayContentImmediate(preparedChapter, page, isAppendOrPrepend = true, isPrepend = false)
                    logcat(LogPriority.INFO) {
                        "NovelWebViewViewer: Successfully appended pre-fetched chapter ${preparedChapter.chapter.name}"
                    }
                } finally {
                    if (!silent) inlineFeedback.hideInlineLoading(isPrepend = false)
                    setJsLoadingNext()
                }
            }
            // Already loaded counts as success; the caller still advances TTS onto it.
            return true
        }

        // Coalesce with an in-flight TTS pre-fetch: if one is running, wait for
        // it to complete instead of starting a second fetch + showing loading.
        if (silent && handoffState.isPreFetching) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: TTS append waiting on in-flight pre-fetch" }
            withTimeoutOrNull(5_000L) { prefetchCompletedSignal.first() }
            if (handoffState.cachedOrNull != null) {
                // Cache populated while we waited - recurse to take the cache path.
                return appendNextChapterIfAvailable(silent = true)
            }
            // Timed out: the prefetch is still running but we're proceeding with a
            // cold fetch. Clear PreFetching now so the racing prefetch coroutine
            // cannot later set handoffState = Cached for a chapter we're about to
            // load here - that stale entry would confuse the *next* TTS handoff.
            handoffState = TtsHandoffState.Idle
        }

        val anchor = loadedChapters.lastOrNull() ?: currentChapters?.currChapter ?: run {
            logcat(LogPriority.ERROR) {
                "NovelWebViewViewer: appendNext failed, no anchor chapter (loadedCount=${loadedChapters.size})"
            }
            inlineFeedback.showInlineError("No anchor chapter for infinite scroll", isPrepend = false)
            return false
        }
        logcat(LogPriority.DEBUG) {
            "NovelWebViewViewer: appendNext starting from anchor=${anchor.chapter.id}/${anchor.chapter.name}"
        }

        val preparedChapter = activity.viewModel.prepareNextChapterForInfiniteScroll(anchor) ?: run {
            logcat(LogPriority.WARN) { "NovelWebViewViewer: No next chapter available after ${anchor.chapter.name}" }
            // Surface once, then latch so the scroll handler stops re-triggering at the last chapter.
            if (!reachedNovelEnd) inlineFeedback.showInlineError("No next chapter available", isPrepend = false)
            reachedNovelEnd = true
            setJsNoMoreChapters(true)
            return false
        }
        val nextId = preparedChapter.chapter.id ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: prepared next chapter has null id" }
            inlineFeedback.showInlineError("Chapter has no id", isPrepend = false)
            return false
        }
        logcat(LogPriority.DEBUG) { "NovelWebViewViewer: prepared next=$nextId/${preparedChapter.chapter.name}" }

        if (loadedChapterIds.contains(nextId)) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: next chapter $nextId already loaded, skipping" }
            return true
        }

        val page = preparedChapter.pages?.firstOrNull() ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page in prepared next chapter" }
            inlineFeedback.showInlineError("No page in next chapter", isPrepend = false)
            return false
        }
        val loader = page.chapter.pageLoader ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page loader for next chapter" }
            inlineFeedback.showInlineError("No loader for next chapter", isPrepend = false)
            return false
        }

        if (!silent) inlineFeedback.showInlineLoading(isPrepend = false)
        try {
            logcat(LogPriority.DEBUG) {
                "NovelWebViewViewer: loading page for next chapter $nextId, state=${page.status}"
            }
            val loaded = try {
                awaitPageText(page = page, loader = loader, timeoutMs = 30_000)
            } catch (_: TimeoutCancellationException) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: Timed out loading next chapter page after 30s" }
                inlineFeedback.showInlineError("Timeout loading next chapter", isPrepend = false)
                false
            } catch (_: CancellationException) {
                logcat(LogPriority.DEBUG) { "NovelWebViewViewer: appendNext cancelled" }
                false
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: Error loading next chapter page: ${e.message}" }
                inlineFeedback.showInlineError("Error: ${e.message ?: "Unknown error"}", isPrepend = false)
                false
            }

            if (!loaded) return false

            logcat(LogPriority.DEBUG) {
                "NovelWebViewViewer: appending content for chapter $nextId ts=${System.currentTimeMillis()} ttsCurrentChunkIndex=${ttsController.ttsCurrentChunkIndex} ttsResumeChunkIndex=${ttsController.ttsResumeChunkIndex} ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex} ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}"
            }
            displayContentImmediate(preparedChapter, page, isAppendOrPrepend = true, isPrepend = false)
            logcat(LogPriority.INFO) {
                "NovelWebViewViewer: Successfully appended next chapter ${preparedChapter.chapter.name}"
            }
            return true
        } finally {
            if (!silent) inlineFeedback.hideInlineLoading(isPrepend = false)
            setJsLoadingNext()
        }
    }

    /**
     * Scroll to the top of the content
     */
    fun scrollToTop() {
        webView.scrollTo(0, 0)
    }

    /**
     * Scroll by [fraction] of the viewport in [direction] (+1 down, -1 up). Uses window.innerHeight
     * (CSS pixels) rather than container.height (device pixels): window.scrollBy expects CSS pixels,
     * so passing device pixels overshoots by devicePixelRatio and skips content between taps.
     */
    private fun pageScrollBy(direction: Int, fraction: Double = 0.9) {
        val sign = if (direction < 0) "-" else ""
        evaluateJavascriptSafe("window.scrollBy(0, $sign(window.innerHeight * $fraction));")
    }

    fun toggleAutoScroll() {
        if (isAutoScrolling) stopAutoScroll() else startAutoScroll()
    }

    private fun startAutoScroll() {
        isAutoScrolling = true
        autoScrollStartAttempt = 0
        issueAutoScrollStart()
    }

    private fun issueAutoScrollStart() {
        // Pref is half-steps (speed x2); level is 1.0..10.0 in 0.5 increments.
        val level = preferences.novelAutoScrollLevel()

        // Drive the scroll from a single in-page requestAnimationFrame loop instead of a Kotlin
        // timer that fires window.scrollBy over the JS bridge every 50ms: those round-trips arrive
        // with jittery timing and each moves a fixed integer step, which reads as stutter. The rAF
        // loop advances by (px/sec * frame delta) with sub-pixel accumulation for smooth motion, and
        // naturally pauses while the WebView is backgrounded (no frames), resuming without a jump via
        // the dt clamp. speed level (1..10) maps to CSS px/sec.
        val pxPerSec = level * 20
        val attempt = ++autoScrollStartAttempt
        evaluateJavascriptSafe(
            """
            (function() {
                var s = window.__tdAutoScroll || (window.__tdAutoScroll = {});
                s.pxPerSec = $pxPerSec;
                if (s.running) return;
                s.running = true;
                s.last = null;
                s.acc = 0;
                function step(ts) {
                    if (!s.running) return;
                    if (s.last === null) s.last = ts;
                    var dt = (ts - s.last) / 1000;
                    s.last = ts;
                    // Clamp so a long background gap (or first frame) can't jump the page.
                    if (dt > 0.05) dt = 0.05;
                    s.acc += s.pxPerSec * dt;
                    var whole = Math.floor(s.acc);
                    if (whole > 0) { window.scrollBy(0, whole); s.acc -= whole; }
                    s.raf = requestAnimationFrame(step);
                }
                s.raf = requestAnimationFrame(step);
            })();
            """.trimIndent(),
            null,
        )
        // Confirm the in-page loop actually started: evaluateJavascript is silently dropped when the
        // JS context isn't ready, which would leave isAutoScrolling stuck on with no motion. Query the
        // loop's own flag; retry a few times, then give up and clear isAutoScrolling so the state
        // reflects reality. s.running stays true while backgrounded (rAF paused), so this won't
        // false-negative on a paused page.
        webView.postDelayed({
            if (!isAutoScrolling || attempt != autoScrollStartAttempt) return@postDelayed
            evaluateJavascriptSafe(
                "(function(){ return !!(window.__tdAutoScroll && window.__tdAutoScroll.running); })();",
            ) { running ->
                if (!isAutoScrolling || attempt != autoScrollStartAttempt) return@evaluateJavascriptSafe
                if (running != "true") {
                    if (autoScrollStartAttempt < AUTO_SCROLL_MAX_START_ATTEMPTS) {
                        issueAutoScrollStart()
                    } else {
                        isAutoScrolling = false
                        logcat(LogPriority.WARN) { "NovelWebViewViewer: autoscroll failed to start, giving up" }
                    }
                }
            }
        }, AUTO_SCROLL_START_VERIFY_MS)
    }

    fun stopAutoScroll() {
        isAutoScrolling = false
        evaluateJavascriptSafe(
            """
            (function() {
                var s = window.__tdAutoScroll;
                if (s) { s.running = false; if (s.raf) cancelAnimationFrame(s.raf); }
            })();
            """.trimIndent(),
            null,
        )
    }

    fun isAutoScrollActive(): Boolean = isAutoScrolling

    fun getProgressPercent(): Int {
        return NovelProgress.progressToPercent(lastSavedProgress)
    }

    fun setProgressPercent(percent: Int) {
        val progress = percent.coerceIn(0, 100)
        lastSavedProgress = progress / 100f
        // An explicit user seek is a real sample, so drop the backward-entry baseline hold or a
        // flush on pause right after would skip persisting this position.
        awaitingFirstScrollSample = false
        // Suppress the scroll->slider echo so the async, throttled onScrollUpdate from this
        // programmatic scroll can't fight the user's finger.
        lastUserSeekAt = System.currentTimeMillis()

        evaluateJavascriptSafe(
            """
            (function() {
                var frac = $progress / 100;
                var viewport = window.innerHeight || document.documentElement.clientHeight;
                var boundaries = window.chapterBoundaries || [];
                if (boundaries.length > 1) {
                    // The slider shows per-chapter progress, so seek within the current chapter
                    // (not the whole loaded document) or the landing point won't match the display.
                    var scrollTop = window.scrollY || document.documentElement.scrollTop || 0;
                    var idx = 0;
                    for (var i = 0; i < boundaries.length; i++) {
                        if (scrollTop >= boundaries[i].startOffset) idx = i; else break;
                    }
                    var b = boundaries[idx];
                    var isLast = idx === boundaries.length - 1;
                    var effectiveHeight = Math.max(b.height - (isLast ? viewport : 0), 1);
                    window.scrollTo(0, b.startOffset + effectiveHeight * frac);
                } else {
                    var docHeight = Math.max(
                        document.documentElement.scrollHeight,
                        document.body ? document.body.scrollHeight : 0
                    );
                    window.scrollTo(0, (docHeight - viewport) * frac);
                }
                // A programmatic scrollTo does not reliably fire the page's 'scroll' listener, so the
                // infinite-scroll threshold check (which lives there) never runs. Dispatch one so a
                // slider jump to the chapter end still triggers the next-chapter load.
                window.dispatchEvent(new Event('scroll'));
            })();
            """.trimIndent(),
            null,
        )
    }

    fun reloadChapter() {
        val chapters = currentChapters ?: return
        chapterQueue.clear()
        currentChapterIndex = 0
        setChapters(chapters)
    }

    private fun ensureTtsInitialized() {
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
        dispatchTtsState()
        if (!webChapterContentReady) {
            // Loading indicator still up; reading the body now would speak the placeholder
            // and auto-advance. Defer; onPageFinished starts TTS once content is rendered.
            pendingTtsAutoStartOnLoad = true
            return
        }
        val (chapterIdx, chapterId) = getTtsChapterContext()
        evaluateJavascriptSafe(TTS_TEXT_EXTRACTION_JS) { result ->
            val text = unescapeJsResult(result)

            if (text.isNotBlank() && text != "null") {
                logcat(LogPriority.DEBUG) { "TTS (WebView): Starting to speak ${text.length} characters" }
                ttsController.speak(text, chapterIdx, chapterId)

                // Inf-scroll TTS reads in place; the JS scroll threshold that normally kicks
                // the prefetch may never fire. Start it when playback begins on the last loaded
                // chapter so the next chapter is cached before onLastChunkDone hands off,
                // instead of stalling on a cold fetch. Idempotent via the handoffState guard.
                if (preferences.novelInfiniteScroll.get() &&
                    handoffState.isIdle &&
                    loadedChapters.getOrNull(currentChapterIndex + 1) == null
                ) {
                    scope.launch { preFetchNextChapterForTts() }
                }
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
        // Drop a pending real-load signal so a stale onPageFinished after stop can't inject; leave a
        // committed READY/ERROR document intact.
        if (docState == DocState.LOADING_REAL) docState = DocState.LOADING
        ttsController.stop()
        handoffState = TtsHandoffState.Idle
        dispatchTtsState()
        // The loadNextChapter TTS branch skips the JS-latch release, so after a threshold hit during
        // playback runtime.loadingNext stays true and scroll-driven appending never re-fires. Clear
        // it so infinite scroll resumes once TTS is off, but not while a real append is in flight:
        // that append still owns the latch and will release it, and clobbering it here would let a
        // second scroll launch a duplicate append.
        if (appendJob?.isActive != true) {
            isLoadingNext = false
            setJsLoadingNext()
        }
        // Don't clear the end-of-novel latch if it's already set: loadNextChapterForTts() calls
        // stopTts() right after appendNextChapterIfAvailable() sets reachedNovelEnd on a genuine
        // "no next chapter" result, and resetting it here would let the next scroll event re-fetch
        // and re-show the "No next chapter available" error a second time.
        if (!reachedNovelEnd) {
            setJsNoMoreChapters(false)
        }
    }

    fun pauseTts() {
        logcat(LogPriority.DEBUG) {
            "TTS (WebView): pauseTts called ts=${System.currentTimeMillis()} currentChapterIndex=$currentChapterIndex, ttsCurrentChunkIndex=${ttsController.ttsCurrentChunkIndex}, ttsResumeChunkIndex=${ttsController.ttsResumeChunkIndex}, ttsPlaybackChapterIndex=${ttsController.ttsPlaybackChapterIndex}, ttsPlaybackChapterId=${ttsController.ttsPlaybackChapterId}"
        }
        ttsController.pause()
        dispatchTtsState()
    }

    fun resumeTts() {
        ttsController.resume()
        dispatchTtsState()
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
        ensureTtsInitialized()

        if (!ttsController.ttsInitialized) {
            logcat(LogPriority.WARN) { "TTS (WebView): Not initialized yet" }
            ttsController.pendingStartRequest = TtsController.StartRequest.VIEWPORT
            return
        }

        ttsController.pendingStartRequest = null
        ttsController.isTtsAutoPlay = true
        dispatchTtsState()
        if (!webChapterContentReady) {
            // Still loading; defer so onPageFinished re-runs the viewport start once content is in.
            ttsController.pendingStartRequest = TtsController.StartRequest.VIEWPORT
            return
        }
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
            val firstVisibleParagraphIndex = rawIndex.trim('"').toIntOrNull() ?: 0
            evaluateJavascriptSafe(TTS_TEXT_EXTRACTION_JS) { result ->
                val text = unescapeJsResult(result)

                if (text.isNotBlank() && text != "null") {
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
            selectedText = unescapeJsResult(result)
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
            var sel = window.getSelection();
            if (!sel || sel.rangeCount === 0) return null;
            var text = sel.toString().trim();
            if (!text) return null;
            var range = sel.getRangeAt(0);
            var node = range.startContainer;
            if (node && node.nodeType === 3) node = node.parentNode;
            var para = -1;
            try {
                var chapterEl = (node && node.closest) ? node.closest('tsundoku-chapter') : null;
                if (chapterEl) {
                    var plain = chapterEl.querySelector('[data-tsundoku-plain-text]');
                    if (plain) {
                        var pre = document.createRange();
                        pre.selectNodeContents(plain);
                        pre.setEnd(range.startContainer, range.startOffset);
                        var lines = pre.toString().split('\n');
                        var count = 0;
                        for (var i = 0; i < lines.length - 1; i++) {
                            if (lines[i].trim().length > 0) count++;
                        }
                        para = count;
                    } else {
                        var blocks = chapterEl.querySelectorAll('p, li, blockquote, h1, h2, h3, h4, h5, h6, pre');
                        for (var j = 0; j < blocks.length; j++) {
                            if (blocks[j].contains(node)) { para = j; break; }
                        }
                    }
                }
            } catch (e) {
                para = -1;
            }
            return para + '\n' + text;
        })();
            """.trimIndent(),
        ) { result ->
            activity.runOnUiThread {
                actionMode?.finish() // finish AFTER JS has read the selection
                val raw = if (result != "null") unescapeJsResult(result) else null
                val newlineIdx = raw?.indexOf('\n') ?: -1
                val paragraphIndex = raw?.takeIf { newlineIdx >= 0 }
                    ?.substring(0, newlineIdx)
                    ?.toIntOrNull()
                    ?.takeIf { it >= 0 }
                val selectedText = when {
                    raw == null -> null
                    newlineIdx >= 0 -> raw.substring(newlineIdx + 1).trim().ifEmpty { null }
                    else -> raw.trim().ifEmpty { null }
                }

                if (!selectedText.isNullOrBlank()) {
                    pendingSelectedText = selectedText
                    pendingParagraphIndex = paragraphIndex
                    activity.onRememberSelectedText()
                    clearTextSelection()
                } else {
                    activity.toast("No text selected")
                }
            }
        }
    }
}
