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
        val savedSuccessfully: Boolean = false,
        val error: String? = null,
    )

    fun saveConfig(config: SelectorConfig, features: SourceFeatures) {
        screenModelScope.launch {
            try {
                val sourceConfig = convertToCustomSourceConfig(config, features)
                customSourceManager.createSource(sourceConfig).fold(
                    onSuccess = { mutableState.update { it.copy(savedSuccessfully = true) } },
                    onFailure = { e -> mutableState.update { it.copy(error = e.message) } },
                )
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message) }
            }
        }
    }

    private fun convertToCustomSourceConfig(
        selectorConfig: SelectorConfig,
        features: SourceFeatures,
    ): CustomSourceConfig {
        val baseUrl = selectorConfig.baseUrl.trimEnd('/')

        // The shared card selectors feed whichever listing(s) the user enabled. Popular falls back to
        // the latest list selector for a latest-only setup.
        val cardList = selectorConfig.trendingSelector.ifBlank { selectorConfig.newNovelsSelector }
        val cardTitle = selectorConfig.novelTitleSelector.ifBlank { null }
        val cardCover = selectorConfig.novelCoverSelector.ifBlank { null }
        val generate = features.chapterGenerateFromPattern

        // Page 1 = verbatim browsed URL; page 2+ = page1/page2 diff. Each gated by its section +
        // pagination toggle so the saved config matches the ticked boxes and round-trips.
        val popularUrl = if (features.hasPopular) {
            selectorConfig.popularUrl.trim().ifBlank { baseUrl }.trimEnd('/')
        } else {
            ""
        }
        val popularPagedUrl = if (features.hasPopular && features.popularPagination) {
            derivePagedFromPair(popularUrl, selectorConfig.popularPage2Url.trim())
        } else {
            null
        }

        val latestUrl = if (features.hasLatest) {
            selectorConfig.latestUrl.ifBlank { selectorConfig.popularUrl }.trim().trimEnd('/').ifBlank { baseUrl }
        } else {
            null
        }
        val latestPagedUrl = if (features.hasLatest && features.latestPagination) {
            derivePagedFromPair(latestUrl.orEmpty(), selectorConfig.latestPage2Url.trim())
        } else {
            null
        }

        // Numbered chapter-list pagination template (preferred over the next-button selector).
        val chapterPagedPattern = if (!generate && features.chapterListPagination) {
            deriveChapterListPagePattern(
                selectorConfig.chapterListPage1Url.ifBlank { selectorConfig.sampleNovelUrl },
                selectorConfig.chapterListPage2Url,
                baseUrl,
                selectorConfig.sampleNovelUrl,
            )
        } else {
            null
        }

        val searchUrl = if (features.hasSearch) {
            selectorConfig.searchUrl.ifBlank { "$baseUrl/?s={query}" }
        } else {
            ""
        }
        val searchPagedUrl = if (features.hasSearch && features.searchPagination) {
            val p1 = selectorConfig.searchSampleUrl.trim()
            val p2 = selectorConfig.searchPage2Url.trim()
            if (p1.isNotBlank() && p2.isNotBlank()) {
                derivePagedFromPair(p1, p2)?.let { insertQueryPlaceholder(it, selectorConfig.searchKeyword) }
            } else {
                null
            }
        } else {
            null
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
            testSearchQuery = if (features.hasSearch) selectorConfig.searchKeyword.ifBlank { null } else null,
            selectors = SourceSelectors(
                popular = MangaListSelectors(
                    list = if (features.hasPopular) cardList else "",
                    link = if (features.hasPopular) cardTitle else null,
                    title = if (features.hasPopular) cardTitle else null,
                    cover = if (features.hasPopular) cardCover else null,
                ),
                latest = if (features.hasLatest) {
                    MangaListSelectors(
                        list = selectorConfig.newNovelsSelector.ifBlank { cardList },
                        link = cardTitle,
                        title = cardTitle,
                        cover = cardCover,
                    )
                } else {
                    null
                },
                search = if (features.hasSearch) {
                    MangaListSelectors(
                        list = cardList,
                        link = cardTitle,
                        title = cardTitle,
                        cover = cardCover,
                    )
                } else {
                    null
                },
                details = DetailSelectors(
                    title = selectorConfig.novelPageTitleSelector,
                    description = selectorConfig.novelDescriptionSelector,
                    cover = selectorConfig.novelCoverPageSelector,
                    genre = selectorConfig.novelTagsSelector,
                ),
                chapters = ChapterSelectors(
                    list = selectorConfig.chapterListSelector,
                    link = if (generate) null else selectorConfig.chapterLinkSelector.ifBlank { null },
                    name = if (generate) null else selectorConfig.chapterLinkSelector.ifBlank { null },
                    date = if (generate) null else selectorConfig.chapterDateSelector.ifBlank { null },
                    nextPage = if (!generate && features.chapterListPagination) {
                        selectorConfig.chapterListPaginationSelector.ifBlank { null }
                    } else {
                        null
                    },
                    pagedUrlPattern = chapterPagedPattern,
                    indexLinkSelector = if (!generate && features.chapterListSeparatePage) {
                        selectorConfig.chapterIndexLinkSelector.ifBlank { null }
                    } else {
                        null
                    },
                    urlPattern = if (generate) selectorConfig.chapterUrlPattern.ifBlank { null } else null,
                    countSelector = if (generate) selectorConfig.chapterCountSelector.ifBlank { null } else null,
                    firstNumber = if (generate) selectorConfig.chapterFirstNumber else null,
                    lastNumber = if (generate) selectorConfig.chapterLastNumber else null,
                ),
                content = ContentSelectors(
                    primary = selectorConfig.chapterContentSelector,
                    // Boilerplate stripping is handled by the source's built-in cleanup pipeline.
                    removeBoilerplate = true,
                ),
            ),
        )
    }

    /** Diffs two URLs into the page-2 URL with the differing digits replaced by {page}. */
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

    /**
     * Diffs the chapter-list page 1/2 URLs into a numbered template, generalizing the novel path to
     * {novelUrl}. e.g. ("/series/abc/", "/series/abc/?page=2") -> "{novelUrl}/?page={page}".
     */
    private fun deriveChapterListPagePattern(
        page1: String,
        page2: String,
        baseUrl: String,
        novelUrl: String,
    ): String? {
        val p1 = page1.trim()
        val p2 = page2.trim()
        if (p1.isBlank() || p2.isBlank()) return null
        val templated = derivePagedFromPair(p1, p2) ?: return null
        val base = baseUrl.trimEnd('/')
        var rel = if (templated.startsWith(base)) templated.removePrefix(base) else templated
        val novelRel = novelUrl.trim()
            .let { if (it.startsWith(base)) it.removePrefix(base) else it }
            .trimEnd('/')
        if (novelRel.isNotBlank() && rel.startsWith(novelRel)) {
            rel = "{novelUrl}" + rel.removePrefix(novelRel)
        }
        return rel.ifBlank { null }
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
        features: SourceFeatures,
        section: SourceTestSection = SourceTestSection.ALL,
        onResult: (SourceTestResult) -> Unit,
    ) {
        screenModelScope.launch {
            val result = customSourceManager.testSource(convertToCustomSourceConfig(selectorConfig, features), section)
            onResult(result)
        }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }
}
