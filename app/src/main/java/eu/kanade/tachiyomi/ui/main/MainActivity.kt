package eu.kanade.tachiyomi.ui.main

import android.animation.ValueAnimator
import android.app.SearchManager
import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.animation.doOnEnd
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.util.Consumer
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppStateBanners
import eu.kanade.presentation.components.DownloadedOnlyBannerBackgroundColor
import eu.kanade.presentation.components.IncognitoModeBannerBackgroundColor
import eu.kanade.presentation.components.IndexingBannerBackgroundColor
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.tachiyomi.ui.library.ImportEpubScreen
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.browse.extension.NovelExtensionReposScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.deeplink.DeepLinkScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.isNavigationBarNeedsScrim
import eu.kanade.tachiyomi.util.system.updaterEnabled
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.migration.Migrator
import mihon.feature.support.SupportUsScreen
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.injectLazy
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.times

class MainActivity : BaseActivity() {

    private val libraryPreferences: LibraryPreferences by injectLazy()
    private val preferences: BasePreferences by injectLazy()

    private val downloadCache: DownloadCache by injectLazy()
    private val chapterCache: ChapterCache by injectLazy()

    private val getIncognitoState: GetIncognitoState by injectLazy()

    // To be checked by splash screen. If true then splash screen will be removed.
    var ready = false

    private var navigator: Navigator? = null

    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isLaunch = savedInstanceState == null

        // Prevent splash screen showing up on configuration changes
        val splashScreen = if (isLaunch) installSplashScreen() else null

        super.onCreate(savedInstanceState)

        Migrator.awaitAndRelease()

        // Only block duplicate launcher entries. External open/share intents may arrive in a
        // non-root task and still need to be handled by this activity.
        if (!isTaskRoot && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish()
            return
        }

        setComposeContent {
            val context = LocalContext.current

            var incognito by remember { mutableStateOf(getIncognitoState.await(null)) }
            val downloadOnly by preferences.downloadedOnly.collectAsState()
            val indexing by downloadCache.isInitializing.collectAsState()

            val isSystemInDarkTheme = isSystemInDarkTheme()
            val statusBarBackgroundColor = when {
                indexing -> IndexingBannerBackgroundColor
                downloadOnly -> DownloadedOnlyBannerBackgroundColor
                incognito -> IncognitoModeBannerBackgroundColor
                else -> MaterialTheme.colorScheme.surface
            }
            LaunchedEffect(isSystemInDarkTheme, statusBarBackgroundColor) {
                // Draw edge-to-edge and set system bars color to transparent
                val lightStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
                val darkStyle = SystemBarStyle.dark(Color.TRANSPARENT)
                enableEdgeToEdge(
                    statusBarStyle = if (statusBarBackgroundColor.luminance() > 0.5) lightStyle else darkStyle,
                    navigationBarStyle = if (isSystemInDarkTheme) darkStyle else lightStyle,
                )
            }

            Navigator(
                screen = HomeScreen,
                disposeBehavior = NavigatorDisposeBehavior(disposeNestedNavigators = false, disposeSteps = true),
            ) { navigator ->
                LaunchedEffect(navigator) {
                    this@MainActivity.navigator = navigator

                    if (isLaunch) {
                        // Mass-import restore/auto-resume deliberately NOT run here: starting its
                        // foreground workers during cold start jammed the splash window (the
                        // activity could fail to start). It now runs lazily when the mass-import
                        // dialog is opened instead.
                        // Set start screen
                        handleIntentAction(intent, navigator, closeImportScreenOnDone = true)

                        // Reset Incognito Mode on relaunch
                        preferences.incognitoMode.set(false)
                    }
                }
                LaunchedEffect(navigator.lastItem) {
                    (navigator.lastItem as? BrowseSourceScreen)?.sourceId
                        .let(getIncognitoState::subscribe)
                        .collectLatest { incognito = it }
                }

                val scaffoldInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
                Scaffold(
                    topBar = {
                        AppStateBanners(
                            downloadedOnlyMode = downloadOnly,
                            incognitoMode = incognito,
                            indexing = indexing,
                            modifier = Modifier.windowInsetsPadding(scaffoldInsets),
                        )
                    },
                    contentWindowInsets = scaffoldInsets,
                ) { contentPadding ->
                    // Consume insets already used by app state banners
                    Box {
                        // Shows current screen
                        DefaultNavigatorScreenTransition(
                            navigator = navigator,
                            modifier = Modifier
                                .padding(contentPadding)
                                .consumeWindowInsets(contentPadding),
                        )

                        // Draw navigation bar scrim when needed
                        if (remember { isNavigationBarNeedsScrim() }) {
                            Spacer(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                                    .alpha(0.8f)
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            )
                        }
                    }
                }

                // Pop source-related screens when incognito mode is turned off
                LaunchedEffect(Unit) {
                    preferences.incognitoMode.changes()
                        .drop(1)
                        .filter { !it }
                        .onEach {
                            val currentScreen = navigator.lastItem
                            if (currentScreen is BrowseSourceScreen ||
                                (currentScreen is MangaScreen && currentScreen.fromSource)
                            ) {
                                navigator.popUntilRoot()
                            }
                        }
                        .launchIn(this)
                }

                HandleOnNewIntent(context = context, navigator = navigator)

                CheckForUpdates()
                ShowOnboarding()
                // ShowDonationCampaign()
            }
        }

