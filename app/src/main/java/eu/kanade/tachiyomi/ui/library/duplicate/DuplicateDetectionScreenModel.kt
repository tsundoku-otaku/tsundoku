package eu.kanade.tachiyomi.ui.library.duplicate

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryClearJob
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.custom.CustomNovelSource
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.DuplicateMatchMode
import tachiyomi.domain.manga.interactor.FindDuplicateNovels
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DuplicateDetectionScreenModel(
    private val findDuplicateNovels: FindDuplicateNovels = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val libraryPreferences: tachiyomi.domain.library.service.LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val translatedChapterRepository: TranslatedChapterRepository = Injekt.get(),
) : StateScreenModel<DuplicateDetectionScreenModel.State>(State()) {

    private val pinnedSourceIds: Set<Long> by lazy {
        sourcePreferences.pinnedSources.get().mapNotNull { it.toLongOrNull() }.toSet()
    }

    override fun onDispose() {
        super.onDispose()
        // Cancel all ongoing coroutines to prevent DB contention
        screenModelScope.coroutineContext.cancelChildren()
    }

    enum class ContentType {
        ALL,
        MANGA,
        NOVEL,
    }

    enum class SourceType {
        JS,
        KT,
        CUSTOM,
        LOCAL,
        STUB,
    }

    enum class CategoryIncludeMode {
        ANY,
        ALL,
    }

    /** Primitive per-entry data for bulk selection over the full matching set (no materialized Manga). */
    data class SelItem(
        val id: Long,
        val source: Long,
        val chapterCount: Int,
        val readCount: Int,
    )

    data class State(
        val isLoading: Boolean = false,
        val hasStartedAnalysis: Boolean = false,
        val matchMode: DuplicateMatchMode = DuplicateMatchMode.EXACT,
        val contentType: ContentType = ContentType.ALL,
        val duplicateGroups: Map<String, List<MangaWithChapterCount>> = emptyMap(),
        val selection: Set<Long> = emptySet(),
        val showDeleteDialog: Boolean = false,
        val showMoveToCategoryDialog: Boolean = false,
        val categories: List<Category> = emptyList(),
        val selectedCategoryFilters: Set<Long> = emptySet(),
        val categoryIncludeMode: CategoryIncludeMode = CategoryIncludeMode.ANY,
        val searchQuery: String = "",
        val excludedCategoryFilters: Set<Long> = emptySet(),
        val filterByGroupCategory: Boolean = false,
        val sortMode: SortMode = SortMode.NAME,
        val mangaCategories: Map<Long, List<Category>> = emptyMap(),
        val mangaCategoryIdSets: Map<Long, Set<Long>> = emptyMap(),
        val showFullUrls: Boolean = false,
        val mangaDownloadCounts: Map<Long, Int> = emptyMap(),
        val mangaReadCounts: Map<Long, Int> = emptyMap(),
        val pinnedSourceIds: Set<Long> = emptySet(),
        val novelSourceIds: Set<Long> = emptySet(),
        val sourcePriorities: Map<SourceType, Int> = SourceType.entries.associateWith { 0 },
        val specificSourcePriorities: Map<Long, Int> = emptyMap(),
        val sourceTypeMap: Map<Long, SourceType> = emptyMap(),
        val dismissedGroups: Set<String> = emptySet(),
        val filteredDuplicateGroups: Map<String, List<MangaWithChapterCount>> = emptyMap(),
        val listingMode: Boolean = false,
        val listingTruncated: Boolean = false,
        val selectionGroups: List<List<SelItem>> = emptyList(),
    ) {

        fun computeFilteredGroups(): Map<String, List<MangaWithChapterCount>> {
            val visibleGroups = duplicateGroups.filterKeys { it !in dismissedGroups }
            val searchFiltered = if (searchQuery.isBlank()) {
                visibleGroups
            } else {
                val query = searchQuery.lowercase()
                visibleGroups.filter { (key, items) ->
                    key.lowercase().contains(query) ||
                        items.any { it.manga.title.lowercase().contains(query) }
                }
            }

            val minGroupSize = if (listingMode) 1 else 2
            val contentFiltered = when (contentType) {
                ContentType.ALL -> searchFiltered
                ContentType.MANGA -> searchFiltered.mapValues { (_, items) ->
                    items.filter { it.manga.source !in novelSourceIds }
                }.filter { it.value.size >= minGroupSize }
                ContentType.NOVEL -> searchFiltered.mapValues { (_, items) ->
                    items.filter { it.manga.source in novelSourceIds }
                }.filter { it.value.size >= minGroupSize }
            }

            val filtered = if (selectedCategoryFilters.isEmpty() && excludedCategoryFilters.isEmpty()) {
                contentFiltered
            } else {
                if (filterByGroupCategory) {
                    contentFiltered.filter { (_, novels) ->
                        val groupMatches = novels.any { novel ->
                            val categoryIds = mangaCategoryIdSets[novel.manga.id] ?: setOf(0L)
                            val passesInclude =
                                selectedCategoryFilters.isEmpty() || when (categoryIncludeMode) {
                                    CategoryIncludeMode.ANY -> categoryIds.any { it in selectedCategoryFilters }
                                    CategoryIncludeMode.ALL -> selectedCategoryFilters.all { it in categoryIds }
                                }
                            val passesExclude = excludedCategoryFilters.isEmpty() ||
                                categoryIds.none { it in excludedCategoryFilters }
                            passesInclude && passesExclude
                        }
                        groupMatches
                    }
                } else {
                    contentFiltered.filter { (_, novels) ->
                        novels.all { novel ->
                            val categoryIds = mangaCategoryIdSets[novel.manga.id] ?: setOf(0L)
                            val passesInclude = selectedCategoryFilters.isEmpty() || when (categoryIncludeMode) {
                                CategoryIncludeMode.ANY -> categoryIds.any { it in selectedCategoryFilters }
                                CategoryIncludeMode.ALL -> selectedCategoryFilters.all { it in categoryIds }
                            }
                            val passesExclude = excludedCategoryFilters.isEmpty() ||
                                categoryIds.none { it in excludedCategoryFilters }
                            passesInclude && passesExclude
                        }
                    }
                }
            }

            return when (sortMode) {
                SortMode.NAME -> filtered.toSortedMap()
                SortMode.LATEST_ADDED ->
                    filtered.entries
                        .sortedByDescending { (_, novels) ->
                            novels.maxOfOrNull { it.manga.dateAdded } ?: 0L
                        }
                        .associate { it.key to it.value }
                SortMode.CHAPTER_COUNT_DESC ->
                    filtered.entries
                        .sortedByDescending { (_, novels) ->
                            novels.sumOf { it.chapterCount }
                        }
                        .associate { it.key to it.value }
                SortMode.CHAPTER_COUNT_ASC ->
                    filtered.entries
                        .sortedBy { (_, novels) ->
                            novels.sumOf { it.chapterCount }
                        }
                        .associate { it.key to it.value }
                SortMode.DOWNLOAD_COUNT_DESC ->
                    filtered.entries
                        .sortedByDescending { (_, novels) ->
                            novels.sumOf { mangaDownloadCounts[it.manga.id] ?: 0 }
                        }
                        .associate { it.key to it.value }
                SortMode.DOWNLOAD_COUNT_ASC ->
                    filtered.entries
                        .sortedBy { (_, novels) ->
                            novels.sumOf { mangaDownloadCounts[it.manga.id] ?: 0 }
                        }
                        .associate { it.key to it.value }
                SortMode.READ_COUNT_DESC ->
                    filtered.entries
                        .sortedByDescending { (_, novels) ->
                            novels.sumOf { mangaReadCounts[it.manga.id] ?: 0 }
                        }
                        .associate { it.key to it.value }
                SortMode.READ_COUNT_ASC ->
                    filtered.entries
                        .sortedBy { (_, novels) ->
                            novels.sumOf { mangaReadCounts[it.manga.id] ?: 0 }
                        }
                        .associate { it.key to it.value }
                SortMode.PINNED_SOURCE ->
                    filtered.entries
                        .sortedByDescending { (_, novels) ->
                            novels.count { it.manga.source in pinnedSourceIds }
                        }
                        .associate { it.key to it.value }
                SortMode.SOURCE_PRIORITY ->
                    filtered.entries
                        .sortedByDescending { (_, novels) ->
                            novels.maxOfOrNull { getSourcePriority(it.manga.source) } ?: 0
                        }
                        .associate { it.key to it.value }
            }
        }

        fun getSourcePriority(sourceId: Long): Int {
            val specificPriority = specificSourcePriorities[sourceId]
            if (specificPriority != null && specificPriority != 0) return specificPriority
            val type = sourceTypeMap[sourceId] ?: SourceType.STUB
            return sourcePriorities[type] ?: 0
        }

        // Helper to check if a manga is from a pinned source
        // Maybe prioritize pinned sources in the future
        // fun isMangaPinned(manga: Manga): Boolean = manga.source in pinnedSourceIds
    }

    enum class SortMode {
        NAME,
        LATEST_ADDED,
        CHAPTER_COUNT_DESC,
        CHAPTER_COUNT_ASC,
        DOWNLOAD_COUNT_DESC,
        DOWNLOAD_COUNT_ASC,
        READ_COUNT_DESC,
        READ_COUNT_ASC,
        PINNED_SOURCE,
        SOURCE_PRIORITY,
    }

    init {
        loadCategories()
        loadPrioritiesFromPreferences()
    }

    private fun loadPrioritiesFromPreferences() {
        // Load source type priorities
        val typeRaw = libraryPreferences.sourceTypePriorities.get()
        if (typeRaw.isNotBlank()) {
            val typeMap = typeRaw.split(";").filter { it.isNotBlank() }.mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    try {
                        SourceType.valueOf(parts[0]) to parts[1].toInt()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
            }.toMap()
            mutableState.update { state ->
                state.copy(
                    sourcePriorities = SourceType.entries.associateWith { typeMap[it] ?: 0 },
                )
            }
        }

        // Load specific source priorities
        val specificRaw = libraryPreferences.specificSourcePriorities.get()
        if (specificRaw.isNotBlank()) {
            val specificMap = specificRaw.split(";").filter { it.isNotBlank() }.mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    try {
                        parts[0].toLong() to parts[1].toInt()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
            }.toMap()
            mutableState.update { state ->
                state.copy(specificSourcePriorities = specificMap)
            }
        }
    }

    private fun loadCategories() {
        screenModelScope.launch(Dispatchers.IO) {
            val categories = getCategories.await()
            mutableState.update { it.copy(categories = categories) }
        }
    }

    // Category filters bound the listing materialization, so a change must reload; duplicate mode has
    // everything in memory already and only needs to recompute.
    private fun refreshAfterCategoryChange() {
        if (state.value.listingMode) loadDuplicates() else recomputeFiltered()
    }

    private fun recomputeFiltered() {
        mutableState.update { it.copy(filteredDuplicateGroups = it.computeFilteredGroups()) }
    }

    fun setSearchQuery(query: String) {
        mutableState.update { it.copy(searchQuery = query) }
        recomputeFiltered()
    }

    fun loadDuplicates() {
        screenModelScope.launch(Dispatchers.IO) {
            // Release the previous result before loading a new one so a near-full heap can be reclaimed
            // first, instead of holding both the old and new data sets at once.
            mutableState.update {
                it.copy(
                    isLoading = true,
                    hasStartedAnalysis = true,
                    duplicateGroups = emptyMap(),
                    filteredDuplicateGroups = emptyMap(),
                    mangaCategories = emptyMap(),
                    mangaCategoryIdSets = emptyMap(),
                    mangaDownloadCounts = emptyMap(),
                    mangaReadCounts = emptyMap(),
                    selectionGroups = emptyList(),
                )
            }
            try {
                var truncated = false
                var selectionGroups = emptyList<List<SelItem>>()
                val groups = if (state.value.listingMode) {
                    val metrics = mangaRepository.getFavoriteSelectionMetrics(
                        listingIncludeCategories(state.value),
                        SELECTION_MAX,
                    )
                    truncated = metrics.size > LISTING_MAX
                    selectionGroups = metrics.groupBy { it.groupKey }
                        .map { (_, items) -> items.map { SelItem(it.id, it.source, it.chapterCount, it.readCount) } }
                    findDuplicateNovels.findGroupedByIds(metrics.take(LISTING_MAX).map { it.id })
                } else {
                    findDuplicateNovels.findDuplicatesGrouped(state.value.matchMode)
                }

                val allMangaItems = groups.values.flatten()
                val allMangaIds = allMangaItems.map { it.manga.id }.distinct()

                val mangaCategoriesMap = getCategories.awaitForMangas(allMangaIds)
                val mangaCategoryIdSets = mangaCategoriesMap.mapValues { (_, categories) ->
                    categories.map { it.id }.toSet().ifEmpty { setOf(0L) }
                }

                // Cover sources of the whole selection set, not just the displayed page, so content-type
                // and source-priority selection are correct beyond the materialized rows.
                val allSourceIds = (allMangaItems.map { it.manga.source } + selectionGroups.flatten().map { it.source })
                    .distinct()
                val novelSourceIds = allSourceIds.filter { sourceId ->
                    sourceManager.getOrStub(sourceId).isNovelSource()
                }.toSet()

                val sourceTypeMap = allSourceIds.associateWith { sourceId ->
                    classifySourceType(sourceId)
                }

                val downloadCounts = downloadManager.getDownloadCounts(allMangaItems.map { it.manga })

                val readCounts = allMangaItems.associate { it.manga.id to it.readCount.toInt() }

                mutableState.update {
                    it.copy(
                        duplicateGroups = groups,
                        mangaCategories = mangaCategoriesMap,
                        mangaCategoryIdSets = mangaCategoryIdSets,
                        novelSourceIds = novelSourceIds,
                        sourceTypeMap = sourceTypeMap,
                        mangaDownloadCounts = downloadCounts,
                        mangaReadCounts = readCounts,
                        pinnedSourceIds = pinnedSourceIds,
                        dismissedGroups = emptySet(),
                        isLoading = false,
                        listingTruncated = truncated,
                        selectionGroups = selectionGroups,
                    )
                }
                recomputeFiltered()
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(
                        duplicateGroups = emptyMap(),
                        filteredDuplicateGroups = emptyMap(),
                        isLoading = false,
                        listingTruncated = false,
                        selectionGroups = emptyList(),
                    )
                }
            }
        }
    }

    /**
     * Category ids to push into the metrics query. Empty means "all favorites": the uncategorized
     * bucket cannot be expressed as a join, so those cases load all and refine in memory.
     */
    private fun listingIncludeCategories(state: State): List<Long> {
        val hasUncategorized = UNCATEGORIZED_ID in state.selectedCategoryFilters
        val includeCategories = state.selectedCategoryFilters.filter { it != UNCATEGORIZED_ID }
        return if (includeCategories.isNotEmpty() && !hasUncategorized) includeCategories else emptyList()
    }

    /**
     * Groups used by the bulk-selection actions. In listing mode this is the full lightweight set
     * (well beyond the displayed page) filtered by content type; otherwise the displayed duplicate
     * groups. Selection therefore covers every matching entry, not just the materialized rows.
     */
    private fun selectionItemGroups(state: State): List<List<SelItem>> {
        // The full lightweight set carries no category membership, so it can only drive selection when
        // no category filter is active. With a category filter (include, exclude, uncategorized or ALL
        // mode) selection must fall back to the displayed, category-filtered groups to avoid selecting
        // entries the user filtered out.
        val categoryFilterActive =
            state.selectedCategoryFilters.isNotEmpty() || state.excludedCategoryFilters.isNotEmpty()
        return if (state.listingMode && !categoryFilterActive) {
            val novelIds = state.novelSourceIds
            state.selectionGroups.mapNotNull { group ->
                val filtered = when (state.contentType) {
                    ContentType.ALL -> group
                    ContentType.MANGA -> group.filter { it.source !in novelIds }
                    ContentType.NOVEL -> group.filter { it.source in novelIds }
                }
                filtered.ifEmpty { null }
            }
        } else {
            val readCounts = state.mangaReadCounts
            state.filteredDuplicateGroups.values.map { group ->
                group.map { entry ->
                    SelItem(
                        id = entry.manga.id,
                        source = entry.manga.source,
                        chapterCount = entry.chapterCount.toInt(),
                        readCount = readCounts[entry.manga.id] ?: entry.readCount.toInt(),
                    )
                }
            }
        }
    }

    fun setMatchMode(mode: DuplicateMatchMode) {
        if (mode != state.value.matchMode) {
            mutableState.update { it.copy(matchMode = mode, selection = emptySet()) }
            loadDuplicates()
        }
    }

    fun setContentType(contentType: ContentType) {
        mutableState.update { it.copy(contentType = contentType, selection = emptySet()) }
        recomputeFiltered()
    }

    fun setListingMode(enabled: Boolean) {
        if (enabled == state.value.listingMode) return
        mutableState.update { it.copy(listingMode = enabled, selection = emptySet()) }
        loadDuplicates()
    }

    fun toggleCategoryFilter(categoryId: Long) {
        mutableState.update { state ->
            when {
                // Currently included → move to excluded
                categoryId in state.selectedCategoryFilters -> state.copy(
                    selectedCategoryFilters = state.selectedCategoryFilters - categoryId,
                    excludedCategoryFilters = state.excludedCategoryFilters + categoryId,
                )
                // Currently excluded → remove filter
                categoryId in state.excludedCategoryFilters -> state.copy(
                    excludedCategoryFilters = state.excludedCategoryFilters - categoryId,
                )
                // Not filtered → include
                else -> state.copy(
                    selectedCategoryFilters = state.selectedCategoryFilters + categoryId,
                )
            }
        }
        refreshAfterCategoryChange()
    }

    fun clearCategoryFilters() {
        mutableState.update { it.copy(selectedCategoryFilters = emptySet(), excludedCategoryFilters = emptySet()) }
        refreshAfterCategoryChange()
    }

    fun setCategoryIncludeMode(mode: CategoryIncludeMode) {
        mutableState.update { it.copy(categoryIncludeMode = mode) }
        refreshAfterCategoryChange()
    }

    fun setFilterByGroupCategory(filter: Boolean) {
        mutableState.update { it.copy(filterByGroupCategory = filter) }
        refreshAfterCategoryChange()
    }

    fun setSortMode(mode: SortMode) {
        mutableState.update { it.copy(sortMode = mode) }
        recomputeFiltered()
    }

    fun toggleShowFullUrls() {
        mutableState.update { it.copy(showFullUrls = !it.showFullUrls) }
    }

    fun toggleSelection(mangaId: Long) {
        mutableState.update { state ->
            val newSelection = if (mangaId in state.selection) {
                state.selection - mangaId
            } else {
                state.selection + mangaId
            }
            state.copy(selection = newSelection)
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = emptySet()) }
    }

    fun invertSelection() {
        mutableState.update { state ->
            val allIds = selectionItemGroups(state).flatMapTo(HashSet()) { group -> group.map { it.id } }
            state.copy(selection = allIds - state.selection)
        }
    }

    fun selectAllDuplicates() {
        mutableState.update { state ->
            val allIds = selectionItemGroups(state).flatMapTo(HashSet()) { group -> group.map { it.id } }
            state.copy(selection = allIds)
        }
    }

    fun selectAllExceptFirst() {
        mutableState.update { state ->
            val ids = selectionItemGroups(state).flatMapTo(HashSet()) { group -> group.drop(1).map { it.id } }
            state.copy(selection = ids)
        }
    }

    /**
     * Select the pinned source novel in each group (the one from a pinned source).
     * If no pinned source in a group, selects the first novel.
     */
    fun selectPinnedInGroups() {
        val pinned = pinnedSourceIds
        mutableState.update { state ->
            val ids = selectionItemGroups(state).mapNotNullTo(HashSet()) { group ->
                (group.firstOrNull { it.source in pinned } ?: group.firstOrNull())?.id
            }
            state.copy(selection = ids)
        }
    }

    /**
     * Select all novels except those from pinned sources in each group.
     * Useful to keep pinned sources and delete the rest.
     */
    fun selectNonPinnedInGroups() {
        val pinned = pinnedSourceIds
        mutableState.update { state ->
            val ids = selectionItemGroups(state)
                .flatMapTo(HashSet()) { group -> group.filter { it.source !in pinned }.map { it.id } }
            state.copy(selection = ids)
        }
    }

    fun selectLowestChapterCount() {
        mutableState.update { state ->
            val ids = selectionItemGroups(state)
                .mapNotNullTo(HashSet()) { group -> group.minByOrNull { it.chapterCount }?.id }
            state.copy(selection = ids)
        }
    }

    fun selectHighestChapterCount() {
        mutableState.update { state ->
            val ids = selectionItemGroups(state)
                .mapNotNullTo(HashSet()) { group -> group.maxByOrNull { it.chapterCount }?.id }
            state.copy(selection = ids)
        }
    }

    fun selectLowestDownloadCount() {
        val downloadCounts = state.value.mangaDownloadCounts
        val ids = state.value.filteredDuplicateGroups.values
            .mapNotNull { group ->
                // Filter to only those with downloads > 0
                val withDownloads = group.filter { (downloadCounts[it.manga.id] ?: 0) > 0 }
                withDownloads.minByOrNull { downloadCounts[it.manga.id] ?: 0 }?.manga?.id
            }
            .toSet()
        mutableState.update { it.copy(selection = ids) }
    }

    fun selectHighestDownloadCount() {
        val downloadCounts = state.value.mangaDownloadCounts
        val ids = state.value.filteredDuplicateGroups.values
            .mapNotNull { group ->
                // Filter to only those with downloads > 0
                val withDownloads = group.filter { (downloadCounts[it.manga.id] ?: 0) > 0 }
                withDownloads.maxByOrNull { downloadCounts[it.manga.id] ?: 0 }?.manga?.id
            }
            .toSet()
        mutableState.update { it.copy(selection = ids) }
    }

    fun selectLowestReadCount() {
        mutableState.update { state ->
            val ids = selectionItemGroups(state).mapNotNullTo(HashSet()) { group ->
                group.filter { it.readCount > 0 }.minByOrNull { it.readCount }?.id
            }
            state.copy(selection = ids)
        }
    }

    fun selectHighestReadCount() {
        mutableState.update { state ->
            val ids = selectionItemGroups(state).mapNotNullTo(HashSet()) { group ->
                group.filter { it.readCount > 0 }.maxByOrNull { it.readCount }?.id
            }
            state.copy(selection = ids)
        }
    }

    fun selectGroup(groupTitle: String) {
        val group = state.value.filteredDuplicateGroups[groupTitle] ?: return
        val groupIds = group.map { it.manga.id }.toSet()
        mutableState.update { state ->
            val allSelected = groupIds.isNotEmpty() && state.selection.containsAll(groupIds)
            val newSelection = if (allSelected) state.selection - groupIds else state.selection + groupIds
            state.copy(selection = newSelection)
        }
    }

    fun dismissGroup(groupTitle: String) {
        val group = state.value.duplicateGroups[groupTitle]
        val groupIds = group?.map { it.manga.id }?.toSet() ?: emptySet()
        mutableState.update { state ->
            state.copy(
                dismissedGroups = state.dismissedGroups + groupTitle,
                selection = state.selection - groupIds,
            )
        }
        recomputeFiltered()
    }

    /**
     * Classify a source by type: JS, KT, CUSTOM, LOCAL, or STUB.
     */
    private fun classifySourceType(sourceId: Long): SourceType {
        val source = sourceManager.get(sourceId) ?: return SourceType.STUB
        return when {
            source.isLocal() -> SourceType.LOCAL
            source is CustomNovelSource -> SourceType.CUSTOM
            source is JsSource -> SourceType.JS
            else -> SourceType.KT
        }
    }

    fun setSourcePriority(type: SourceType, priority: Int) {
        val newMap = state.value.sourcePriorities + (type to priority)
        mutableState.update { state ->
            state.copy(sourcePriorities = newMap)
        }
        // Persist to preferences
        val serialized = newMap.entries
            .filter { it.value != 0 }
            .joinToString(";") { "${it.key.name}:${it.value}" }
        libraryPreferences.sourceTypePriorities.set(serialized)
        recomputeFiltered()
    }

    /**
     * Select the lowest-priority source entry in each duplicate group.
     * This keeps the highest-priority entry and marks the rest for deletion.
     */
    fun selectLowestSourcePriority() {
        mutableState.update { state ->
            val ids = selectionItemGroups(state).flatMapTo(HashSet()) { group ->
                if (group.size < 2) {
                    emptyList()
                } else {
                    group.sortedByDescending { state.getSourcePriority(it.source) }.drop(1).map { it.id }
                }
            }
            state.copy(selection = ids)
        }
    }

    /**
     * Select the highest-priority source entry in each duplicate group.
     */
    fun selectHighestSourcePriority() {
        mutableState.update { state ->
            val ids = selectionItemGroups(state).mapNotNullTo(HashSet()) { group ->
                group.maxByOrNull { state.getSourcePriority(it.source) }?.id
            }
            state.copy(selection = ids)
        }
    }

    suspend fun getSelectedUrls(): List<String> {
        val selectedIds = state.value.selection.toList()
        if (selectedIds.isEmpty()) return emptyList()
        return mangaRepository.getMangaWithCountsLight(selectedIds)
            .map { mangaWithCount ->
                val manga = mangaWithCount.manga
                val url = manga.url
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    url
                } else {
                    when (val source = sourceManager.getOrStub(manga.source)) {
                        is HttpSource -> try {
                            source.getMangaUrl(manga.toSManga())
                        } catch (_: Exception) {
                            source.baseUrl + url
                        }
                        else -> url
                    }
                }
            }
    }

    fun openDeleteDialog() {
        mutableState.update { it.copy(showDeleteDialog = true) }
    }

    fun closeDeleteDialog() {
        mutableState.update { it.copy(showDeleteDialog = false) }
    }

    fun openMoveToCategoryDialog() {
        mutableState.update { it.copy(showMoveToCategoryDialog = true) }
    }

    fun closeMoveToCategoryDialog() {
        mutableState.update { it.copy(showMoveToCategoryDialog = false) }
    }

    fun selectionContainsLocalManga(): Boolean {
        val selectedIds = state.value.selection
        return selectionItemGroups(state.value).any { group ->
            group.any { it.id in selectedIds && sourceManager.getOrStub(it.source).isLocal() }
        }
    }

    suspend fun deleteSelected(
        removeFromLibrary: Boolean,
        deleteDownloads: Boolean,
        clearChaptersFromDb: Boolean,
        deleteTranslations: Boolean,
        clearCovers: Boolean,
        clearDescriptions: Boolean,
        clearTags: Boolean,
    ) {
        val selectedIdList = state.value.selection.toList()
        withContext(Dispatchers.IO) {
            val selectedManga = mangaRepository.getMangaWithCountsLight(selectedIdList).map { it.manga }
            if (deleteDownloads) {
                selectedManga.forEach { manga ->
                    try {
                        val source = sourceManager.get(manga.source) ?: return@forEach
                        downloadManager.deleteManga(manga, source)
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR) { "Error deleting downloads for manga ${manga.id}: ${e.message}" }
                    }
                }
            }

            if (removeFromLibrary) {
                selectedManga.forEach { it.removeCovers(coverCache) }
                selectedIdList.chunked(100).forEach { batch ->
                    try {
                        mangaRepository.updateAll(
                            batch.map { tachiyomi.domain.manga.model.MangaUpdate(id = it, favorite = false) },
                        )
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR) { "Error batch updating manga favorites: ${e.message}" }
                    }
                }
            }

            if (deleteTranslations) {
                selectedManga.forEach { manga ->
                    val chapters = getChaptersByMangaId.await(manga.id)
                    translatedChapterRepository.deleteAllForChapters(chapters.map { it.id })
                }
            }

            val clearOperations = buildList {
                if (clearChaptersFromDb) add(LibraryClearJob.OP_CLEAR_CHAPTERS)
                if (clearCovers) add(LibraryClearJob.OP_CLEAR_COVERS)
                if (clearDescriptions) add(LibraryClearJob.OP_CLEAR_DESCRIPTIONS)
                if (clearTags) add(LibraryClearJob.OP_CLEAR_TAGS)
            }
            if (clearOperations.isNotEmpty()) {
                LibraryClearJob.start(Injekt.get<android.app.Application>(), selectedIdList, clearOperations)
            }

            mutableState.update { it.copy(selection = emptySet()) }
            loadDuplicates()
        }
    }

    suspend fun moveSelectedToCategories(categoryIds: List<Long>) {
        val selectedIds = state.value.selection.toList()
        withContext(Dispatchers.IO) {
            mangaRepository.setMangasCategories(selectedIds, categoryIds)
            mutableState.update { it.copy(selection = emptySet()) }
            loadDuplicates()
        }
    }

    companion object {
        // Rows materialized for display. Selection runs over the far larger metric set below.
        private const val LISTING_MAX = 2000
        private const val SELECTION_MAX = 1000_000L
        private const val UNCATEGORIZED_ID = 0L
    }
}
