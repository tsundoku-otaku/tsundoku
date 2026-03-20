package eu.kanade.tachiyomi.ui.updates

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.util.fastFilter
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.updates.UpdatesUiModel
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.updates.interactor.ClearUpdatesCache
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZonedDateTime

enum class UpdatesFilter {
    ALL,
    MANGA,
    NOVELS,
}

/**
 * Represents a group of updates for a single novel.
 * Used when "group by novel" is enabled.
 */
@Immutable
data class UpdatesNovelGroup(
    val mangaId: Long,
    val mangaTitle: String,
    val coverUrl: String?,
    val sourceId: Long,
    val latestChapterDate: Long,
    val chapters: List<UpdatesItem>,
    val isNovel: Boolean = false,
) {
    val chapterCount: Int get() = chapters.size
    val hasUnreadChapters: Boolean get() = chapters.any { !it.update.read }
    val hasDownloaded: Boolean get() = chapters.any { it.downloadStateProvider() == Download.State.DOWNLOADED }
}

class UpdatesScreenModel(
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val getUpdates: GetUpdates = Injekt.get(),
    private val clearUpdatesCache: ClearUpdatesCache = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val updatesPreferences: UpdatesPreferences = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<UpdatesScreenModel.State>(
    State(groupByNovel = Injekt.get<LibraryPreferences>().updatesGroupByNovel().get()),
) {

    companion object {
        private const val GROUPED_NOVEL_TARGET = 30
    }

    @Volatile
    private var latestUpdates: List<UpdatesWithRelations> = emptyList()

    private val _events: Channel<Event> = Channel(Int.MAX_VALUE)
    val events: Flow<Event> = _events.receiveAsFlow()

    val lastUpdated by libraryPreferences.lastUpdatedTimestamp().asState(screenModelScope)

    // First and last selected index in list
    private val selectedPositions: Array<Int> = arrayOf(-1, -1)
    private val selectedChapterIds: HashSet<Long> = HashSet()

    // DB-level pagination: start with one page, grow as user scrolls
    private val currentLimit = MutableStateFlow(GetUpdates.PAGE_SIZE)

    init {
        screenModelScope.launchIO {
            // Set date limit for recent chapters
            val dateThreshold = ZonedDateTime.now().minusMonths(3).toInstant()

            combine(
                // needed for SQL filters (unread, started, bookmarked, etc)
                combine(
                    getUpdatesItemPreferenceFlow().distinctUntilChanged(),
                    currentLimit,
                ) { prefs, dbLimit -> prefs to dbLimit }
                    .flatMapLatest { (prefs, dbLimit) ->
                        getUpdates.subscribe(
                            dateThreshold,
                            limit = dbLimit,
                            unread = prefs.filterUnread.toBooleanOrNull(),
                            started = prefs.filterStarted.toBooleanOrNull(),
                            bookmarked = prefs.filterBookmarked.toBooleanOrNull(),
                            hideExcludedScanlators = prefs.filterExcludedScanlators,
                        ).distinctUntilChanged()
                    },
                downloadCache.changes,
                downloadManager.queueState,
                libraryPreferences.lastUpdatesClearedTimestamp().changes(),
                // needed for Kotlin filters (downloaded)
                getUpdatesItemPreferenceFlow().distinctUntilChanged { old, new ->
                    old.filterDownloaded == new.filterDownloaded
                },
            ) { updates, _, _, clearedAt, itemPreferences ->
                val filteredUpdates = if (clearedAt > 0L) {
                    updates.filter { it.dateFetch > clearedAt }
                } else {
                    updates
                }
                latestUpdates = filteredUpdates
                val items = filteredUpdates
                    .toUpdateItems()
                    .applyFilters(itemPreferences)
                    .toPersistentList()
                // If returned items fill the limit, there may be more
                val hasMore = updates.size.toLong() >= currentLimit.value
                items to hasMore
            }
                .catch {
                    logcat(LogPriority.ERROR, it)
                    _events.send(Event.InternalError)
                }
                .collectLatest { (updateItems, hasMore) ->
                    val shouldPrefetchForGroupedView =
                        state.value.groupByNovel &&
                            hasMore &&
                            updateItems.groupBy { it.update.mangaId }.size < GROUPED_NOVEL_TARGET

                    if (shouldPrefetchForGroupedView) {
                        mutableState.update { it.copy(isLoadingMore = true) }
                        currentLimit.value += GetUpdates.PAGE_SIZE
                        return@collectLatest
                    }

                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            items = updateItems,
                            hasMorePages = hasMore,
                        )
                    }
                }
        }

        screenModelScope.launchIO {
            merge(downloadManager.statusFlow(), downloadManager.progressFlow())
                .catch { logcat(LogPriority.ERROR, it) }
                .collect(this@UpdatesScreenModel::updateDownloadState)
        }

        getUpdatesItemPreferenceFlow()
            .map { prefs ->
                listOf(
                    prefs.filterUnread,
                    prefs.filterDownloaded,
                    prefs.filterStarted,
                    prefs.filterBookmarked,
                )
                    .any { it != TriState.DISABLED }
            }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(hasActiveFilters = it)
                }
            }
            .launchIn(screenModelScope)
    }

    private fun List<UpdatesItem>.applyFilters(
        preferences: ItemPreferences,
    ): List<UpdatesItem> {
        val filterDownloaded = preferences.filterDownloaded

        val filterFnDownloaded: (UpdatesItem) -> Boolean = {
            applyFilter(filterDownloaded) {
                it.downloadStateProvider() == Download.State.DOWNLOADED
            }
        }

        return fastFilter {
            filterFnDownloaded(it)
        }
    }

    private fun List<UpdatesWithRelations>.toUpdateItems(): List<UpdatesItem> {
        val filter = state.value.filter
        // Cache source lookups to avoid repeated getOrStub + isNovelSource calls
        val novelSourceCache = mutableMapOf<Long, Boolean>()
        fun isNovel(sourceId: Long): Boolean = novelSourceCache.getOrPut(sourceId) {
            sourceManager.getOrStub(sourceId).isNovelSource()
        }

        return this
            .filter { update ->
                when (filter) {
                    UpdatesFilter.ALL -> true
                    UpdatesFilter.MANGA -> !isNovel(update.sourceId)
                    UpdatesFilter.NOVELS -> isNovel(update.sourceId)
                }
            }
            .map { update ->
                val activeDownload = downloadManager.getQueuedDownloadOrNull(update.chapterId)
                val downloaded = downloadManager.isChapterDownloaded(
                    update.chapterName,
                    update.scanlator,
                    update.chapterUrl,
                    update.mangaTitle,
                    update.sourceId,
                )
                val downloadState = when {
                    activeDownload != null -> activeDownload.status
                    downloaded -> Download.State.DOWNLOADED
                    else -> Download.State.NOT_DOWNLOADED
                }
                UpdatesItem(
                    update = update,
                    downloadStateProvider = { downloadState },
                    downloadProgressProvider = { activeDownload?.progress ?: 0 },
                    selected = update.chapterId in selectedChapterIds,
                    isNovel = isNovel(update.sourceId),
                )
            }
    }

    fun updateLibrary(): Boolean {
        val started = LibraryUpdateJob.startNow(Injekt.get<Application>())
        screenModelScope.launch {
            _events.send(Event.LibraryUpdateTriggered(started))
        }
        return started
    }

    /**
     * Update status of chapters.
     *
     * @param download download object containing progress.
     */
    private fun updateDownloadState(download: Download) {
        mutableState.update { state ->
            val newItems = state.items.mutate { list ->
                val modifiedIndex = list.indexOfFirst { it.update.chapterId == download.chapterId }
                if (modifiedIndex < 0) return@mutate

                val item = list[modifiedIndex]
                list[modifiedIndex] = item.copy(
                    downloadStateProvider = { download.status },
                    downloadProgressProvider = { download.progress },
                )
            }
            state.copy(items = newItems)
        }
    }

    fun downloadChapters(items: List<UpdatesItem>, action: ChapterDownloadAction) {
        if (items.isEmpty()) return
        screenModelScope.launch {
            when (action) {
                ChapterDownloadAction.START -> {
                    downloadChapters(items)
                    if (items.any { it.downloadStateProvider() == Download.State.ERROR }) {
                        downloadManager.startDownloads()
                    }
                }
                ChapterDownloadAction.START_NOW -> {
                    val chapterId = items.singleOrNull()?.update?.chapterId ?: return@launch
                    startDownloadingNow(chapterId)
                }
                ChapterDownloadAction.CANCEL -> {
                    val chapterId = items.singleOrNull()?.update?.chapterId ?: return@launch
                    cancelDownload(chapterId)
                }
                ChapterDownloadAction.DELETE -> {
                    deleteChapters(items)
                }
            }
            toggleAllSelection(false)
        }
    }

    private suspend fun startDownloadingNow(chapterId: Long) {
        downloadManager.startDownloadNow(chapterId)
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    /**
     * Mark the selected updates list as read/unread.
     * @param updates the list of selected updates.
     * @param read whether to mark chapters as read or unread.
     */
    fun markUpdatesRead(updates: List<UpdatesItem>, read: Boolean) {
        screenModelScope.launchIO {
            val chapterIds = updates.map { it.update.chapterId }
            val chapters = getChapter.awaitAll(chapterIds)
            setReadStatus.await(
                read = read,
                chapters = chapters.toTypedArray(),
            )
        }
        toggleAllSelection(false)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param updates the list of chapters to bookmark.
     */
    fun bookmarkUpdates(updates: List<UpdatesItem>, bookmark: Boolean) {
        screenModelScope.launchIO {
            updates
                .filterNot { it.update.bookmark == bookmark }
                .map { ChapterUpdate(id = it.update.chapterId, bookmark = bookmark) }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param updatesItem the list of chapters to download.
     */
    private fun downloadChapters(updatesItem: List<UpdatesItem>) {
        screenModelScope.launchNonCancellable {
            val groupedUpdates = updatesItem.groupBy { it.update.mangaId }.values
            for (updates in groupedUpdates) {
                val mangaId = updates.first().update.mangaId
                val manga = getManga.await(mangaId) ?: continue
                // Don't download if source isn't available
                sourceManager.get(manga.source) ?: continue
                val chapterIds = updates.map { it.update.chapterId }
                val chapters = getChapter.awaitAll(chapterIds)
                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    /**
     * Delete selected chapters
     *
     * @param updatesItem list of chapters
     */
    fun deleteChapters(updatesItem: List<UpdatesItem>) {
        screenModelScope.launchNonCancellable {
            updatesItem
                .groupBy { it.update.mangaId }
                .entries
                .forEach { (mangaId, updates) ->
                    val manga = getManga.await(mangaId) ?: return@forEach
                    val source = sourceManager.get(manga.source) ?: return@forEach
                    val chapterIds = updates.map { it.update.chapterId }
                    val chapters = getChapter.awaitAll(chapterIds)
                    downloadManager.deleteChapters(chapters, manga, source)
                }
        }
        toggleAllSelection(false)
    }

    fun showConfirmDeleteChapters(updatesItem: List<UpdatesItem>) {
        setDialog(Dialog.DeleteConfirmation(updatesItem))
    }

    fun toggleSelection(
        item: UpdatesItem,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        mutableState.update { state ->
            val newItems = state.items.toMutableList().apply {
                val selectedIndex = indexOfFirst { it.update.chapterId == item.update.chapterId }
                if (selectedIndex < 0) return@apply

                val selectedItem = get(selectedIndex)
                if (selectedItem.selected == selected) return@apply

                val firstSelection = none { it.selected }
                set(selectedIndex, selectedItem.copy(selected = selected))
                selectedChapterIds.addOrRemove(item.update.chapterId, selected)

                if (selected && fromLongPress) {
                    if (firstSelection) {
                        selectedPositions[0] = selectedIndex
                        selectedPositions[1] = selectedIndex
                    } else {
                        // Try to select the items in-between when possible
                        val range: IntRange
                        if (selectedIndex < selectedPositions[0]) {
                            range = selectedIndex + 1..<selectedPositions[0]
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            range = (selectedPositions[1] + 1)..<selectedIndex
                            selectedPositions[1] = selectedIndex
                        } else {
                            // Just select itself
                            range = IntRange.EMPTY
                        }

                        range.forEach {
                            val inbetweenItem = get(it)
                            if (!inbetweenItem.selected) {
                                selectedChapterIds.add(inbetweenItem.update.chapterId)
                                set(it, inbetweenItem.copy(selected = true))
                            }
                        }
                    }
                } else if (!fromLongPress) {
                    if (!selected) {
                        if (selectedIndex == selectedPositions[0]) {
                            selectedPositions[0] = indexOfFirst { it.selected }
                        } else if (selectedIndex == selectedPositions[1]) {
                            selectedPositions[1] = indexOfLast { it.selected }
                        }
                    } else {
                        if (selectedIndex < selectedPositions[0]) {
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            selectedPositions[1] = selectedIndex
                        }
                    }
                }
            }
            state.copy(items = newItems.toPersistentList())
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        mutableState.update { state ->
            val newItems = state.items.map {
                selectedChapterIds.addOrRemove(it.update.chapterId, selected)
                it.copy(selected = selected)
            }
            state.copy(items = newItems.toPersistentList())
        }

        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun invertSelection() {
        mutableState.update { state ->
            val newItems = state.items.map {
                selectedChapterIds.addOrRemove(it.update.chapterId, !it.selected)
                it.copy(selected = !it.selected)
            }
            state.copy(items = newItems.toPersistentList())
        }
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun resetNewUpdatesCount() {
        libraryPreferences.newUpdatesCount().set(0)
    }

    fun toggleGroupByNovel() {
        val newValue = !state.value.groupByNovel
        mutableState.update { it.copy(groupByNovel = newValue) }
        libraryPreferences.updatesGroupByNovel().set(newValue)
    }

    fun clearUpdatesCacheAll() {
        screenModelScope.launchIO {
            clearUpdatesCache.clearAll()
            snackbarHostState.showSnackbar("Updates cache cleared")
        }
    }

    /**
     * Load more updates from the database.
     * Increases the SQL LIMIT to fetch the next page of results.
     */
    fun loadMore() {
        if (!state.value.hasMorePages || state.value.isLoadingMore) return
        mutableState.update { it.copy(isLoadingMore = true) }
        currentLimit.value += GetUpdates.PAGE_SIZE
    }

    private fun getUpdatesItemPreferenceFlow(): Flow<ItemPreferences> {
        return combine(
            updatesPreferences.filterDownloaded().changes(),
            updatesPreferences.filterUnread().changes(),
            updatesPreferences.filterStarted().changes(),
            updatesPreferences.filterBookmarked().changes(),
            updatesPreferences.filterExcludedScanlators().changes(),
        ) { downloaded, unread, started, bookmarked, excludedScanlators ->
            ItemPreferences(
                filterDownloaded = downloaded,
                filterUnread = unread,
                filterStarted = started,
                filterBookmarked = bookmarked,
                filterExcludedScanlators = excludedScanlators,
            )
        }
    }

    fun showFilterDialog() {
        mutableState.update { it.copy(dialog = Dialog.FilterSheet) }
    }

    @Immutable
    private data class ItemPreferences(
        val filterDownloaded: TriState,
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterExcludedScanlators: Boolean,
    )

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val hasActiveFilters: Boolean = false,
        val items: PersistentList<UpdatesItem> = persistentListOf(),
        val dialog: Dialog? = null,
        val filter: UpdatesFilter = UpdatesFilter.ALL,
        val groupByNovel: Boolean = false,
        val hasMorePages: Boolean = true,
        val isLoadingMore: Boolean = false,
    ) {
        val selected = items.filter { it.selected }
        val selectionMode = selected.isNotEmpty()

        /**
         * Groups updates by novel when groupByNovel is enabled.
         * Each group shows the novel with count of new chapters.
         */
        fun getNovelGroups(): List<UpdatesNovelGroup> {
            return items
                .groupBy { it.update.mangaId }
                .map { (mangaId, chapters) ->
                    val firstChapter = chapters.first()
                    UpdatesNovelGroup(
                        mangaId = mangaId,
                        mangaTitle = firstChapter.update.mangaTitle,
                        coverUrl = firstChapter.update.coverData.url,
                        sourceId = firstChapter.update.sourceId,
                        latestChapterDate = chapters.maxOf { it.update.dateFetch },
                        chapters = chapters,
                        isNovel = firstChapter.isNovel,
                    )
                }
                .sortedByDescending { it.latestChapterDate }
        }

        fun getUiModel(): List<UpdatesUiModel> {
            return items
                .map { UpdatesUiModel.Item(it) }
                .insertSeparators { before, after ->
                    val beforeDate = before?.item?.update?.dateFetch?.toLocalDate()
                    val afterDate = after?.item?.update?.dateFetch?.toLocalDate()
                    when {
                        beforeDate != afterDate && afterDate != null -> UpdatesUiModel.Header(afterDate)
                        // Return null to avoid adding a separator between two items.
                        else -> null
                    }
                }
        }
    }

    fun setFilter(filter: UpdatesFilter) {
        mutableState.update { it.copy(filter = filter) }
        mutableState.update {
            it.copy(items = latestUpdates.toUpdateItems().toPersistentList())
        }
        // Reset pagination when filter changes
        currentLimit.value = GetUpdates.PAGE_SIZE
    }

    fun syncFilterWithHideManga(hideMangaUi: Boolean) {
        if (!hideMangaUi) return
        if (state.value.filter == UpdatesFilter.NOVELS) return
        setFilter(UpdatesFilter.NOVELS)
    }

    sealed interface Dialog {
        data class DeleteConfirmation(val toDelete: List<UpdatesItem>) : Dialog
        data object FilterSheet : Dialog
    }

    sealed interface Event {
        data object InternalError : Event
        data class LibraryUpdateTriggered(val started: Boolean) : Event
    }
}

private fun TriState.toBooleanOrNull(): Boolean? {
    return when (this) {
        TriState.DISABLED -> null
        TriState.ENABLED_IS -> true
        TriState.ENABLED_NOT -> false
    }
}

@Immutable
data class UpdatesItem(
    val update: UpdatesWithRelations,
    val downloadStateProvider: () -> Download.State,
    val downloadProgressProvider: () -> Int,
    val selected: Boolean = false,
    val isNovel: Boolean = false,
)
