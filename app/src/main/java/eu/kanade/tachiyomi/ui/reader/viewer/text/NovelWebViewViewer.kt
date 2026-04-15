package eu.kanade.tachiyomi.ui.reader.viewer.text

import android.annotation.SuppressLint
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.injectLazy
import java.util.Locale

/**
 * NovelWebViewViewer renders novel content using a WebView for more flexibility.
 * Supports custom CSS and JavaScript injection.
 */
class NovelWebViewViewer(val activity: ReaderActivity) : Viewer, TextToSpeech.OnInitListener {

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

    // TTS support
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private var pendingTtsText: String? = null
    private var isTtsAutoPlay = false // Track if TTS should auto-continue to next chapter

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

                val viewWidth = container.width.toFloat()
                val viewHeight = container.height.toFloat()
                val x = e.x
                val y = e.y

                // Define center region (middle third of the screen)
                val centerXStart = viewWidth / 3
                val centerXEnd = viewWidth * 2 / 3
                val centerYStart = viewHeight / 3
                val centerYEnd = viewHeight * 2 / 3

                if (x in centerXStart..centerXEnd && y in centerYStart..centerYEnd) {
                    activity.toggleMenu()
                    return true
                }

                // Handle tap-to-scroll if enabled
                if (preferences.novelTapToScroll.get()) {
                    // Top zone - scroll up
                    if (y < centerYStart) {
                        webView.evaluateJavascript("window.scrollBy(0, -${(viewHeight * 0.8).toInt()});", null)
                        return true
                    }
                    // Bottom zone - scroll down
                    if (y > centerYEnd) {
                        webView.evaluateJavascript("window.scrollBy(0, ${(viewHeight * 0.8).toInt()});", null)
                        return true
                    }
                }

