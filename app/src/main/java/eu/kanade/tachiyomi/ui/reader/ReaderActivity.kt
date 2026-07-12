package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.LAYER_TYPE_HARDWARE
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.transition.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.hippo.unifile.UniFile
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.reader.DisplayRefreshHost
import eu.kanade.presentation.reader.EstimatedStatusBarHeight
import eu.kanade.presentation.reader.NovelStatusBar
import eu.kanade.presentation.reader.OrientationSelectDialog
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderPageActionsDialog
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.presentation.reader.TranslationLanguageSelectDialog
import eu.kanade.presentation.reader.appbars.BottomBarEditorSheet
import eu.kanade.presentation.reader.appbars.BottomBarItem
import eu.kanade.presentation.reader.appbars.NovelReaderAppBars
import eu.kanade.presentation.reader.appbars.QuotesSheet
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.appbars.bottomBarItemInfo
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.presentation.reader.deserializeStatusBarOrder
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.AddToLibraryFirst
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Error
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Success
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.textview.NovelViewer
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewViewer
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.isNightMode
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.graphics.Color as ComposeColor

class ReaderActivity : BaseActivity() {

    companion object {
        fun newIntent(context: Context, mangaId: Long?, chapterId: Long?): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private val readerPreferences = Injekt.get<ReaderPreferences>()
    private val preferences = Injekt.get<BasePreferences>()

    lateinit var binding: ReaderActivityBinding

    val viewModel by viewModels<ReaderViewModel>()
    private var assistUrl: String? = null

    /**
     * Configuration at reader level, like background color or forced orientation.
     */
    private var config: ReaderConfig? = null

    private var menuToggleToast: Toast? = null
    private var readingModeToast: Toast? = null
    private val displayRefreshHost = DisplayRefreshHost()

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    private var loadingIndicator: ReaderProgressIndicator? = null
    private var ttsNotificationSyncJob: Job? = null

    private val ttsNotificationControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TtsPlaybackService.ACTION_CONTROL) return

