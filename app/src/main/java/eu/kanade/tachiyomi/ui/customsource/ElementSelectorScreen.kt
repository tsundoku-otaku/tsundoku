@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.customsource

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.annotation.Keep
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.LoadingState
import com.kevinnzou.web.WebView
import com.kevinnzou.web.rememberWebViewNavigator
import com.kevinnzou.web.rememberWebViewState
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.source.custom.SourceTestResult
import eu.kanade.tachiyomi.source.custom.SourceTestSection
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.components.material.Scaffold as TachiyomiScaffold

/** Which sections a source exposes; chosen up front so the wizard only shows the needed steps. */
data class SourceFeatures(
    val hasPopular: Boolean = true,
    val hasLatest: Boolean = true,
    val hasSearch: Boolean = true,
    // Per-section pagination; each adds a capture step.
    val popularPagination: Boolean = false,
    val latestPagination: Boolean = false,
    val searchPagination: Boolean = false,
    // Chapter list spans multiple pages.
    val chapterListPagination: Boolean = false,
    // Chapter list lives on a separate linked page (adds a step to tap that link).
    val chapterListSeparatePage: Boolean = false,
    // Chapters have sequential numeric URLs; generate from a pattern instead of scraping.
    val chapterGenerateFromPattern: Boolean = false,
) : java.io.Serializable

/**
 * Wizard steps; the active list is derived from [SourceFeatures] (reading steps always included).
 * Each step optionally maps to a [SourceTestSection] for in-place validation.
 */
enum class SelectorWizardStep(
    val titleRes: StringResource,
    val descriptionRes: StringResource,
    val detailedHelpRes: StringResource,
    val testSection: SourceTestSection? = null,
    // Shows the (i) help button. Only steps with non-obvious guidance.
    val detailed: Boolean = true,
) {
    POPULAR_LIST(
        TDMR.strings.selector_step_novel_card_title,
        TDMR.strings.selector_step_novel_card_desc,
        TDMR.strings.selector_step_novel_card_detail,
        SourceTestSection.POPULAR,
    ),
    POPULAR_PAGINATION(
        TDMR.strings.selector_step_pagination_title,
        TDMR.strings.selector_step_pagination_desc,
        TDMR.strings.selector_step_pagination_detail,
    ),
    LATEST_LIST(
        TDMR.strings.selector_step_new_novels_title,
        TDMR.strings.selector_step_new_novels_desc,
        TDMR.strings.selector_step_new_novels_detail,
        SourceTestSection.LATEST,
        detailed = false,
    ),
    LATEST_PAGINATION(
        TDMR.strings.selector_step_pagination_title,
        TDMR.strings.selector_step_pagination_desc,
        TDMR.strings.selector_step_pagination_detail,
    ),
    SEARCH(
        TDMR.strings.selector_step_search_title,
        TDMR.strings.selector_step_search_desc,
        TDMR.strings.selector_step_search_detail,
        SourceTestSection.SEARCH,
    ),
    SEARCH_PAGINATION(
        TDMR.strings.selector_step_pagination_title,
        TDMR.strings.selector_step_pagination_desc,
        TDMR.strings.selector_step_pagination_detail,
    ),
    NOVEL_DETAILS(
        TDMR.strings.selector_step_novel_details_title,
        TDMR.strings.selector_step_novel_details_desc,
        TDMR.strings.selector_step_novel_details_detail,
        detailed = false,
    ),
    CHAPTER_INDEX_LINK(
        TDMR.strings.selector_step_chapter_index_title,
        TDMR.strings.selector_step_chapter_index_desc,
        TDMR.strings.selector_step_chapter_index_detail,
        detailed = false,
    ),
    CHAPTER_RANGE(
        TDMR.strings.selector_step_chapter_range_title,
        TDMR.strings.selector_step_chapter_range_desc,
        TDMR.strings.selector_step_chapter_range_detail,
        detailed = false,
    ),
    CHAPTER_LIST(
        TDMR.strings.selector_step_chapter_list_title,
        TDMR.strings.selector_step_chapter_list_desc,
        TDMR.strings.selector_step_chapter_list_detail,
        detailed = false,
    ),
    CHAPTER_LIST_PAGINATION(
        TDMR.strings.selector_step_chapter_pagination_title,
        TDMR.strings.selector_step_chapter_pagination_desc,
        TDMR.strings.selector_step_chapter_pagination_detail,
        detailed = false,
    ),
    CHAPTER_DATE(
        TDMR.strings.selector_step_chapter_date_title,
        TDMR.strings.selector_step_chapter_date_desc,
        TDMR.strings.selector_step_chapter_date_detail,
        detailed = false,
    ),
    CHAPTER_CONTENT(
        TDMR.strings.selector_step_chapter_content_title,
        TDMR.strings.selector_step_chapter_content_desc,
        TDMR.strings.selector_step_chapter_content_detail,
        SourceTestSection.READING,
        detailed = false,
    ),
    REVIEW(
        TDMR.strings.selector_review_title,
        TDMR.strings.selector_review_desc,
        TDMR.strings.selector_step_complete_detail,
        detailed = false,
    ),
    ;

    companion object {
        /** Steps shown for the given features, in order. */
        fun activeSteps(features: SourceFeatures): List<SelectorWizardStep> = buildList {
            if (features.hasPopular) {
                add(POPULAR_LIST)
                if (features.popularPagination) add(POPULAR_PAGINATION)
            }
            if (features.hasLatest) {
                add(LATEST_LIST)
                if (features.latestPagination) add(LATEST_PAGINATION)
            }
            if (features.hasSearch) {
                add(SEARCH)
                if (features.searchPagination) add(SEARCH_PAGINATION)
            }
            add(NOVEL_DETAILS)
            if (features.chapterGenerateFromPattern) {
                // Generated mode: derive chapters from a numeric URL pattern; no list scraping.
                add(CHAPTER_RANGE)
            } else {
                if (features.chapterListSeparatePage) add(CHAPTER_INDEX_LINK)
                add(CHAPTER_LIST)
                if (features.chapterListPagination) add(CHAPTER_LIST_PAGINATION)
            }
            add(CHAPTER_CONTENT)
            add(REVIEW)
        }
    }
}

data class SelectorConfig(
    var sourceName: String = "",
    var baseUrl: String = "",
    var popularUrl: String = "",
    var latestUrl: String = "",
    var trendingSelector: String = "",
    var newNovelsSelector: String = "",
    var searchUrl: String = "",
    var searchKeyword: String = "",
    // Raw page-1 search URL; paired with searchPage2Url to derive {page}.
    var searchSampleUrl: String = "",
    // Listing page-2 URLs, diffed against page 1 to build the {page} template.
    var popularPage2Url: String = "",
    var latestPage2Url: String = "",
    var searchPage2Url: String = "",
    // Followed-link pagination selector (fallback when no URL pattern).
    var chapterListPaginationSelector: String = "",
    // Chapter-list page 1/2 URLs, diffed into a {novelUrl}-generalized {page} template.
    var chapterListPage1Url: String = "",
    var chapterListPage2Url: String = "",
    // Details-page selector linking to a separate chapter-list page.
    var chapterIndexLinkSelector: String = "",
    var novelCoverSelector: String = "",
    var novelTitleSelector: String = "",
    var novelPageTitleSelector: String = "",
    var novelDescriptionSelector: String = "",
    var novelCoverPageSelector: String = "",
    var novelTagsSelector: String = "",
    var chapterListSelector: String = "",
    var chapterItems: MutableList<String> = mutableListOf(),
    var chapterLinkSelector: String = "",
    var chapterDateSelector: String = "",
    var chapterContentSelector: String = "",
    // Generated chapter list (mode B): numeric URL pattern + range/count.
    var chapterUrlPattern: String = "",
    var chapterCountSelector: String = "",
    var chapterFirstNumber: Int? = null,
    var chapterLastNumber: Int? = null,
    // Some sites list newest chapter first; reverse so reading order is correct.
    var reverseChapters: Boolean = false,
    // Details page URL the user browsed; lets the reading test open a known novel without a listing.
    var sampleNovelUrl: String = "",
)

