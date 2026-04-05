package eu.kanade.tachiyomi.ui.customsource

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.annotation.Keep
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevinnzou.web.AccompanistWebChromeClient
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.LoadingState
import com.kevinnzou.web.WebContent
import com.kevinnzou.web.WebView
import com.kevinnzou.web.WebViewNavigator
import com.kevinnzou.web.WebViewState
import com.kevinnzou.web.rememberWebViewNavigator
import com.kevinnzou.web.rememberWebViewState
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.components.material.Scaffold as TachiyomiScaffold

/**
 * Element Selector Wizard Steps
 * Reordered to collect essential selectors (cover/title/link) early for popular/latest/search
 */
enum class SelectorWizardStep(
    val titleRes: StringResource,
    val descriptionRes: StringResource,
    val detailedHelpRes: StringResource,
) {
    TRENDING(
        TDMR.strings.selector_step_trending_title,
        TDMR.strings.selector_step_trending_desc,
        TDMR.strings.selector_step_trending_detail,
    ),
    NOVEL_CARD(
        TDMR.strings.selector_step_novel_card_title,
        TDMR.strings.selector_step_novel_card_desc,
        TDMR.strings.selector_step_novel_card_detail,
    ),
    TRENDING_NOVELS(
        TDMR.strings.selector_step_trending_novels_title,
        TDMR.strings.selector_step_trending_novels_desc,
        TDMR.strings.selector_step_trending_novels_detail,
    ),
    NEW_NOVELS_SECTION(
        TDMR.strings.selector_step_new_section_title,
        TDMR.strings.selector_step_new_section_desc,
        TDMR.strings.selector_step_new_section_detail,
    ),
    NEW_NOVELS(
        TDMR.strings.selector_step_new_novels_title,
        TDMR.strings.selector_step_new_novels_desc,
        TDMR.strings.selector_step_new_novels_detail,
    ),
    SEARCH(
        TDMR.strings.selector_step_search_title,
        TDMR.strings.selector_step_search_desc,
        TDMR.strings.selector_step_search_detail,
    ),
    SEARCH_URL_PATTERN(
        TDMR.strings.selector_step_search_url_title,
        TDMR.strings.selector_step_search_url_desc,
        TDMR.strings.selector_step_search_url_detail,
    ),
    PAGINATION(
        TDMR.strings.selector_step_pagination_title,
        TDMR.strings.selector_step_pagination_desc,
        TDMR.strings.selector_step_pagination_detail,
    ),
    NOVEL_PAGE(
        TDMR.strings.selector_step_novel_page_title,
        TDMR.strings.selector_step_novel_page_desc,
        TDMR.strings.selector_step_novel_page_detail,
    ),
    NOVEL_DETAILS(
        TDMR.strings.selector_step_novel_details_title,
        TDMR.strings.selector_step_novel_details_desc,
        TDMR.strings.selector_step_novel_details_detail,
    ),
    CHAPTER_LIST(
        TDMR.strings.selector_step_chapter_list_title,
        TDMR.strings.selector_step_chapter_list_desc,
        TDMR.strings.selector_step_chapter_list_detail,
    ),
    CHAPTER_PAGE(
        TDMR.strings.selector_step_chapter_page_title,
        TDMR.strings.selector_step_chapter_page_desc,
        TDMR.strings.selector_step_chapter_page_detail,
    ),
    CHAPTER_CONTENT(
        TDMR.strings.selector_step_chapter_content_title,
        TDMR.strings.selector_step_chapter_content_desc,
        TDMR.strings.selector_step_chapter_content_detail,
    ),
    COMPLETE(
        TDMR.strings.selector_step_complete_title,
        TDMR.strings.selector_step_complete_desc,
        TDMR.strings.selector_step_complete_detail,
    ),
    ;

    companion object {
        fun fromOrdinal(ordinal: Int): SelectorWizardStep = entries.getOrElse(ordinal) { TRENDING }
        val totalSteps: Int get() = entries.size
    }
}

/**
 * Data class for storing selected CSS selectors
 */
data class SelectorConfig(
    var sourceName: String = "",
    var baseUrl: String = "",
    var trendingSelector: String = "",
    var trendingNovels: MutableList<String> = mutableListOf(),
    var newNovelsSelector: String = "",
    var newNovels: MutableList<String> = mutableListOf(),
    var searchUrl: String = "",
    var searchKeyword: String = "",
    var paginationPattern: String = "",
    var novelCoverSelector: String = "",
    var novelTitleSelector: String = "",
    var novelPageTitleSelector: String = "",
    var novelDescriptionSelector: String = "",
    var novelCoverPageSelector: String = "",
    var novelTagsSelector: String = "",
    var chapterListSelector: String = "",
    var chapterItems: MutableList<String> = mutableListOf(),
    var chapterContentSelector: String = "",
)