        val startTime = System.currentTimeMillis()
        splashScreen?.setKeepOnScreenCondition {
            val elapsed = System.currentTimeMillis() - startTime
            elapsed <= SPLASH_MIN_DURATION || (!ready && elapsed <= SPLASH_MAX_DURATION)
        }
        setSplashScreenExitAnimation(splashScreen)

        if (isLaunch && libraryPreferences.autoClearChapterCache.get()) {
            lifecycleScope.launchIO {
                chapterCache.clear()
            }
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        when (val screen = navigator?.lastItem) {
            is AssistContentScreen -> {
                screen.onProvideAssistUrl()?.let { outContent.webUri = it.toUri() }
            }
        }
    }

    @Composable
    private fun HandleOnNewIntent(context: Context, navigator: Navigator) {
        LaunchedEffect(Unit) {
            callbackFlow {
                val componentActivity = context as ComponentActivity
                val consumer = Consumer<Intent> { trySend(it) }
                componentActivity.addOnNewIntentListener(consumer)
                awaitClose { componentActivity.removeOnNewIntentListener(consumer) }
            }
                .collectLatest { handleIntentAction(it, navigator, closeImportScreenOnDone = false) }
        }
    }

    @Composable
    private fun CheckForUpdates() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        // App updates
        LaunchedEffect(Unit) {
            if (updaterEnabled) {
                try {
                    val result = AppUpdateChecker().checkForUpdate(context)
                    if (result is GetApplicationRelease.Result.NewUpdate) {
                        val updateScreen = NewUpdateScreen(
                            versionName = result.release.version,
                            changelogInfo = result.release.info,
                            releaseLink = result.release.releaseLink,
                            downloadLink = result.release.downloadLink,
                        )
                        navigator.push(updateScreen)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                }
            }
        }

        // Extensions updates
        LaunchedEffect(Unit) {
            try {
                ExtensionApi().checkForUpdates(context)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    @Composable
    private fun ShowOnboarding() {
        val navigator = LocalNavigator.currentOrThrow

        LaunchedEffect(Unit) {
            if (!preferences.shownOnboardingFlow.get() && navigator.lastItem !is OnboardingScreen) {
                navigator.push(OnboardingScreen())
            }
        }
    }

    // @Composable
    // private fun ShowDonationCampaign() {
    //     val navigator = LocalNavigator.currentOrThrow

    //     var showCampaign by remember { mutableStateOf(false) }
    //     if (showCampaign) {
    //         val uriHandler = LocalUriHandler.current
    //         val dismissSupportMessage = {
    //             preferences.donationCampaignShown.set(true)
    //             @Suppress("AssignedValueIsNeverRead")
    //             showCampaign = false
    //         }
    //         AdaptiveSheet(
    //             onDismissRequest = dismissSupportMessage,
    //             enableImplicitDismiss = false,
    //         ) {
    //             Column {
    //                 Spacer(modifier = Modifier.height(16.dp))
    //                 Column(
    //                     modifier = Modifier
    //                         .verticalScroll(rememberScrollState())
    //                         .padding(16.dp)
    //                         .weight(1f, fill = false)
    //                         .fillMaxWidth(),
    //                     verticalArrangement = Arrangement.spacedBy(8.dp),
    //                 ) {
    //                     Text(
    //                         text = stringResource(MR.strings.donationCampaign_title),
    //                         color = MaterialTheme.colorScheme.primary,
    //                         style = MaterialTheme.typography.headlineSmall,
    //                     )
    //                     Text(
    //                         text = stringResource(MR.strings.donationCampaign_paragraph1),
    //                         style = MaterialTheme.typography.bodyMedium,
    //                     )
    //                     Text(
    //                         text = stringResource(MR.strings.donationCampaign_paragraph2),
    //                         style = MaterialTheme.typography.bodyMedium,
    //                     )
    //                     Text(
    //                         text = stringResource(MR.strings.donationCampaign_paragraph3),
    //                         style = MaterialTheme.typography.bodyMedium,
    //                     )
    //                 }

    //                 HorizontalDivider()

    //                 Button(
    //                     modifier = Modifier
    //                         .padding(top = MaterialTheme.padding.small)
    //                         .padding(horizontal = MaterialTheme.padding.medium)
    //                         .fillMaxWidth(),
    //                     onClick = {
    //                         navigator.push(SupportUsScreen())
    //                         dismissSupportMessage()
    //                     },
    //                 ) {
    //                     Row(
    //                         verticalAlignment = Alignment.CenterVertically,
    //                         horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    //                     ) {
    //                         Icon(
    //                             imageVector = Icons.Default.VolunteerActivism,
    //                             contentDescription = null,
    //                         )
    //                         Text(
    //                             text = stringResource(MR.strings.label_support_us),
    //                             color = MaterialTheme.colorScheme.onPrimary,
    //                         )
    //                     }
    //                 }
    //                 Row(
    //                     horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    //                     modifier = Modifier
    //                         .padding(bottom = MaterialTheme.padding.small)
    //                         .padding(horizontal = MaterialTheme.padding.medium),
    //                 ) {
    //                     OutlinedButton(
    //                         modifier = Modifier
    //                             .fillMaxWidth()
    //                             .weight(1f),
    //                         onClick = { uriHandler.openUri(Constants.URL_DISCORD) },
    //                     ) {
    //                         Row(
    //                             verticalAlignment = Alignment.CenterVertically,
    //                             horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    //                         ) {
    //                             Text(
    //                                 text = stringResource(MR.strings.donationCampaign_contactPlatform),
    //                             )
    //                             Icon(
    //                                 imageVector = Icons.AutoMirrored.Default.OpenInNew,
    //                                 contentDescription = null,
    //                             )
    //                         }
    //                     }
    //                     OutlinedButton(
    //                         modifier = Modifier
    //                             .fillMaxWidth()
    //                             .weight(1f),
    //                         onClick = dismissSupportMessage,
    //                     ) {
    //                         Text(
    //                             text = stringResource(MR.strings.donationCampaign_dismiss),
    //                         )
    //                     }
    //                 }
    //             }
    //         }
    //     }

    //     LaunchedEffect(Unit) {
    //         try {
    //             val firstInstallTime = packageManager.getPackageInfo(packageName, 0).firstInstallTime
    //             val eligibleTime = Instant.fromEpochMilliseconds(firstInstallTime).plus(6 * 30.days)
    //             @Suppress("AssignedValueIsNeverRead")
    //             showCampaign = (Clock.System.now() >= eligibleTime && !preferences.donationCampaignShown.get())
    //         } catch (_: PackageManager.NameNotFoundException) {
    //         }
    //     }
    // }

    /**
     * Sets custom splash screen exit animation on devices prior to Android 12.
     *
     * When custom animation is used, status and navigation bar color will be set to transparent and will be restored
     * after the animation is finished.
     */
    @Suppress("Deprecation")
    private fun setSplashScreenExitAnimation(splashScreen: SplashScreen?) {
        val root = findViewById<View>(android.R.id.content)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && splashScreen != null) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT

            splashScreen.setOnExitAnimationListener { splashProvider ->
                // For some reason the SplashScreen applies (incorrect) Y translation to the iconView
                splashProvider.iconView.translationY = 0F

                val activityAnim = ValueAnimator.ofFloat(1F, 0F).apply {
                    interpolator = LinearOutSlowInInterpolator()
                    duration = SPLASH_EXIT_ANIM_DURATION
                    addUpdateListener { va ->
                        val value = va.animatedValue as Float
                        root.translationY = value * 16.dpToPx
                    }
                }

                val splashAnim = ValueAnimator.ofFloat(1F, 0F).apply {
                    interpolator = FastOutSlowInInterpolator()
                    duration = SPLASH_EXIT_ANIM_DURATION
                    addUpdateListener { va ->
                        val value = va.animatedValue as Float
                        splashProvider.view.alpha = value
                    }
                    doOnEnd {
                        splashProvider.remove()
                    }
                }

                activityAnim.start()
                splashAnim.start()
            }
        }
    }

    private fun handleIntentAction(
        intent: Intent,
        navigator: Navigator,
        closeImportScreenOnDone: Boolean,
    ): Boolean {
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId > -1) {
            NotificationReceiver.dismissNotification(
                applicationContext,
                notificationId,
                intent.getIntExtra("groupId", 0),
            )
        }

        // Open / share-target EPUB files from outside the app. Detected by
        // intent action + MIME (or filename extension) before the regular
        // action dispatch so an EPUB ACTION_VIEW doesn't fall through to the
        // backup-file branch and an EPUB ACTION_SEND doesn't trigger the
        // global-search path.
        val epubUris = extractIncomingEpubUris(intent)
        if (epubUris.isNotEmpty()) {
            persistEpubReadPermissions(epubUris)
            navigator.popUntilRoot()
            navigator.push(
                ImportEpubScreen(
                    initialUriStrings = epubUris.map { it.toString() },
                    closeActivityOnDone = closeImportScreenOnDone,
                ),
            )
            ready = true
            return true
        }

        val tabToOpen = when (intent.action) {
            Constants.SHORTCUT_LIBRARY -> HomeScreen.Tab.Library()
            Constants.SHORTCUT_MANGA -> {
                val idToOpen = intent.extras?.getLong(Constants.MANGA_EXTRA) ?: return false
                navigator.popUntilRoot()
                HomeScreen.Tab.Library(idToOpen)
            }
            Constants.SHORTCUT_UPDATES -> HomeScreen.Tab.Updates
            Constants.SHORTCUT_HISTORY -> HomeScreen.Tab.History
            Constants.SHORTCUT_SOURCES -> HomeScreen.Tab.Browse(false)
            Constants.SHORTCUT_EXTENSIONS -> HomeScreen.Tab.Browse(true)
            Constants.SHORTCUT_DOWNLOADS -> {
                navigator.popUntilRoot()
                HomeScreen.Tab.More(toDownloads = true)
            }
            Intent.ACTION_SEARCH, Intent.ACTION_SEND, "com.google.android.gms.actions.SEARCH_ACTION" -> {
                // If the intent match the "standard" Android search intent
                // or the Google-specific search intent (triggered by saying or typing "search *query* on *Tachiyomi*" in Google Search/Google Assistant)

                // Get the search query provided in extras, and if not null, perform a global search with it.
                val query = intent.getStringExtra(SearchManager.QUERY) ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!query.isNullOrEmpty()) {
                    navigator.popUntilRoot()
                    navigator.push(DeepLinkScreen(query))
                }
                null
            }
            INTENT_SEARCH -> {
                val query = intent.getStringExtra(INTENT_SEARCH_QUERY)
                if (!query.isNullOrEmpty()) {
                    val filter = intent.getStringExtra(INTENT_SEARCH_FILTER)
                    navigator.popUntilRoot()
                    navigator.push(GlobalSearchScreen(query, filter))
                }
                null
            }
            Intent.ACTION_VIEW -> {
                // Handling opening of backup files
                if (intent.data.toString().endsWith(".tachibk")) {
                    navigator.popUntilRoot()
                    navigator.push(RestoreBackupScreen(intent.data.toString()))
                }
                // Deep link to add extension store
                else if (intent.isAddExtensionStoreIntent()) {
                    intent.data?.getQueryParameter("url")?.let { repoUrl ->
                        navigator.popUntilRoot()
                        navigator.push(ExtensionStoresScreen(repoUrl))
                    }
                }
                // Deep link to add LNReader JS repo
                else if (intent.scheme == "lnreader" && intent.data?.host == "repo" && intent.data?.path == "/add") {
                    intent.data?.getQueryParameter("url")?.let { repoUrl ->
                        navigator.popUntilRoot()
                        navigator.push(NovelExtensionReposScreen(repoUrl))
                    }
                }
                null
            }
            else -> return false
        }

