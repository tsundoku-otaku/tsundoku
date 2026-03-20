package eu.kanade.tachiyomi.ui.library

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.core.util.fastFilterNot
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.epub.EpubExportJob
import eu.kanade.tachiyomi.data.library.LibraryClearJob
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.translation.TranslationJob
import eu.kanade.tachiyomi.data.translation.TranslationService
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import logcat.LogPriority
import mihon.core.common.utils.mutate
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.RemoveChapters
import tachiyomi.domain.chapter.interactor.SearchChapterNames
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

class LibraryScreenModel(
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracksPerManga: GetTracksPerManga = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val removeChapters: RemoveChapters = Injekt.get(),
    private val getBookmarkedChaptersByMangaId: GetBookmarkedChaptersByMangaId = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
    private val searchChapterNames: SearchChapterNames = Injekt.get(),
    private val preferences: BasePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val translatedChapterRepository: TranslatedChapterRepository = Injekt.get(),
    private val type: LibraryType = LibraryType.All,
) : StateScreenModel<LibraryScreenModel.State>(State()) {

    val snackbarHostState: SnackbarHostState = SnackbarHostState()

    private val itemCache = HashMap<Long, LibraryItem>()
    private val itemCacheMangaRef = HashMap<Long, LibraryManga>()
    private var itemCachePrefs: ItemPreferences? = null

    // Performance: Cache for filtered results to avoid recomputing on every change
    private var lastLibraryData: LibraryData? = null
    private var lastFilteredFavorites: List<LibraryItem>? = null
    private var lastItemPreferences: ItemPreferences? = null
    
    // Performance: Cache for grouped results to avoid full regrouping
    private var lastGroupingKey: Pair<List<Long>, Boolean>? = null
    private var lastGroupedResult: Map<Category, List<Long>>? = null

    enum class LibraryType {
        All,
        Manga,
        Novel,
    }

    init {
        mutableState.update { state ->
            state.copy(activeCategoryIndex = libraryPreferences.lastUsedCategory().get())
        }
        screenModelScope.launchIO {
            // Subscribe to categories filtered by content type based on library type
            val categoriesFlow = when (type) {
                LibraryType.All -> getCategories.subscribe()
                LibraryType.Manga -> getCategories.subscribe().map { categories ->
                    categories.filter { it.contentType == Category.CONTENT_TYPE_ALL || it.contentType == Category.CONTENT_TYPE_MANGA }
                }
                LibraryType.Novel -> getCategories.subscribe().map { categories ->
                    categories.filter { it.contentType == Category.CONTENT_TYPE_ALL || it.contentType == Category.CONTENT_TYPE_NOVEL }
                }
            }

            // Flow that emits manga IDs with matching chapter names when search is active
            // No debounce needed since search is committed on Enter key press
            val searchQueryFlow = state.map { it.searchQuery }.distinctUntilChanged()

            val chapterMatchIdsFlow = combine(
                searchQueryFlow,
                libraryPreferences.searchChapterNames().changes(),
            ) { query, searchChapters ->
                if (query.isNullOrEmpty() || !searchChapters) {
                    emptySet()
                } else {
                    // For "chapter:" prefix, extract the actual query
                    val actualQuery = if (query.startsWith("chapter:", true)) {
                        query.substringAfter("chapter:").trim()
                    } else {
                        query
                    }
                    if (actualQuery.isNotEmpty()) {
                        withIOContext { searchChapterNames.await(actualQuery).toSet() }
                    } else {
                        emptySet()
                    }
                }
            }

            // Combine search query with chapter match IDs
            val searchWithChapterMatchesFlow = combine(
                searchQueryFlow,
                chapterMatchIdsFlow,
                libraryPreferences.searchByUrl().changes(),
                libraryPreferences.useRegexSearch().changes(),
            ) { query, chapterMatchIds, searchByUrl, useRegex ->
                SearchConfig(query, chapterMatchIds, searchByUrl, useRegex)
            }

            combine(
                searchWithChapterMatchesFlow,
                categoriesFlow,
                getFavoritesFlow(),
                combine(getTracksPerManga.subscribe(), getTrackingFiltersFlow(), ::Pair),
                getLibraryItemPreferencesFlow(),
                getLibraryManga.isLoading(),
            ) { flows: Array<*> ->
                val searchConfig = flows[0] as SearchConfig

                @Suppress("UNCHECKED_CAST")
                val categories = flows[1] as List<Category>

                @Suppress("UNCHECKED_CAST")
                val favorites = flows[2] as List<LibraryItem>
                val (tracksMap, trackingFilters) = flows[3] as Pair<*, *>
                val itemPreferences = flows[4]
                val isLoading = flows[5] as Boolean

                val showSystemCategory = favorites.any { it.libraryManga.categories.contains(0L) }

                @Suppress("UNCHECKED_CAST")
                val filteredFavorites = favorites
                    .applyFilters(tracksMap as Map<Long, List<Track>>, trackingFilters as Map<Long, TriState>, itemPreferences as ItemPreferences)
                    .let { if (searchConfig.query == null) it else it.filter { m -> m.matches(searchConfig.query, searchConfig.chapterMatchIds, searchConfig.searchByUrl, searchConfig.useRegex) } }

                LibraryData(
                    isInitialized = !isLoading,
                    showSystemCategory = showSystemCategory,
                    categories = categories,
                    favorites = filteredFavorites,
                    tracksMap = tracksMap as Map<Long, List<Track>>,
                    loggedInTrackerIds = (trackingFilters as Map<Long, TriState>).keys,
                )
            }
                .collectLatest { libraryData ->
                    mutableState.update { state ->
                        state.copy(libraryData = libraryData)
                    }
                }
        }

        screenModelScope.launchIO {
            state
                .dropWhile { !it.libraryData.isInitialized }
                .map { it.libraryData }
                .distinctUntilChanged()
                .onEach {
                    mutableState.update { state ->
                        if (state.isLoading) state else state.copy(isQueryRunning = true)
                    }
                }
                .map { data ->
                    val favoritesById = HashMap<Long, LibraryItem>(data.favorites.size * 2)
                    for (item in data.favorites) {
                        favoritesById[item.id] = item
                    }
                    data.favorites
                        .applyGrouping(data.categories, data.showSystemCategory)
                        .applySort(favoritesById, data.tracksMap, data.loggedInTrackerIds)
                }
                .collectLatest {
                    mutableState.update { state ->
                        state.copy(
                            isLoading = false,
                            isQueryRunning = false,
                            groupedFavorites = it,
                        )
                    }
                }
        }

        combine(
            libraryPreferences.categoryTabs().changes(),
            libraryPreferences.categoryNumberOfItems().changes(),
            libraryPreferences.showContinueReadingButton().changes(),
        ) { a, b, c -> arrayOf(a, b, c) }
            .onEach { (showCategoryTabs, showMangaCount, showMangaContinueButton) ->
                mutableState.update { state ->
                    state.copy(
                        showCategoryTabs = showCategoryTabs,
                        showMangaCount = showMangaCount,
                        showMangaContinueButton = showMangaContinueButton,
                    )
                }
            }
            .launchIn(screenModelScope)

        combine(
            getLibraryItemPreferencesFlow(),
            getTrackingFiltersFlow(),
        ) { prefs, trackFilters ->
            listOf(
                prefs.filterDownloaded,
                prefs.filterUnread,
                prefs.filterStarted,
                prefs.filterBookmarked,
                prefs.filterCompleted,
                prefs.filterIntervalCustom,
                prefs.filterNoTags,
                *trackFilters.values.toTypedArray(),
            )
                .any { it != TriState.DISABLED } ||
                prefs.includedTags.isNotEmpty() ||
                prefs.excludedTags.isNotEmpty()
        }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(hasActiveFilters = it)
                }
            }
            .launchIn(screenModelScope)
    }

    private fun List<LibraryItem>.applyFilters(
        trackMap: Map<Long, List<Track>>,
        trackingFilter: Map<Long, TriState>,
        preferences: ItemPreferences,
    ): List<LibraryItem> {
        val downloadedOnly = preferences.globalFilterDownloaded
        val skipOutsideReleasePeriod = preferences.skipOutsideReleasePeriod
        val filterDownloaded = if (downloadedOnly) TriState.ENABLED_IS else preferences.filterDownloaded
        val filterUnread = preferences.filterUnread
        val filterStarted = preferences.filterStarted
        val filterBookmarked = preferences.filterBookmarked
        val filterCompleted = preferences.filterCompleted
        val filterIntervalCustom = preferences.filterIntervalCustom

        val isNotLoggedInAnyTrack = trackingFilter.isEmpty()

        val excludedTracks = trackingFilter.mapNotNull { if (it.value == TriState.ENABLED_NOT) it.key else null }
        val includedTracks = trackingFilter.mapNotNull { if (it.value == TriState.ENABLED_IS) it.key else null }
        val trackFiltersIsIgnored = includedTracks.isEmpty() && excludedTracks.isEmpty()

        val tagIncluded = preferences.includedTags
        val tagExcluded = preferences.excludedTags
        val tagCaseSensitive = preferences.tagCaseSensitive
        val normalizedIncluded = if (tagCaseSensitive) tagIncluded else tagIncluded.mapTo(HashSet()) { it.lowercase() }
        val normalizedExcluded = if (tagCaseSensitive) tagExcluded else tagExcluded.mapTo(HashSet()) { it.lowercase() }
        val tagIncludeModeAnd = preferences.tagIncludeModeAnd
        val tagExcludeModeAnd = preferences.tagExcludeModeAnd

        val result = fastFilter {
            // Quick exit for some checks that are fast
            val downloadPasses = applyFilter(filterDownloaded) {
                it.libraryManga.manga.isLocal() || it.downloadCount > 0
            }
            if (!downloadPasses) return@fastFilter false

            val unreadPasses = applyFilter(filterUnread) { it.libraryManga.unreadCount > 0 }
            if (!unreadPasses) return@fastFilter false

            val startedPasses = applyFilter(filterStarted) { it.libraryManga.hasStarted }
            if (!startedPasses) return@fastFilter false

            val bookmarkedPasses = applyFilter(filterBookmarked) { it.libraryManga.hasBookmarks }
            if (!bookmarkedPasses) return@fastFilter false

            val completedPasses = applyFilter(filterCompleted) { 
                it.libraryManga.manga.status.toInt() == SManga.COMPLETED 
            }
            if (!completedPasses) return@fastFilter false

            val intervalPasses = if (skipOutsideReleasePeriod) {
                applyFilter(filterIntervalCustom) { it.libraryManga.manga.fetchInterval < 0 }
            } else {
                true
            }
            if (!intervalPasses) return@fastFilter false

            // Tracking filter
            val trackingPasses = if (isNotLoggedInAnyTrack || trackFiltersIsIgnored) {
                true
            } else {
                val mangaTracks = trackMap[it.id].orEmpty().map { track -> track.trackerId }
                val isExcluded = excludedTracks.isNotEmpty() && mangaTracks.fastAny { trackId -> trackId in excludedTracks }
                val isIncluded = includedTracks.isEmpty() || mangaTracks.fastAny { trackId -> trackId in includedTracks }
                !isExcluded && isIncluded
            }
            if (!trackingPasses) return@fastFilter false

            // Extension filter
            val extensionPasses = it.libraryManga.manga.source.toString() !in preferences.excludedExtensions
            if (!extensionPasses) return@fastFilter false

            // Novel filter
            val novelPasses = applyFilter(preferences.filterNovel) { it.libraryManga.manga.isNovel }
            if (!novelPasses) return@fastFilter false

            // Tags filter (more expensive, so do it last)
            val tagsPasses = if (normalizedIncluded.isEmpty() && normalizedExcluded.isEmpty()) {
                true
            } else {
                val mangaTags = it.libraryManga.manga.genre
                    ?.map { tag -> tag.trim() }
                    ?.filter { tag -> tag.isNotBlank() }
                    ?: emptyList()

                // Handle "No Tags" filter
                val noTagsFilter = preferences.filterNoTags
                if (noTagsFilter != TriState.DISABLED) {
                    val hasNoTags = mangaTags.isEmpty()
                    when (noTagsFilter) {
                        TriState.ENABLED_IS -> if (!hasNoTags) return@fastFilter false
                        TriState.ENABLED_NOT -> if (hasNoTags) return@fastFilter false
                        else -> {}
                    }
                }

                if (normalizedExcluded.isNotEmpty()) {
                    val normalizedMangaTags = if (tagCaseSensitive) mangaTags else mangaTags.map { tag -> tag.lowercase() }
                    val hasExcludedTag = if (tagExcludeModeAnd) {
                        normalizedExcluded.all { excludedTag -> normalizedMangaTags.any { it == excludedTag } }
                    } else {
                        normalizedMangaTags.any { tag -> tag in normalizedExcluded }
                    }
                    if (hasExcludedTag) return@fastFilter false
                }

                if (normalizedIncluded.isNotEmpty()) {
                    val normalizedMangaTags = if (tagCaseSensitive) mangaTags else mangaTags.map { tag -> tag.lowercase() }
                    val hasIncludedTag = if (tagIncludeModeAnd) {
                        normalizedIncluded.all { includedTag -> normalizedMangaTags.any { it == includedTag } }
                    } else {
                        normalizedMangaTags.any { tag -> tag in normalizedIncluded }
                    }
                    if (!hasIncludedTag) return@fastFilter false
                }

                true
            }
            if (!tagsPasses) return@fastFilter false

            // Chapter count filter
            val chapterCountPasses = applyFilter(preferences.filterChapterCount) {
                it.libraryManga.totalChapters >= preferences.filterChapterCountThreshold
            }
            chapterCountPasses
        }
        
        return result
    }

    private fun List<LibraryItem>.applyGrouping(
        categories: List<Category>,
        showSystemCategory: Boolean,
    ): Map<Category, List</* LibraryItem */ Long>> {
        val groupCache = mutableMapOf</* Category */ Long, MutableList</* LibraryItem */ Long>>()
        for (item in this) {
            val cats = item.libraryManga.categories
            for (categoryId in cats) {
                groupCache.getOrPut(categoryId) { ArrayList() }.add(item.id)
            }
        }
        
        val visibleCategories = categories.filter { showSystemCategory || !it.isSystemCategory }
        return visibleCategories.associateWith { 
            groupCache[it.id] ?: emptyList()
        }
    }

    private fun Map<Category, List</* LibraryItem */ Long>>.applySort(
        favoritesById: Map<Long, LibraryItem>,
        trackMap: Map<Long, List<Track>>,
        loggedInTrackerIds: Set<Long>,
    ): Map<Category, List</* LibraryItem */ Long>> {
        val sortAlphabetically: (LibraryItem, LibraryItem) -> Int = { manga1, manga2 ->
            manga1.libraryManga.manga.title.compareToWithCollator(manga2.libraryManga.manga.title)
        }

        val defaultTrackerScoreSortValue = -1.0
        val sourceNameCache = HashMap<Long, String>()
        
        // Compute tracker scores once, not per-category
        val trackerScores = if (loggedInTrackerIds.isEmpty()) {
            emptyMap()
        } else {
            val trackerMap = trackerManager.getAll(loggedInTrackerIds).associateBy { e -> e.id }
            trackMap.mapValues { entry ->
                when {
                    entry.value.isEmpty() -> null
                    else ->
                        entry.value
                            .mapNotNull { trackerMap[it.trackerId]?.get10PointScore(it) }
                            .average()
                }
            }
        }

        fun LibrarySort.comparator(): Comparator<LibraryItem> = Comparator { manga1, manga2 ->
            when (this.type) {
                LibrarySort.Type.Alphabetical -> {
                    sortAlphabetically(manga1, manga2)
                }
                LibrarySort.Type.LastRead -> {
                    manga1.libraryManga.lastRead.compareTo(manga2.libraryManga.lastRead)
                }
                LibrarySort.Type.LastUpdate -> {
                    manga1.libraryManga.manga.lastUpdate.compareTo(manga2.libraryManga.manga.lastUpdate)
                }
                LibrarySort.Type.UnreadCount -> when {
                    // Ensure unread content comes first
                    manga1.libraryManga.unreadCount == manga2.libraryManga.unreadCount -> 0
                    manga1.libraryManga.unreadCount == 0L -> if (this.isAscending) 1 else -1
                    manga2.libraryManga.unreadCount == 0L -> if (this.isAscending) -1 else 1
                    else -> manga1.libraryManga.unreadCount.compareTo(manga2.libraryManga.unreadCount)
                }
                LibrarySort.Type.TotalChapters -> {
                    manga1.libraryManga.totalChapters.compareTo(manga2.libraryManga.totalChapters)
                }
                LibrarySort.Type.DownloadedChapters -> {
                    manga1.downloadCount.compareTo(manga2.downloadCount)
                }
                LibrarySort.Type.LatestChapter -> {
                    manga1.libraryManga.latestUpload.compareTo(manga2.libraryManga.latestUpload)
                }
                LibrarySort.Type.ChapterFetchDate -> {
                    manga1.libraryManga.chapterFetchedAt.compareTo(manga2.libraryManga.chapterFetchedAt)
                }
                LibrarySort.Type.DateAdded -> {
                    manga1.libraryManga.manga.dateAdded.compareTo(manga2.libraryManga.manga.dateAdded)
                }
                LibrarySort.Type.TrackerMean -> {
                    val item1Score = trackerScores[manga1.id] ?: defaultTrackerScoreSortValue
                    val item2Score = trackerScores[manga2.id] ?: defaultTrackerScoreSortValue
                    item1Score.compareTo(item2Score)
                }
                LibrarySort.Type.SourceName -> {
                    val sourceName1 = sourceNameCache.getOrPut(manga1.libraryManga.manga.source) {
                        sourceManager.getOrStub(manga1.libraryManga.manga.source).name
                    }
                    val sourceName2 = sourceNameCache.getOrPut(manga2.libraryManga.manga.source) {
                        sourceManager.getOrStub(manga2.libraryManga.manga.source).name
                    }
                    sourceName1.compareToWithCollator(sourceName2)
                }
                LibrarySort.Type.Random -> {
                    error("Why Are We Still Here? Just To Suffer?")
                }
            }
        }

        return mapValues { (key, value) ->
            if (key.sort.type == LibrarySort.Type.Random) {
                return@mapValues value.shuffled(Random(libraryPreferences.randomSortSeed().get()))
            }

            val manga = value.mapNotNull { favoritesById[it] }

            val comparator = key.sort.comparator()
                .let { if (key.sort.isAscending) it else it.reversed() }
                .thenComparator(sortAlphabetically)

            manga.sortedWith(comparator).map { it.id }
        }
    }

    private fun getLibraryItemPreferencesFlow(): Flow<ItemPreferences> {
        return combine(
            combine(
                libraryPreferences.downloadBadge().changes(),
                libraryPreferences.unreadBadge().changes(),
                libraryPreferences.localBadge().changes(),
                libraryPreferences.languageBadge().changes(),
                libraryPreferences.autoUpdateMangaRestrictions().changes(),
                preferences.downloadedOnly().changes(),
            ) { arr -> arr },
            combine(
                libraryPreferences.filterDownloaded().changes(),
                libraryPreferences.filterUnread().changes(),
                libraryPreferences.filterStarted().changes(),
                libraryPreferences.filterBookmarked().changes(),
                libraryPreferences.filterCompleted().changes(),
                libraryPreferences.filterIntervalCustom().changes(),
                libraryPreferences.excludedExtensions().changes(),
                libraryPreferences.filterNovel().changes(),
            ) { arr -> arr },
            combine(
                libraryPreferences.includedTags().changes(),
                libraryPreferences.excludedTags().changes(),
                libraryPreferences.filterNoTags().changes(),
                libraryPreferences.tagIncludeMode().changes(),
                libraryPreferences.tagExcludeMode().changes(),
                libraryPreferences.tagCaseSensitive().changes(),
                libraryPreferences.filterChapterCount().changes(),
                libraryPreferences.filterChapterCountThreshold().changes(),
            ) { arr -> arr },
        ) { first, second, third ->
            ItemPreferences(
                downloadBadge = first[0] as Boolean,
                unreadBadge = first[1] as Boolean,
                localBadge = first[2] as Boolean,
                languageBadge = first[3] as Boolean,
                skipOutsideReleasePeriod = LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in (first[4] as Set<*>),
                globalFilterDownloaded = first[5] as Boolean,
                filterDownloaded = second[0] as TriState,
                filterUnread = second[1] as TriState,
                filterStarted = second[2] as TriState,
                filterBookmarked = second[3] as TriState,
                filterCompleted = second[4] as TriState,
                filterIntervalCustom = second[5] as TriState,
                excludedExtensions = @Suppress("UNCHECKED_CAST") (second[6] as Set<String>),
                filterNovel = second[7] as TriState,
                includedTags = @Suppress("UNCHECKED_CAST") (third[0] as Set<String>),
                excludedTags = @Suppress("UNCHECKED_CAST") (third[1] as Set<String>),
                filterNoTags = third[2] as TriState,
                tagIncludeModeAnd = third[3] as Boolean,
                tagExcludeModeAnd = third[4] as Boolean,
                tagCaseSensitive = third[5] as Boolean,
                filterChapterCount = third[6] as TriState,
                filterChapterCountThreshold = third[7] as Int,
            )
        }
    }

    private var isNovelBackfillDone = false

    private fun getFavoritesFlow(): Flow<List<LibraryItem>> {
        return combine(
            getLibraryManga.subscribe(),
            getLibraryItemPreferencesFlow(),
            downloadCache.changes.onStart { emit(Unit) },
        ) { libraryManga, preferences, _ ->
            if (!isNovelBackfillDone) {
                isNovelBackfillDone = true
                val toUpdate = libraryManga
                    .filter { !it.manga.isNovel }
                    .filter { sourceManager.get(it.manga.source)?.isNovelSource() == true }
                    .map { MangaUpdate(id = it.manga.id, isNovel = true) }
                if (toUpdate.isNotEmpty()) {
                    screenModelScope.launchIO { updateManga.awaitAll(toUpdate) }
                }
            }

            // If preferences or download cache changed, invalidate all cached items
            val prefsChanged = itemCachePrefs != preferences
            if (prefsChanged) {
                itemCache.clear()
                itemCacheMangaRef.clear()
                itemCachePrefs = preferences
            }

            val result = ArrayList<LibraryItem>(libraryManga.size)
            for (manga in libraryManga) {
                // Filter by library type
                when (type) {
                    LibraryType.All -> {}
                    LibraryType.Manga -> if (manga.manga.isNovel) continue
                    LibraryType.Novel -> if (!manga.manga.isNovel) continue
                }

                val dlCount = downloadManager.getDownloadCount(manga.manga).toLong()

                val cached = itemCache[manga.id]
                val cachedRef = itemCacheMangaRef[manga.id]
                if (cached != null && cachedRef === manga && cached.downloadCount == dlCount) {
                    result.add(cached)
                    continue
                }

                val item = LibraryItem(
                    libraryManga = manga,
                    downloadCount = dlCount,
                    unreadCount = if (preferences.unreadBadge) {
                        manga.unreadCount
                    } else {
                        0
                    },
                    isLocal = if (preferences.localBadge) {
                        manga.manga.isLocal()
                    } else {
                        false
                    },
                    sourceLanguage = if (preferences.languageBadge) {
                        sourceManager.getOrStub(manga.manga.source).lang
                    } else {
                        ""
                    },
                )
                itemCache[manga.id] = item
                itemCacheMangaRef[manga.id] = manga
                result.add(item)
            }

            if (itemCache.size > result.size + 100) {
                val currentIds = result.mapTo(HashSet(result.size)) { it.libraryManga.id }
                itemCache.keys.retainAll(currentIds)
                itemCacheMangaRef.keys.retainAll(currentIds)
            }

            result
        }
    }

    /**
     * Flow of tracking filter preferences
     *
     * @return map of track id with the filter value
     */
    private fun getTrackingFiltersFlow(): Flow<Map<Long, TriState>> {
        return trackerManager.loggedInTrackersFlow().flatMapLatest { loggedInTrackers ->
            if (loggedInTrackers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val filterFlows = loggedInTrackers.map { tracker ->
                    libraryPreferences.filterTracking(tracker.id.toInt()).changes().map { tracker.id to it }
                }
                combine(filterFlows) { it.toMap() }
            }
        }
    }

    /**
     * Returns the common categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    private suspend fun getCommonCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        return mangas
            .map { getCategories.await(it.id).toSet() }
            .reduce { set1, set2 -> set1.intersect(set2) }
    }

    suspend fun getNextUnreadChapter(manga: Manga): Chapter? {
        return getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true).getNextUnread(manga, downloadManager)
    }

    /**
     * Returns the mix (non-common) categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    private suspend fun getMixCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        val mangaCategories = mangas.map { getCategories.await(it.id).toSet() }
        val common = mangaCategories.reduce { set1, set2 -> set1.intersect(set2) }
        return mangaCategories.flatten().distinct().subtract(common)
    }

    /**
     * Queues the amount specified of unread chapters from the list of selected manga
     */
    fun performDownloadAction(action: DownloadAction) {
        when (action) {
            DownloadAction.NEXT_1_CHAPTER -> downloadNextChapters(1)
            DownloadAction.NEXT_5_CHAPTERS -> downloadNextChapters(5)
            DownloadAction.NEXT_10_CHAPTERS -> downloadNextChapters(10)
            DownloadAction.NEXT_25_CHAPTERS -> downloadNextChapters(25)
            DownloadAction.UNREAD_CHAPTERS -> downloadNextChapters(null)
            DownloadAction.BOOKMARKED_CHAPTERS -> downloadBookmarkedChapters()
        }
        clearSelection()
    }

    private fun downloadNextChapters(amount: Int?) {
        val mangas = state.value.selectedManga
        screenModelScope.launchNonCancellable {
            mangas.forEach { manga ->
                val chapters = getNextChapters.await(manga.id)
                    .fastFilterNot { chapter ->
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                manga.title,
                                manga.source,
                            )
                    }
                    .let { if (amount != null) it.take(amount) else it }

                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    private fun downloadBookmarkedChapters() {
        val mangas = state.value.selectedManga
        screenModelScope.launchNonCancellable {
            mangas.forEach { manga ->
                val chapters = getBookmarkedChaptersByMangaId.await(manga.id)
                    .fastFilterNot { chapter ->
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                manga.title,
                                manga.source,
                            )
                    }
                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    /**
     * Queues all downloaded chapters from the selected novels for translation.
     * Only processes chapters that haven't been translated yet.
     */
    fun translateSelectedNovels() {
        val translationService: TranslationService = Injekt.get()
        val mangas = state.value.selectedManga.filter { manga ->
            manga.isNovel
        }
        clearSelection()
        screenModelScope.launchNonCancellable {
            mangas.forEach { manga ->
                val chapters = getChaptersByMangaId.await(manga.id)
                // Queue all chapters for translation (TranslationService will skip already translated ones)
                translationService.enqueueAll(manga, chapters)
            }
            // Start background worker with notification
            TranslationJob.start(Injekt.get<android.app.Application>())
        }
    }

    /**
     * Show confirmation dialog for marking mangas read/unread.
     */
    fun showMarkReadConfirmation(read: Boolean) {
        mutableState.update {
            it.copy(dialog = Dialog.MarkReadConfirmation(read))
        }
    }

    /**
     * Marks mangas' chapters read status.
     */
    fun markReadSelection(read: Boolean) {
        val selection = state.value.selectedManga
        screenModelScope.launchNonCancellable {
            selection.forEach { manga ->
                setReadStatus.await(
                    manga = manga,
                    read = read,
                )
            }
            val updates = selection.associate { manga ->
                manga.id to { libraryManga: tachiyomi.domain.library.model.LibraryManga ->
                    libraryManga.copy(
                        readCount = if (read) libraryManga.totalChapters else 0,
                        lastRead = if (read) System.currentTimeMillis() else libraryManga.lastRead,
                    )
                }
            }
            getLibraryManga.applyBatchChapterUpdates(updates)
        }
        clearSelection()
    }

    /**
     * Remove the selected manga.
     *
     * @param mangas the list of manga to delete.
     * @param deleteFromLibrary whether to delete manga from library.
     * @param deleteChapters whether to delete downloaded chapters.
     * @param clearChaptersFromDb whether to delete chapter entries from database.
     */
    fun removeMangas(
        mangas: List<Manga>,
        deleteFromLibrary: Boolean,
        deleteChapters: Boolean,
        clearChaptersFromDb: Boolean = false,
        deleteTranslations: Boolean = false,
        clearCovers: Boolean = false,
        clearDescriptions: Boolean = false,
        clearTags: Boolean = false,
    ) {
        screenModelScope.launchNonCancellable {
            if (deleteFromLibrary) {
                val toDelete = mangas.map {
                    it.removeCovers(coverCache)
                    MangaUpdate(
                        favorite = false,
                        id = it.id,
                    )
                }
                updateManga.awaitAll(toDelete)
            }

            if (deleteChapters) {
                mangas.forEach { manga ->
                    val source = sourceManager.get(manga.source) as? HttpSource
                    if (source != null) {
                        downloadManager.deleteManga(manga, source)
                    }
                }
            }

            if (clearChaptersFromDb) {
                val mangaIds = mangas.map { it.id }
                removeChapters.awaitByMangaIds(mangaIds)
            }

            if (deleteTranslations) {
                mangas.forEach { manga ->
                    val chapters = getChaptersByMangaId.await(manga.id)
                    translatedChapterRepository.deleteAllForChapters(chapters.map { it.id })
                }
            }

            // Delegate clear operations to LibraryClearJob (runs as background WorkManager job)
            if (!deleteFromLibrary) {
                val mangaIds = mangas.map { it.id }
                val context = Injekt.get<android.app.Application>()
                if (clearCovers) {
                    LibraryClearJob.start(context, mangaIds, LibraryClearJob.OP_CLEAR_COVERS)
                }
                if (clearDescriptions) {
                    LibraryClearJob.start(context, mangaIds, LibraryClearJob.OP_CLEAR_DESCRIPTIONS)
                }
                if (clearTags) {
                    LibraryClearJob.start(context, mangaIds, LibraryClearJob.OP_CLEAR_TAGS)
                }
            }

            // Refresh library UI after modifications
            if (deleteFromLibrary) {
            } else if (deleteChapters || clearChaptersFromDb || deleteTranslations || clearCovers || clearDescriptions || clearTags) {
                getLibraryManga.notifyChanged()
            }
        }
    }

    /**
     * Clear covers for selected manga.
     */
    fun clearCoversForSelection() {
        val mangas = state.value.selectedManga
        if (mangas.isEmpty()) return

        val context = Injekt.get<android.app.Application>()
        LibraryClearJob.start(context, mangas.map { it.id }, LibraryClearJob.OP_CLEAR_COVERS)
        clearSelection()
    }

    /**
     * Clear descriptions for selected manga.
     */
    fun clearDescriptionsForSelection() {
        val mangas = state.value.selectedManga
        if (mangas.isEmpty()) return

        val context = Injekt.get<android.app.Application>()
        LibraryClearJob.start(context, mangas.map { it.id }, LibraryClearJob.OP_CLEAR_DESCRIPTIONS)
        clearSelection()
    }

    /**
     * Clear tags/genres for selected manga.
     */
    fun clearTagsForSelection() {
        val mangas = state.value.selectedManga
        if (mangas.isEmpty()) return

        val context = Injekt.get<android.app.Application>()
        LibraryClearJob.start(context, mangas.map { it.id }, LibraryClearJob.OP_CLEAR_TAGS)
        clearSelection()
    }

    /**
     * Bulk update categories of manga using old and new common categories.
     *
     * @param mangaList the list of manga to move.
     * @param addCategories the categories to add for all mangas.
     * @param removeCategories the categories to remove in all mangas.
     */
    fun setMangaCategories(mangaList: List<Manga>, addCategories: List<Long>, removeCategories: List<Long>) {
        screenModelScope.launchNonCancellable {
            val mangaIds = mangaList.map { it.id }
            val count = mangaIds.size

            logcat(LogPriority.DEBUG) {
                "Category change: ids=$count add=${addCategories.size} remove=${removeCategories.size} type=$type"
            }

            if (addCategories.isNotEmpty()) {
                logcat(LogPriority.DEBUG) { "Category change: adding categories $addCategories to $count manga" }
                setMangaCategories.add(mangaIds, addCategories, skipRefresh = true)
            }
            if (removeCategories.isNotEmpty()) {
                logcat(LogPriority.DEBUG) { "Category change: removing categories $removeCategories from $count manga" }
                setMangaCategories.remove(mangaIds, removeCategories, skipRefresh = true)
            }

            logcat(LogPriority.DEBUG) { "Category change: applying in-memory patch" }
            getLibraryManga.applyCategoryUpdates(mangaIds, addCategories, removeCategories)

            clearSelection()
        }
    }

    fun getDisplayMode(): PreferenceMutableState<LibraryDisplayMode> {
        return libraryPreferences.displayMode().asState(screenModelScope)
    }

    fun getColumnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> {
        return (if (isLandscape) libraryPreferences.landscapeColumns() else libraryPreferences.portraitColumns())
            .asState(screenModelScope)
    }

    fun getRandomLibraryItemForCurrentCategory(): LibraryItem? {
        val state = state.value
        return state.getItemsForCategoryId(state.activeCategory?.id).randomOrNull()
    }

    fun showSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.SettingsSheet) }
    }

    private var lastSelectionCategory: Long? = null

    fun clearSelection() {
        lastSelectionCategory = null
        mutableState.update { it.copy(selection = setOf()) }
    }

    fun toggleSelection(category: Category, manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { set ->
                if (!set.remove(manga.id)) set.add(manga.id)
            }
            lastSelectionCategory = category.id.takeIf { newSelection.isNotEmpty() }
            state.copy(selection = newSelection)
        }
    }

    /**
     * Selects all mangas between and including the given manga and the last pressed manga from the
     * same category as the given manga
     */
    fun toggleRangeSelection(category: Category, manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val lastSelected = list.lastOrNull()
                if (lastSelectionCategory != category.id) {
                    list.add(manga.id)
                    return@mutate
                }

                val items = state.getItemsForCategoryId(category.id).fastMap { it.id }
                val lastMangaIndex = items.indexOf(lastSelected)
                val curMangaIndex = items.indexOf(manga.id)

                val selectionRange = when {
                    lastMangaIndex < curMangaIndex -> lastMangaIndex..curMangaIndex
                    curMangaIndex < lastMangaIndex -> curMangaIndex..lastMangaIndex
                    // We shouldn't reach this point
                    else -> return@mutate
                }
                selectionRange.mapNotNull { items[it] }.let(list::addAll)
            }
            lastSelectionCategory = category.id
            state.copy(selection = newSelection)
        }
    }

    fun selectAll() {
        lastSelectionCategory = null
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                state.getItemsForCategoryId(state.activeCategory?.id).map { it.id }.let(list::addAll)
            }
            state.copy(selection = newSelection)
        }
    }

    fun invertSelection() {
        lastSelectionCategory = null
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val itemIds = state.getItemsForCategoryId(state.activeCategory?.id).fastMap { it.id }
                val (toRemove, toAdd) = itemIds.partition { it in list }
                list.removeAll(toRemove)
                list.addAll(toAdd)
            }
            state.copy(selection = newSelection)
        }
    }

    /**
     * Returns the URLs of the selected manga.
     * Uses the source's getMangaUrl method to get the proper URL for each manga.
     */
    fun getSelectedMangaUrls(): List<String> {
        return state.value.selectedManga.mapNotNull { manga ->
            val source = sourceManager.get(manga.source) as? HttpSource
            source?.let {
                try {
                    it.getMangaUrl(manga.toSManga())
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Update selected entries by delegating to [LibraryUpdateJob] so updates respect
     * extension rate-limits, throttling, semaphores, and show progress notifications.
     */
    fun updateSelected(
        mangaList: List<Manga>,
        fetchChapters: Boolean,
        fetchDetails: Boolean,
        ignoreSkipRecentlyUpdated: Boolean,
    ) {
        if (mangaList.isEmpty()) return
        clearSelection()

        val context = Injekt.get<android.app.Application>()
        val started = LibraryUpdateJob.startNow(
            context = context,
            mangaIds = mangaList.map { it.id }.toLongArray(),
            fetchChapters = fetchChapters,
            fetchDetails = fetchDetails,
            ignoreSkipRecentlyUpdated = ignoreSkipRecentlyUpdated,
        )
        screenModelScope.launchNonCancellable {
            if (started) {
                snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.batch_updating_entries, mangaList.size),
                    duration = SnackbarDuration.Short,
                )
            } else {
                snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.update_already_running),
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    fun search(query: String?) {
        // Update toolbar display, don't trigger filtering
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    fun commitSearch(query: String) {
        // Commit the search, triggering filtering
        mutableState.update { it.copy(searchQuery = query.ifBlank { null }, isQueryRunning = true) }
    }

    fun clearSearch() {
        // Clear both toolbar and committed search
        mutableState.update { it.copy(toolbarQuery = null, searchQuery = null, isQueryRunning = true) }
    }

    fun updateActiveCategoryIndex(index: Int) {
        val newIndex = mutableState.updateAndGet { state ->
            state.copy(activeCategoryIndex = index)
        }
            .coercedActiveCategoryIndex

        libraryPreferences.lastUsedCategory().set(newIndex)
    }

    fun openChangeCategoryDialog() {
        screenModelScope.launchIO {
            // Create a copy of selected manga
            val mangaList = state.value.selectedManga

            // Hide the default category because it has a different behavior than the ones from db.
            val categories = state.value.displayedCategories.filter { it.id != 0L }

            // Get indexes of the common categories to preselect.
            val common = getCommonCategories(mangaList)
            // Get indexes of the mix categories to preselect.
            val mix = getMixCategories(mangaList)
            val preselected = categories
                .map {
                    when (it) {
                        in common -> CheckboxState.State.Checked(it)
                        in mix -> CheckboxState.TriState.None(it)
                        else -> CheckboxState.State.None(it)
                    }
                }
                .toImmutableList()
            mutableState.update { it.copy(dialog = Dialog.ChangeCategory(mangaList, preselected)) }
        }
    }

    fun openDeleteMangaDialog() {
        mutableState.update { it.copy(dialog = Dialog.DeleteManga(state.value.selectedManga)) }
    }

    fun openUpdateSelectedDialog() {
        mutableState.update { it.copy(dialog = Dialog.UpdateSelected(state.value.selectedManga)) }
    }

    fun openRemoveChaptersDialog() {
        mutableState.update { it.copy(dialog = Dialog.RemoveChapters(state.value.selectedManga)) }
    }

    fun removeChaptersFromSelectedManga(mangaList: List<Manga>) {
        val context = Injekt.get<android.app.Application>()
        LibraryClearJob.start(context, mangaList.map { it.id }, LibraryClearJob.OP_CLEAR_CHAPTERS)
        clearSelection()
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun openMassImportDialog() {
        mutableState.update { it.copy(dialog = Dialog.MassImport) }
    }

    fun openImportEpubDialog() {
        mutableState.update { it.copy(dialog = Dialog.ImportEpub) }
    }

    fun openExportEpubDialog() {
        val selectedNovels = state.value.selectedManga.filter { manga ->
            manga.isNovel
        }
        if (selectedNovels.isEmpty()) {
            screenModelScope.launchIO {
                withUIContext {
                    snackbarHostState.showSnackbar("No novels selected for export")
                }
            }
            return
        }
        mutableState.update { it.copy(dialog = Dialog.ExportEpub(selectedNovels)) }
    }

    fun exportNovelsAsEpub(
        mangaList: List<Manga>,
        uri: android.net.Uri,
        options: eu.kanade.presentation.library.components.EpubExportOptions = eu.kanade.presentation.library.components.EpubExportOptions(),
    ) {
        val context = Injekt.get<android.app.Application>()

        // Start the export job with notifications
        EpubExportJob.start(
            context = context,
            mangaIds = mangaList.map { it.id },
            outputUri = uri,
            downloadedOnly = options.downloadedOnly,
            translationMode = options.translationMode,
            includeChapterCount = options.includeChapterCount,
            includeChapterRange = options.includeChapterRange,
            includeStatus = options.includeStatus,
        )

        screenModelScope.launchIO {
            withUIContext {
                snackbarHostState.showSnackbar(
                    "EPUB export started for ${mangaList.size} novels",
                    duration = SnackbarDuration.Short,
                )
            }
        }
        clearSelection()
    }

    // Legacy method kept for reference - now using EpubExportJob instead
    @Suppress("unused")
    private fun exportNovelsAsEpubLegacy(
        mangaList: List<Manga>,
        uri: android.net.Uri,
        options: eu.kanade.presentation.library.components.EpubExportOptions = eu.kanade.presentation.library.components.EpubExportOptions(),
    ) {
        screenModelScope.launchIO {
            try {
                val context = Injekt.get<android.app.Application>()
                val downloadProvider = Injekt.get<DownloadProvider>()
                val networkHelper = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>()

                // Create a temp directory for the batch export
                val tempDir = java.io.File(context.cacheDir, "epub_batch_export")
                tempDir.mkdirs()

                val results = mutableListOf<String>()
                var successCount = 0
                var skippedCount = 0

                for ((index, manga) in mangaList.withIndex()) {
                    withUIContext {
                        snackbarHostState.showSnackbar(
                            "Exporting ${index + 1}/${mangaList.size}: ${manga.title}",
                            duration = SnackbarDuration.Short,
                        )
                    }

                    try {
                        val source = sourceManager.get(manga.source)
                        if (source == null || !source.isNovelSource()) {
                            results.add("${manga.title}: Not a novel source")
                            continue
                        }

                        val chapters = getChaptersByMangaId.await(manga.id)
                            .sortedBy { it.chapterNumber }

                        if (chapters.isEmpty()) {
                            results.add("${manga.title}: No chapters found")
                            continue
                        }

                        val epubChapters = mutableListOf<mihon.core.archive.EpubWriter.Chapter>()
                        var hasDownloads = false
                        var firstChapterNum = Double.MAX_VALUE
                        var lastChapterNum = Double.MIN_VALUE

                        // Get translated chapter IDs for this manga if translation mode needs them
                        val translatedChapterIds = if (options.translationMode != tachiyomi.domain.translation.model.TranslationMode.ORIGINAL) {
                            translatedChapterRepository.getTranslatedChapterIds(chapters.map { it.id })
                        } else {
                            emptySet()
                        }

                        for ((chapterIndex, chapter) in chapters.withIndex()) {
                            // Check if chapter is downloaded first
                            val isDownloaded = downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                manga.title,
                                manga.source,
                            )

                            // Check if chapter has translation
                            val hasTranslation = chapter.id in translatedChapterIds

                            if (isDownloaded) hasDownloads = true

                            // Skip undownloaded chapters if downloadedOnly is true
                            if (options.downloadedOnly && !isDownloaded && !hasTranslation) {
                                continue
                            }

                            // Try to get translated content first if translation mode needs it
                            var content: String? = null
                            if (options.translationMode != tachiyomi.domain.translation.model.TranslationMode.ORIGINAL && hasTranslation) {
                                try {
                                    val translations = translatedChapterRepository.getAllTranslationsForChapter(chapter.id)
                                    content = translations.firstOrNull()?.translatedContent
                                } catch (e: Exception) {
                                    logcat(LogPriority.WARN, e) { "Failed to get translation for chapter: ${chapter.name}" }
                                }
                            }

                            // Fall back to original content if no translation or not preferred
                            if (content == null && isDownloaded) {
                                // Get from disk
                                try {
                                    val chapterDir = downloadProvider.findChapterDir(
                                        chapter.name,
                                        chapter.scanlator,
                                        chapter.url,
                                        manga.title,
                                        source,
                                    )

                                    if (chapterDir != null) {
                                        val htmlFiles = chapterDir.listFiles()?.filter {
                                            it.isFile && it.name?.endsWith(".html") == true
                                        }?.sortedBy { it.name } ?: emptyList()

                                        if (htmlFiles.isNotEmpty()) {
                                            val sb = StringBuilder()
                                            htmlFiles.forEachIndexed { i, file ->
                                                val fileContent = context.contentResolver.openInputStream(file.uri)?.use {
                                                    it.bufferedReader().readText()
                                                } ?: ""
                                                sb.append(fileContent)
                                                if (i < htmlFiles.size - 1) {
                                                    sb.append("\n\n")
                                                }
                                            }
                                            content = sb.toString()
                                        }
                                    }
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e) { "Failed to read downloaded chapter: ${chapter.name}" }
                                }
                            }

                            // Fetch from source if still no content
                            if (content == null && !options.downloadedOnly) {
                                try {
                                    if (source is eu.kanade.tachiyomi.source.online.HttpSource) {
                                        val pages = source.getPageList(chapter.toSChapter())
                                        if (pages.isNotEmpty()) {
                                            val page = pages.first()
                                            val pageText = if (page.text != null) {
                                                page.text
                                            } else if (page.imageUrl != null) {
                                                // For text-based novels, imageUrl may contain the actual text
                                                page.imageUrl
                                            } else {
                                                "<p>No content available</p>"
                                            }
                                            content = pageText ?: "<p>No content available</p>"
                                        } else {
                                            content = "<p>No pages found</p>"
                                        }
                                    }
                                } catch (e: Exception) {
                                    logcat(LogPriority.WARN, e) { "Failed to fetch chapter from source: ${chapter.name}" }
                                }
                            }

                            if (content != null) {
                                // Track chapter numbers for filename
                                val chNum = chapter.chapterNumber
                                if (chNum < firstChapterNum) firstChapterNum = chNum
                                if (chNum > lastChapterNum) lastChapterNum = chNum

                                epubChapters.add(
                                    mihon.core.archive.EpubWriter.Chapter(
                                        title = chapter.name,
                                        content = content,
                                        order = chapterIndex,
                                    ),
                                )
                            }
                        }

                        // Skip novels without any exported chapters
                        if (epubChapters.isEmpty()) {
                            if (options.downloadedOnly) {
                                results.add("${manga.title}: Skipped (no downloads)")
                                skippedCount++
                            } else {
                                results.add("${manga.title}: No chapters could be exported")
                            }
                            continue
                        }

                        // Get cover image
                        val coverImage = try {
                            manga.thumbnailUrl?.let { url ->
                                val request = okhttp3.Request.Builder().url(url).build()
                                networkHelper.client.newCall(request).execute().use { it.body.bytes() }
                            }
                        } catch (e: Exception) {
                            null
                        }

                        // Create EPUB metadata
                        val metadata = mihon.core.archive.EpubWriter.Metadata(
                            title = manga.title,
                            author = manga.author,
                            description = manga.description,
                            language = "en",
                            genres = manga.genre ?: emptyList(),
                            publisher = source.name,
                        )

                        // Build filename with options
                        val filenameBuilder = StringBuilder(sanitizeFilename(manga.title))
                        if (options.includeChapterCount) {
                            filenameBuilder.append(" [${epubChapters.size}ch]")
                        }
                        if (options.includeChapterRange && firstChapterNum != Double.MAX_VALUE) {
                            val firstCh = if (firstChapterNum == firstChapterNum.toLong().toDouble()) {
                                firstChapterNum.toLong().toString()
                            } else {
                                firstChapterNum.toString()
                            }
                            val lastCh = if (lastChapterNum == lastChapterNum.toLong().toDouble()) {
                                lastChapterNum.toLong().toString()
                            } else {
                                lastChapterNum.toString()
                            }
                            if (firstCh != lastCh) {
                                filenameBuilder.append(" [ch$firstCh-$lastCh]")
                            } else {
                                filenameBuilder.append(" [ch$firstCh]")
                            }
                        }
                        if (options.includeStatus) {
                            val statusStr = when (manga.status) {
                                SManga.ONGOING.toLong() -> "Ongoing"
                                SManga.COMPLETED.toLong() -> "Completed"
                                SManga.LICENSED.toLong() -> "Licensed"
                                SManga.PUBLISHING_FINISHED.toLong() -> "Finished"
                                SManga.CANCELLED.toLong() -> "Cancelled"
                                SManga.ON_HIATUS.toLong() -> "Hiatus"
                                else -> null
                            }
                            statusStr?.let { filenameBuilder.append(" [$it]") }
                        }
                        filenameBuilder.append(".epub")

                        // Write to temp file
                        val filename = filenameBuilder.toString()
                        val tempFile = java.io.File(tempDir, filename)
                        tempFile.outputStream().use { outputStream ->
                            mihon.core.archive.EpubWriter().write(
                                outputStream = outputStream,
                                metadata = metadata,
                                chapters = epubChapters,
                                coverImage = coverImage,
                            )
                        }

                        results.add("${manga.title}: Exported ${epubChapters.size} chapters")
                        successCount++
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to export ${manga.title}" }
                        results.add("${manga.title}: Error - ${e.message}")
                    }
                }

                // Create a ZIP of all EPUBs if multiple
                if (mangaList.size > 1) {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        java.util.zip.ZipOutputStream(outputStream).use { zipOut ->
                            tempDir.listFiles()?.forEach { file ->
                                if (file.isFile && file.name.endsWith(".epub")) {
                                    val entry = java.util.zip.ZipEntry(file.name)
                                    zipOut.putNextEntry(entry)
                                    file.inputStream().use { input ->
                                        input.copyTo(zipOut)
                                    }
                                    zipOut.closeEntry()
                                }
                            }
                        }
                    }
                } else if (tempDir.listFiles()?.isNotEmpty() == true) {
                    // Single file, just copy
                    val singleFile = tempDir.listFiles()!!.first()
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        singleFile.inputStream().use { input ->
                            input.copyTo(outputStream)
                        }
                    }
                }

                // Cleanup temp dir
                tempDir.deleteRecursively()

                withUIContext {
                    val message = buildString {
                        append("Exported $successCount/${mangaList.size} novels")
                        if (skippedCount > 0) append(" ($skippedCount skipped)")
                    }
                    snackbarHostState.showSnackbar(message)
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Batch EPUB export failed" }
                withUIContext {
                    snackbarHostState.showSnackbar("Export failed: ${e.message}")
                }
            }
        }
        clearSelection()
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(200)
    }

    fun reloadLibraryFromDB() {
        screenModelScope.launchIO {
            logcat { "LibraryScreenModel: User requested library reload" }
            val library = getLibraryManga.refreshForced()
            logcat { "LibraryScreenModel: Library reloaded with ${library.size} items" }
        }
    }

    /**
     * Find potential duplicate manga in the library based on title similarity.
     * Uses Levenshtein distance for fuzzy matching.
     */
    fun findDuplicates(): List<DuplicateGroup> {
        val favorites = state.value.libraryData.favorites
        if (favorites.size < 2) return emptyList()

        val duplicateGroups = mutableListOf<DuplicateGroup>()
        val processed = mutableSetOf<Long>()

        for (i in favorites.indices) {
            if (favorites[i].id in processed) continue

            val currentItem = favorites[i]
            val currentTitle = normalizeTitle(currentItem.libraryManga.manga.title)
            val group = mutableListOf(currentItem)

            for (j in i + 1 until favorites.size) {
                if (favorites[j].id in processed) continue

                val otherItem = favorites[j]
                val otherTitle = normalizeTitle(otherItem.libraryManga.manga.title)

                if (isSimilar(currentTitle, otherTitle)) {
                    group.add(otherItem)
                    processed.add(favorites[j].id)
                }
            }

            if (group.size > 1) {
                duplicateGroups.add(DuplicateGroup(group.toList()))
                processed.add(favorites[i].id)
            }
        }

        return duplicateGroups
    }

    private fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("[\\[\\(].*?[\\]\\)]"), "") // Remove content in brackets/parentheses
            .replace(Regex("[^a-z0-9\\s]"), "") // Remove non-alphanumeric except spaces
            .replace(Regex("\\s+"), " ") // Normalize whitespace
            .trim()
    }

    private fun isSimilar(title1: String, title2: String): Boolean {
        // Exact match after normalization
        if (title1 == title2) return true

        // Check if one contains the other
        if (title1.contains(title2) || title2.contains(title1)) return true

        // Use Levenshtein distance for fuzzy matching
        val maxLen = maxOf(title1.length, title2.length)
        if (maxLen == 0) return true
        val distance = levenshteinDistance(title1, title2)
        val similarity = 1.0 - (distance.toDouble() / maxLen)
        return similarity >= 0.8 // 80% similarity threshold
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost,
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    fun openDuplicateDetectionDialog() {
        val duplicates = findDuplicates()
        mutableState.update { it.copy(dialog = Dialog.DuplicateDetection(duplicates)) }
    }

    fun selectDuplicatesExceptFirst(groups: List<DuplicateGroup>) {
        val toSelect = groups.flatMap { it.items.drop(1) }.map { it.id }.toSet()
        mutableState.update { state ->
            state.copy(selection = state.selection + toSelect)
        }
        closeDialog()
    }

    fun selectAllDuplicates(groups: List<DuplicateGroup>) {
        val toSelect = groups.flatMap { it.items }.map { it.id }.toSet()
        mutableState.update { state ->
            state.copy(selection = state.selection + toSelect)
        }
        closeDialog()
    }

    data class DuplicateGroup(val items: List<LibraryItem>)

    sealed interface Dialog {
        data object SettingsSheet : Dialog
        data object MassImport : Dialog
        data object ImportEpub : Dialog
        data class ExportEpub(val manga: List<Manga>) : Dialog
        data class DuplicateDetection(val duplicates: List<DuplicateGroup>) : Dialog
        data class UpdateSelected(val manga: List<Manga>) : Dialog
        data class ChangeCategory(
            val manga: List<Manga>,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteManga(val manga: List<Manga>) : Dialog
        data class RemoveChapters(val manga: List<Manga>) : Dialog
        data class MarkReadConfirmation(val read: Boolean) : Dialog
    }

    @Immutable
    private data class ItemPreferences(
        val downloadBadge: Boolean,
        val unreadBadge: Boolean,
        val localBadge: Boolean,
        val languageBadge: Boolean,
        val skipOutsideReleasePeriod: Boolean,

        val globalFilterDownloaded: Boolean,
        val filterDownloaded: TriState,
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterCompleted: TriState,
        val filterIntervalCustom: TriState,
        val excludedExtensions: Set<String>,
        val filterNovel: TriState,
        val includedTags: Set<String>,
        val excludedTags: Set<String>,
        val filterNoTags: TriState,
        val tagIncludeModeAnd: Boolean,
        val tagExcludeModeAnd: Boolean,
        val tagCaseSensitive: Boolean,
        val filterChapterCount: TriState = TriState.DISABLED,
        val filterChapterCountThreshold: Int = 10,
    )

    /**
     * Configuration for library search.
     */
    private data class SearchConfig(
        val query: String?,
        val chapterMatchIds: Set<Long>,
        val searchByUrl: Boolean,
        val useRegex: Boolean,
    )

    @Immutable
    data class LibraryData(
        val isInitialized: Boolean = false,
        val showSystemCategory: Boolean = false,
        val categories: List<Category> = emptyList(),
        val favorites: List<LibraryItem> = emptyList(),
        val tracksMap: Map</* Manga */ Long, List<Track>> = emptyMap(),
        val loggedInTrackerIds: Set<Long> = emptySet(),
    ) {
        val favoritesById: Map<Long, LibraryItem> by lazy {
            HashMap<Long, LibraryItem>(favorites.size * 2).also { map ->
                for (item in favorites) map[item.id] = item
            }
        }
    }

    @Immutable
    data class State(
        val isInitialized: Boolean = false,
        val isLoading: Boolean = true,
        val isQueryRunning: Boolean = false,
        val toolbarQuery: String? = null, // What's shown in the search box
        val searchQuery: String? = null, // Committed search (triggers filtering)
        val selection: Set</* Manga */ Long> = setOf(),
        val hasActiveFilters: Boolean = false,
        val showCategoryTabs: Boolean = false,
        val showMangaCount: Boolean = false,
        val showMangaContinueButton: Boolean = false,
        val dialog: Dialog? = null,
        val libraryData: LibraryData = LibraryData(),
        private val activeCategoryIndex: Int = 0,
        private val groupedFavorites: Map<Category, List</* LibraryItem */ Long>> = emptyMap(),
    ) {
        val displayedCategories: List<Category> = groupedFavorites.keys.toList()

        val coercedActiveCategoryIndex = activeCategoryIndex.coerceIn(
            minimumValue = 0,
            maximumValue = displayedCategories.lastIndex.coerceAtLeast(0),
        )

        val activeCategory: Category? = displayedCategories.getOrNull(coercedActiveCategoryIndex)

        val isLibraryEmpty = libraryData.favorites.isEmpty()

        val selectionMode = selection.isNotEmpty()

        val selectedManga by lazy { selection.mapNotNull { libraryData.favoritesById[it]?.libraryManga?.manga } }

        fun getItemsForCategoryId(categoryId: Long?): List<LibraryItem> {
            if (categoryId == null) return emptyList()
            val category = displayedCategories.find { it.id == categoryId } ?: return emptyList()
            return getItemsForCategory(category)
        }

        fun getItemsForCategory(category: Category): List<LibraryItem> {
            return groupedFavorites[category].orEmpty().mapNotNull { libraryData.favoritesById[it] }
        }

        fun getItemCountForCategory(category: Category): Int? {
            return if (showMangaCount || !searchQuery.isNullOrEmpty()) groupedFavorites[category]?.size else null
        }

        fun getToolbarTitle(
            defaultTitle: String,
            defaultCategoryTitle: String,
            page: Int,
        ): LibraryToolbarTitle {
            val category = displayedCategories.getOrNull(page) ?: return LibraryToolbarTitle(defaultTitle)
            val categoryName = category.let {
                if (it.isSystemCategory) defaultCategoryTitle else it.name
            }
            val title = if (showCategoryTabs) defaultTitle else categoryName
            val count = when {
                !showMangaCount -> null
                !showCategoryTabs -> getItemCountForCategory(category)
                // Whole library count
                else -> libraryData.favorites.size
            }
            return LibraryToolbarTitle(title, count)
        }
    }
}