/**
 * JavaScript interface for element selection communication
 */
@Keep
class ElementSelectorJSInterface(
    private val onElementSelected: (String, String, String, String) -> Unit,
    private val onSelectionModeChanged: (Boolean) -> Unit,
) {
    @JavascriptInterface
    fun onElementClick(selector: String, outerHtml: String, textContent: String, parentSelectorsJson: String) {
        onElementSelected(selector, outerHtml, textContent, parentSelectorsJson)
    }

    @JavascriptInterface
    fun setSelectionMode(enabled: Boolean) {
        onSelectionModeChanged(enabled)
    }
}

/**
 * WebView Element Selector Screen
 * Guides user through selecting CSS selectors for custom source creation
 */
@Composable
fun ElementSelectorScreen(
    initialUrl: String,
    initialSourceName: String = "",
    onNavigateUp: () -> Unit,
    onSaveConfig: (SelectorConfig) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var currentStep by remember { mutableStateOf(SelectorWizardStep.TRENDING) }
    var config by remember {
        mutableStateOf(
            SelectorConfig(
                sourceName = initialSourceName,
                baseUrl = initialUrl,
            ),
        )
    }
    var selectionModeEnabled by remember { mutableStateOf(false) }
    var lastSelectedElement by remember { mutableStateOf<SelectedElement?>(null) }
    var showSelectorDialog by remember { mutableStateOf(false) }
    var showSourceNameDialog by remember { mutableStateOf(false) }
    var showPatternLibrary by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(initialUrl) }

    // Site framework detection
    var detectedFramework by remember { mutableStateOf(SitePatternLibrary.SiteFramework.CUSTOM) }
    var pageHtml by remember { mutableStateOf("") }

    // Test selector state
    var testSelectorResult by remember { mutableStateOf<String?>(null) }

    // WebView state
    val webViewState = rememberWebViewState(url = initialUrl)
    val navigator = rememberWebViewNavigator()
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Selected elements for current step
    val selectedElements = remember { mutableStateListOf<SelectedElement>() }

    val jsInterface = remember {
        ElementSelectorJSInterface(
            onElementSelected = { selector, html, text, parentSelectorsJson ->
                val parentSelectors = try {
                    val json = org.json.JSONObject(parentSelectorsJson)
                    val map = mutableMapOf<String, String>()
                    json.keys().forEach { key ->
                        if (!json.isNull(key)) {
                            map[key] = json.getString(key)
                        }
                    }
                    map
                } catch (e: Exception) {
                    emptyMap()
                }
                lastSelectedElement = SelectedElement(selector, html, text, parentSelectors)
                showSelectorDialog = true
            },
            onSelectionModeChanged = { enabled ->
                selectionModeEnabled = enabled
            },
        )
    }

    // Inject JavaScript for element selection
    fun injectSelectionScript() {
        webView?.evaluateJavascript(ELEMENT_SELECTOR_JS, null)
    }

    // Enable selection mode
    fun enableSelectionMode() {
        webView?.evaluateJavascript("window.enableSelectionMode(true);", null)
        selectionModeEnabled = true
    }

    // Disable selection mode
    fun disableSelectionMode() {
        webView?.evaluateJavascript("window.enableSelectionMode(false);", null)
        selectionModeEnabled = false
    }

    // Highlight elements matching a selector
    fun highlightSelector(selector: String) {
        webView?.evaluateJavascript(
            "window.highlightElements('$selector');",
            null,
        )
    }

    // Clear all highlights
    fun clearHighlights() {
        webView?.evaluateJavascript("window.clearHighlights();", null)
    }

    // Test a selector and get count of matching elements
    fun testSelector(selector: String, callback: (Int) -> Unit) {
        val escapedSelector = selector.replace("'", "\\'")
        webView?.evaluateJavascript(
            "window.testSelector('$escapedSelector');",
        ) { result ->
            val count = result?.toIntOrNull() ?: 0
            callback(count)
        }
    }

    // Get page HTML for framework detection
    fun getPageHtml(callback: (String) -> Unit) {
        webView?.evaluateJavascript("document.documentElement.outerHTML.substring(0, 5000);") { result ->
            val html = result?.trim('"')?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
            callback(html)
        }
    }

    val webClient = remember {
        object : AccompanistWebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { currentUrl = it }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                url?.let { newUrl ->
                    currentUrl = newUrl

                    // Detect site framework
                    getPageHtml { html ->
                        pageHtml = html
                        detectedFramework = SitePatternLibrary.SiteFramework.detect(html)
                    }

                    // Auto-detect search URL when on SEARCH step
                    if (currentStep == SelectorWizardStep.SEARCH ||
                        currentStep == SelectorWizardStep.SEARCH_URL_PATTERN
                    ) {
                        val detectedSearchUrl = detectSearchUrl(newUrl, config.baseUrl)
                        if (detectedSearchUrl != null) {
                            config.searchUrl = detectedSearchUrl.first
                            config.searchKeyword = detectedSearchUrl.second
                        }
                    }
                }
                injectSelectionScript()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http") || url.startsWith("https")) {
                    view?.loadUrl(url)
                    return true
                }
                return false
            }
        }
    }

    BackHandler(enabled = true) {
        if (selectionModeEnabled) {
            disableSelectionMode()
        } else if (navigator.canGoBack) {
            navigator.navigateBack()
        } else {
            onNavigateUp()
        }
    }

    TachiyomiScaffold(
        topBar = {
            Column {
                // Top App Bar
                AppBar(
                    title = stringResource(TDMR.strings.selector_title_format, stringResource(currentStep.titleRes)),
                    subtitle = "${currentStep.ordinal + 1}/${SelectorWizardStep.totalSteps}",
                    navigateUp = onNavigateUp,
                    navigationIcon = Icons.Outlined.Close,
                    actions = {
                        IconButton(onClick = { navigator.reload() }) {
                            Icon(Icons.Outlined.Refresh, stringResource(MR.strings.action_webview_refresh))
                        }
                        IconButton(onClick = {
                            // Show name dialog before saving
                            showSourceNameDialog = true
                        }) {
                            Icon(Icons.Outlined.Save, stringResource(MR.strings.action_save))
                        }
                    },
                )

                // Progress indicator
                val loadingState = webViewState.loadingState
                if (loadingState is LoadingState.Loading) {
                    LinearProgressIndicator(
                        progress = { loadingState.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Step indicator bar
                StepIndicatorBar(
                    currentStep = currentStep,
                    onStepClick = { step ->
                        currentStep = step
                    },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (selectionModeEnabled) {
                        disableSelectionMode()
                    } else {
                        enableSelectionMode()
                    }
                },
                containerColor = if (selectionModeEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                icon = {
                    Icon(
                        if (selectionModeEnabled) Icons.Filled.TouchApp else Icons.Filled.Edit,
                        contentDescription = stringResource(TDMR.strings.selector_select_element),
                    )
                },
                text = {
                    Text(
                        if (selectionModeEnabled) {
                            stringResource(
                                TDMR.strings.selector_selection_on,
                            )
                        } else {
                            stringResource(TDMR.strings.selector_select_element)
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Step instruction card
            StepInstructionCard(
                step = currentStep,
                selectedCount = selectedElements.size,
                detectedFramework = detectedFramework,
                onClearSelections = {
                    selectedElements.clear()
                    clearHighlights()
                },
                onShowPatternLibrary = { showPatternLibrary = true },
                onSkipStep = if (currentStep == SelectorWizardStep.SEARCH ||
                    currentStep == SelectorWizardStep.SEARCH_URL_PATTERN ||
                    currentStep == SelectorWizardStep.PAGINATION
                ) {
                    {
                        // Move to next step, skipping current
                        val nextOrdinal = currentStep.ordinal + 1
                        if (nextOrdinal < SelectorWizardStep.totalSteps) {
                            currentStep = SelectorWizardStep.fromOrdinal(nextOrdinal)
                        }
                    }
                } else {
                    null
                },
            )

            // Navigation bar
            NavigationBar(
                canGoBack = navigator.canGoBack,
                canGoForward = navigator.canGoForward,
                onBack = { navigator.navigateBack() },
                onForward = { navigator.navigateForward() },
                currentUrl = currentUrl,
                onUrlSubmit = { url ->
                    navigator.loadUrl(url)
                },
            )

            // WebView
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(
                        width = if (selectionModeEnabled) 3.dp else 0.dp,
                        color = if (selectionModeEnabled) MaterialTheme.colorScheme.primary else Color.Transparent,
                    ),
            ) {
                WebView(
                    state = webViewState,
                    navigator = navigator,
                    modifier = Modifier.fillMaxSize(),
                    onCreated = { wv ->
                        webView = wv
                        wv.setDefaultSettings()
                        wv.settings.javaScriptEnabled = true
                        wv.settings.domStorageEnabled = true
                        wv.addJavascriptInterface(jsInterface, "AndroidSelector")
                    },
                    client = webClient,
                )

                // Selection mode overlay indicator
                if (selectionModeEnabled) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(8.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(16.dp),
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(TDMR.strings.selector_tap_to_select),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            // Selected elements panel
            AnimatedVisibility(
                visible = selectedElements.isNotEmpty(),
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                SelectedElementsPanel(
                    elements = selectedElements,
                    onRemove = { element ->
                        selectedElements.remove(element)
                        clearHighlights()
                        selectedElements.forEach { highlightSelector(it.selector) }
                    },
                    onHighlight = { element ->
                        highlightSelector(element.selector)
                    },
                )
            }

            // Step navigation
            StepNavigationBar(
                currentStep = currentStep,
                canProceed = canProceedToNextStep(currentStep, selectedElements, config),
                onPrevious = {
                    if (currentStep.ordinal > 0) {
                        currentStep = SelectorWizardStep.fromOrdinal(currentStep.ordinal - 1)
                    }
                },
                onNext = {
                    saveSelectionsForStep(currentStep, selectedElements, config)
                    selectedElements.clear()
                    clearHighlights()
                    if (currentStep.ordinal < SelectorWizardStep.totalSteps - 1) {
                        currentStep = SelectorWizardStep.fromOrdinal(currentStep.ordinal + 1)
                    }
                },
                onComplete = {
                    saveSelectionsForStep(currentStep, selectedElements, config)
                    // Show name dialog before saving
                    showSourceNameDialog = true
                },
            )
        }
    }

    // Selector confirmation dialog
    if (showSelectorDialog && lastSelectedElement != null) {
        SelectorConfirmDialog(
            element = lastSelectedElement!!,
            onConfirm = { selector ->
                selectedElements.add(lastSelectedElement!!.copy(selector = selector))
                showSelectorDialog = false
                lastSelectedElement = null
            },
            onDismiss = {
                showSelectorDialog = false
                lastSelectedElement = null
            },
        )
    }

    // Source name dialog (shown before saving)
    if (showSourceNameDialog) {
        SourceNameDialog(
            currentName = config.sourceName,
            baseUrl = config.baseUrl,
            onSave = { name ->
                config = config.copy(sourceName = name)
                showSourceNameDialog = false
                onSaveConfig(config)
            },
            onDismiss = { showSourceNameDialog = false },
        )
    }

    // Pattern Library dialog
    if (showPatternLibrary) {
        PatternLibraryDialog(
            framework = detectedFramework,
            currentStep = currentStep,
            onSelectPattern = { selector ->
                // Test the selector and highlight matches
                testSelector(selector) { count ->
                    testSelectorResult = "$count matches for: $selector"
                }
                highlightSelector(selector)
            },
            onApplySelector = { selector ->
                // Add as selected element
                selectedElements.add(
                    SelectedElement(
                        selector = selector,
                        outerHtml = "",
                        textContent = "(Pattern suggestion)",
                        parentSelectors = emptyMap(),
                    ),
                )
                showPatternLibrary = false
            },
            onDismiss = { showPatternLibrary = false },
            testResult = testSelectorResult,
        )
    }
}

@Composable
private fun StepIndicatorBar(
    currentStep: SelectorWizardStep,
    onStepClick: (SelectorWizardStep) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SelectorWizardStep.entries.forEach { step ->
            val isCompleted = step.ordinal < currentStep.ordinal
            val isCurrent = step == currentStep

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when {
                            isCompleted -> MaterialTheme.colorScheme.primary
                            isCurrent -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                    )
                    .clickable { onStepClick(step) },
            )
        }
    }
}

@Composable
private fun StepInstructionCard(
    step: SelectorWizardStep,
    selectedCount: Int,
    detectedFramework: SitePatternLibrary.SiteFramework = SitePatternLibrary.SiteFramework.CUSTOM,
    onClearSelections: () -> Unit,
    onShowPatternLibrary: () -> Unit,
    onSkipStep: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(step.descriptionRes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (true) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(step.detailedHelpRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        )
                    }
                    if (selectedCount > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(TDMR.strings.selector_elements_selected, selectedCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Row {
                    if (onSkipStep != null) {
                        TextButton(onClick = onSkipStep) {
                            Text(
                                stringResource(TDMR.strings.custom_selector_skip),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    if (selectedCount > 0) {
                        IconButton(onClick = onClearSelections) {
                            Icon(Icons.Filled.Delete, stringResource(TDMR.strings.selector_clear_selections))
                        }
                    }
                }
            }

            // Detected framework info
            if (detectedFramework != SitePatternLibrary.SiteFramework.CUSTOM) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(TDMR.strings.selector_detected_format, detectedFramework.displayName),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(onClick = onShowPatternLibrary) {
                        Icon(
                            Icons.Filled.Code,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(TDMR.strings.custom_selector_suggestions))
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    currentUrl: String,
    onUrlSubmit: (String) -> Unit,
) {
    var editingUrl by remember { mutableStateOf(false) }
    var urlText by remember(currentUrl) { mutableStateOf(currentUrl) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(MR.strings.nav_zone_prev))
        }
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, stringResource(MR.strings.nav_zone_next))
        }

        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = {
                IconButton(
                    onClick = {
                        onUrlSubmit(urlText)
                    },
                ) {
                    Icon(Icons.Outlined.Search, stringResource(MR.strings.action_search))
                }
            },
        )
    }
}

@Composable
private fun SelectedElementsPanel(
    elements: List<SelectedElement>,
    onRemove: (SelectedElement) -> Unit,
    onHighlight: (SelectedElement) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = stringResource(TDMR.strings.selector_selected_elements),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))

            elements.forEach { element ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = element.selector,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = element.textContent.take(50),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row {
                        IconButton(onClick = { onHighlight(element) }) {
                            Icon(
                                Icons.Filled.TouchApp,
                                stringResource(TDMR.strings.selector_select_element),
                                Modifier.height(20.dp),
                            )
                        }
                        IconButton(onClick = { onRemove(element) }) {
                            Icon(Icons.Filled.Delete, stringResource(MR.strings.action_delete), Modifier.height(20.dp))
                        }
                    }
                }
                Divider()
            }
        }
    }
}

@Composable
private fun StepNavigationBar(
    currentStep: SelectorWizardStep,
    canProceed: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onComplete: () -> Unit,
) {
    val isLastStep = currentStep == SelectorWizardStep.COMPLETE
    val isFirstStep = currentStep == SelectorWizardStep.TRENDING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(
            onClick = onPrevious,
            enabled = !isFirstStep,
        ) {
            Text(stringResource(TDMR.strings.custom_selector_previous))
        }

        if (isLastStep) {
            Button(
                onClick = onComplete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Filled.CheckCircle, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(TDMR.strings.custom_selector_save_source))
            }
        } else {
            Button(
                onClick = onNext,
                enabled = canProceed,
            ) {
                Text(stringResource(TDMR.strings.custom_selector_next_step))
            }
        }
    }
}

