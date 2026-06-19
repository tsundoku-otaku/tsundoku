package eu.kanade.tachiyomi.ui.customsource

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.source.custom.ChapterSelectors
import eu.kanade.tachiyomi.source.custom.ContentSelectors
import eu.kanade.tachiyomi.source.custom.CustomSourceConfig
import eu.kanade.tachiyomi.source.custom.CustomSourceManager
import eu.kanade.tachiyomi.source.custom.DetailSelectors
import eu.kanade.tachiyomi.source.custom.MangaListSelectors
import eu.kanade.tachiyomi.source.custom.SourceSelectors
import eu.kanade.tachiyomi.source.custom.SourceTestResult
import eu.kanade.tachiyomi.source.custom.SourceTestSection
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Screen model for managing Element Selector state and converting
 * user selections into a custom source configuration.
 */
class ElementSelectorScreenModel(
    private val initialUrl: String,
    private val initialSourceName: String = "",
    private val customSourceManager: CustomSourceManager = Injekt.get(),
) : StateScreenModel<ElementSelectorScreenModel.State>(State()) {

    private val json = Json { prettyPrint = true }

    // ---- Wizard UI state, held here (not in the composable) so it survives Activity recreation
    // on rotation — the same reason the rest of the app keeps screen state in ScreenModels. The
    // composable delegates to these directly. ----
    val wizardConfigState = mutableStateOf(
        SelectorConfig(sourceName = initialSourceName, baseUrl = initialUrl),
    )
    val currentStepState = mutableStateOf<SelectorWizardStep?>(null)
    val currentUrlState = mutableStateOf(initialUrl)
    val searchProbeQueryState = mutableStateOf("")
    val searchStatusState = mutableStateOf<String?>(null)
    val selectionsByStep = mutableStateMapOf<SelectorWizardStep, SnapshotStateList<SelectedElement>>()
    val detectedListState = mutableStateMapOf<SelectorWizardStep, String>()

    data class State(
        val isLoading: Boolean = false,
        val sourceName: String = "",
        val baseUrl: String = "",
        val config: SelectorConfig = SelectorConfig(),
        val savedSuccessfully: Boolean = false,
        val error: String? = null,
        // Preview data for confirmation UI
        val previewData: StepPreviewData? = null,
    )

    init {
        mutableState.update {
            it.copy(
                sourceName = initialSourceName,
                baseUrl = initialUrl,
            )
        }
    }

    fun updateSourceName(name: String) {
        mutableState.update { it.copy(sourceName = name) }
    }

    fun updatePreviewData(preview: StepPreviewData) {
        mutableState.update { it.copy(previewData = preview) }
    }

    fun clearPreview() {
        mutableState.update { it.copy(previewData = null) }
    }

    fun saveConfig(config: SelectorConfig) {
        screenModelScope.launch {
            mutableState.update { it.copy(isLoading = true) }

            try {
                // Convert SelectorConfig to CustomSourceConfig
                val sourceConfig = convertToCustomSourceConfig(config)

                // Save via CustomSourceManager
                val result = customSourceManager.createSource(sourceConfig)

                result.fold(
                    onSuccess = {
                        mutableState.update {
                            it.copy(
                                isLoading = false,
                                savedSuccessfully = true,
                                config = config,
                            )
                        }
                    },
                    onFailure = { e ->
                        mutableState.update {
                            it.copy(
                                isLoading = false,
                                error = e.message,
                            )
                        }
                    },
                )
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message,
                    )
                }
            }
        }
    }

    private fun convertToCustomSourceConfig(selectorConfig: SelectorConfig): CustomSourceConfig {
        val baseUrl = selectorConfig.baseUrl.trimEnd('/')

        // Popular and search reuse the popular card layout. When popular was toggled off (latest-only
        // setup), fall back to the latest list selector so those sections still resolve items instead
        // of throwing "List selector is empty".
        val primaryListSelector = selectorConfig.trendingSelector.ifBlank { selectorConfig.newNovelsSelector }

        // Page 1 = the verbatim URL the user browsed. Page 2+ template = page1/page2 diff (handles
        // ?page= vs /page/ alike). The source uses page1 for page 1 and the template afterwards, so
        // the first page keeps or omits the page token exactly as the site does.
        val popularUrl = selectorConfig.popularUrl.trim().ifBlank { baseUrl }.trimEnd('/')
        val popularPagedUrl = derivePagedFromPair(popularUrl, selectorConfig.popularPage2Url.trim())

        val hasLatest = selectorConfig.newNovelsSelector.isNotEmpty() ||
            selectorConfig.latestPage2Url.isNotBlank()
        val latestPage1 = selectorConfig.latestUrl.ifBlank { selectorConfig.popularUrl }.trim().trimEnd('/')
        val latestUrl = if (hasLatest) latestPage1.ifBlank { popularUrl } else null
        val latestPagedUrl = if (hasLatest) {
            derivePagedFromPair(
                latestPage1,
                selectorConfig.latestPage2Url.trim(),
            )
        } else {
            null
        }

        val searchUrl = selectorConfig.searchUrl.ifBlank { "$baseUrl/?s={query}" }
        val searchPagedUrl = run {
            val p1 = selectorConfig.searchSampleUrl.trim()
            val p2 = selectorConfig.searchPage2Url.trim()
            if (p1.isNotBlank() && p2.isNotBlank()) {
                derivePagedFromPair(p1, p2)?.let { insertQueryPlaceholder(it, selectorConfig.searchKeyword) }
            } else {
                null
            }
        }

        return CustomSourceConfig(
            name = selectorConfig.sourceName.ifEmpty {
                initialSourceName.ifEmpty { "Custom Source" }
            },
            baseUrl = baseUrl,
            language = "en",
            reverseChapters = selectorConfig.reverseChapters,
            popularUrl = popularUrl,
            latestUrl = latestUrl,
            searchUrl = searchUrl,
            popularPagedUrl = popularPagedUrl,
            latestPagedUrl = latestPagedUrl,
            searchPagedUrl = searchPagedUrl,
            sampleNovelUrl = selectorConfig.sampleNovelUrl.ifBlank { null },
            testSearchQuery = selectorConfig.searchKeyword.ifBlank { null },
            selectors = SourceSelectors(
                popular = MangaListSelectors(
                    list = primaryListSelector,
                    link = selectorConfig.novelTitleSelector.ifBlank { null },
                    title = selectorConfig.novelTitleSelector.ifBlank { null },
                    cover = selectorConfig.novelCoverSelector.ifBlank { null },
                ),
                // Separate latest selectors when the latest list layout was captured; otherwise
                // latest falls back to popular at parse time.
                latest = selectorConfig.newNovelsSelector.ifBlank { null }?.let {
                    MangaListSelectors(
                        list = it,
                        link = selectorConfig.novelTitleSelector.ifBlank { null },
                        title = selectorConfig.novelTitleSelector.ifBlank { null },
                        cover = selectorConfig.novelCoverSelector.ifBlank { null },
                    )
                },
                search = MangaListSelectors(
                    list = primaryListSelector, // Usually same as popular
                    link = selectorConfig.novelTitleSelector.ifBlank { null },
                    title = selectorConfig.novelTitleSelector.ifBlank { null },
                    cover = selectorConfig.novelCoverSelector.ifBlank { null },
                ),
                details = DetailSelectors(
                    title = selectorConfig.novelPageTitleSelector,
                    description = selectorConfig.novelDescriptionSelector,
                    cover = selectorConfig.novelCoverPageSelector,
                    genre = selectorConfig.novelTagsSelector,
                ),
                chapters = ChapterSelectors(
                    list = selectorConfig.chapterListSelector,
                    link = selectorConfig.chapterLinkSelector.ifBlank { null },
                    name = selectorConfig.chapterLinkSelector.ifBlank { null },
                    date = selectorConfig.chapterDateSelector.ifBlank { null },
                    nextPage = selectorConfig.chapterListPaginationSelector.ifBlank { null },
                    indexLinkSelector = selectorConfig.chapterIndexLinkSelector.ifBlank { null },
                    urlPattern = selectorConfig.chapterUrlPattern.ifBlank { null },
                    countSelector = selectorConfig.chapterCountSelector.ifBlank { null },
                    firstNumber = selectorConfig.chapterFirstNumber,
                    lastNumber = selectorConfig.chapterLastNumber,
                ),
                content = ContentSelectors(
                    primary = selectorConfig.chapterContentSelector,
                    // Boilerplate stripping is handled by the source's built-in cleanup pipeline.
                    removeBoilerplate = true,
                ),
            ),
        )
    }

    /**
     * Diffs two URLs that differ only by page number, returning the page-2 URL with the differing
     * digits replaced by {page}. Works regardless of param vs path form. Null if they don't differ.
     */
    private fun derivePagedFromPair(p1: String, p2: String): String? {
        if (p2.isBlank() || p1 == p2) return null
        val maxLen = minOf(p1.length, p2.length)
        var pre = 0
        while (pre < maxLen && p1[pre] == p2[pre]) pre++
        var suf = 0
        while (suf < maxLen - pre && p1[p1.length - 1 - suf] == p2[p2.length - 1 - suf]) suf++
        val mid = p2.substring(pre, p2.length - suf)
        if (mid.isBlank() || !mid.any { it.isDigit() }) return null
        val templated = mid.replace(Regex("""\d+"""), "{page}")
        return p2.substring(0, pre) + templated + p2.substring(p2.length - suf)
    }

    private fun insertQueryPlaceholder(url: String, query: String): String? {
        if (query.isBlank()) return null
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        val candidates = listOf(enc, enc.replace("+", "%20"), query).distinct().filter { it.isNotBlank() }
        for (cand in candidates) {
            val idx = url.indexOf(cand, ignoreCase = true)
            if (idx >= 0) return url.substring(0, idx) + "{query}" + url.substring(idx + cand.length)
        }
        return null
    }

    fun testConfig(
        selectorConfig: SelectorConfig,
        section: SourceTestSection = SourceTestSection.ALL,
        onResult: (SourceTestResult) -> Unit,
    ) {
        screenModelScope.launch {
            val result = customSourceManager.testSource(convertToCustomSourceConfig(selectorConfig), section)
            onResult(result)
        }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    fun exportConfigAsJson(config: SelectorConfig): String {
        return json.encodeToString(convertToCustomSourceConfig(config))
    }
}

data class StepPreviewData(
    val stepName: String,
    val detectedTitle: String? = null,
    val detectedUrl: String? = null,
    val detectedImageUrl: String? = null,
    val detectedDescription: String? = null,
    val detectedChaptersTotal: Int? = null,
    val detectedChapterFirstUrl: String? = null,
    val detectedChapterLastUrl: String? = null,
    val sampleContentStart: String? = null,
    val sampleContentEnd: String? = null,
    val searchResultsCount: Int? = null,
    val sampleSearchResults: List<String>? = null,
)