        if (tabToOpen != null) {
            lifecycleScope.launch { HomeScreen.openTab(tabToOpen) }
        }

        ready = true
        return true
    }

    /**
     * Return the URIs of any EPUB files attached to [intent], or an empty list
     * when the intent isn't an EPUB open/share request.
     *
     * Handles three shapes:
     *  - `ACTION_VIEW` with a single URI in [Intent.getData]. Triggered by the
     *    file-manager "open with…" flow.
     *  - `ACTION_SEND` with one URI under [Intent.EXTRA_STREAM]. Triggered by
     *    apps that share a single EPUB via the Android share sheet.
     *  - `ACTION_SEND_MULTIPLE` with a list of URIs under
     *    [Intent.EXTRA_STREAM]. Same share sheet, multiple files selected.
     *
     * The MIME-type check is loose (any type containing `"epub"`) because
     * different senders report `application/epub+zip`, `application/epub`, or
     * even `application/octet-stream`. The filename-extension fallback
     * catches the octet-stream case.
     */
    private fun extractIncomingEpubUris(intent: Intent): List<android.net.Uri> {
        fun isEpubMime(type: String?): Boolean = type?.contains("epub", ignoreCase = true) == true
        fun looksLikeEpub(uri: android.net.Uri?): Boolean {
            if (uri == null) return false
            val path = (uri.lastPathSegment ?: uri.path ?: uri.toString()).lowercase()
            if (path.endsWith(".epub")) return true
            // Fallback for content URIs where the path has no extension (e.g. Downloads provider
            // uses numeric IDs like content://…/document/12345). Query the display name instead.
            if (uri.scheme == "content") {
                val displayName = runCatching {
                    contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull()
                if (displayName?.lowercase()?.endsWith(".epub") == true) return true
            }
            return false
        }

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return emptyList()
                if (isEpubMime(intent.type) || looksLikeEpub(uri)) listOf(uri) else emptyList()
            }
            Intent.ACTION_SEND -> {
                if (!isEpubMime(intent.type)) return emptyList()
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (!isEpubMime(intent.type)) return emptyList()
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                }
                list?.toList().orEmpty()
            }
            else -> emptyList()
        }
    }

    /**
     * Take a persistable read-permission grant on each URI so the
     * EPUB-import flow can re-open it across coroutine launches without
     * `SecurityException`. Sharing-side senders may not have set the
     * persistable flag, so the call is wrapped in [runCatching] per-URI.
     */
    private fun persistEpubReadPermissions(uris: List<android.net.Uri>) {
        val resolver = contentResolver
        uris.forEach { uri ->
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun Intent.isAddExtensionStoreIntent(): Boolean {
        return (scheme == "tachiyomi" && data?.host == "add-repo") ||
            (scheme == "mihon" && data?.host == "extension-store")
    }

    companion object {
        const val INTENT_SEARCH = "eu.kanade.tachiyomi.SEARCH"
        const val INTENT_SEARCH_QUERY = "query"
        const val INTENT_SEARCH_FILTER = "filter"
    }
}

// Splash screen
private const val SPLASH_MIN_DURATION = 500 // ms
private const val SPLASH_MAX_DURATION = 5000 // ms
private const val SPLASH_EXIT_ANIM_DURATION = 400L // ms