@Composable
private fun SelectorConfirmDialog(
    element: SelectedElement,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editedSelector by remember { mutableStateOf(element.selector) }
    var showHtml by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.custom_selector_confirm_selection)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // Visual preview card - shows what the selected element looks like
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Selected Text:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = element.textContent.ifEmpty { "(No text content)" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Parent selectors options
                if (element.parentSelectors.isNotEmpty()) {
                    Text(
                        text = stringResource(TDMR.strings.selector_select_parent),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    element.parentSelectors.forEach { (tag, selector) ->
                        if (selector.isNotEmpty() && selector != element.selector) {
                            OutlinedButton(
                                onClick = { editedSelector = selector },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Text(
                                    stringResource(
                                        TDMR.strings.custom_selector_select_parent_format,
                                        tag.uppercase(),
                                    ),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // CSS Selector input
                OutlinedTextField(
                    value = editedSelector,
                    onValueChange = { editedSelector = it },
                    label = { Text(stringResource(TDMR.strings.custom_selector_css_selector)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Toggle to show/hide HTML
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showHtml = !showHtml }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (showHtml) Icons.Filled.Code else Icons.Filled.Code,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (showHtml) {
                            stringResource(
                                TDMR.strings.selector_hide_html,
                            )
                        } else {
                            stringResource(TDMR.strings.selector_show_html)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // HTML preview (collapsible)
                AnimatedVisibility(visible = showHtml) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(8.dp),
                    ) {
                        Text(
                            text = element.outerHtml.take(800),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            ),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(editedSelector) }) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(MR.strings.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun SourceNameDialog(
    currentName: String,
    baseUrl: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Extract domain name as default suggestion
    val customSourceFallback = stringResource(TDMR.strings.selector_custom_source)
    val suggestedName = remember(baseUrl, customSourceFallback) {
        try {
            val host = java.net.URI(baseUrl).host ?: baseUrl
            host.removePrefix("www.")
                .split(".")
                .firstOrNull()
                ?.replaceFirstChar { it.uppercase() }
                ?: customSourceFallback
        } catch (e: Exception) {
            customSourceFallback
        }
    }

    var sourceName by remember { mutableStateOf(currentName.ifEmpty { suggestedName }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.custom_selector_save_custom_source)) },
        text = {
            Column {
                Text(
                    text = stringResource(TDMR.strings.selector_enter_name),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = sourceName,
                    onValueChange = { sourceName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(TDMR.strings.custom_selector_source_name_required)) },
                    placeholder = { Text(suggestedName) },
                    singleLine = true,
                    isError = sourceName.isBlank(),
                )

                if (sourceName.isBlank()) {
                    Text(
                        text = stringResource(TDMR.strings.selector_name_required),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(TDMR.strings.selector_base_url_format, baseUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(sourceName) },
                enabled = sourceName.isNotBlank(),
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

data class SelectedElement(
    val selector: String,
    val outerHtml: String,
    val textContent: String,
    val parentSelectors: Map<String, String> = emptyMap(),
)

private fun canProceedToNextStep(
    step: SelectorWizardStep,
    selectedElements: List<SelectedElement>,
    config: SelectorConfig,
): Boolean {
    return when (step) {
        SelectorWizardStep.TRENDING -> true // Navigation step
        SelectorWizardStep.NOVEL_CARD -> selectedElements.size >= 2 // Cover + Title (essential!)
        SelectorWizardStep.TRENDING_NOVELS -> selectedElements.size >= 1
        SelectorWizardStep.NEW_NOVELS_SECTION -> true
        SelectorWizardStep.NEW_NOVELS -> selectedElements.size >= 1
        SelectorWizardStep.SEARCH -> true // Optional - can skip
        SelectorWizardStep.SEARCH_URL_PATTERN -> true // Optional - can skip (removed keyword requirement)
        SelectorWizardStep.PAGINATION -> true // Optional - can skip
        SelectorWizardStep.NOVEL_PAGE -> true // Navigation step
        SelectorWizardStep.NOVEL_DETAILS -> selectedElements.size >= 1
        // Fixed: Allow proceeding with at least 1 chapter selected (was requiring 3)
        SelectorWizardStep.CHAPTER_LIST -> selectedElements.isNotEmpty() || config.chapterItems.isNotEmpty()
        SelectorWizardStep.CHAPTER_PAGE -> true // Navigation step
        // Fixed: Check both current selections AND saved config for chapter content
        SelectorWizardStep.CHAPTER_CONTENT -> selectedElements.isNotEmpty() ||
            config.chapterContentSelector.isNotEmpty()
        SelectorWizardStep.COMPLETE -> true
    }
}

private fun saveSelectionsForStep(
    step: SelectorWizardStep,
    selectedElements: List<SelectedElement>,
    config: SelectorConfig,
) {
    when (step) {
        SelectorWizardStep.TRENDING_NOVELS -> {
            config.trendingNovels.clear()
            config.trendingNovels.addAll(selectedElements.map { it.selector })
            // Generate a common selector from selected elements
            if (selectedElements.isNotEmpty()) {
                config.trendingSelector = findCommonSelector(selectedElements)
            }
        }
        SelectorWizardStep.NEW_NOVELS -> {
            config.newNovels.clear()
            config.newNovels.addAll(selectedElements.map { it.selector })
            if (selectedElements.isNotEmpty()) {
                config.newNovelsSelector = findCommonSelector(selectedElements)
            }
        }
        SelectorWizardStep.NOVEL_CARD -> {
            // Expect cover first, then title
            selectedElements.getOrNull(0)?.let { config.novelCoverSelector = it.selector }
            selectedElements.getOrNull(1)?.let { config.novelTitleSelector = it.selector }
        }
        SelectorWizardStep.NOVEL_DETAILS -> {
            selectedElements.forEachIndexed { index, element ->
                when (index) {
                    0 -> config.novelPageTitleSelector = element.selector
                    1 -> config.novelDescriptionSelector = element.selector
                    2 -> config.novelCoverPageSelector = element.selector
                    3 -> config.novelTagsSelector = element.selector
                }
            }
        }
        SelectorWizardStep.CHAPTER_LIST -> {
            config.chapterItems.clear()
            config.chapterItems.addAll(selectedElements.map { it.selector })
            if (selectedElements.isNotEmpty()) {
                config.chapterListSelector = findCommonSelector(selectedElements)
            }
        }
        SelectorWizardStep.CHAPTER_CONTENT -> {
            selectedElements.firstOrNull()?.let { config.chapterContentSelector = it.selector }
        }
        else -> { /* Navigation steps, no selections to save */ }
    }
}

private fun findCommonSelector(elements: List<SelectedElement>): String {
    if (elements.isEmpty()) return ""
    if (elements.size == 1) return elements.first().selector

    // Find common parent path
    val selectors = elements.map { it.selector }
    val parts = selectors.map { it.split(" > ", " ").toMutableList() }

    val commonParts = mutableListOf<String>()
    val minLength = parts.minOf { it.size }

    for (i in 0 until minLength) {
        val part = parts.first()[i]
        if (parts.all { it[i] == part }) {
            commonParts.add(part)
        } else {
            break
        }
    }

    return commonParts.joinToString(" > ")
}

/**
 * Pattern Library Dialog - shows selector suggestions based on detected site framework
 */
@Composable
private fun PatternLibraryDialog(
    framework: SitePatternLibrary.SiteFramework,
    currentStep: SelectorWizardStep,
    onSelectPattern: (String) -> Unit,
    onApplySelector: (String) -> Unit,
    onDismiss: () -> Unit,
    testResult: String? = null,
) {
    val suggestions = SitePatternLibrary.getSuggestedSelectors(framework, currentStep)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(TDMR.strings.custom_selector_pattern_library))
                Text(
                    text = framework.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (suggestions.isEmpty()) {
                    Text(
                        text = stringResource(TDMR.strings.selector_no_patterns),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(
                            TDMR.strings.selector_suggested_format,
                            stringResource(currentStep.titleRes),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Show test result if available
                    testResult?.let { result ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            ),
                        ) {
                            Text(
                                text = result,
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }

                    suggestions
                        .sortedByDescending { it.priority }
                        .forEach { preset ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = preset.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Row {
                                            TextButton(
                                                onClick = { onSelectPattern(preset.selector) },
                                            ) {
                                                Text(stringResource(TDMR.strings.custom_selector_test))
                                            }
                                            TextButton(
                                                onClick = { onApplySelector(preset.selector) },
                                            ) {
                                                Text(stringResource(TDMR.strings.custom_selector_use))
                                            }
                                        }
                                    }
                                    Text(
                                        text = preset.selector,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = preset.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                }

                // Show all frameworks option
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(TDMR.strings.selector_available_frameworks),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))

                SitePatternLibrary.getAvailableFrameworks().forEach { fw ->
                    Text(
                        text = "• ${fw.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fw == framework) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_close))
            }
        },
    )
}

/**
 * JavaScript code for element selection
 */
private val ELEMENT_SELECTOR_JS = """
(function() {
    let selectionMode = false;
    let highlightedElements = [];

    // Style for highlighted elements
    const highlightStyle = 'outline: 3px solid #4CAF50 !important; background-color: rgba(76, 175, 80, 0.2) !important;';
    const hoverStyle = 'outline: 3px dashed #2196F3 !important; background-color: rgba(33, 150, 243, 0.2) !important;';

    // Create style element
    const styleEl = document.createElement('style');
    styleEl.textContent = '.element-selector-highlight { ' + highlightStyle + ' } .element-selector-hover { ' + hoverStyle + ' }';
    document.head.appendChild(styleEl);

    // Generate CSS selector for element
    function getSelector(element) {
        if (element.id) {
            return '#' + CSS.escape(element.id);
        }

        let path = [];
        while (element && element.nodeType === Node.ELEMENT_NODE) {
            let selector = element.nodeName.toLowerCase();

            if (element.className) {
                const classes = element.className.toString().trim().split(/\s+/)
                    .filter(c => c && !c.startsWith('element-selector'))
                    .slice(0, 2);
                if (classes.length > 0) {
                    selector += '.' + classes.map(c => CSS.escape(c)).join('.');
                }
            }

            // Add nth-child if needed
            if (element.parentNode) {
                const siblings = Array.from(element.parentNode.children)
                    .filter(e => e.nodeName === element.nodeName);
                if (siblings.length > 1) {
                    const index = siblings.indexOf(element) + 1;
                    selector += ':nth-child(' + index + ')';
                }
            }

            path.unshift(selector);
            element = element.parentNode;

            // Limit depth
            if (path.length > 6) break;
        }

        return path.join(' > ');
    }

    // Handle element hover
    let lastHovered = null;
    document.addEventListener('mouseover', function(e) {
        if (!selectionMode) return;

        if (lastHovered) {
            lastHovered.classList.remove('element-selector-hover');
        }

        e.target.classList.add('element-selector-hover');
        lastHovered = e.target;
    }, true);

    // Handle element click
    document.addEventListener('click', function(e) {
        if (!selectionMode) return;

        e.preventDefault();
        e.stopPropagation();

        const element = e.target;
        const selector = getSelector(element);
        const outerHtml = element.outerHTML.substring(0, 500);
        const textContent = element.textContent.substring(0, 200);

        // Find closest block parents
        const closestA = element.closest('a');
        const closestLi = element.closest('li');
        const closestDiv = element.closest('div');

        const parentSelectors = {
            a: closestA ? getSelector(closestA) : null,
            li: closestLi ? getSelector(closestLi) : null,
            div: closestDiv ? getSelector(closestDiv) : null
        };

        // Notify Android
        if (window.AndroidSelector) {
            window.AndroidSelector.onElementClick(selector, outerHtml, textContent, JSON.stringify(parentSelectors));
        }

        // Highlight selected element
        element.classList.add('element-selector-highlight');
        highlightedElements.push(element);
    }, true);

    // Enable/disable selection mode
    window.enableSelectionMode = function(enabled) {
        selectionMode = enabled;
        if (!enabled && lastHovered) {
            lastHovered.classList.remove('element-selector-hover');
        }
        if (window.AndroidSelector) {
            window.AndroidSelector.setSelectionMode(enabled);
        }
    };

    // Highlight elements by selector
    window.highlightElements = function(selector) {
        try {
            const elements = document.querySelectorAll(selector);
            elements.forEach(el => {
                el.classList.add('element-selector-highlight');
                highlightedElements.push(el);
            });
        } catch (e) {
            console.error('Invalid selector:', selector);
        }
    };

    // Clear highlights
    window.clearHighlights = function() {
        highlightedElements.forEach(el => {
            el.classList.remove('element-selector-highlight');
        });
        highlightedElements = [];
    };

    // Test a selector and return count of matching elements
    window.testSelector = function(selector) {
        try {
            const elements = document.querySelectorAll(selector);
            // Also highlight found elements
            elements.forEach(el => {
                el.classList.add('element-selector-highlight');
                highlightedElements.push(el);
            });
            return elements.length;
        } catch (e) {
            console.error('Invalid selector:', selector);
            return 0;
        }
    };

})();
"""

/**
 * Detect search URL pattern from current URL
 * Returns Pair(searchUrlPattern, detectedKeyword) or null if not detected
 */
private fun detectSearchUrl(url: String, baseUrl: String): Pair<String, String>? {
    val baseUri = try {
        java.net.URI(baseUrl.trimEnd('/'))
    } catch (e: Exception) {
        null
    }
    val currentUri = try {
        java.net.URI(url)
    } catch (e: Exception) {
        null
    }

    if (baseUri != null && currentUri != null && baseUri.host != null && currentUri.host != null) {
        if (!currentUri.host.equals(baseUri.host, ignoreCase = true)) return null
    }

    // Common search parameter patterns
    val searchParams = listOf(
        "s" to Regex("""[?&]s=([^&]+)"""),
        "q" to Regex("""[?&]q=([^&]+)"""),
        "query" to Regex("""[?&]query=([^&]+)"""),
        "keyword" to Regex("""[?&]keyword=([^&]+)"""),
        "search" to Regex("""[?&]search=([^&]+)"""),
        "k" to Regex("""[?&]k=([^&]+)"""),
        "term" to Regex("""[?&]term=([^&]+)"""),
    )

    // Try each query-param pattern
    for ((param, regex) in searchParams) {
        val match = regex.find(url)
        if (match != null) {
            val keyword = java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
            if (keyword.isBlank()) continue

            val searchUrlPattern = url
                .replace(match.groupValues[1], "{query}")
                .replace("&&", "&")
                .replace("?&", "?")

            return Pair(searchUrlPattern, keyword)
        }
    }

    // Check for path-based search patterns like /search/keyword or /s/keyword
    val pathPatterns = listOf(
        Regex("""(/search/)([^/?]+)"""),
        Regex("""(/s/)([^/?]+)"""),
        Regex("""(/find/)([^/?]+)"""),
    )

    for (regex in pathPatterns) {
        val match = regex.find(url)
        if (match != null) {
            val keyword = java.net.URLDecoder.decode(match.groupValues[2], "UTF-8")
            if (keyword.isBlank()) continue
            val searchUrlPattern = url
                .replace(match.groupValues[2], "{query}")

            return Pair(searchUrlPattern, keyword)
        }
    }

    return null
}