                return false
            }
        },
    ).apply {
        // Disable long press handling so WebView can handle text selection
        setIsLongpressEnabled(false)
    }

    init {
        initWebView()
        observePreferences()
        // Defer TTS initialization until actually needed to avoid "not bound" errors
        // TTS will be initialized lazily when startTts() is called
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsInitialized = true
            applyTtsSettings()
            setupTtsListener()
            // If there was pending text to speak, speak it now
            pendingTtsText?.let { text ->
                pendingTtsText = null
                speak(text)
            }
        } else {
            logcat(LogPriority.ERROR) { "TTS initialization failed with status: $status" }
            ttsInitialized = false
        }
    }

    private fun setupTtsListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                // Check if this was the last chunk and auto-play is enabled
                if (isTtsAutoPlay && preferences.novelTtsAutoNextChapter.get()) {
                    activity.runOnUiThread {
                        scope.launch {
                            delay(500)
                            if (!isTtsSpeaking()) {
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

    private fun loadNextChapterForTts() {
        val chapters = currentChapters ?: return
        val nextChapter = chapters.nextChapter ?: return

        logcat(LogPriority.DEBUG) { "TTS (WebView): Auto-loading next chapter for playback" }

        scope.launch {
            activity.loadNextChapter()
            delay(1000)
            startTts()
        }
    }

    private fun applyTtsSettings() {
        tts?.let { textToSpeech ->
            // Set voice/locale
            val voicePref = preferences.novelTtsVoice.get()
            if (voicePref.isNotEmpty()) {
                val voices = textToSpeech.voices
                val selectedVoice = voices?.find { it.name == voicePref }
                if (selectedVoice != null) {
                    textToSpeech.voice = selectedVoice
                } else {
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
                    if (url.startsWith("tsundoku-novel-image://")) {
                        val imagePath = android.net.Uri.decode(url.removePrefix("tsundoku-novel-image://"))
                        val loader = activity.viewModel.state.value.viewerChapters?.currChapter?.pageLoader
                        if (loader != null) {
                            val stream = kotlinx.coroutines.runBlocking { loader.getPageDataStream(imagePath) }
                            if (stream != null) {
                                val mimeType = when (imagePath.substringAfterLast('.', "").lowercase()) {
                                    "png" -> "image/png"
                                    "jpg", "jpeg" -> "image/jpeg"
                                    "gif" -> "image/gif"
                                    "svg" -> "image/svg+xml"
                                    "webp" -> "image/webp"
                                    "avif" -> "image/avif"
                                    else -> "image/jpeg"
                                }
                                return android.webkit.WebResourceResponse(mimeType, "UTF-8", stream)
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    hideLoadingIndicator()
                    injectCustomStyles()
                    injectCustomScript()
                    injectScrollTracking()
                    restoreScrollPosition()
                    if (!preferences.novelInfiniteScroll.get()) {
                        injectNextChapterButton()
                    }
                    if (isEditingMode) {
                        toggleEditMode(true)
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
                preferences.novelParagraphIndent.changes(),
                preferences.novelParagraphSpacing.changes(),
                preferences.novelCustomCss.changes(),
                preferences.novelCustomCssSnippets.changes(),
                preferences.novelUseOriginalFonts.changes(),
                preferences.novelHideChapterTitle.changes(),
                preferences.novelTextSelectable.changes(),
            ).drop(20) // Drop initial emissions from all 20 preferences
                .collect {
                    injectCustomStyles()
                }
        }

        // Observe JS changes separately to re-inject scripts
        scope.launch {
            merge(
                preferences.novelCustomJs.changes(),
                preferences.novelCustomJsSnippets.changes(),
            ).drop(2)
                .collect {
                    injectCustomScript()
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
    }

    private fun injectCustomStyles() {
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

        webView.setBackgroundColor(finalBgColor)
        container.setBackgroundColor(finalBgColor)

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

        val css = """
            $fontFaceDeclaration
            body {
                font-size: ${fontSize}px;
                $fontFamilyCss
                line-height: $lineHeight;
                margin: ${marginTop}px ${marginRight}px ${marginBottom}px ${marginLeft}px;
                color: $textColorHex !important;
                background-color: $bgColorHex !important;
                text-align: $textAlign;
                -webkit-user-select: ${if (preferences.novelTextSelectable.get()) "text" else "none"};
                user-select: ${if (preferences.novelTextSelectable.get()) "text" else "none"};
            }
            p {
                text-indent: ${paragraphIndent}em;
                margin-top: ${paragraphSpacing}em;
                margin-bottom: ${paragraphSpacing}em;
            }
            * {
                color: inherit !important;
            }
            $customCss
            $enabledSnippetsCss
        """.trimIndent().replace("\n", " ")

        val js = """
            (function() {
                var style = document.getElementById('tsundoku-custom-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'tsundoku-custom-style';
                    document.head.appendChild(style);
                }
                style.textContent = `$css`;

                // Hide chapter title if enabled
                if ($hideChapterTitle) {
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
        val chapter = currentChapters?.currChapter?.chapter
            ?: loadedChapters.getOrNull(currentChapterIndex)?.chapter
        val chapterTitle = (chapter?.name ?: "").jsEscape()
        val chapterNumber = chapter?.chapter_number ?: -1f
        val chapterUrl = (chapter?.url ?: "").jsEscape()
        val novelUrl = (activity.viewModel.manga?.url ?: "").jsEscape()

        val script = """
        window.Tsundoku = {
            chapterTitle: "$chapterTitle",
            chapterNumber: $chapterNumber,
            chapterUrl: "$chapterUrl",
            novelUrl: "$novelUrl",
            isEditMode: $isEditingMode,
            isInfScroll: ${preferences.novelInfiniteScroll.get()},
            textSelectionBlocked: ${!preferences.novelTextSelectable.get()},
            forcedLowercase: ${preferences.novelForceTextLowercase.get()}
        };
        """.trimIndent()
        evaluateJavascriptSafe(script, null)

        val customJs = preferences.novelCustomJs.get()
        if (customJs.isNotBlank()) {
            evaluateJavascriptSafe(customJs, null)
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
                var existing = document.getElementById('next-chapter-btn-container');
                if (existing) existing.remove();

                var container = document.createElement('div');
                container.id = 'next-chapter-btn-container';
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
                if (window.__tsundokuInfiniteScrollInstalled) {
                    return;
                }
                window.__tsundokuInfiniteScrollInstalled = true;

                var lastProgress = 0;
                var saveTimeout = null;
                window.__tsundokuLoadingNext = window.__tsundokuLoadingNext || false;
                window.__tsundokuSetLoadingNext = function(v) { window.__tsundokuLoadingNext = !!v; };
                var infiniteScrollEnabled = $infiniteScrollActuallyEnabled;
                var loadThreshold = $effectiveThreshold;

                // Track chapter boundaries for multi-chapter infinite scroll
                window.chapterBoundaries = window.chapterBoundaries || [];
                window.__tsundokuLastBoundaryUpdate = window.__tsundokuLastBoundaryUpdate || 0;

                window.addEventListener('scroll', function() {
                    // Keep chapter boundaries in sync with actual DOM markers.
                    // This is important after appends/prepends.
                    if (infiniteScrollEnabled && typeof window.updateChapterBoundaries === 'function') {
                        var dividers = document.querySelectorAll('.chapter-divider');
                        if (!window.chapterBoundaries || window.chapterBoundaries.length !== dividers.length) {
                            window.updateChapterBoundaries();
                        } else if (Date.now() - window.__tsundokuLastBoundaryUpdate > 1000) {
                            window.__tsundokuLastBoundaryUpdate = Date.now();
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
                    if (shouldLoadNext && !window.__tsundokuLoadingNext) {
                        console.log('Infinite scroll: Loading next chapter at progress ' + currentChapterProgress + ' (threshold: ' + loadThreshold + ')');
                        window.__tsundokuLoadingNext = true;
                        try {
                            Android.loadNextChapter();
                            console.log('Infinite scroll: Successfully called loadNextChapter()');
                        } catch(e) {
                            console.error('Infinite scroll: Error calling loadNextChapter:', e);
                            window.__tsundokuLoadingNext = false; // Reset immediately on error
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
                    var dividers = document.querySelectorAll('.chapter-divider');
                    var boundaries = [];
                    var lastEnd = 0;
                    dividers.forEach(function(divider, index) {
                        var chapterId = divider.getAttribute('data-chapter-id');
                        var nextDivider = dividers[index + 1];
                        var endPos = nextDivider ? nextDivider.offsetTop : document.body.scrollHeight;
                        boundaries.push({
                            chapterId: chapterId,
                            startOffset: divider.offsetTop,
                            height: endPos - divider.offsetTop
                        });
                    });
                    window.chapterBoundaries = boundaries;
                    window.__tsundokuLastBoundaryUpdate = Date.now();
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

        // Cleanup TTS - only if initialized
        if (ttsInitialized) {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null

        // Mark destroyed first so coroutine finally-blocks won't touch WebView.
        isDestroyed = true

        scope.cancel()
        loadJob?.cancel()
        webView.destroy()
    }

    private fun evaluateJavascriptSafe(js: String, callback: ((String) -> Unit)? = null) {
        if (isDestroyed) return
        try {
            webView.evaluateJavascript(js, callback)
        } catch (t: Throwable) {
            // WebView may already be destroyed; avoid crashing.
            logcat(LogPriority.WARN) { "NovelWebViewViewer: evaluateJavascript ignored (${t.message})" }
        }
    }

    private fun saveProgress() {
        currentPage?.let { page ->
            val progressValue = (lastSavedProgress * 100).toInt().coerceIn(0, 100)
            activity.saveNovelProgress(page, progressValue)
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Saving progress $progressValue%" }
        }
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
                    loadHtmlContent(translatedContent, chapterId, chapter.chapter.name)
                }
            }
        } else {
            loadHtmlContent(finalContent, chapterId, chapter.chapter.name)
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        val page = chapters.currChapter.pages?.firstOrNull() as? ReaderPage ?: return
        val chapterId = chapters.currChapter.chapter.id ?: return

        loadJob?.cancel()
        // Stop TTS when loading a new chapter (unless it's auto-play transition which handles restart)
        // But even for auto-play, we want to stop the old chapter audio before loading new one.
        tts?.stop()

        currentPage = page
        currentChapters = chapters

        // setChapters() is for loading a single chapter (manual navigation or initial load).
        // Infinite scroll appends/prepends are handled explicitly via WebViewInterface.loadNext/PrevChapter().
        val isInfiniteScrollAppend = false

        val isPrepend = isInfiniteScrollPrepend
        isInfiniteScrollPrepend = false

        // Reset the flag after checking
        isInfiniteScrollNavigation = false

        // Check if chapter is already loaded
        if (loadedChapterIds.contains(chapterId)) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Chapter $chapterId already loaded, skipping" }
            // Find and scroll to this chapter
            val index = loadedChapterIds.indexOf(chapterId)
            if (index >= 0) {
                currentChapterIndex = index
            }
            return
        }

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
                var loadingDiv = document.getElementById('inline-loading');
                if (!loadingDiv) {
                    loadingDiv = document.createElement('div');
                    loadingDiv.id = 'inline-loading';
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
                var loadingDiv = document.getElementById('inline-loading');
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
                    var errorDiv = document.getElementById('inline-error');
                    if (errorDiv) {
                        errorDiv.remove();
                    }
                    errorDiv = document.createElement('div');
                    errorDiv.id = 'inline-error';
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
                "document.getElementById('inline-error')?.remove();",
                null,
            )
        }
    }

    private fun scrollToChapterIndex(index: Int) {
        val js = """
            (function() {
                var dividers = document.querySelectorAll('.chapter-divider');
                if (dividers[$index]) {
                    dividers[$index].scrollIntoView({ behavior: 'smooth' });
                }
            })();
        """.trimIndent()
        evaluateJavascriptSafe(js, null)
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
                        prependHtmlContent(processedContent, chapterId, chapter.chapter.name)
                    } else {
                        appendHtmlContent(processedContent, chapterId, chapter.chapter.name)
                    }
                } else {
                    loadHtmlContent(processedContent, chapterId, chapter.chapter.name)

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
    private fun prependHtmlContent(content: String, chapterId: Long, chapterName: String) {
        // Strip script/style/noscript tags from content
        var cleanContent = content
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<script[^>]*/>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<noscript[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        if (preferences.novelBlockMedia.get()) {
            cleanContent = stripMediaTags(cleanContent)
        }
        val escapedContent = cleanContent.replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("\$", "\\\$")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        val js = """
            (function() {
                var oldHeight = document.body.scrollHeight;
                var oldScrollY = window.scrollY || window.pageYOffset;

                var contentDiv = document.createElement('div');
                contentDiv.className = 'chapter-content';
                contentDiv.setAttribute('data-chapter-id', '$chapterId');
                contentDiv.innerHTML = `$escapedContent`;

                var divider = document.createElement('div');
                divider.className = 'chapter-divider';
                divider.setAttribute('data-chapter-id', '$chapterId');

                var firstChild = document.body.firstChild;
                document.body.insertBefore(contentDiv, firstChild);
                document.body.insertBefore(divider, contentDiv);

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
    private fun appendHtmlContent(content: String, chapterId: Long, chapterName: String) {
        // Strip script/style/noscript tags from content
        var cleanContent = content
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<script[^>]*/>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<noscript[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        if (preferences.novelBlockMedia.get()) {
            cleanContent = stripMediaTags(cleanContent)
        }
        val escapedContent = cleanContent.replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("\$", "\\\$")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        val js = """
            (function() {
                var divider = document.createElement('hr');
                divider.className = 'chapter-divider';
                divider.setAttribute('data-chapter-id', '$chapterId');
                document.body.appendChild(divider);

                var contentDiv = document.createElement('div');
                contentDiv.className = 'chapter-content';
                contentDiv.setAttribute('data-chapter-id', '$chapterId');
                contentDiv.innerHTML = `$escapedContent`;
                document.body.appendChild(contentDiv);

                // Update chapter boundaries after DOM update
                setTimeout(function() {
                    if (typeof window.updateChapterBoundaries === 'function') {
                        window.updateChapterBoundaries();
                    }
                }, 100);
            })();
        """.trimIndent()

        evaluateJavascriptSafe(js, null)

        // Re-run custom JS for newly appended DOM so selector-based scripts apply consistently.
        webView.postDelayed({ injectCustomScript() }, 120)

        logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Appended chapter $chapterId (${loadedChapterIds.size} total)" }
    }

    private fun loadHtmlContent(content: String, chapterId: Long? = null, chapterName: String? = null) {
        // Strip script tags from content to prevent unwanted JS execution
        var cleanContent = content
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<script[^>]*/>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<noscript[^>]*>.*?</noscript>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        val blockMedia = preferences.novelBlockMedia.get()
        if (blockMedia) {
            cleanContent = stripMediaTags(cleanContent)
        }

        // Apply user's regex find & replace rules
        cleanContent = applyRegexReplacements(cleanContent)

        val theme = preferences.novelTheme.get()
        val (themeBgColor, themeTextColor) = getThemeColors(theme)
        val bgColorHex = String.format("#%06X", 0xFFFFFF and themeBgColor)
        val textColorHex = String.format("#%06X", 0xFFFFFF and themeTextColor)

        // Clear loaded chapters for fresh start
        loadedChapterIds.clear()
        loadedChapters.clear()
        currentChapterIndex = 0

        // Add initial invisible chapter divider marker for tracking (no visible separator)
        val chapterDivider = if (chapterId != null && preferences.novelInfiniteScroll.get()) {
            """<div class="chapter-divider" data-chapter-id="$chapterId" style="height:0;margin:0;padding:0;"></div>
               <div class="chapter-content" data-chapter-id="$chapterId">"""
        } else {
            ""
        }
        val chapterDividerEnd = if (chapterId != null && preferences.novelInfiniteScroll.get()) "</div>" else ""

        val mediaBlockCss = if (blockMedia) {
            "img, video, audio, source, svg, image { display: none !important; }"
        } else {
            ""
        }

        val userSelectCss = if (preferences.novelTextSelectable.get()) "text" else "none"

        // Build global JS variables for custom scripts
        val chapterMetaScript = buildChapterMetaScript()

        var finalContent = cleanContent
        var epubHead = ""

        try {
            val doc = org.jsoup.Jsoup.parse(finalContent)

            // Extract and filter EPUB styles
            if (preferences.enableEpubStyles.get()) {
                doc.select("style[data-epub-css]").forEach { style ->
                    epubHead += "\n" + style.outerHtml()
                }
            }
            doc.select("style[data-epub-css]").remove()

            // Extract and filter EPUB scripts
            if (preferences.enableEpubJs.get()) {
                doc.select("script[data-epub-js]").forEach { script ->
                    epubHead += "\n" + script.outerHtml()
                }
            }
            doc.select("script[data-epub-js]").remove()

            val bodyNode = doc.body()
            if (bodyNode != null && bodyNode.hasText()) {
                finalContent = bodyNode.html()
            } else if (bodyNode != null && bodyNode.children().isNotEmpty()) {
                finalContent = bodyNode.html()
            }
        } catch (_: Exception) {}

        val html = """
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
                        -webkit-user-select: $userSelectCss;
                        user-select: $userSelectCss;
                    }
                    .chapter-divider {
                        height: 1px;
                        margin: 32px auto;
                        padding: 0;
                        border: none;
                        border-top: 1px solid $textColorHex;
                        opacity: 0.4;
                        width: 60%;
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
                    $mediaBlockCss
                </style>
                $epubHead
                <script>$chapterMetaScript</script>
            </head>
            <body>
                $chapterDivider
                $finalContent
                $chapterDividerEnd
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
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
     * Build a JS snippet that sets global chapter/novel metadata variables.
     * Called during initial [loadHtmlContent] to embed in `<script>` tags.
     */
    private fun buildChapterMetaScript(): String {
        val chapter = currentChapters?.currChapter?.chapter
            ?: loadedChapters.getOrNull(currentChapterIndex)?.chapter
        val chapterTitle = (chapter?.name ?: "").jsEscape()
        val chapterNumber = chapter?.chapter_number ?: -1f
        val chapterUrl = (chapter?.url ?: "").jsEscape()
        val novelUrl = (activity.viewModel.manga?.url ?: "").jsEscape()

        return """
            window.__TSUNDOKU_CHAPTER_TITLE = "$chapterTitle";
            window.__TSUNDOKU_CHAPTER_NUMBER = $chapterNumber;
            window.__TSUNDOKU_CHAPTER_URL = "$chapterUrl";
            window.__TSUNDOKU_NOVEL_URL = "$novelUrl";
            window.__TSUNDOKU_IS_EDIT_MODE = $isEditingMode;
            window.__TSUNDOKU_IS_INF_SCROLL = ${preferences.novelInfiniteScroll.get()};
            window.__TSUNDOKU_TEXT_SELECTION_BLOCKED = ${!preferences.novelTextSelectable.get()};
            window.__TSUNDOKU_FORCED_LOWERCASE = ${preferences.novelForceTextLowercase.get()};
        """.trimIndent()
    }

    /**
     * Update global JS chapter metadata variables after an infinite-scroll
     * boundary change (when the user scrolls into a different chapter).
     */
    private fun updateChapterMetaJs() {
        val js = buildChapterMetaScript()
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

        webView.loadDataWithBaseURL(null, loadingHtml, "text/html", "UTF-8", null)
    }

    private fun hideLoadingIndicator() {
        // No-op, content will replace loading HTML
    }

    private fun displayError(error: Throwable) {
        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val fontColor = preferences.novelFontColor.get()
        val (themeBgColor, themeTextColor) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        val finalTextColor = if (fontColor != 0) fontColor else themeTextColor
        val bgColorHex = String.format("#%06X", 0xFFFFFF and finalBgColor)
        val textColorHex = String.format("#%06X", 0xFFFFFF and finalTextColor)

        val escapedMessage = (error.message ?: "Unknown error")
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
                    var styleId = 'edit-mode-style';
                    if ('$isEditing' === 'true') {
                        if (!document.getElementById(styleId)) {
                            var style = document.createElement('style');
                            style.id = styleId;
                            style.innerHTML = '.chapter-content, [data-tsundoku-editable="1"], body { -webkit-user-select: text !important; user-select: text !important; pointer-events: auto !important; -webkit-tap-highlight-color: transparent; outline: none; }';
                            document.head.appendChild(style);
                        }

                        var editTargets = document.querySelectorAll('.chapter-content');
                        if (editTargets.length === 0 && document.body) {
                            document.body.setAttribute('contenteditable', 'true');
                            document.body.setAttribute('data-tsundoku-editable', '1');
                            document.body.setAttribute('tabindex', '0');
                        } else {
                            for (var i = 0; i < editTargets.length; i++) {
                                editTargets[i].setAttribute('contenteditable', 'true');
                                editTargets[i].setAttribute('data-tsundoku-editable', '1');
                                editTargets[i].setAttribute('tabindex', '0');
                            }
                        }

                        if (!window.__tsundokuEditInputBound) {
                            window.__tsundokuEditInputBound = true;
                            document.addEventListener('input', function(e) {
                                if (window.Android && window.Android.onContentEdited) {
                                    window.Android.onContentEdited();
                                }
                            });
                        }
                    } else {
                        var style = document.getElementById(styleId);
                        if (style) {
                            style.parentNode.removeChild(style);
                        }

                        var editableNodes = document.querySelectorAll('[data-tsundoku-editable="1"]');
                        for (var j = 0; j < editableNodes.length; j++) {
                            editableNodes[j].removeAttribute('contenteditable');
                            editableNodes[j].removeAttribute('data-tsundoku-editable');
                            editableNodes[j].removeAttribute('tabindex');
                        }

                        var contents = [];
                        var nodes = document.querySelectorAll('.chapter-content');
                        if (nodes.length > 0) {
                            for (var i = 0; i < nodes.length; i++) {
                                var html = nodes[i].innerHTML;
                                var chapterId = nodes[i].getAttribute('data-chapter-id');
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
                if (chapterIndex != currentChapterIndex && chapterIndex >= 0 && chapterIndex < loadedChapters.size) {
                    val oldIndex = currentChapterIndex
                    currentChapterIndex = chapterIndex
                    logcat(LogPriority.DEBUG) {
                        "NovelWebViewViewer: Chapter scroll changed from $oldIndex to $chapterIndex"
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
    }

    private fun setJsLoadingNext(isLoading: Boolean) {
        val flag = if (isLoading) "true" else "false"
        evaluateJavascriptSafe(
            "(function(){ if (window.__tsundokuSetLoadingNext) window.__tsundokuSetLoadingNext($flag); })();",
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

        // Optionally force lowercase
        if (preferences.novelForceTextLowercase.get()) {
            content = content.lowercase()
        }

        val processedContent = activity.translateContentIfEnabled(content)

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
                appendHtmlContent(processedContent, chapterId, chapter.chapter.name)
            } else {
                loadHtmlContent(processedContent, chapterId, chapter.chapter.name)
                loadedChapterIds.clear()
                loadedChapters.clear()
                loadedChapterIds.add(chapterId)
                loadedChapters.add(chapter)
                currentChapterIndex = 0
            }
        }
    }

    private suspend fun appendNextChapterIfAvailable() {
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

            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: appending content for chapter $nextId" }
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
        if (tts == null) {
            // Check if TTS data is available first
            val intent = android.content.Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
            try {
                tts = TextToSpeech(activity, this)
                logcat(LogPriority.DEBUG) { "TTS (WebView): Initialization started" }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "TTS (WebView): Failed to create TextToSpeech instance: ${e.message}" }
                activity.runOnUiThread {
                    logcat(LogPriority.DEBUG) {
                        "TTS engine not available. Please install a TTS engine from Google Play."
                    }
                }
            }
        }
    }

    fun startTts() {
        ensureTtsInitialized()

        if (!ttsInitialized) {
            logcat(LogPriority.WARN) { "TTS (WebView): Not initialized yet, waiting..." }
            // Queue the speech to start when TTS becomes available
            scope.launch {
                // Wait up to 2 seconds for initialization
                var waited = 0
                while (!ttsInitialized && waited < 2000) {
                    delay(100)
                    waited += 100
                }
                if (ttsInitialized) {
                    startTts() // Retry now that it's initialized
                } else {
                    activity.runOnUiThread {
                        logcat(LogPriority.WARN) { "TTS not available. Please check your TTS settings." }
                    }
                }
            }
            return
        }

        isTtsAutoPlay = true // Enable auto-continue
        // Extract text from WebView using JavaScript
        evaluateJavascriptSafe(
            """
            (function() {
                var body = document.body;
                return body ? body.innerText || body.textContent : '';
            })();
            """.trimIndent(),
        ) { result ->
            // JavaScript returns quoted string, need to unquote and unescape
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
                speak(text)
            } else {
                logcat(LogPriority.WARN) { "TTS (WebView): No text to speak" }
            }
        }
    }

    fun stopTts() {
        isTtsAutoPlay = false // Disable auto-continue when manually stopped
        if (ttsInitialized) {
            tts?.stop()
        }
    }

    fun isTtsSpeaking(): Boolean = ttsInitialized && tts?.isSpeaking == true

    private fun speak(text: String) {
        if (!ttsInitialized || tts == null) {
            // Store for later when TTS is initialized
            pendingTtsText = text
            logcat(LogPriority.WARN) { "TTS not initialized, storing text for later" }
            return
        }

        applyTtsSettings()

        // Android TTS has a max length limit (~4000 chars), chunk long text
        val maxLength = TextToSpeech.getMaxSpeechInputLength()

        if (text.length <= maxLength) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_utterance_0")
        } else {
            val chunks = splitTextForTts(text, maxLength)
            chunks.forEachIndexed { index, chunk ->
                val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                tts?.speak(chunk, queueMode, null, "tts_utterance_$index")
            }
        }
    }

    private fun splitTextForTts(text: String, maxLength: Int): List<String> =
        NovelViewerTextUtils.splitTextForTts(text, maxLength)

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
    companion object {
        private const val REMEMBER_MENU_ITEM_ID = 0xBEEF // arbitrary unique ID
    }
}