            when (intent.getStringExtra(TtsPlaybackService.EXTRA_COMMAND)) {
                TtsPlaybackService.COMMAND_TOGGLE_PAUSE -> togglePauseResumeFromNotification()
                TtsPlaybackService.COMMAND_PREV_PARAGRAPH -> stepTtsParagraph(isNext = false)
                TtsPlaybackService.COMMAND_NEXT_PARAGRAPH -> stepTtsParagraph(isNext = true)
                TtsPlaybackService.COMMAND_STOP -> stopTtsFromNotification()
            }
        }
    }

    var isScrollingThroughPages = false
        private set

    /**
     * Has the tap-zone preview overlay been shown at least once in this
     * activity instance? Set to `true` after the first viewer construction
     * shows the overlay; subsequent viewer constructions (e.g. switching the
     * novel rendering mode between TextView and WebView mid-session) check
     * this flag and skip the on-start auto-display.
     *
     * Per-activity-instance — automatically reset on `onCreate`.
     */
    var tapZonesShownInSession = false

    // Quotes functionality
    private var showQuotesSheet by mutableStateOf(false)

    /**
     * Called when the activity is created. Initializes the presenter and configuration.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        binding = ReaderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.setComposeOverlay()

        ContextCompat.registerReceiver(
            this,
            ttsNotificationControlReceiver,
            IntentFilter(TtsPlaybackService.ACTION_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        if (viewModel.needsInit()) {
            val manga = intent.extras?.getLong("manga", -1) ?: -1L
            val chapter = intent.extras?.getLong("chapter", -1) ?: -1L
            if (manga == -1L || chapter == -1L) {
                finish()
                return
            }
            NotificationReceiver.dismissNotification(this, manga.hashCode(), Notifications.ID_NEW_CHAPTERS)

            lifecycleScope.launchNonCancellable {
                val initResult = viewModel.init(manga, chapter)
                if (!initResult.getOrDefault(false)) {
                    val exception = initResult.exceptionOrNull() ?: IllegalStateException("Unknown err")
                    withUIContext {
                        setInitialChapterError(exception)
                    }
                }
            }
        }

        config = ReaderConfig()
        setMenuVisibility(viewModel.state.value.menuVisible)

        // Finish when incognito mode is disabled
        preferences.incognitoMode.changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.isLoadingAdjacentChapter }
            .distinctUntilChanged()
            .onEach { isLoading ->
                // Skip loading dialog for infinite scroll - the viewer handles inline indicators
                val isNovelViewer = viewModel.state.value.viewer is NovelViewer ||
                    viewModel.state.value.viewer is NovelWebViewViewer
                val infiniteScrollEnabled = readerPreferences.novelInfiniteScroll.get()
                if (isNovelViewer && infiniteScrollEnabled) {
                    // Don't show popup for infinite scroll - viewer shows inline indicators
                    return@onEach
                }
                setProgressDialog(isLoading)
            }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.manga }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { updateViewer() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.viewerChapters }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setChapters)
            .launchIn(lifecycleScope)

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    ReaderViewModel.Event.ReloadViewerChapters -> {
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                    }
                    ReaderViewModel.Event.ReloadWithTranslation -> {
                        // Force reload content with new translation state
                        reloadContentWithTranslation()
                    }
                    ReaderViewModel.Event.PageChanged -> {
                        displayRefreshHost.flash()
                    }
                    is ReaderViewModel.Event.SetOrientation -> {
                        setOrientation(event.orientation)
                    }
                    is ReaderViewModel.Event.SavedImage -> {
                        onSaveImageResult(event.result)
                    }
                    is ReaderViewModel.Event.ShareImage -> {
                        onShareImageResult(event.uri, event.page)
                    }
                    is ReaderViewModel.Event.CopyImage -> {
                        onCopyImageResult(event.uri)
                    }
                    is ReaderViewModel.Event.SetCoverResult -> {
                        onSetAsCoverResult(event.result)
                    }
                }
            }
            .launchIn(lifecycleScope)

        readerPreferences.novelTtsBackgroundPlayback.changes()
            .drop(1)
            .onEach { enabled ->
                if (enabled) {
                    val state = currentNovelTtsState()
                    if (state?.active == true) {
                        startTtsNotificationSync()
                        syncBackgroundTtsState()
                    }
                } else {
                    stopBackgroundTtsIfRunning()
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun ReaderActivityBinding.setComposeOverlay(): Unit = composeOverlay.setComposeContent {
        val state by viewModel.state.collectAsState()
        val showPageNumber by readerPreferences.showPageNumber.collectAsState()
        val autoTranslateEnabled by readerPreferences.autoTranslate.collectAsState()
        val novelStatusBarEnabled by readerPreferences.novelStatusBarEnabled.collectAsState()
        val novelStatusBarShowTime by readerPreferences.novelStatusBarShowTime.collectAsState()
        val novelStatusBarShowBattery by readerPreferences.novelStatusBarShowBattery.collectAsState()
        val novelStatusBarShowChapterNumber by readerPreferences.novelStatusBarShowChapterNumber.collectAsState()
        val novelStatusBarShowChapterTitle by readerPreferences.novelStatusBarShowChapterTitle.collectAsState()
        val novelStatusBarShowProgress by readerPreferences.novelStatusBarShowProgress.collectAsState()
        val novelStatusBarPosition by readerPreferences.novelStatusBarPosition.collectAsState()
        val novelStatusBarSize by readerPreferences.novelStatusBarSize.collectAsState()
        val novelStatusBarShowCharging by readerPreferences.novelStatusBarShowCharging.collectAsState()
        val novelStatusBarOrderRaw by readerPreferences.novelStatusBarOrder.collectAsState()
        val novelTtsControlsActive by readerPreferences.novelTtsControlsVisible.collectAsState()
        val novelTheme by readerPreferences.novelTheme.collectAsState()
        val novelBgColorInt by readerPreferences.novelBackgroundColor.collectAsState()
        val novelFontColorInt by readerPreferences.novelFontColor.collectAsState()
        var statusBarCollapsed by remember { mutableStateOf(false) }
        val density = LocalDensity.current
        var statusBarHeightPx by remember {
            mutableIntStateOf(with(density) { EstimatedStatusBarHeight.roundToPx() })
        }
        val settingsScreenModel = remember {
            ReaderSettingsScreenModel(
                readerState = viewModel.state,
                onChangeReadingMode = viewModel::setMangaReadingMode,
                onChangeOrientation = viewModel::setMangaOrientationType,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val isNovelMode = state.viewer is NovelViewer || state.viewer is NovelWebViewViewer
            if (!state.menuVisible && showPageNumber && !isNovelMode) {
                ReaderPageIndicator(
                    currentPage = state.currentPage,
                    totalPages = state.totalPages,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }

            ContentOverlay(state = state)

            val statusBarAtBottom = novelStatusBarPosition != "top"
            val ttsOverlayBottomPadding = if (
                isNovelMode && novelStatusBarEnabled && statusBarAtBottom && !state.menuVisible
            ) {
                with(density) { statusBarHeightPx.toDp() }
            } else {
                0.dp
            }

            AppBars(state = state, ttsOverlayBottomPadding = ttsOverlayBottomPadding)

            if (isNovelMode && !state.menuVisible && novelStatusBarEnabled) {
                val chapter = state.novelVisibleChapter ?: state.currentChapter?.chapter
                val showChapterSegment = novelStatusBarShowChapterNumber || novelStatusBarShowChapterTitle
                val chapterText: String? = chapter?.takeIf { showChapterSegment }?.let { ch ->
                    val numStr = if (novelStatusBarShowChapterNumber && ch.chapter_number >= 0f) {
                        "Ch. ${formatChapterNumber(ch.chapter_number.toDouble())}"
                    } else {
                        null
                    }
                    val nameStr = if (novelStatusBarShowChapterTitle) ch.name.ifEmpty { null } else null
                    when {
                        numStr != null && nameStr != null -> "$numStr: $nameStr"
                        numStr != null -> numStr
                        else -> nameStr
                    }
                }
                val (bgInt, textInt) = remember(novelTheme, novelBgColorInt, novelFontColorInt) {
                    ThemeUtils.getThemeColors(this@ReaderActivity, readerPreferences, novelTheme)
                }
                val readerBgColor = ComposeColor(bgInt)
                val readerTextColor = ComposeColor(textInt)
                val statusBarOrder = remember(novelStatusBarOrderRaw) {
                    novelStatusBarOrderRaw.deserializeStatusBarOrder()
                }
                NovelStatusBar(
                    chapterText = chapterText,
                    progressPercent = state.novelProgressPercent,
                    order = statusBarOrder,
                    showTime = novelStatusBarShowTime,
                    showChapter = showChapterSegment,
                    showProgress = novelStatusBarShowProgress,
                    showBattery = novelStatusBarShowBattery,
                    showCharging = novelStatusBarShowCharging,
                    backgroundColor = readerBgColor,
                    textColor = readerTextColor,
                    isCollapsed = statusBarCollapsed,
                    onToggleCollapse = { statusBarCollapsed = !statusBarCollapsed },
                    size = novelStatusBarSize,
                    onHeightChanged = { statusBarHeightPx = it },
                    modifier = Modifier
                        .align(if (statusBarAtBottom) Alignment.BottomCenter else Alignment.TopCenter)
                        .then(if (statusBarAtBottom) Modifier else Modifier.statusBarsPadding()),
                )
            }
        }

        val onDismissRequest = viewModel::closeDialog
        when (state.dialog) {
            is ReaderViewModel.Dialog.Loading -> {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(MR.strings.loading))
                        }
                    },
                )
            }
            is ReaderViewModel.Dialog.Settings -> {
                ReaderSettingsDialog(
                    onDismissRequest = onDismissRequest,
                    onShowMenus = { setMenuVisibility(true) },
                    onHideMenus = { setMenuVisibility(false) },
                    screenModel = settingsScreenModel,
                    isNovelMode = state.viewer is NovelViewer || state.viewer is NovelWebViewViewer,
                )
            }
            is ReaderViewModel.Dialog.ReadingModeSelect -> {
                ReadingModeSelectDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        if (!readerPreferences.showReadingMode.get()) {
                            menuToggleToast = toast(stringRes)
                        }
                    },
                )
            }
            is ReaderViewModel.Dialog.OrientationModeSelect -> {
                OrientationSelectDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        menuToggleToast = toast(stringRes)
                    },
                )
            }
            is ReaderViewModel.Dialog.TranslationLanguageSelect -> {
                TranslationLanguageSelectDialog(
                    onDismissRequest = onDismissRequest,
                    currentLanguage = viewModel.getTargetTranslationLanguage(),
                    autoTranslateEnabled = autoTranslateEnabled,
                    onToggleAutoTranslate = { enabled ->
                        readerPreferences.autoTranslate.set(enabled)
                    },
                    onSelectLanguage = { languageCode ->
                        viewModel.setTargetTranslationLanguage(languageCode)
                        // Optionally trigger translation with new language
                        if (!viewModel.state.value.isTranslating) {
                            viewModel.toggleTranslation()
                        } else {
                            // Retrigger translation with new language
                            viewModel.toggleTranslation() // Turn off
                            viewModel.toggleTranslation() // Turn on with new language
                        }
                    },
                )
            }
            is ReaderViewModel.Dialog.PageActions -> {
                ReaderPageActionsDialog(
                    onDismissRequest = onDismissRequest,
                    onSetAsCover = viewModel::setAsCover,
                    onShare = viewModel::shareImage,
                    onSave = viewModel::saveImage,
                )
            }
            null -> {}
        }

        // Quotes sheet
        val quotesState = remember { mutableStateOf(viewModel.getQuotes()) }
        val showQuotesState = remember { mutableStateOf(false) }

        LaunchedEffect(showQuotesSheet) {
            showQuotesState.value = showQuotesSheet
            if (showQuotesSheet) {
                quotesState.value = viewModel.getQuotes()
            }
        }

        // Handle back button when quotes sheet is open
        androidx.activity.compose.BackHandler(enabled = showQuotesSheet) {
            showQuotesSheet = false
        }

        if (showQuotesState.value) {
            QuotesSheet(
                quotes = quotesState.value,
                novelTitle = state.manga?.title.orEmpty(),
                onDismiss = {
                    showQuotesSheet = false
                },
                onQuoteClick = { quote ->
                    logcat(LogPriority.DEBUG) { "Quote clicked: ${quote.content.take(50)}..." }
                },
                onQuoteDelete = { quote ->
                    logcat(LogPriority.DEBUG) { "Quote deleted: ${quote.content.take(50)}..." }
                    viewModel.deleteQuote(quote)
                    quotesState.value = viewModel.getQuotes()
                },
                onQuoteUpdate = { quote ->
                    logcat(LogPriority.DEBUG) { "Quote updated: ${quote.content.take(50)}..." }
                    viewModel.updateQuote(quote)
                    quotesState.value = viewModel.getQuotes()
                },
                onQuoteAdd = { content ->
                    logcat(LogPriority.DEBUG) { "Quote added: ${content.take(50)}..." }
                    viewModel.saveQuote(content, "")
                    quotesState.value = viewModel.getQuotes()
                },
                onQuoteReorder = { reorderedQuotes ->
                    logcat(LogPriority.DEBUG) { "Quotes reordered: ${reorderedQuotes.size} quotes" }
                    viewModel.reorderQuotes(reorderedQuotes)
                    quotesState.value = viewModel.getQuotes()
                },
            )
        }
    }

    /**
     * Called when the activity is destroyed. Cleans up the viewer, configuration and any view.
     */
    override fun onDestroy() {
        stopBackgroundTtsIfRunning()
        ttsNotificationSyncJob?.cancel()
        unregisterReceiver(ttsNotificationControlReceiver)
        super.onDestroy()
        viewModel.state.value.viewer?.destroy()
        config = null
        menuToggleToast?.cancel()
        readingModeToast?.cancel()
    }

    override fun onPause() {
        when (val viewer = viewModel.state.value.viewer) {
            is NovelViewer -> viewer.flushProgress()
            is NovelWebViewViewer -> viewer.flushProgress()
            else -> {}
        }

        lifecycleScope.launchNonCancellable {
            viewModel.updateHistory()
        }

        if (!isChangingConfigurations && !readerPreferences.novelTtsBackgroundPlayback.get()) {
            stopAnyActiveNovelTts()
            stopBackgroundTtsIfRunning()
        }

        super.onPause()
    }

    /**
     * Set menu visibility again on activity resume to apply immersive mode again if needed.
     * Helps with rotations.
     */
    override fun onResume() {
        super.onResume()
        viewModel.restartReadTimer()
        setMenuVisibility(viewModel.state.value.menuVisible)
        if (readerPreferences.novelTtsBackgroundPlayback.get() &&
            currentNovelTtsState()?.active == true
        ) {
            startTtsNotificationSync()
            syncBackgroundTtsState()
        }
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply immersive mode again if needed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setMenuVisibility(viewModel.state.value.menuVisible)
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    /**
     * Called when the user clicks the back key or the button on the toolbar. The call is
     * delegated to the presenter.
     */
    override fun finish() {
        viewModel.onActivityFinish()
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_N) {
            loadNextChapter()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_P) {
            loadPreviousChapter()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = viewModel.state.value.viewer?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = viewModel.state.value.viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    @Composable
    private fun ContentOverlay(state: ReaderViewModel.State) {
        val flashOnPageChange by readerPreferences.flashOnPageChange.collectAsState()

        val colorOverlayEnabled by readerPreferences.colorFilter.collectAsState()
        val colorOverlay by readerPreferences.colorFilterValue.collectAsState()
        val colorOverlayMode by readerPreferences.colorFilterMode.collectAsState()
        val colorOverlayBlendMode = remember(colorOverlayMode) {
            ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
        }

        ReaderContentOverlay(
            brightness = state.brightnessOverlayValue,
            color = colorOverlay.takeIf { colorOverlayEnabled },
            colorBlendMode = colorOverlayBlendMode,
        )

        if (flashOnPageChange) {
            DisplayRefreshHost(hostState = displayRefreshHost)
        }
    }

    @Composable
    fun AppBars(state: ReaderViewModel.State, ttsOverlayBottomPadding: Dp = 0.dp) {
        if (!ifSourcesLoaded()) {
            return
        }

        val source = viewModel.getSource()
        val hasWebViewSupport = source is HttpSource || source is JsSource
        val isNovelViewer = state.viewer is NovelViewer || state.viewer is NovelWebViewViewer

        if (isNovelViewer) {
            var isAutoScrolling by remember { mutableStateOf(false) }

            // Get common callbacks that work for both viewer types
            val onScrollToTop: () -> Unit = {
                when (val viewer = state.viewer) {
                    is NovelViewer -> viewer.scrollToTop()
                    is NovelWebViewViewer -> viewer.scrollToTop()
                }
            }
            val onToggleAutoScroll: () -> Unit = {
                when (val viewer = state.viewer) {
                    is NovelViewer -> {
                        viewer.toggleAutoScroll()
                        isAutoScrolling = viewer.isAutoScrollActive()
                    }
                    is NovelWebViewViewer -> {
                        viewer.toggleAutoScroll()
                        isAutoScrolling = viewer.isAutoScrollActive()
                    }
                }
            }

            // Get novel progress for slider - use state from ViewModel for real-time updates
            val showProgressSlider by readerPreferences.novelShowProgressSlider.collectAsState()
            val showVerticalProgressSlider by readerPreferences.novelVerticalScrollbar.collectAsState()
            val verticalProgressSliderPosition by readerPreferences.novelVerticalScrollbarPosition.collectAsState()
            val verticalProgressSliderSize by readerPreferences.novelVerticalProgressSliderSize.collectAsState()
            val progressSliderMode = when {
                !showProgressSlider -> "none"
                showVerticalProgressSlider && verticalProgressSliderPosition == "left" -> "vertical_left"
                showVerticalProgressSlider && verticalProgressSliderPosition == "right" -> "vertical_right"
                else -> "horizontal"
            }

            // Use state.novelProgressPercent for slider value, which is updated via onNovelProgressChanged callback
            val novelProgressFromState = state.novelProgressPercent

            var isTtsActive by remember { mutableStateOf(false) }
            var isTtsPaused by remember { mutableStateOf(false) }
            var ttsControlsVisible by remember { mutableStateOf(readerPreferences.novelTtsControlsVisible.get()) }
            // Re-sync the pause/play button on menu open and chapter change. Chapter nav
            // stops TTS without a button tap, so key on chapter id to reset it.
            LaunchedEffect(state.menuVisible, state.novelVisibleChapter?.id) {
                if (!state.menuVisible) return@LaunchedEffect
                val viewer = state.viewer
                isTtsActive = when (viewer) {
                    is NovelViewer -> viewer.isTtsActive()
                    is NovelWebViewViewer -> viewer.isTtsActive()
                    else -> false
                }
                isTtsPaused = when (viewer) {
                    is NovelViewer -> viewer.isTtsPaused()
                    is NovelWebViewViewer -> viewer.isTtsPaused()
                    else -> false
                }
            }

            // Also sync from viewer when menu becomes visible (for initial sync)
            LaunchedEffect(state.menuVisible) {
                if (state.menuVisible) {
                    val viewer = state.viewer
                    if (viewer is NovelViewer) {
                        viewModel.updateNovelProgressPercent(viewer.getProgressPercent())
                    } else if (viewer is NovelWebViewViewer) {
                        viewModel.updateNovelProgressPercent(viewer.getProgressPercent())
                    }
                }
            }

            // Format chapter title based on preference
            val chapterTitleDisplay by readerPreferences.novelChapterTitleDisplay.collectAsState()
            val formattedChapterTitle = remember(state.currentChapter, state.novelVisibleChapter, chapterTitleDisplay) {
                val chapter = state.novelVisibleChapter ?: state.currentChapter?.chapter
                if (chapter == null) {
                    null
                } else {
                    when (chapterTitleDisplay) {
                        1 -> { // Number only
                            if (chapter.chapter_number >= 0) {
                                "Chapter ${chapter.chapter_number.let {
                                    if (it == it.toLong().toFloat()) it.toLong().toString() else it.toString()
                                }}"
                            } else {
                                chapter.name // Fallback to name if no valid number
                            }
                        }
                        2 -> { // Both name and number
                            if (chapter.chapter_number >= 0) {
                                val numStr = chapter.chapter_number.let {
                                    if (it ==
                                        it.toLong().toFloat()
                                    ) {
                                        it.toLong().toString()
                                    } else {
                                        it.toString()
                                    }
                                }
                                "Ch. $numStr: ${chapter.name}"
                            } else {
                                chapter.name // Fallback to name if no valid number
                            }
                        }
                        else -> chapter.name // 0 = Name only (default)
                    }
                }
            }

            val bottomBarItems by viewModel.bottomBarItems.collectAsState()
            var showBottomBarEditor by remember { mutableStateOf(false) }
            var showEditSaveDialog by remember { mutableStateOf(false) }
            var isEditing by remember { mutableStateOf(false) }

            NovelReaderAppBars(
                visible = state.menuVisible,

                novelTitle = state.manga?.title,
                chapterTitle = formattedChapterTitle,
                navigateUp = onBackPressedDispatcher::onBackPressed,
                onClickTopAppBar = ::openMangaScreen,
                bookmarked = state.bookmarked,
                onToggleBookmarked = viewModel::toggleChapterBookmark,
                onOpenInWebView = ::openChapterInWebView.takeIf { hasWebViewSupport },
                onOpenInBrowser = ::openChapterInBrowser.takeIf { hasWebViewSupport },
                onShare = ::shareChapter.takeIf { hasWebViewSupport },
                onReloadLocal = { viewModel.reloadChapter(fromSource = false) },
                onReloadSource = { viewModel.reloadChapter(fromSource = true) },
                onEditBottomBar = { showBottomBarEditor = true },

                showProgressSlider = showProgressSlider,
                progressSliderMode = progressSliderMode,
                verticalProgressSliderSize = verticalProgressSliderSize,
                currentProgress = novelProgressFromState,
                onProgressChange = { newProgress ->
                    viewModel.updateNovelProgressPercent(newProgress)
                    val viewer = state.viewer
                    if (viewer is NovelViewer) {
                        viewer.setProgressPercent(newProgress)
                    } else if (viewer is NovelWebViewViewer) {
                        viewer.setProgressPercent(newProgress)
                    }
                },

                onNextChapter = {
                    loadNextChapter()
                    // Sync slider after navigation
                    lifecycleScope.launch {
                        delay(100)
                        val viewer = viewModel.state.value.viewer
                        val progress = when (viewer) {
                            is NovelViewer -> viewer.getProgressPercent()
                            is NovelWebViewViewer -> viewer.getProgressPercent()
                            else -> 0
                        }
                        viewModel.updateNovelProgressPercent(progress)
                    }
                },
                enabledNext = state.viewerChapters?.nextChapter != null,
                onPreviousChapter = {
                    loadPreviousChapter()
                    // Sync slider after navigation
                    lifecycleScope.launch {
                        delay(100)
                        val viewer = viewModel.state.value.viewer
                        val progress = when (viewer) {
                            is NovelViewer -> viewer.getProgressPercent()
                            is NovelWebViewViewer -> viewer.getProgressPercent()
                            else -> 0
                        }
                        viewModel.updateNovelProgressPercent(progress)
                    }
                },
                enabledPrevious = state.viewerChapters?.prevChapter != null,

                orientation = ReaderOrientation.fromPreference(
                    viewModel.getMangaOrientation(resolveDefault = false),
                ),
                onClickOrientation = viewModel::openOrientationModeSelectDialog,
                onClickSettings = viewModel::openSettingsDialog,
                onScrollToTop = onScrollToTop,
                isAutoScrolling = isAutoScrolling,
                onToggleAutoScroll = onToggleAutoScroll,
                isTranslating = state.isTranslating,
                onToggleTranslation = viewModel::toggleTranslation,
                onLongPressTranslation = viewModel::openTranslationLanguageDialog,
                onRetranslate = if (state.isTranslating) viewModel::retranslateCurrentChapter else null,
                isTtsActive = isTtsActive,
                isTtsPaused = isTtsPaused,
                ttsControlsVisible = ttsControlsVisible,
                onToggleTtsControls = {
                    val nowVisible = !ttsControlsVisible
                    ttsControlsVisible = nowVisible
                    readerPreferences.novelTtsControlsVisible.set(nowVisible)
                    val viewer = state.viewer
                    if (nowVisible) {
                        if (!isTtsActive && readerPreferences.novelTtsAutoStartOnPanelOpen.get()) {
                            when (viewer) {
                                is NovelViewer -> {
                                    startBackgroundTtsIfEnabled()
                                    viewer.startTts()
                                    isTtsActive = true
                                    isTtsPaused = false
                                    syncBackgroundTtsState()
                                }
                                is NovelWebViewViewer -> {
                                    startBackgroundTtsIfEnabled()
                                    viewer.startTts()
                                    isTtsActive = true
                                    isTtsPaused = false
                                    syncBackgroundTtsState()
                                }
                                else -> {}
                            }
                        }
                    } else {
                        when (viewer) {
                            is NovelViewer -> {
                                stopBackgroundTtsIfRunning()
                                viewer.stopTts()
                                isTtsActive = false
                                isTtsPaused = false
                                stopTtsNotificationSync()
                            }
                            is NovelWebViewViewer -> {
                                stopBackgroundTtsIfRunning()
                                viewer.stopTts()
                                isTtsActive = false
                                isTtsPaused = false
                                stopTtsNotificationSync()
                            }
                            else -> {}
                        }
                    }
                },
                onToggleTts = {
                    val viewer = state.viewer
                    when (viewer) {
                        is NovelViewer -> {
                            if (viewer.isTtsSpeaking()) {
                                viewer.pauseTts()
                                isTtsPaused = true
                                syncBackgroundTtsState()
                            } else if (viewer.isTtsPaused()) {
                                viewer.resumeTts()
                                isTtsPaused = false
                                startBackgroundTtsIfEnabled()
                                syncBackgroundTtsState()
                            } else {
                                startBackgroundTtsIfEnabled()
                                viewer.startTts()
                                isTtsActive = true
                                isTtsPaused = false
                                syncBackgroundTtsState()
                            }
                        }
                        is NovelWebViewViewer -> {
                            if (viewer.isTtsSpeaking()) {
                                viewer.pauseTts()
                                isTtsPaused = true
                                syncBackgroundTtsState()
                            } else if (viewer.isTtsPaused()) {
                                viewer.resumeTts()
                                isTtsPaused = false
                                startBackgroundTtsIfEnabled()
                                syncBackgroundTtsState()
                            } else {
                                startBackgroundTtsIfEnabled()
                                viewer.startTts()
                                isTtsActive = true
                                isTtsPaused = false
                                syncBackgroundTtsState()
                            }
                        }
                        else -> {}
                    }
                },
                onLongPressTts = {
                    // Force stop without hiding panel
                    val viewer = state.viewer
                    when (viewer) {
                        is NovelViewer -> {
                            stopBackgroundTtsIfRunning()
                            viewer.stopTts()
                            isTtsActive = false
                            isTtsPaused = false
                            stopTtsNotificationSync()
                        }
                        is NovelWebViewViewer -> {
                            stopBackgroundTtsIfRunning()
                            viewer.stopTts()
                            isTtsActive = false
                            isTtsPaused = false
                            stopTtsNotificationSync()
                        }
                        else -> {}
                    }
                },
                onTtsStartFromViewport = {
                    val viewer = state.viewer
                    when (viewer) {
                        is NovelViewer -> {
                            startBackgroundTtsIfEnabled()
                            viewer.startTtsFromViewport()
                            isTtsActive = true
                            isTtsPaused = false
                            syncBackgroundTtsState()
                        }
                        is NovelWebViewViewer -> {
                            startBackgroundTtsIfEnabled()
                            viewer.startTtsFromViewport()
                            isTtsActive = true
                            isTtsPaused = false
                            syncBackgroundTtsState()
                        }
                        else -> {}
                    }
                },
                onTtsPreviousParagraph = { stepTtsParagraph(isNext = false) },
                onTtsNextParagraph = { stepTtsParagraph(isNext = true) },

                isEditing = isEditing,
                onToggleEdit = {
                    val viewer = state.viewer
                    if (isEditing) {
                        if (state.hasUnsavedChanges) {
                            showEditSaveDialog = true
                        } else {
                            isEditing = false
                            (viewer as? NovelWebViewViewer)?.toggleEditMode(isEditing = false, save = false)
                        }
                    } else {
                        isEditing = true
                        if (viewer is NovelWebViewViewer) {
                            viewer.toggleEditMode(true)
                        }
                    }
                },

                isWebView = state.viewer is NovelWebViewViewer,
                bottomBarItems = bottomBarItems,
                onQuotes = ::onQuotesClicked,
                ttsOverlayBottomPadding = ttsOverlayBottomPadding,
            )

            androidx.activity.compose.BackHandler(enabled = isEditing && state.hasUnsavedChanges) {
                showEditSaveDialog = true
            }

            if (showEditSaveDialog) {
                AlertDialog(
                    onDismissRequest = { showEditSaveDialog = false },
                    title = {
                        Text(
                            tachiyomi.presentation.core.i18n.stringResource(
                                tachiyomi.i18n.novel.TDMR.strings.prompt_save_changes,
                            ),
                        )
                    },
                    text = {
                        Text(
                            tachiyomi.presentation.core.i18n.stringResource(
                                tachiyomi.i18n.novel.TDMR.strings.prompt_save_changes_message,
                            ),
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showEditSaveDialog = false
                            isEditing = false
                            (state.viewer as? NovelWebViewViewer)?.toggleEditMode(isEditing = false, save = true)
                        }) {
                            Text(tachiyomi.presentation.core.i18n.stringResource(MR.strings.action_save))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showEditSaveDialog = false
                            isEditing = false
                            (state.viewer as? NovelWebViewViewer)?.toggleEditMode(isEditing = false, save = false)
                        }) {
                            Text(
                                tachiyomi.presentation.core.i18n.stringResource(
                                    tachiyomi.i18n.novel.TDMR.strings.action_discard,
                                ),
                            )
                        }
                    },
                )
            }

            if (showBottomBarEditor) {
                val legacyTtsItems = setOf(
                    BottomBarItem.TTS_PREV_PARAGRAPH,
                    BottomBarItem.TTS_NEXT_PARAGRAPH,
                    BottomBarItem.TTS_VIEWPORT,
                )
                BottomBarEditorSheet(
                    items = bottomBarItems.filter { it.item !in legacyTtsItems },
                    onItemsChange = { viewModel.saveBottomBarItems(it) },
                    onDismiss = { showBottomBarEditor = false },
                    itemInfo = { item ->
                        bottomBarItemInfo(
                            item = item,
                            orientation = ReaderOrientation.fromPreference(
                                viewModel.getMangaOrientation(resolveDefault = false),
                            ),
                            isAutoScrolling = isAutoScrolling,
                            isTtsActive = isTtsActive,
                            isTtsPaused = isTtsPaused,
                        )
                    },
                )
            }
        } else {
            val cropBorderPaged by readerPreferences.cropBorders.collectAsState()
            val cropBorderWebtoon by readerPreferences.cropBordersWebtoon.collectAsState()
            val isPagerType = ReadingMode.isPagerType(viewModel.getMangaReadingMode())
            val cropEnabled = if (isPagerType) cropBorderPaged else cropBorderWebtoon
            val verticalNavigatorForLongStrip by readerPreferences.verticalNavigatorForLongStrip.collectAsState()
            val verticalNavigatorOnLeft by readerPreferences.verticalNavigatorOnLeft.collectAsState()

            ReaderAppBars(
                visible = state.menuVisible,

                mangaTitle = state.manga?.title,
                chapterTitle = state.currentChapter?.chapter?.name,
                navigateUp = onBackPressedDispatcher::onBackPressed,
                onClickTopAppBar = ::openMangaScreen,
                bookmarked = state.bookmarked,
                onToggleBookmarked = viewModel::toggleChapterBookmark,
                onOpenInWebView = ::openChapterInWebView.takeIf { hasWebViewSupport },
                onOpenInBrowser = ::openChapterInBrowser.takeIf { hasWebViewSupport },
                onShare = ::shareChapter.takeIf { hasWebViewSupport },

                chapterNavigatorType = if (isPagerType || !verticalNavigatorForLongStrip) {
                    if (state.viewer is R2LPagerViewer) {
                        ChapterNavigatorType.HORIZONTAL_RTL
                    } else {
                        ChapterNavigatorType.HORIZONTAL_LTR
                    }
                } else {
                    if (verticalNavigatorOnLeft) {
                        ChapterNavigatorType.VERTICAL_LEFT
                    } else {
                        ChapterNavigatorType.VERTICAL_RIGHT
                    }
                },
                onNextChapter = ::loadNextChapter,
                enabledNext = state.viewerChapters?.nextChapter != null,
                onPreviousChapter = ::loadPreviousChapter,
                enabledPrevious = state.viewerChapters?.prevChapter != null,
                currentPage = state.currentPage,
                totalPages = state.totalPages,
                onPageIndexChange = {
                    isScrollingThroughPages = true
                    moveToPageIndex(it)
                },

                readingMode = ReadingMode.fromPreference(
                    viewModel.getMangaReadingMode(resolveDefault = false),
                ),
                onClickReadingMode = viewModel::openReadingModeSelectDialog,
                orientation = ReaderOrientation.fromPreference(
                    viewModel.getMangaOrientation(resolveDefault = false),
                ),
                onClickOrientation = viewModel::openOrientationModeSelectDialog,
                cropEnabled = cropEnabled,
                onClickCropBorder = {
                    val enabled = viewModel.toggleCropBorders()
                    menuToggleToast?.cancel()
                    menuToggleToast = toast(if (enabled) MR.strings.on else MR.strings.off)
                },
                onClickSettings = viewModel::openSettingsDialog,
            )
        }
    }

    /**
     * Sets the visibility of the menu according to [visible].
     */
    private fun setMenuVisibility(visible: Boolean) {
        viewModel.showMenus(visible)
        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else if (readerPreferences.fullscreen.get()) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun startBackgroundTtsIfEnabled() {
        if (readerPreferences.novelTtsBackgroundPlayback.get()) {
            // No placeholder notification: the caller's syncBackgroundTtsState() starts
            // the service with the real novel/chapter title.
            startTtsNotificationSync()
        }
    }

    private fun stopBackgroundTtsIfRunning() {
        TtsPlaybackService.stop(this)
        stopTtsNotificationSync()
    }

    private fun syncBackgroundTtsState() {
        if (!readerPreferences.novelTtsBackgroundPlayback.get()) {
            stopBackgroundTtsIfRunning()
            return
        }

        val state = currentNovelTtsState() ?: return
        if (!state.active) {
            TtsPlaybackService.stop(this)
            stopTtsNotificationSync()
            return
        }

        TtsPlaybackService.syncState(
            context = this,
            isPaused = state.paused,
            progressPercent = state.progressPercent,
            novelTitle = state.novelTitle,
            chapterTitle = state.chapterTitle,
            mangaId = state.mangaId,
            chapterId = state.chapterId,
        )
    }

    private fun startTtsNotificationSync() {
        ttsNotificationSyncJob?.cancel()
        ttsNotificationSyncJob = lifecycleScope.launch {
            // First pass runs before the caller sets TTS state. Don't stop the service
            // until TTS has been active once: stopping it before startForeground() crashes
            // with ForegroundServiceDidNotStartInTimeException.
            var ttsWasActive = false
            while (isActive) {
                if (currentNovelTtsState()?.active == true) ttsWasActive = true
                if (ttsWasActive) syncBackgroundTtsState()
                delay(750)
            }
        }
    }

    private fun stopTtsNotificationSync() {
        ttsNotificationSyncJob?.cancel()
        ttsNotificationSyncJob = null
    }

    private data class NovelTtsState(
        val active: Boolean,
        val paused: Boolean,
        val progressPercent: Int,
        val novelTitle: String,
        val chapterTitle: String,
        val mangaId: Long,
        val chapterId: Long,
    )

    private fun currentNovelTtsState(): NovelTtsState? {
        val readerState = viewModel.state.value
        val novelTitle = readerState.manga?.title.orEmpty().ifBlank { "TTS playback" }
        val chapterTitle = readerState.novelVisibleChapter?.name ?: readerState.currentChapter?.chapter?.name.orEmpty()
        val mangaId = readerState.manga?.id ?: -1L
        val chapterId = readerState.currentChapter?.chapter?.id ?: -1L

        return when (val viewer = viewModel.state.value.viewer) {
            is NovelViewer -> NovelTtsState(
                // Use isTtsActive() (covers the autoPlay flag) so the brief
                // gap inside stepParagraph (stop → speakChunksFrom) doesn't
                // make the periodic sync drop the foreground service.
                active = viewer.isTtsActive(),
                paused = viewer.isTtsPaused(),
                progressPercent = viewer.getTtsProgressPercent(),
                novelTitle = novelTitle,
                chapterTitle = chapterTitle,
                mangaId = mangaId,
                chapterId = chapterId,
            )
            is NovelWebViewViewer -> NovelTtsState(
                active = viewer.isTtsActive(),
                paused = viewer.isTtsPaused(),
                progressPercent = viewer.getTtsProgressPercent(),
                novelTitle = novelTitle,
                chapterTitle = chapterTitle,
                mangaId = mangaId,
                chapterId = chapterId,
            )
            else -> null
        }
    }

    private fun stopAnyActiveNovelTts() {
        when (val viewer = viewModel.state.value.viewer) {
            is NovelViewer -> if (viewer.isTtsSpeaking() || viewer.isTtsPaused()) viewer.stopTts()
            is NovelWebViewViewer -> if (viewer.isTtsSpeaking() || viewer.isTtsPaused()) viewer.stopTts()
            else -> Unit
        }
    }

    private fun togglePauseResumeFromNotification() {
        when (val viewer = viewModel.state.value.viewer) {
            is NovelViewer -> {
                if (viewer.isTtsSpeaking()) {
                    viewer.pauseTts()
                } else if (viewer.isTtsPaused()) {
                    viewer.resumeTts()
                }
            }
            is NovelWebViewViewer -> {
                if (viewer.isTtsSpeaking()) {
                    viewer.pauseTts()
                } else if (viewer.isTtsPaused()) {
                    viewer.resumeTts()
                }
            }
            else -> Unit
        }
        syncBackgroundTtsState()
    }

    private fun stepTtsParagraph(isNext: Boolean) {
        val step: (() -> Unit)? = when (val viewer = viewModel.state.value.viewer) {
            is NovelViewer -> if (isNext) viewer::ttsNextParagraph else viewer::ttsPreviousParagraph
            is NovelWebViewViewer -> if (isNext) viewer::ttsNextParagraph else viewer::ttsPreviousParagraph
            else -> null
        }
        step ?: return
        startBackgroundTtsIfEnabled()
        step()
        syncBackgroundTtsState()
    }

    private fun stopTtsFromNotification() {
        stopAnyActiveNovelTts()
        stopBackgroundTtsIfRunning()
    }

    /**
     * Called from the presenter when a manga is ready. Used to instantiate the appropriate viewer.
     */
    private fun updateViewer() {
        val prevViewer = viewModel.state.value.viewer
        val newViewer = ReadingMode.toViewer(viewModel.getMangaReadingMode(), this)

        if (window.sharedElementEnterTransition is MaterialContainerTransform) {
            // Wait until transition is complete to avoid crash on API 26
            window.sharedElementEnterTransition.doOnEnd {
                setOrientation(viewModel.getMangaOrientation())
            }
        } else {
            setOrientation(viewModel.getMangaOrientation())
        }

        // Destroy previous viewer if there was one
        if (prevViewer != null) {
            prevViewer.destroy()
            binding.viewerContainer.removeAllViews()
        }
        viewModel.onViewerLoaded(newViewer)
        updateViewerInset(readerPreferences.fullscreen.get(), readerPreferences.drawUnderCutout.get())
        binding.viewerContainer.addView(newViewer.getView())

        if (readerPreferences.showReadingMode.get()) {
            showReadingModeToast(viewModel.getMangaReadingMode())
        }

        loadingIndicator = ReaderProgressIndicator(this)
        binding.readerContainer.addView(loadingIndicator)

        startPostponedEnterTransition()
    }

    private fun openMangaScreen() {
        viewModel.manga?.id?.let { id ->
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_MANGA
                    putExtra(Constants.MANGA_EXTRA, id)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    private fun openChapterInWebView() {
        val manga = viewModel.manga ?: return
        assistUrl?.let {
            val intent = WebViewActivity.newIntent(this@ReaderActivity, it, viewModel.getSource()?.id, manga.title)
            startActivity(intent)
        }
    }

    private fun openChapterInBrowser() {
        assistUrl?.let {
            openInBrowser(it.toUri(), forceDefaultBrowser = false)
        }
    }

    private fun shareChapter() {
        assistUrl?.let {
            val intent = it.toUri().toShareIntent(this, type = "text/plain")
            startActivity(intent)
        }
    }

    private fun showReadingModeToast(mode: Int) {
        try {
            val readingMode = ReadingMode.fromPreference(mode)
            // Skip toast for novel mode - it's obvious we're reading text
            if (readingMode == ReadingMode.NOVEL) return

            readingModeToast?.cancel()
            readingModeToast = toast(readingMode.stringRes)
        } catch (_: ArrayIndexOutOfBoundsException) {
            logcat(LogPriority.ERROR) { "Unknown reading mode: $mode" }
        }
    }

    /**
     * Called from the presenter whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the toolbar, and
     * hides or disables the reader prev/next buttons if there's a prev or next chapter
     */
    @SuppressLint("RestrictedApi")
    private fun setChapters(viewerChapters: ViewerChapters) {
        binding.readerContainer.removeView(loadingIndicator)
        viewModel.state.value.viewer?.setChapters(viewerChapters)

        lifecycleScope.launchIO {
            viewModel.getChapterUrl()?.let { url ->
                assistUrl = url
            }
        }
    }

    /**
     * Called from the presenter if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialChapterError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        finish()
        toast(error.message)
    }

    /**
     * Called from the presenter whenever it's loading the next or previous chapter. It shows or
     * dismisses a non-cancellable dialog to prevent user interaction according to the value of
     * [show]. This is only used when the next/previous buttons on the toolbar are clicked; the
     * other cases are handled with chapter transitions on the viewers and chapter preloading.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun setProgressDialog(show: Boolean) {
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    private fun moveToPageIndex(index: Int) {
        val viewer = viewModel.state.value.viewer ?: return
        val currentChapter = viewModel.state.value.currentChapter ?: return
        val page = currentChapter.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page)
    }

    /**
     * Tells the presenter to load the next chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    internal fun loadNextChapter() {
        stopNovelTtsForManualNav()
        loadNextChapterInternal()
    }

    /**
     * Loads the next chapter for a TTS auto-advance without stopping TTS. The viewer
     * has set pendingTtsAutoStart and needs it to survive the chapter swap so playback
     * resumes; [loadNextChapter] would clear it via [stopNovelTtsForManualNav].
     */
    internal fun loadNextChapterForTtsHandoff() {
        loadNextChapterInternal()
    }

    private fun loadNextChapterInternal() {
        lifecycleScope.launch {
            viewModel.loadNextChapter()
            // Only reset to page 0 if NOT using infinite scroll for novel viewers
            val isNovelViewer = viewModel.state.value.viewer is NovelViewer ||
                viewModel.state.value.viewer is NovelWebViewViewer
            val infiniteScrollEnabled = readerPreferences.novelInfiniteScroll.get()
            if (!(isNovelViewer && infiniteScrollEnabled)) {
                moveToPageIndex(0)
            }
        }
    }

    /**
     * Tells the presenter to load the previous chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    internal fun loadPreviousChapter() {
        stopNovelTtsForManualNav()
        lifecycleScope.launch {
            viewModel.loadPreviousChapter()
            // Only reset to page 0 if NOT using infinite scroll for novel viewers
            val isNovelViewer = viewModel.state.value.viewer is NovelViewer ||
                viewModel.state.value.viewer is NovelWebViewViewer
            val infiniteScrollEnabled = readerPreferences.novelInfiniteScroll.get()
            if (!(isNovelViewer && infiniteScrollEnabled)) {
                moveToPageIndex(0)
            }
        }
    }

    /**
     * Stops any in-flight TTS session before a user-driven prev/next chapter
     * navigation. TTS-internal handoffs (auto-advance) go through
     * `loadNextChapterForTts` instead of this code path, so it's safe to
     * unconditionally cut TTS here without disturbing automatic advancement.
     */
    private fun stopNovelTtsForManualNav() {
        when (val viewer = viewModel.state.value.viewer) {
            is NovelViewer -> viewer.stopTts()
            is NovelWebViewViewer -> viewer.stopTts()
            else -> {}
        }
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    fun onPageSelected(page: ReaderPage) {
        viewModel.onPageSelected(page)
    }

    /**
     * Called from the novel viewer to save reading progress with a percentage.
     * Progress is stored as percentage (0-100) in last_page_read.
     */
    fun saveNovelProgress(page: ReaderPage, progressPercentage: Int) {
        viewModel.saveNovelProgress(page, progressPercentage)
    }

    /**
     * Called from the novel viewer when scroll progress changes.
     * Updates the progress slider in real-time.
     */
    fun onNovelProgressChanged(progress: Float) {
        val percentage = (progress * 100).roundToInt().coerceIn(0, 100)
        viewModel.updateNovelProgressPercent(percentage)
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(page: ReaderPage) {
        viewModel.openPageDialog(page)
    }

    /**
     * Called from the viewer when the given [chapter] should be preloaded. It should be called when
     * the viewer is reaching the beginning or end of a chapter or the transition page is active.
     */
    fun requestPreloadChapter(chapter: ReaderChapter) {
        lifecycleScope.launchIO { viewModel.preload(chapter) }
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!viewModel.state.value.menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!viewModel.state.value.menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Called from the viewer to hide the menu.
     */
    fun hideMenu() {
        if (viewModel.state.value.menuVisible) {
            setMenuVisibility(false)
        }
    }

    /**
     * Check if translation mode is currently enabled.
     */
    fun isTranslationEnabled(): Boolean {
        return viewModel.state.value.isTranslating
    }

    /** Whether a cached translation exists for [chapterId]; viewers use it to pick the loading label. */
    suspend fun hasCachedTranslation(chapterId: Long?): Boolean {
        if (chapterId == null || !isTranslationEnabled()) return false
        return try {
            viewModel.hasCachedTranslation(chapterId)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "hasCachedTranslation lookup failed" }
            false
        }
    }

    /**
     * Reload content with current translation state.
     * Called when translation is toggled to re-render existing content.
     */
    private fun reloadContentWithTranslation() {
        val state = viewModel.state.value
        val viewer = state.viewer

        when (viewer) {
            is NovelViewer -> viewer.reloadWithTranslation()
            is NovelWebViewViewer -> viewer.reloadWithTranslation()
            else -> {
                // For other viewers, just reload chapters
                state.viewerChapters?.let(::setChapters)
            }
        }
    }

    /**
     * Translate text content using the translation service.
     * Returns translated text if translation is enabled and successful,
     * otherwise returns original text.
     */
    suspend fun translateContentIfEnabled(content: String, chapterId: Long? = null): String {
        if (!isTranslationEnabled()) return content
        return try {
            viewModel.translateContent(content, chapterId)
        } catch (e: CancellationException) {
            logcat(LogPriority.DEBUG) { "Translation was cancelled" }
            content
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Translation failed" }
            runOnUiThread {
                viewModel.disableTranslation()
                toast(e.message ?: "Translation failed")
            }
            content
        }
    }

    /**
     * Called from the presenter when a page is ready to be shared. It shows Android's default
     * sharing tool.
     */
    private fun onShareImageResult(uri: Uri, page: ReaderPage) {
        val manga = viewModel.manga ?: return
        val chapter = page.chapter.chapter

        val intent = uri.toShareIntent(
            context = applicationContext,
            message = stringResource(MR.strings.share_page_info, manga.title, chapter.name, page.number),
        )
        startActivity(intent)
    }

    private fun onCopyImageResult(uri: Uri) {
        val clipboardManager = applicationContext.getSystemService<ClipboardManager>() ?: return
        val clipData = ClipData.newUri(applicationContext.contentResolver, "", uri)
        clipboardManager.setPrimaryClip(clipData)
    }

    /**
     * Called from the presenter when a page is saved or fails. It shows a message or logs the
     * event depending on the [result].
     */
    private fun onSaveImageResult(result: ReaderViewModel.SaveImageResult) {
        when (result) {
            is ReaderViewModel.SaveImageResult.Success -> {
                toast(MR.strings.picture_saved)
            }
            is ReaderViewModel.SaveImageResult.Error -> {
                logcat(LogPriority.ERROR, result.error)
            }
        }
    }

    /**
     * Called from the presenter when a page is set as cover or fails. It shows a different message
     * depending on the [result].
     */
    private fun onSetAsCoverResult(result: ReaderViewModel.SetAsCoverResult) {
        toast(
            when (result) {
                Success -> MR.strings.cover_updated
                AddToLibraryFirst -> MR.strings.notification_first_add_to_library
                Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    /**
     * Called when the quotes button is clicked.
     */
    fun onQuotesClicked() {
        showQuotesSheet = true
    }

    /**
     * Called when the "Remember" action is triggered.
     * Gets selected text from the current viewer and adds it as a quote.
     */
    fun onRememberSelectedText() {
        val selectedText = when (val viewer = viewModel.state.value.viewer) {
            is NovelViewer -> viewer.getSelectedText()
            is NovelWebViewViewer -> viewer.pendingSelectedText ?: viewer.getSelectedText()
            else -> null
        }
        val paragraphIndex = when (val viewer = viewModel.state.value.viewer) {
            is NovelViewer -> viewer.getSelectedParagraphIndex()
            is NovelWebViewViewer -> viewer.pendingParagraphIndex
            else -> null
        }
        val chapterName = when (val viewer = viewModel.state.value.viewer) {
            is NovelViewer -> viewer.getCurrentChapterName()
            is NovelWebViewViewer -> viewer.getCurrentChapterName()
            else -> null
        }

        if (selectedText != null && chapterName != null) {
            viewModel.saveQuote(selectedText, chapterName, paragraphIndex)
            // Clear selection after adding quote
            when (val viewer = viewModel.state.value.viewer) {
                is NovelViewer -> viewer.clearTextSelection()
                is NovelWebViewViewer -> {
                    viewer.clearTextSelection()
                    viewer.pendingSelectedText = null
                    viewer.pendingParagraphIndex = null
                }
            }
            toast("Quote saved!")
        } else {
            toast("No text selected")
        }
    }

    /**
     * Forces the user preferred [orientation] on the activity.
     */
    private fun setOrientation(orientation: Int) {
        val newOrientation = ReaderOrientation.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) {
            requestedOrientation = newOrientation.flag
        }
    }

    /**
     * Updates viewer inset depending on fullscreen reader preferences.
     */
    private fun updateViewerInset(fullscreen: Boolean, drawUnderCutout: Boolean) {
        if (!::binding.isInitialized) return
        val view = binding.viewerContainer

        view.applyInsetsPadding(ViewCompat.getRootWindowInsets(view), fullscreen, drawUnderCutout)
        ViewCompat.setOnApplyWindowInsetsListener(view) { view, windowInsets ->
            view.applyInsetsPadding(windowInsets, fullscreen, drawUnderCutout)
            windowInsets
        }
    }

    private fun View.applyInsetsPadding(
        windowInsets: WindowInsetsCompat?,
        fullscreen: Boolean,
        drawUnderCutout: Boolean,
    ) {
        val insets = when {
            !fullscreen -> windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
            !drawUnderCutout -> windowInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
            else -> null
        }
            ?: Insets.NONE

        setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint {
            return Paint().apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        if (grayscale) {
                            setSaturation(0f)
                        }
                        if (invertedColors) {
                            postConcat(
                                ColorMatrix(
                                    floatArrayOf(
                                        -1f, 0f, 0f, 0f, 255f,
                                        0f, -1f, 0f, 0f, 255f,
                                        0f, 0f, -1f, 0f, 255f,
                                        0f, 0f, 0f, 1f, 0f,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }

        private val grayBackgroundColor = Color.rgb(0x20, 0x21, 0x25)

        /*
         * Initializes the reader subscriptions.
         */
        init {
            readerPreferences.readerTheme.changes()
                .onEach { theme ->
                    binding.readerContainer.setBackgroundColor(
                        when (theme) {
                            0 -> Color.WHITE
                            2 -> grayBackgroundColor
                            3 -> automaticBackgroundColor()
                            else -> Color.BLACK
                        },
                    )
                }
                .launchIn(lifecycleScope)

            preferences.displayProfile.changes()
                .onEach { setDisplayProfile(it) }
                .launchIn(lifecycleScope)

            readerPreferences.keepScreenOn.changes()
                .onEach(::setKeepScreenOn)
                .launchIn(lifecycleScope)

            readerPreferences.customBrightness.changes()
                .onEach(::setCustomBrightness)
                .launchIn(lifecycleScope)

            // Novel-specific brightness
            readerPreferences.novelCustomBrightness.changes()
                .onEach(::setNovelCustomBrightness)
                .launchIn(lifecycleScope)

            // Novel-specific keep screen on
            readerPreferences.novelKeepScreenOn.changes()
                .onEach { enabled ->
                    val viewer = viewModel.state.value.viewer
                    if (viewer is NovelViewer || viewer is NovelWebViewViewer) {
                        setKeepScreenOn(enabled)
                    }
                }
                .launchIn(lifecycleScope)

            // Apply novel brightness and keep screen on when viewer changes to a novel viewer
            viewModel.state
                .map { it.viewer }
                .distinctUntilChanged()
                .filterNotNull()
                .onEach { viewer ->
                    if (viewer is NovelViewer || viewer is NovelWebViewViewer) {
                        setNovelCustomBrightness(readerPreferences.novelCustomBrightness.get())
                        setKeepScreenOn(readerPreferences.novelKeepScreenOn.get())
                    } else {
                        // Switch back to manga reader settings for non-novel viewers
                        setKeepScreenOn(readerPreferences.keepScreenOn.get())
                    }
                }
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.grayscale.changes(),
                readerPreferences.invertedColors.changes(),
            ) { grayscale, invertedColors -> grayscale to invertedColors }
                .onEach { (grayscale, invertedColors) ->
                    setLayerPaint(grayscale, invertedColors)
                }
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.fullscreen.changes(),
                readerPreferences.drawUnderCutout.changes(),
            ) { fullscreen, drawUnderCutout -> fullscreen to drawUnderCutout }
                .onEach { (fullscreen, drawUnderCutout) ->
                    updateViewerInset(fullscreen, drawUnderCutout)
                }
                .launchIn(lifecycleScope)

            // Re-create viewer when novel rendering mode changes
            readerPreferences.novelRenderingMode.changes()
                .drop(1) // Skip initial value
                .onEach {
                    val currentViewer = viewModel.state.value.viewer
                    // Only re-create if currently using a novel viewer
                    if (currentViewer is NovelViewer || currentViewer is NovelWebViewViewer) {
                        updateViewer()
                        viewModel.state.value.viewerChapters?.let { chapters ->
                            setChapters(chapters)
                        }
                        // Re-apply brightness for novel viewers
                        setNovelCustomBrightness(readerPreferences.novelCustomBrightness.get())
                    }
                }
                .launchIn(lifecycleScope)
        }

        /**
         * Picks background color for [ReaderActivity] based on light/dark theme preference
         */
        private fun automaticBackgroundColor(): Int {
            return if (baseContext.isNightMode()) {
                grayBackgroundColor
            } else {
                Color.WHITE
            }
        }

        /**
         * Sets the display profile to [path].
         */
        private fun setDisplayProfile(path: String) {
            val file = UniFile.fromUri(baseContext, path.toUri())
            if (file != null && file.exists()) {
                val inputStream = file.openInputStream()
                val outputStream = ByteArrayOutputStream()
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                val data = outputStream.toByteArray()
                SubsamplingScaleImageView.setDisplayProfile(data)
                TachiyomiImageDecoder.displayProfile = data
            }
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        private fun setCustomBrightness(enabled: Boolean) {
            // Skip if using novel viewer with its own brightness setting
            val viewer = viewModel.state.value.viewer
            if (viewer is NovelViewer || viewer is NovelWebViewViewer) {
                return
            }
            if (enabled) {
                readerPreferences.customBrightnessValue.changes()
                    .sample(0.1.seconds)
                    .onEach(::setCustomBrightnessValue)
                    .launchIn(lifecycleScope)
            } else {
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the novel-specific custom brightness overlay according to [enabled].
         */
        private fun setNovelCustomBrightness(enabled: Boolean) {
            // Only apply if using novel viewer
            val viewer = viewModel.state.value.viewer
            if (viewer !is NovelViewer && viewer !is NovelWebViewViewer) {
                return
            }
            if (enabled) {
                readerPreferences.novelCustomBrightnessValue.changes()
                    .sample(100)
                    .onEach(::setCustomBrightnessValue)
                    .launchIn(lifecycleScope)
            } else {
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            // Calculate and set reader brightness.
            val readerBrightness = when {
                value > 0 -> {
                    value / 100f
                }
                value < 0 -> {
                    0.01f
                }
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

            viewModel.setBrightnessOverlayValue(value)
        }
        private fun setLayerPaint(grayscale: Boolean, invertedColors: Boolean) {
            val paint = if (grayscale || invertedColors) getCombinedPaint(grayscale, invertedColors) else null
            binding.viewerContainer.setLayerType(LAYER_TYPE_HARDWARE, paint)
        }
    }
}