@Keep
class ElementSelectorJSInterface(
    private val onElementSelected: (String, String, String, String, String) -> Unit,
    private val onSelectionModeChanged: (Boolean) -> Unit,
) {
    @JavascriptInterface
    fun onElementClick(
        selector: String,
        outerHtml: String,
        textContent: String,
        parentSelectorsJson: String,
        href: String,
    ) {
        onElementSelected(selector, outerHtml, textContent, parentSelectorsJson, href)
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementSelectorScreen(
    screenModel: ElementSelectorScreenModel,
    initialUrl: String,
    initialSourceName: String = "",
    features: SourceFeatures = SourceFeatures(),
    onNavigateUp: () -> Unit,
    onSaveConfig: (SelectorConfig) -> Unit,
    onTestConfig: ((SelectorConfig, SourceTestSection, (SourceTestResult) -> Unit) -> Unit)? = null,
) {
    val steps = remember(features) { SelectorWizardStep.activeSteps(features) }
    // currentStep / config / currentUrl / search state live in the ScreenModel so they survive
    // Activity recreation (rotation). currentStep is derived non-null; writes go to the state value.
    val currentStep = screenModel.currentStepState.value ?: steps.first()
    val stepIndex = steps.indexOf(currentStep).coerceAtLeast(0)

    var config by screenModel.wizardConfigState
    var selectionModeEnabled by remember { mutableStateOf(false) }
    var lastSelectedElement by remember { mutableStateOf<SelectedElement?>(null) }
    var showSelectorDialog by remember { mutableStateOf(false) }
    var showSourceNameDialog by remember { mutableStateOf(false) }
    var showSelectedSheet by remember { mutableStateOf(false) }
    var currentUrl by screenModel.currentUrlState
    var pageLoadTick by remember { mutableStateOf(0) }

    // Dynamic search probe state
    var showSearchProbeDialog by remember { mutableStateOf(false) }
    var searchProbeQuery by screenModel.searchProbeQueryState
    var searchStatus by screenModel.searchStatusState

    // Inline test (per section) + final test state
    var testResult by remember { mutableStateOf<SourceTestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    // Live test (runs the step's selectors against the loaded page; shows a preview).
    var liveTest by remember { mutableStateOf<String?>(null) }

    // WebView state. Initialized from the persisted current URL so a rotation reload returns to the
    // page the user was on rather than the wizard's start page.
    val webViewState = rememberWebViewState(url = screenModel.currentUrlState.value.ifBlank { initialUrl })
    val navigator = rememberWebViewNavigator()
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Selections are kept per step so going back restores (and re-highlights) what was picked.
    val selectionsByStep = screenModel.selectionsByStep
    fun selectionsFor(step: SelectorWizardStep): SnapshotStateList<SelectedElement> =
        selectionsByStep.getOrPut(step) { mutableStateListOf() }
    val selectedElements = selectionsFor(currentStep)
    // DOM-detected list selector per step (POPULAR_LIST/LATEST_LIST/CHAPTER_LIST), so the commit
    // for the review/save doesn't clobber it with the weaker Kotlin heuristic.
    val detectedList = screenModel.detectedListState

    val jsInterface = remember {
        ElementSelectorJSInterface(
            onElementSelected = { selector, html, text, parentSelectorsJson, href ->
                val parentSelectors = try {
                    val json = org.json.JSONObject(parentSelectorsJson)
                    val map = mutableMapOf<String, String>()
                    json.keys().forEach { key ->
                        if (!json.isNull(key)) map[key] = json.getString(key)
                    }
                    map
                } catch (e: Exception) {
                    emptyMap()
                }
                lastSelectedElement = SelectedElement(selector, html, text, parentSelectors, href)
                showSelectorDialog = true
            },
            onSelectionModeChanged = { enabled -> selectionModeEnabled = enabled },
        )
    }

    fun injectSelectionScript() {
        webView?.evaluateJavascript(ELEMENT_SELECTOR_JS, null)
    }

    fun enableSelectionMode() {
        webView?.evaluateJavascript("window.enableSelectionMode(true);", null)
        selectionModeEnabled = true
    }

    fun disableSelectionMode() {
        webView?.evaluateJavascript("window.enableSelectionMode(false);", null)
        selectionModeEnabled = false
    }

    fun highlightSelector(selector: String) {
        webView?.evaluateJavascript("window.highlightElements('$selector');", null)
    }

    // parent selector + child (selector,label) list for the confirm dialog's up/down navigation.
    fun resolveRelatives(selector: String, callback: (String, List<Pair<String, String>>) -> Unit) {
        val wv = webView ?: return callback("", emptyList())
        wv.evaluateJavascript("window.relatives(${org.json.JSONObject.quote(selector)});") { result ->
            val json = result?.trim('"').orEmpty().replace("\\\"", "\"").replace("\\\\", "\\")
            val obj = runCatching { org.json.JSONObject(json) }.getOrNull()
            if (obj == null) {
                callback("", emptyList())
            } else {
                val parent = obj.optString("parent")
                val children = obj.optJSONArray("children")?.let { arr ->
                    (0 until arr.length()).map {
                        val c = arr.getJSONObject(it)
                        c.optString("selector") to c.optString("label")
                    }
                }.orEmpty()
                callback(parent, children)
            }
        }
    }

    fun clearHighlights() {
        webView?.evaluateJavascript("window.clearHighlights();", null)
    }

    fun autoDetectContent(callback: (String) -> Unit) {
        webView?.evaluateJavascript("window.autoDetectContent();") { result ->
            callback(result?.trim('"').orEmpty().replace("\\\"", "\""))
        }
    }

    fun autoDetectChapters(callback: (String) -> Unit) {
        webView?.evaluateJavascript("window.autoDetectChapters();") { result ->
            callback(result?.trim('"').orEmpty().replace("\\\"", "\""))
        }
    }

    // Derive one repeating selector from the picked items via the DOM (handles tables/lists/divs,
    // unordered picks). Falls back to the Kotlin heuristic if the JS returns nothing.
    fun detectItemSelector(selectors: List<String>, fallback: List<SelectedElement>, callback: (String) -> Unit) {
        val arg = org.json.JSONObject.quote(org.json.JSONArray(selectors).toString())
        val wv = webView
        if (wv == null) {
            callback(deriveListSelector(fallback))
            return
        }
        wv.evaluateJavascript("window.detectItemSelector($arg);") { result ->
            val detected = result?.trim('"').orEmpty().replace("\\\"", "\"").replace("\\\\", "\\")
            callback(detected.ifBlank { deriveListSelector(fallback) })
        }
    }

    // Flush every step's stored selections into the config so it can be tested/saved at any point.
    fun commitAllSelections() {
        steps.forEach { step -> saveSelectionsForStep(step, selectionsFor(step), config, detectedList[step]) }
        if (config.popularUrl.isBlank() && currentStep == SelectorWizardStep.POPULAR_LIST) {
            config.popularUrl = currentUrl
        }
        if (config.latestUrl.isBlank() && currentStep == SelectorWizardStep.LATEST_LIST) {
            config.latestUrl = currentUrl
        }
    }

    fun runSectionTest(section: SourceTestSection) {
        val test = onTestConfig ?: return
        commitAllSelections()
        isTesting = true
        test(config, section) { result ->
            isTesting = false
            testResult = result
        }
    }

    // Test the current step's selectors against the loaded page (no network). For list/chapter steps
    // the repeating selector is DOM-detected from the current picks first.
    fun runLiveTest() {
        val wv = webView ?: return
        val picks = selectedElements.toList()
        fun quote(s: String) = org.json.JSONObject.quote(s)
        fun fire(kind: String, cfg: org.json.JSONObject) {
            wv.evaluateJavascript("window.testStep(${quote(kind)}, ${quote(cfg.toString())});") { result ->
                liveTest = result?.trim('"').orEmpty().replace("\\\"", "\"").replace("\\\\", "\\")
            }
        }
        when (currentStep) {
            SelectorWizardStep.SEARCH -> {
                // Reuse the popular list selectors against the loaded search-results page.
                val listSel = config.trendingSelector
                if (listSel.isBlank()) return
                fire(
                    "list",
                    org.json.JSONObject().apply {
                        put("list", listSel)
                        if (config.novelTitleSelector.isNotBlank()) put("name", config.novelTitleSelector)
                        if (config.novelCoverSelector.isNotBlank()) put("cover", config.novelCoverSelector)
                    },
                )
            }
            SelectorWizardStep.POPULAR_LIST, SelectorWizardStep.LATEST_LIST -> {
                if (picks.isEmpty()) return
                detectItemSelector(picks.map { it.selector }, picks) { listSel ->
                    val cover = picks.getOrNull(0)?.let { relativize(it.selector, listSel) }.orEmpty()
                    val title = picks.getOrNull(1)?.let { relativize(it.selector, listSel) }.orEmpty()
                    fire(
                        "list",
                        org.json.JSONObject().apply {
                            put("list", listSel)
                            if (title.isNotBlank()) put("name", title)
                            if (cover.isNotBlank()) put("cover", cover)
                        },
                    )
                }
            }
            SelectorWizardStep.CHAPTER_LIST -> {
                if (picks.isEmpty()) return
                detectItemSelector(picks.map { it.selector }, picks) { listSel ->
                    val link = relativize(picks.first().selector, listSel)
                    fire(
                        "chapters",
                        org.json.JSONObject().apply {
                            put("list", listSel)
                            if (link.isNotBlank() && link != "a") put("name", link)
                            if (config.chapterDateSelector.isNotBlank()) put("date", config.chapterDateSelector)
                        },
                    )
                }
            }
            SelectorWizardStep.NOVEL_DETAILS -> {
                if (picks.isEmpty()) return
                fire(
                    "details",
                    org.json.JSONObject().apply {
                        picks.getOrNull(0)?.let { put("title", it.selector) }
                        picks.getOrNull(1)?.let { put("description", it.selector) }
                        picks.getOrNull(2)?.let { put("cover", it.selector) }
                        picks.getOrNull(3)?.let { put("genre", it.selector) }
                    },
                )
            }
            SelectorWizardStep.CHAPTER_CONTENT -> {
                val primary = picks.firstOrNull()?.selector ?: config.chapterContentSelector
                if (primary.isBlank()) return
                fire("content", org.json.JSONObject().apply { put("primary", primary) })
            }
            else -> {}
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

                    // Dynamic search capture: if the user gave a probe word and it shows up in the
                    // resulting URL (raw or percent-encoded), derive the {query} pattern.
                    if (currentStep == SelectorWizardStep.SEARCH && searchProbeQuery.isNotBlank()) {
                        val derived = deriveSearchUrl(newUrl, config.baseUrl, searchProbeQuery)
                        if (derived != null) {
                            config.searchUrl = derived
                            config.searchKeyword = searchProbeQuery
                            config.searchSampleUrl = newUrl // raw page-1 search URL for pagination diff
                            searchStatus = "ok:$derived"
                        } else {
                            // The probe word isn't in this URL yet. Notify instead of staying silent so
                            // the user knows to run the search (and that case must match).
                            searchStatus = "fail"
                        }
                    }
                }
                injectSelectionScript()
                // The fresh page injects a clean script with selectionMode=false; re-push the
                // current mode so the JS and the FAB never disagree (the "tap navigates instead of
                // selects, or vice-versa" bug after navigating).
                webView?.evaluateJavascript("window.enableSelectionMode($selectionModeEnabled);", null)
                pageLoadTick++
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http") || url.startsWith("https")) {
                    view?.loadUrl(url)
                    return true
                }
                return false
            }
        }
    }

    // Re-highlight the current step's saved selections after a step change or page (re)load, since
    // navigating wipes the DOM highlight classes.
    LaunchedEffect(currentStep, pageLoadTick) {
        clearHighlights()
        selectionsFor(currentStep).forEach { highlightSelector(it.selector) }
    }

    // Flush selections into config when the review step opens so its summary reflects every prior
    // step (was showing "not captured" because the commit only ran on Save).
    LaunchedEffect(currentStep) {
        if (currentStep == SelectorWizardStep.REVIEW) commitAllSelections()
        // Search needs the probe set up before browsing, or "search a" does nothing. Prompt on entry.
        if (currentStep == SelectorWizardStep.SEARCH && config.searchUrl.isBlank() && searchProbeQuery.isBlank()) {
            showSearchProbeDialog = true
        }
    }

    BackHandler(enabled = true) {
        when {
            selectionModeEnabled -> disableSelectionMode()
            navigator.canGoBack -> navigator.navigateBack()
            else -> onNavigateUp()
        }
    }

    val isReview = currentStep == SelectorWizardStep.REVIEW

    TachiyomiScaffold(
        topBar = {
            Column {
                AppBar(
                    title = stringResource(TDMR.strings.selector_title_format, stringResource(currentStep.titleRes)),
                    subtitle = "${stepIndex + 1}/${steps.size}",
                    navigateUp = onNavigateUp,
                    navigationIcon = Icons.Outlined.Close,
                    // Save lives only on the final Review step's button — no duplicate top-bar save.
                    actions = {
                        IconButton(onClick = { navigator.reload() }) {
                            Icon(Icons.Outlined.Refresh, stringResource(MR.strings.action_webview_refresh))
                        }
                    },
                )

                val loadingState = webViewState.loadingState
                if (loadingState is LoadingState.Loading) {
                    LinearProgressIndicator(
                        progress = { loadingState.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (isReview) {
                ReviewContent(
                    config = config,
                    modifier = Modifier.weight(1f),
                    onRunFullTest = { runSectionTest(SourceTestSection.ALL) },
                )
            } else {
                val liveTestable = currentStep in setOf(
                    SelectorWizardStep.POPULAR_LIST,
                    SelectorWizardStep.LATEST_LIST,
                    SelectorWizardStep.SEARCH,
                    SelectorWizardStep.CHAPTER_LIST,
                    SelectorWizardStep.NOVEL_DETAILS,
                    SelectorWizardStep.CHAPTER_CONTENT,
                )
                val testEnabled = when (currentStep) {
                    // Search has no element picks; it reuses the popular list selectors on the loaded
                    // results page, so enable once those exist.
                    SelectorWizardStep.SEARCH -> config.trendingSelector.isNotBlank()
                    SelectorWizardStep.CHAPTER_CONTENT ->
                        selectedElements.isNotEmpty() || config.chapterContentSelector.isNotBlank()
                    else -> selectedElements.isNotEmpty()
                }
                // Live-derived {page} template for the current pagination step (page-1 = the section
                // URL captured on its list step, page-2 = the page the user navigated to now).
                val paginationPair: Pair<String, String>? = when (currentStep) {
                    SelectorWizardStep.POPULAR_PAGINATION -> config.popularUrl to currentUrl
                    SelectorWizardStep.LATEST_PAGINATION ->
                        config.latestUrl.ifBlank { config.popularUrl } to currentUrl
                    SelectorWizardStep.SEARCH_PAGINATION ->
                        config.searchSampleUrl.ifBlank { config.searchUrl } to currentUrl
                    SelectorWizardStep.CHAPTER_LIST_PAGINATION ->
                        config.chapterListPage1Url.ifBlank { config.sampleNovelUrl } to currentUrl
                    else -> null
                }
                val paginationStatus = paginationPair
                    ?.let { (p1, p2) -> derivePageTemplate(p1, p2) }
                    ?.let { stringResource(TDMR.strings.selector_pagination_detected, it) }
                StepInstructionCard(
                    step = currentStep,
                    selectedCount = selectedElements.size,
                    searchStatus = searchStatus,
                    onViewSelected = { showSelectedSheet = true },
                    onTest = if (liveTestable) ({ runLiveTest() }) else null,
                    testEnabled = testEnabled,
                    compact = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                    paginationStatus = paginationStatus,
                    // Latest can reuse the popular page-token instead of navigating to page 2 again.
                    onReusePagination = if (
                        currentStep == SelectorWizardStep.LATEST_PAGINATION &&
                        config.popularPage2Url.isNotBlank()
                    ) {
                        {
                            val latestP1 = config.latestUrl.ifBlank { config.popularUrl }
                            applyPagePattern(config.popularUrl, config.popularPage2Url, latestP1)?.let {
                                config.latestPage2Url = it
                            }
                            if (stepIndex < steps.size - 1) {
                                screenModel.currentStepState.value = steps[stepIndex + 1]
                            }
                        }
                    } else {
                        null
                    },
                    onSetupSearch = if (currentStep == SelectorWizardStep.SEARCH) {
                        { showSearchProbeDialog = true }
                    } else {
                        null
                    },
                    onAutoDetect = when (currentStep) {
                        SelectorWizardStep.CHAPTER_LIST -> {
                            {
                                autoDetectChapters { selector ->
                                    @Suppress("ktlint:standard:max-line-length")
                                    if (selector.isNotBlank()) {
                                        selectedElements.add(
                                            SelectedElement(selector, "", "(auto-detected)", emptyMap()),
                                        )
                                        highlightSelector(selector)
                                    }
                                }
                            }
                        }
                        SelectorWizardStep.CHAPTER_CONTENT -> {
                            {
                                autoDetectContent { selector ->
                                    @Suppress("ktlint:standard:max-line-length")
                                    if (selector.isNotBlank()) {
                                        selectedElements.add(
                                            SelectedElement(selector, "", "(auto-detected)", emptyMap()),
                                        )
                                        highlightSelector(selector)
                                    }
                                }
                            }
                        }
                        else -> null
                    },
                )

                NavigationBar(
                    canGoBack = navigator.canGoBack,
                    canGoForward = navigator.canGoForward,
                    onBack = { navigator.navigateBack() },
                    onForward = { navigator.navigateForward() },
                    currentUrl = currentUrl,
                    onUrlSubmit = { navigator.loadUrl(it) },
                )

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

                    if (selectionModeEnabled) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(8.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Text(
                                stringResource(TDMR.strings.selector_tap_to_select),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            StepNavigationBar(
                isFirstStep = stepIndex == 0,
                isLastStep = currentStep == SelectorWizardStep.REVIEW,
                canProceed = canProceedToNextStep(currentStep, selectedElements, config),
                onPrevious = {
                    if (stepIndex > 0) screenModel.currentStepState.value = steps[stepIndex - 1]
                },
                onNext = {
                    val step = currentStep
                    val advance = {
                        if (stepIndex < steps.size - 1) {
                            screenModel.currentStepState.value = steps[stepIndex + 1]
                        }
                    }
                    when (step) {
                        // List steps: detect the repeating selector from the DOM, then save + advance.
                        SelectorWizardStep.POPULAR_LIST, SelectorWizardStep.CHAPTER_LIST -> {
                            if (step == SelectorWizardStep.POPULAR_LIST && config.popularUrl.isBlank()) {
                                config.popularUrl = currentUrl
                            }
                            // Remember the chapter-list page-1 URL to diff against page 2 later.
                            if (step == SelectorWizardStep.CHAPTER_LIST && config.chapterListPage1Url.isBlank()) {
                                config.chapterListPage1Url = currentUrl
                            }
                            if (selectedElements.isNotEmpty()) {
                                val list = selectedElements.toList()
                                detectItemSelector(list.map { it.selector }, list) { detected ->
                                    detectedList[step] = detected
                                    saveSelectionsForStep(step, list, config, detected)
                                    advance()
                                }
                            } else {
                                saveSelectionsForStep(step, selectedElements, config)
                                advance()
                            }
                        }
                        SelectorWizardStep.LATEST_LIST -> {
                            if (config.latestUrl.isBlank()) config.latestUrl = currentUrl
                            if (selectedElements.isNotEmpty()) {
                                val list = selectedElements.toList()
                                detectItemSelector(list.map { it.selector }, list) { detected ->
                                    detectedList[step] = detected
                                    saveSelectionsForStep(step, list, config, detected)
                                    advance()
                                }
                            } else {
                                saveSelectionsForStep(step, selectedElements, config)
                                advance()
                            }
                        }
                        // Listing pagination: the page-2 URL the user navigated to (diffed later).
                        SelectorWizardStep.POPULAR_PAGINATION -> {
                            config.popularPage2Url = currentUrl
                            advance()
                        }
                        SelectorWizardStep.LATEST_PAGINATION -> {
                            config.latestPage2Url = currentUrl
                            advance()
                        }
                        SelectorWizardStep.SEARCH_PAGINATION -> {
                            config.searchPage2Url = currentUrl
                            advance()
                        }
                        // Record the results page so search pagination can be diffed even when the
                        // user searched manually instead of via the probe.
                        SelectorWizardStep.SEARCH -> {
                            if (config.searchSampleUrl.isBlank() &&
                                currentUrl.trimEnd('/') != config.baseUrl.trimEnd('/')
                            ) {
                                config.searchSampleUrl = currentUrl
                            }
                            saveSelectionsForStep(step, selectedElements, config)
                            advance()
                        }
                        // Page-2 URL of the chapter list (diffed into a {page} template).
                        SelectorWizardStep.CHAPTER_LIST_PAGINATION -> {
                            config.chapterListPage2Url = currentUrl
                            saveSelectionsForStep(step, selectedElements, config)
                            advance()
                        }
                        SelectorWizardStep.NOVEL_DETAILS -> {
                            // Remember the novel page so the reading test can open it without a listing.
                            config.sampleNovelUrl = currentUrl
                            saveSelectionsForStep(step, selectedElements, config)
                            advance()
                        }
                        else -> {
                            saveSelectionsForStep(step, selectedElements, config)
                            advance()
                        }
                    }
                },
                onComplete = {
                    commitAllSelections()
                    showSourceNameDialog = true
                },
                selectionModeEnabled = selectionModeEnabled,
                onToggleSelection = {
                    if (selectionModeEnabled) disableSelectionMode() else enableSelectionMode()
                },
            )
        }
    }

    if (showSelectedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSelectedSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SelectedElementsPanel(
                elements = selectedElements,
                onRemove = { element ->
                    selectedElements.remove(element)
                    clearHighlights()
                    selectedElements.forEach { highlightSelector(it.selector) }
                },
                onHighlight = { element -> highlightSelector(element.selector) },
            )
        }
    }

    if (showSelectorDialog && lastSelectedElement != null) {
        val reHighlight = {
            clearHighlights()
            selectedElements.forEach { highlightSelector(it.selector) }
        }
        SelectorConfirmDialog(
            element = lastSelectedElement!!,
            onResolveRelatives = ::resolveRelatives,
            onHighlight = { sel ->
                clearHighlights()
                highlightSelector(sel)
            },
            onConfirm = { selector ->
                selectedElements.add(lastSelectedElement!!.copy(selector = selector))
                showSelectorDialog = false
                lastSelectedElement = null
                reHighlight()
            },
            onDismiss = {
                showSelectorDialog = false
                lastSelectedElement = null
                reHighlight()
            },
        )
    }

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

    if (showSearchProbeDialog) {
        SearchProbeDialog(
            initialQuery = searchProbeQuery,
            onConfirm = { query ->
                searchProbeQuery = query
                searchStatus = null
                showSearchProbeDialog = false
            },
            onDismiss = { showSearchProbeDialog = false },
        )
    }

    if (isTesting) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(TDMR.strings.selector_testing)) },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = { },
        )
    }

    testResult?.let { result ->
        TestResultDialog(result = result, onDismiss = { testResult = null })
    }

    liveTest?.let { json ->
        LiveTestDialog(
            json = json,
            showReverse = currentStep == SelectorWizardStep.CHAPTER_LIST,
            reversed = config.reverseChapters,
            onReverseChange = { config = config.copy(reverseChapters = it) },
            onDismiss = { liveTest = null },
        )
    }
}

@Composable
private fun StepInstructionCard(
    step: SelectorWizardStep,
    selectedCount: Int,
    searchStatus: String?,
    onViewSelected: () -> Unit,
    onTest: (() -> Unit)? = null,
    testEnabled: Boolean = false,
    onSetupSearch: (() -> Unit)? = null,
    onAutoDetect: (() -> Unit)? = null,
    paginationStatus: String? = null,
    onReusePagination: (() -> Unit)? = null,
    // Landscape: drop the description text and tighten padding so the WebView keeps usable height.
    compact: Boolean = false,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = if (compact) 2.dp else 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp)) {
            var showHelp by remember { mutableStateOf(false) }
            if (!compact || step.detailed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!compact) {
                        Text(
                            text = stringResource(step.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (step.detailed) {
                        IconButton(onClick = { showHelp = true }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = stringResource(TDMR.strings.selector_step_help),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
            if (showHelp) {
                AlertDialog(
                    onDismissRequest = { showHelp = false },
                    title = { Text(stringResource(step.titleRes)) },
                    text = { Text(stringResource(step.detailedHelpRes)) },
                    confirmButton = {
                        TextButton(onClick = { showHelp = false }) {
                            Text(stringResource(MR.strings.action_ok))
                        }
                    },
                )
            }

            // Captured search confirmation (compact, replaces the detailed help on the search step).
            if (step == SelectorWizardStep.SEARCH && searchStatus != null) {
                Spacer(modifier = Modifier.height(4.dp))
                val ok = searchStatus.startsWith("ok:")
                Text(
                    text = if (ok) {
                        stringResource(TDMR.strings.selector_search_captured, searchStatus.removePrefix("ok:"))
                    } else {
                        stringResource(TDMR.strings.selector_search_not_captured)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            // Detected page pattern echo (pagination steps), mirroring the search-captured line.
            if (paginationStatus != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = paginationStatus,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Action row: per-step helpers + selected count, all on one compact line.
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                @Suppress("ktlint:standard:max-line-length")
                if (onReusePagination != null) {
                    CompactAction(
                        Icons.Outlined.Refresh,
                        TDMR.strings.selector_reuse_popular_pagination,
                        onReusePagination,
                    )
                }
                if (onSetupSearch != null) {
                    CompactAction(Icons.Outlined.Search, TDMR.strings.selector_setup_search, onSetupSearch)
                }
                if (onAutoDetect != null) {
                    CompactAction(Icons.Filled.TouchApp, TDMR.strings.selector_auto_detect, onAutoDetect)
                }
                if (onTest != null) {
                    CompactAction(
                        Icons.Filled.Science,
                        TDMR.strings.selector_test_section,
                        onTest,
                        enabled = testEnabled,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (selectedCount > 0) {
                    @Suppress("ktlint:standard:max-line-length")
                    FilledTonalButton(
                        onClick = onViewSelected,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 4.dp,
                        ),
                    ) {
                        Icon(Icons.Filled.PlaylistAddCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(TDMR.strings.selector_view_selected, selectedCount),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: StringResource,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Renders the live-test preview JSON (from window.testStep) for the current step. For chapter lists
 * it also offers a reverse toggle (some sites list newest-first).
 */
@Composable
private fun LiveTestDialog(
    json: String,
    showReverse: Boolean,
    reversed: Boolean,
    onReverseChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val obj = remember(json) { runCatching { org.json.JSONObject(json) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.selector_test_results)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (obj == null) {
                    Text(stringResource(TDMR.strings.selector_no_result), style = MaterialTheme.typography.bodySmall)
                } else {
                    val ok = obj.optBoolean("ok", false)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (ok) Icons.Filled.CheckCircle else Icons.Outlined.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(8.dp))
                        val summary =
                            @Suppress("ktlint:standard:max-line-length")
                            when {
                                obj.has("count") -> "${obj.optInt("count")} found, ${obj.optInt("withUrl")} with URL" +
                                    (
                                        obj.opt(
                                            "next",
                                        )?.takeIf { it != org.json.JSONObject.NULL }?.let { ", next page: $it" }
                                            ?: ""
                                        )
                                obj.has("length") -> "Content length: ${obj.optInt("length")} chars"
                                obj.has("title") -> "Title: ${obj.optString("title").ifBlank { "(none)" }}"
                                else -> obj.optString("message", "")
                            }
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                    }

                    // List/chapter rows.
                    obj.optJSONArray("rows")?.let { rows ->
                        Spacer(Modifier.height(8.dp))
                        for (i in 0 until rows.length()) {
                            val r = rows.getJSONObject(i)
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    r.optString("name").ifBlank { "(no name)" },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val sub = buildList {
                                    r.optString("url").ifBlank { null }?.let { add(it) }
                                    r.optString("date").ifBlank { null }?.let { add(it) }
                                }.joinToString("  •  ")
                                if (sub.isNotBlank()) {
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Divider()
                        }
                    }

                    // Details fields.
                    if (obj.has("title")) {
                        listOf(
                            "Title" to obj.optString("title"),
                            "Description" to obj.optString("description"),
                            "Cover" to obj.optString("cover"),
                            "Tags" to obj.optString("genre"),
                        ).forEach { (k, v) ->
                            if (v.isNotBlank()) {
                                Text(
                                    "$k: $v",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Content preview.
                    @Suppress("ktlint:standard:max-line-length")
                    obj.optString("preview").ifBlank { null }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (showReverse) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Switch(checked = reversed, onCheckedChange = onReverseChange)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(TDMR.strings.selector_reverse_chapters),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(MR.strings.action_ok)) }
        },
    )
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
    var urlText by remember(currentUrl) { mutableStateOf(currentUrl) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, enabled = canGoBack, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(MR.strings.nav_zone_prev))
        }
        IconButton(onClick = onForward, enabled = canGoForward, modifier = Modifier.size(36.dp)) {
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
                IconButton(onClick = { onUrlSubmit(urlText) }) {
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = stringResource(TDMR.strings.selector_selected_elements),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))

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
                    @Suppress("ktlint:standard:max-line-length")
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

@Composable
private fun StepNavigationBar(
    isFirstStep: Boolean,
    isLastStep: Boolean,
    canProceed: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onComplete: () -> Unit,
    selectionModeEnabled: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = !isFirstStep, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                stringResource(TDMR.strings.custom_selector_previous),
            )
        }

        // Select-element toggle lives between prev/next so it is reachable without the FAB. Hidden on
        // the final review step where there is nothing to pick.
        if (!isLastStep && onToggleSelection != null) {
            FilledTonalButton(
                onClick = onToggleSelection,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = if (selectionModeEnabled) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
            ) {
                Icon(
                    if (selectionModeEnabled) Icons.Filled.TouchApp else Icons.Filled.Edit,
                    null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (selectionModeEnabled) {
                        stringResource(TDMR.strings.selector_selection_on)
                    } else {
                        stringResource(TDMR.strings.selector_select_element)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        if (isLastStep) {
            Button(
                onClick = onComplete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Filled.CheckCircle, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(TDMR.strings.custom_selector_save_source))
            }
        } else {
            Button(onClick = onNext, enabled = canProceed) {
                Text(stringResource(TDMR.strings.custom_selector_next_step))
            }
        }
    }
}

/** Final summary: captured selectors at a glance, a full end-to-end test, then save. */
@Composable
private fun ReviewContent(
    config: SelectorConfig,
    modifier: Modifier = Modifier,
    onRunFullTest: () -> Unit,
) {
    val none = stringResource(TDMR.strings.selector_review_none)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(TDMR.strings.selector_review_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))

        ReviewRow("Popular list", config.trendingSelector, none)
        ReviewRow("Cover", config.novelCoverSelector, none)
        ReviewRow("Title", config.novelTitleSelector, none)
        ReviewRow("Popular page 2", config.popularPage2Url, none)
        ReviewRow("Latest list", config.newNovelsSelector, none)
        ReviewRow("Latest page 2", config.latestPage2Url, none)
        ReviewRow("Search URL", config.searchUrl, none)
        ReviewRow("Search page 2", config.searchPage2Url, none)
        ReviewRow("Details title", config.novelPageTitleSelector, none)
        ReviewRow("Details description", config.novelDescriptionSelector, none)
        ReviewRow("Details cover", config.novelCoverPageSelector, none)
        ReviewRow("Details tags", config.novelTagsSelector, none)
        ReviewRow("Chapter list link", config.chapterIndexLinkSelector, none)
        ReviewRow("Chapter list", config.chapterListSelector, none)
        ReviewRow("Chapter list pagination", config.chapterListPaginationSelector, none)
        ReviewRow("Chapter URL pattern", config.chapterUrlPattern, none)
        ReviewRow(
            "Chapter range",
            @Suppress("ktlint:standard:max-line-length")
            if (config.chapterUrlPattern.isNotBlank()) {
                "${config.chapterFirstNumber ?: 1}..${config.chapterLastNumber?.toString() ?: config.chapterCountSelector.ifBlank {
                    "?"
                }}"
            } else {
                ""
            },
            none,
        )
        ReviewRow("Chapter date", config.chapterDateSelector, none)
        ReviewRow("Chapter content", config.chapterContentSelector, none)

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRunFullTest, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(TDMR.strings.selector_run_full_test))
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String, none: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(140.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value.ifBlank { none },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (value.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SelectorConfirmDialog(
    element: SelectedElement,
    onResolveRelatives: (String, (String, List<Pair<String, String>>) -> Unit) -> Unit,
    onHighlight: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editedSelector by remember { mutableStateOf(element.selector) }
    var showHtml by remember { mutableStateOf(false) }
    var parentSelector by remember { mutableStateOf("") }
    var children by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // Whenever the working selector changes, highlight it on the page and refresh up/down navigation
    // so the user can correct an imprecise tap.
    LaunchedEffect(editedSelector) {
        if (editedSelector.isNotBlank()) {
            onHighlight(editedSelector)
            onResolveRelatives(editedSelector) { parent, kids ->
                parentSelector = parent
                children = kids
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.custom_selector_confirm_selection)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(TDMR.strings.selector_selected_text),
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

                Spacer(modifier = Modifier.height(12.dp))

                // Up/down DOM navigation — pick the right element when the tap wasn't precise.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    @Suppress("ktlint:standard:max-line-length")
                    OutlinedButton(
                        onClick = { if (parentSelector.isNotBlank()) editedSelector = parentSelector },
                        enabled = parentSelector.isNotBlank(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 4.dp,
                        ),
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(TDMR.strings.selector_select_parent),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (children.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(TDMR.strings.selector_select_child),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    children.take(12).forEach { (sel, label) ->
                        @Suppress("ktlint:standard:max-line-length")
                        OutlinedButton(
                            onClick = { editedSelector = sel },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 10.dp,
                                vertical = 4.dp,
                            ),
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                label.ifBlank { sel },
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = editedSelector,
                    onValueChange = { editedSelector = it },
                    label = { Text(stringResource(TDMR.strings.custom_selector_css_selector)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showHtml = !showHtml }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Code,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (showHtml) {
                            stringResource(TDMR.strings.selector_hide_html)
                        } else {
                            stringResource(TDMR.strings.selector_show_html)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(visible = showHtml) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    ) {
                        Text(
                            text = element.outerHtml,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            ),
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
            Button(onClick = { onSave(sourceName) }, enabled = sourceName.isNotBlank()) {
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
    val href: String = "",
)

private fun canProceedToNextStep(
    step: SelectorWizardStep,
    selectedElements: List<SelectedElement>,
    config: SelectorConfig,
): Boolean {
    return when (step) {
        // One tap (title) is enough; a second optional tap captures the cover for thumbnails.
        SelectorWizardStep.POPULAR_LIST -> selectedElements.isNotEmpty()
        // Optional: by default latest reuses the popular card layout, so no taps are required —
        // only the latest page URL (captured on Next) is needed.
        SelectorWizardStep.LATEST_LIST -> true
        SelectorWizardStep.SEARCH -> true // Optional
        SelectorWizardStep.NOVEL_DETAILS -> selectedElements.isNotEmpty()
        // Required: the separate chapter-list page can't be reached without this link.
        SelectorWizardStep.CHAPTER_INDEX_LINK -> selectedElements.isNotEmpty()
        // Need the first and last chapter to derive the numeric URL pattern + range.
        SelectorWizardStep.CHAPTER_RANGE -> selectedElements.size >= 2 || config.chapterUrlPattern.isNotEmpty()
        SelectorWizardStep.CHAPTER_LIST -> selectedElements.isNotEmpty() || config.chapterItems.isNotEmpty()
        SelectorWizardStep.CHAPTER_CONTENT -> selectedElements.isNotEmpty() ||
            config.chapterContentSelector.isNotEmpty()
        // Pagination / date steps are all optional.
        SelectorWizardStep.POPULAR_PAGINATION,
        SelectorWizardStep.LATEST_PAGINATION,
        SelectorWizardStep.SEARCH_PAGINATION,
        SelectorWizardStep.CHAPTER_LIST_PAGINATION,
        SelectorWizardStep.CHAPTER_DATE,
        SelectorWizardStep.REVIEW,
        -> true
    }
}

/**
 * Strips the list-container prefix off a document-unique selector so it resolves inside a card
 * (the source parses title/cover/link relative to each item). Falls back to the trailing segment.
 */
private fun relativize(full: String, listSel: String): String {
    if (full.isBlank()) return ""
    if (listSel.isNotBlank()) {
        // Exact prefix: strip the whole card path, keep the full descendant path.
        if (full.startsWith("$listSel > ")) return full.removePrefix("$listSel > ")
        // The card selector was generalized (positional bits stripped) so it may not prefix-match
        // the picked path verbatim. Cut at the card's last segment wherever it appears, keeping the
        // intermediate descendant path rather than collapsing to just the trailing element.
        val cardLast = listSel.substringAfterLast(" > ")
        val marker = "$cardLast > "
        val idx = full.lastIndexOf(marker)
        if (idx >= 0) return full.substring(idx + marker.length)
    }
    return full.substringAfterLast(" > ")
}

/**
 * Diffs page-1/page-2 URLs into the page-2 URL with the differing digits replaced by {page}. Mirrors
 * ElementSelectorScreenModel.derivePagedFromPair (preview = saved template). Null if no numeric diff.
 */
internal fun derivePageTemplate(p1: String, p2: String): String? {
    if (p1.isBlank() || p2.isBlank() || p1 == p2) return null
    val maxLen = minOf(p1.length, p2.length)
    var pre = 0
    while (pre < maxLen && p1[pre] == p2[pre]) pre++
    var suf = 0
    while (suf < maxLen - pre && p1[p1.length - 1 - suf] == p2[p2.length - 1 - suf]) suf++
    val mid = p2.substring(pre, p2.length - suf)
    if (mid.isBlank() || !mid.any { it.isDigit() }) return null
    return p2.substring(0, pre) + mid.replace(Regex("""\d+"""), "{page}") + p2.substring(p2.length - suf)
}

/**
 * Applies the popular page1/page2 page-token to [target] (latest page-1 URL), so latest needn't be
 * navigated to page 2. Handles query-param (`page=2`) and path-segment (`/page/2`) forms.
 */
internal fun applyPagePattern(popularP1: String, popularP2: String, target: String): String? {
    if (popularP1.isBlank() || popularP2.isBlank() || popularP1 == popularP2 || target.isBlank()) return null
    val maxLen = minOf(popularP1.length, popularP2.length)
    var pre = 0
    while (pre < maxLen && popularP1[pre] == popularP2[pre]) pre++
    var suf = 0
    while (suf < maxLen - pre && popularP1[popularP1.length - 1 - suf] == popularP2[popularP2.length - 1 - suf]) suf++
    val inserted = popularP2.substring(pre, popularP2.length - suf)
    if (!inserted.any { it.isDigit() }) return null
    val token = inserted.trim('/', '?', '&')
    if (token.isBlank()) return null
    return if (token.contains('=')) {
        val sep = if (target.contains('?')) '&' else '?'
        target.trimEnd('/') + sep + token
    } else {
        target.trimEnd('/') + "/" + token
    }
}

private fun stripPositional(selector: String): String =
    selector.replace(Regex(""":nth-of-type\(\d+\)"""), "").replace(Regex(""":nth-child\(\d+\)"""), "")

/**
 * Derives the repeating card selector from tapped elements: common path prefix, else a shared
 * closest container (li/div/a) reported by the JS.
 */
private fun deriveListSelector(elements: List<SelectedElement>): String {
    val prefix = findCommonSelector(elements)
    if (prefix.isNotBlank()) return stripPositional(prefix)
    for (tag in listOf("li", "div", "a")) {
        val vals = elements.mapNotNull { it.parentSelectors[tag]?.ifBlank { null } }
        if (vals.size == elements.size && vals.distinct().size == 1) return stripPositional(vals.first())
    }
    return elements.firstOrNull()?.selector.orEmpty()
}

private fun saveSelectionsForStep(
    step: SelectorWizardStep,
    selectedElements: List<SelectedElement>,
    config: SelectorConfig,
    detectedListSelector: String? = null,
) {
    when (step) {
        SelectorWizardStep.POPULAR_LIST -> {
            // One tap = title only; two taps = cover then title. Cover/title stored relative to the
            // repeating item selector so the source resolves them per item.
            if (selectedElements.isNotEmpty()) {
                val listSel = detectedListSelector?.ifBlank { null } ?: deriveListSelector(selectedElements)
                config.trendingSelector = listSel
                if (selectedElements.size == 1) {
                    config.novelCoverSelector = ""
                    config.novelTitleSelector = relativize(selectedElements[0].selector, listSel)
                } else {
                    config.novelCoverSelector = relativize(selectedElements[0].selector, listSel)
                    config.novelTitleSelector = relativize(selectedElements[1].selector, listSel)
                }
            }
        }
        SelectorWizardStep.LATEST_LIST -> {
            if (selectedElements.isNotEmpty()) {
                config.newNovelsSelector = detectedListSelector?.ifBlank { null }
                    ?: deriveListSelector(selectedElements)
            } else {
                // No taps: reuse the popular card layout so latest still works (just needs the URL).
                config.newNovelsSelector = config.trendingSelector
            }
        }
        SelectorWizardStep.CHAPTER_LIST_PAGINATION -> {
            // Strip positional bits (:nth-of-type) so the "next page" selector keeps matching on
            // page 2+ where the element's index differs from page 1.
            selectedElements.firstOrNull()?.let {
                config.chapterListPaginationSelector = stripPositional(it.selector)
            }
        }
        SelectorWizardStep.CHAPTER_INDEX_LINK -> {
            // On the details page; stored as a document selector (resolved against the details doc).
            selectedElements.firstOrNull()?.let { config.chapterIndexLinkSelector = it.selector }
        }
        SelectorWizardStep.NOVEL_DETAILS -> {
            // Fixed order: title, description, cover, then any number of tags merged into one
            // comma-separated selector group (the source joins their texts into one genre string).
            selectedElements.getOrNull(0)?.let { config.novelPageTitleSelector = it.selector }
            selectedElements.getOrNull(1)?.let { config.novelDescriptionSelector = it.selector }
            selectedElements.getOrNull(2)?.let { config.novelCoverPageSelector = it.selector }
            val tagSelectors = selectedElements.drop(3).map { it.selector }.filter { it.isNotBlank() }
            if (tagSelectors.isNotEmpty()) {
                config.novelTagsSelector = tagSelectors.joinToString(", ")
            }
        }
        SelectorWizardStep.CHAPTER_LIST -> {
            config.chapterItems.clear()
            config.chapterItems.addAll(selectedElements.map { it.selector })
            if (selectedElements.isNotEmpty()) {
                val listSel = detectedListSelector?.ifBlank { null } ?: deriveListSelector(selectedElements)
                config.chapterListSelector = listSel
                // Chapter link relative to the list item (usually "a"); blank means "first <a>".
                config.chapterLinkSelector = relativize(selectedElements.first().selector, listSel)
            }
        }
        SelectorWizardStep.CHAPTER_DATE -> {
            selectedElements.firstOrNull()?.let {
                config.chapterDateSelector = relativize(it.selector, config.chapterListSelector)
            }
        }
        SelectorWizardStep.CHAPTER_RANGE -> {
            // First + last chapter hrefs → numeric URL pattern + range. Optional 3rd pick = an
            // element holding the total chapter count (overrides the last number).
            val first = selectedElements.getOrNull(0)?.href.orEmpty()
            val last = selectedElements.getOrNull(1)?.href.orEmpty()
            eu.kanade.tachiyomi.source.custom.deriveGenericChapterPattern(
                first,
                last,
                config.baseUrl,
                config.sampleNovelUrl.ifBlank { null },
            )?.let { (pattern, n1, n2) ->
                config.chapterUrlPattern = pattern
                config.chapterFirstNumber = minOf(n1, n2)
                config.chapterLastNumber = maxOf(n1, n2)
            }
            selectedElements.getOrNull(2)?.let {
                config.chapterCountSelector = it.selector
                config.chapterLastNumber = null // count element is authoritative
            }
        }
        SelectorWizardStep.CHAPTER_CONTENT -> {
            selectedElements.firstOrNull()?.let { config.chapterContentSelector = it.selector }
        }
        // Listing pagination (page-2 URL) is captured in onNext; these have no element selection.
        SelectorWizardStep.POPULAR_PAGINATION,
        SelectorWizardStep.LATEST_PAGINATION,
        SelectorWizardStep.SEARCH_PAGINATION,
        SelectorWizardStep.SEARCH,
        SelectorWizardStep.REVIEW,
        -> { /* No element selections */ }
    }
}

private fun findCommonSelector(elements: List<SelectedElement>): String {
    if (elements.isEmpty()) return ""
    if (elements.size == 1) return elements.first().selector

    val selectors = elements.map { it.selector }
    val parts = selectors.map { it.split(" > ", " ").toMutableList() }

    val commonParts = mutableListOf<String>()
    val minLength = parts.minOf { it.size }

    for (i in 0 until minLength) {
        val part = parts.first()[i]
        if (parts.all { it[i] == part }) commonParts.add(part) else break
    }

    return commonParts.joinToString(" > ")
}

/**
 * Dynamic search probe dialog. The user types a word to search for; after they perform the
 * search on the site, the resulting URL is captured and turned into a {query} pattern.
 */
@Composable
private fun SearchProbeDialog(
    initialQuery: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.selector_search_probe_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(TDMR.strings.selector_search_probe_message),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(TDMR.strings.selector_search_probe_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(query.trim()) }, enabled = query.isNotBlank()) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

/**
 * Shows the result of running the configured source (or one section of it) through a live test.
 * Each step renders its parsed sample data so the user sees what the selectors actually returned.
 */
@Composable
private fun TestResultDialog(
    result: SourceTestResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.selector_test_results)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                result.steps.forEach { (step, stepResult) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = if (stepResult.success) Icons.Filled.CheckCircle else Icons.Outlined.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp).padding(top = 2.dp),
                            tint = if (stepResult.success) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = step.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stepResult.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            stepResult.data?.forEach { (k, v) ->
                                Text(
                                    text = "$k: $v",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Divider()
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
    )
}

/**
 * JavaScript code for element selection
 */
internal val ELEMENT_SELECTOR_JS = """
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

    function cleanClasses(element) {
        if (!element.className || typeof element.className !== 'string') return [];
        return element.className.trim().split(/\s+/)
            .filter(c => c && !c.startsWith('element-selector') && !/^(active|selected|current|hover|on)${'$'}/i.test(c));
    }

    // Build the shortest selector segment that is unique among siblings.
    function segmentFor(element) {
        let tag = element.nodeName.toLowerCase();
        let classes = cleanClasses(element);

        // Prefer a single class that is unique within the parent.
        if (element.parentNode) {
            for (let c of classes) {
                let sel = tag + '.' + CSS.escape(c);
                let matches = Array.from(element.parentNode.children).filter(e => e.matches(sel));
                if (matches.length === 1) return sel;
            }
        }

        if (classes.length > 0) {
            tag += '.' + classes.slice(0, 2).map(c => CSS.escape(c)).join('.');
        }

        // Disambiguate with nth-of-type only when still ambiguous.
        if (element.parentNode) {
            let sameTag = Array.from(element.parentNode.children).filter(e => e.nodeName === element.nodeName);
            if (sameTag.length > 1 && classes.length === 0) {
                tag += ':nth-of-type(' + (sameTag.indexOf(element) + 1) + ')';
            }
        }
        return tag;
    }

    // Generate a CSS selector for an element, preferring stable id/class anchors and
    // stopping early once the selector is document-unique.
    function getSelector(element) {
        if (element.id && document.querySelectorAll('#' + CSS.escape(element.id)).length === 1) {
            return '#' + CSS.escape(element.id);
        }

        let path = [];
        while (element && element.nodeType === Node.ELEMENT_NODE && element.nodeName.toLowerCase() !== 'body') {
            path.unshift(segmentFor(element));
            let candidate = path.join(' > ');
            try {
                if (document.querySelectorAll(candidate).length === 1) return candidate;
            } catch (e) { /* keep walking */ }
            element = element.parentElement;
            if (path.length > 5) break;
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

        // Resolve a link href (the element itself or its closest anchor), for numeric URL patterns.
        const hrefEl = (element.tagName === 'A' && element.href) ? element : closestA;
        const href = hrefEl ? hrefEl.href : '';

        // Notify Android
        if (window.AndroidSelector) {
            window.AndroidSelector.onElementClick(selector, outerHtml, textContent, JSON.stringify(parentSelectors), href);
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

    // Auto-detect the main chapter text block: pick the element with the highest
    // text density (most characters that are not inside links/nav).
    window.autoDetectContent = function() {
        let best = null;
        let bestScore = 0;
        // Score by paragraph text (chapter bodies are <p>-heavy), penalise link text.
        document.querySelectorAll('div, section, article, main').forEach(el => {
            let pText = Array.from(el.querySelectorAll('p')).reduce((n, p) => n + (p.innerText || '').length, 0);
            let linkText = Array.from(el.querySelectorAll('a')).reduce((n, a) => n + (a.innerText || '').length, 0);
            let score = pText - linkText;
            if (score > bestScore && (el.innerText || '').length > 200) {
                bestScore = score;
                best = el;
            }
        });
        if (!best) return '';
        // Unwrap generic wrappers (e.g. #novel) down to the tightest element that still holds nearly
        // all the text — avoids returning a huge page container.
        let guard = 0;
        while (best && guard++ < 6) {
            let kids = Array.from(best.children).filter(c => c.nodeType === 1);
            let bigKid = kids.find(k =>
                (k.innerText || '').length >= (best.innerText || '').length * 0.85 &&
                k.querySelectorAll('p').length > 0
            );
            if (bigKid) best = bigKid; else break;
        }
        best.classList.add('element-selector-highlight');
        highlightedElements.push(best);
        return getSelector(best);
    };

    // Auto-detect the chapter list container: the element holding the most
    // same-host links that look like chapters.
    window.autoDetectChapters = function() {
        let host = location.hostname;
        let best = null;
        let bestCount = 0;
        document.querySelectorAll('ul, ol, div, section, table').forEach(el => {
            let links = Array.from(el.querySelectorAll(':scope > li a, :scope > a, :scope > tr a, :scope a'));
            let chapterLinks = links.filter(a => {
                try {
                    return a.hostname === host && (a.innerText || '').trim().length > 0;
                } catch (e) { return false; }
            });
            // Prefer the tightest container (fewest descendants per link).
            if (chapterLinks.length > bestCount && chapterLinks.length >= 2) {
                bestCount = chapterLinks.length;
                best = el;
            }
        });
        if (best) {
            best.classList.add('element-selector-highlight');
            highlightedElements.push(best);
            return getSelector(best);
        }
        return '';
    };

    // Given the selectors of several picked items (e.g. 5 chapters or 2 cards), derive ONE
    // repeating selector that matches them all and their siblings — even when the picks are
    // unordered or in a table/list/div. Returns a generalized (no nth-of-type) selector.
    window.detectItemSelector = function(selectorsJson) {
        let sels;
        try { sels = JSON.parse(selectorsJson); } catch (e) { return ''; }
        let els = sels.map(s => { try { return document.querySelector(s); } catch (e) { return null; } })
            .filter(Boolean);
        if (els.length === 0) return '';

        function genSelf(e) {
            let t = e.nodeName.toLowerCase();
            let c = cleanClasses(e);
            return c.length ? t + '.' + CSS.escape(c[0]) : t;
        }
        function lca(nodes) {
            let a = nodes[0];
            while (a && !nodes.every(n => a.contains(n))) a = a.parentElement;
            return a;
        }
        // Generalize an element to a selector that matches its repeating siblings (the card).
        function generalizeRepeating(el) {
            let sel = genSelf(el);
            try { if (document.querySelectorAll(sel).length >= 2) return tighten(el, sel); } catch (e) {}
            if (el.parentElement && el.parentElement.nodeName.toLowerCase() !== 'body') {
                let scoped = genSelf(el.parentElement) + ' > ' + sel;
                try { if (document.querySelectorAll(scoped).length >= 2) return tighten(el, scoped); } catch (e) {}
            }
            return tighten(el, sel);
        }

        // A bare-tag selector (e.g. "li", "a", "div > a") matches list items anywhere on the page —
        // nav menus, footers, related-novel widgets. When the selector carries no class/id anchor,
        // scope it under the nearest ancestor that has an id or class so it only matches the intended
        // list. Keeps the selector if no classed ancestor tightens it.
        function tighten(el, sel) {
            if (/[.#]/.test(sel)) return sel; // already anchored by a class or id
            let p = el.parentElement;
            let depth = 0;
            while (p && p.nodeName.toLowerCase() !== 'body' && depth < 6) {
                let anchor = '';
                if (p.id) {
                    anchor = '#' + CSS.escape(p.id);
                } else {
                    let pc = cleanClasses(p);
                    if (pc.length) anchor = p.nodeName.toLowerCase() + '.' + CSS.escape(pc[0]);
                }
                if (anchor) {
                    let scoped = anchor + ' ' + sel;
                    try {
                        if (el.matches(scoped) && document.querySelectorAll(scoped).length >= 2) return scoped;
                    } catch (e) {}
                }
                p = p.parentElement;
                depth++;
            }
            return sel;
        }

        // CASE 1: every pick is the same kind of element (e.g. several chapter links / titles) — the
        // picks themselves ARE the repeating items.
        let self0 = genSelf(els[0]);
        if (els.length > 1 && els.every(e => genSelf(e) === self0)) {
            try {
                if (els.every(e => e.matches(self0))) {
                    let count = document.querySelectorAll(self0).length;
                    if (count > els.length * 6) {
                        // Too broad (bare "a" catching nav): scope under the common ancestor.
                        let anc = lca(els);
                        if (anc && anc.nodeName.toLowerCase() !== 'body') {
                            let scoped = genSelf(anc) + ' ' + self0;
                            try { if (els.every(e => e.matches(scoped))) return tighten(els[0], scoped); } catch (e) {}
                        }
                    }
                    return tighten(els[0], self0);
                }
            } catch (e) {}
        }

        // CASE 2: picks are different parts of ONE card (e.g. cover + title). The repeating unit is
        // their common ancestor (the card); cover/title get resolved RELATIVE to it. Returning a
        // descendant here was the bug that made link resolution grab the wrong <a>.
        if (els.length > 1) {
            let anc = lca(els);
            if (anc && anc.nodeName.toLowerCase() !== 'body') {
                return generalizeRepeating(anc);
            }
        }

        // Single pick: itself if it repeats, else its repeating parent card.
        if (els.length === 1) {
            try { if (document.querySelectorAll(self0).length >= 2) return tighten(els[0], self0); } catch (e) {}
            if (els[0].parentElement) return generalizeRepeating(els[0].parentElement);
        }
        return tighten(els[0], self0);
    };

    // Returns the parent selector + child elements of a selector, so the confirm dialog can walk up
    // AND down the DOM when the tap wasn't precise.
    window.relatives = function(sel) {
        let el;
        try { el = document.querySelector(sel); } catch (e) { el = null; }
        if (!el) return JSON.stringify({ children: [] });
        let parent = (el.parentElement && el.parentElement.nodeName.toLowerCase() !== 'body')
            ? getSelector(el.parentElement) : '';
        let children = [];
        Array.from(el.children).forEach(c => {
            if (c.nodeType === 1) {
                let cls = (c.className && typeof c.className === 'string')
                    ? '.' + c.className.trim().split(/\s+/)[0] : '';
                let text = (c.innerText || '').trim().slice(0, 30);
                children.push({ label: c.nodeName.toLowerCase() + cls + (text ? ': ' + text : ''), selector: getSelector(c) });
            }
        });
        return JSON.stringify({ parent: parent, children: children });
    };

    // Run a step's selectors against the CURRENTLY LOADED page and return a preview, so the user
    // verifies exactly what they see (no network round-trip). kind: list | chapters | details | content.
    window.testStep = function(kind, cfgJson) {
        let cfg;
        try { cfg = JSON.parse(cfgJson); } catch (e) { return JSON.stringify({ ok: false, message: 'bad config' }); }

        function hrefOf(el) {
            if (!el) return '';
            if (el.tagName === 'A' && el.href) return el.href;
            let d = el.querySelector('a[href]'); if (d) return d.href;
            let p = el.closest('a[href]'); if (p) return p.href;
            return '';
        }
        function txtIn(scope, sel) {
            if (!sel) return '';
            try { let e = scope.querySelector(sel); return e ? (e.innerText || '').trim() : ''; } catch (x) { return ''; }
        }
        function q(sel) { try { return sel ? document.querySelectorAll(sel) : []; } catch (e) { return []; } }
        // Mirror CustomNovelSource.resolveImageUrl so the preview matches what the source will parse.
        function imgUrl(el) {
            if (!el) return '';
            const attrs = ['data-src','data-original','data-lazy-src','data-lazy','data-cfsrc','srcset','src','poster','content'];
            for (const a of attrs) {
                let v = el.getAttribute && el.getAttribute(a);
                if (v) { v = v.trim().split(' ')[0].split(',')[0].trim(); if (v) { try { return new URL(v, document.baseURI).href; } catch (e) { return v; } } }
            }
            const style = el.getAttribute && el.getAttribute('style');
            if (style) { const m = style.match(/url\(\s*['"]?([^'")]+)/); if (m) return m[1]; }
            return el.src || '';
        }
        function imgIn(scope, sel) { try { return sel ? imgUrl(scope.querySelector(sel)) : ''; } catch (e) { return ''; } }

        try {
            if (kind === 'list' || kind === 'chapters') {
                let items = q(cfg.list);
                let rows = [];
                for (let i = 0; i < items.length && i < 4; i++) {
                    let el = items[i];
                    rows.push({
                        name: cfg.name ? txtIn(el, cfg.name) : (el.innerText || '').trim().slice(0, 80),
                        url: hrefOf(el),
                        date: cfg.date ? txtIn(el, cfg.date) : '',
                        cover: cfg.cover ? imgIn(el, cfg.cover) : '',
                    });
                }
                let withUrl = Array.from(items).map(hrefOf).filter(Boolean).length;
                let next = (cfg.next != null && cfg.next !== '') ? !!document.querySelector(cfg.next) : null;
                return JSON.stringify({ ok: items.length > 0 && withUrl > 0, count: items.length, withUrl: withUrl, rows: rows, next: next });
            }
            if (kind === 'details') {
                let title = txtIn(document, cfg.title);
                return JSON.stringify({
                    ok: !!title,
                    title: title,
                    description: txtIn(document, cfg.description).slice(0, 200),
                    cover: cfg.cover ? imgIn(document, cfg.cover) : '',
                    genre: txtIn(document, cfg.genre),
                });
            }
            if (kind === 'content') {
                let e = cfg.primary ? document.querySelector(cfg.primary) : null;
                let t = e ? (e.innerText || '').trim() : '';
                return JSON.stringify({ ok: t.length > 0, length: t.length, preview: t.slice(0, 200) });
            }
        } catch (e) {
            return JSON.stringify({ ok: false, message: String(e) });
        }
        return JSON.stringify({ ok: false, message: 'unknown kind' });
    };

})();
"""

/**
 * Derives a {query} template from a URL produced by searching for [userQuery]. Handles raw and
 * percent-encoded occurrences (incl. '+' for spaces), any param/path segment. Null if not found.
 */
internal fun deriveSearchUrl(url: String, baseUrl: String, userQuery: String): String? {
    if (userQuery.isBlank()) return null

    val baseHost = runCatching { java.net.URI(baseUrl.trimEnd('/')).host }.getOrNull()?.removePrefix("www.")
    val currentHost = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")
    if (baseHost != null && currentHost != null && !currentHost.equals(baseHost, ignoreCase = true)) {
        return null
    }

    val encoded = java.net.URLEncoder.encode(userQuery, "UTF-8")
    val encodedSpaces = encoded.replace("+", "%20")

    // Try the most specific encodings first so we don't partially match.
    val candidates = listOf(encoded, encodedSpaces, userQuery)
        .distinct()
        .filter { it.isNotBlank() }

    for (candidate in candidates) {
        val idx = url.indexOf(candidate, ignoreCase = true)
        if (idx >= 0) {
            return url.substring(0, idx) + "{query}" + url.substring(idx + candidate.length)
        }
    }
    return null
}
