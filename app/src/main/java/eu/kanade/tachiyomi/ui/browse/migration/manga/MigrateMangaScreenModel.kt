package eu.kanade.tachiyomi.ui.browse.migration.manga

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.source.SourceTrackerDispatcher
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isNovelSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.common.utils.mutate
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrateMangaScreenModel(
    private val sourceId: Long,
    private val sourceManager: SourceManager = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val getCategories: tachiyomi.domain.category.interactor.GetCategories = Injekt.get(),
    private val createCategoryWithName: tachiyomi.domain.category.interactor.CreateCategoryWithName = Injekt.get(),
    private val setMangaCategories: tachiyomi.domain.category.interactor.SetMangaCategories = Injekt.get(),
    private val sourceTrackerDispatcher: SourceTrackerDispatcher = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val getManga: tachiyomi.domain.manga.interactor.GetManga = Injekt.get(),
    private val downloadManager: eu.kanade.tachiyomi.data.download.DownloadManager = Injekt.get(),
    private val translatedChapterRepository: tachiyomi.domain.translation.repository.TranslatedChapterRepository =
        Injekt.get(),
    private val quoteManager: eu.kanade.tachiyomi.ui.reader.quote.QuoteManager =
        eu.kanade.tachiyomi.ui.reader.quote.QuoteManager(Injekt.get<android.app.Application>()),
) : StateScreenModel<MigrateMangaScreenModel.State>(State()) {

    private val _events: Channel<MigrationMangaEvent> = Channel()
    val events: Flow<MigrationMangaEvent> = _events.receiveAsFlow()

    val isSourceNovel: Boolean
        get() = sourceManager.get(sourceId)?.isNovelSource() == true

    fun getAvailableSources(filterNovel: Boolean): List<CatalogueSource> {
        return sourceManager.getAll().filterIsInstance<CatalogueSource>()
            .filter { it.id != sourceId && it.isNovelSource() == filterNovel }
            .sortedBy { it.name }
    }

    init {
        screenModelScope.launch {
            mutableState.update { state ->
                state.copy(source = sourceManager.getOrStub(sourceId))
            }

            getFavorites.subscribe(sourceId)
                .catch {
                    logcat(LogPriority.ERROR, it)
                    _events.send(MigrationMangaEvent.FailedFetchingFavorites)
                    mutableState.update { state ->
                        state.copy(titleList = listOf())
                    }
                }
                .map { manga ->
                    manga
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                }
                .collectLatest { list ->
                    mutableState.update { it.copy(titleList = list.toList()) }
                }
        }
    }

    fun toggleSelection(item: Manga) {
        mutableState.update { state ->
            val selection = state.selection.mutate { list ->
                if (!list.remove(item.id)) list.add(item.id)
            }
            state.copy(selection = selection)
        }
    }

    fun selectAll() {
        mutableState.update { state ->
            state.copy(selection = state.titles.map { it.id }.toSet())
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = emptySet()) }
    }

    fun showQuickMigrateDialog() {
        mutableState.update { it.copy(dialog = Dialog.QuickMigrateSourcePicker) }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun checkQuickMigrate(targetSourceId: Long) {
        screenModelScope.launchIO {
            try {
                val selectedManga = state.value.titles.filter { it.id in state.value.selection }
                val targetFavoriteUrls = getFavorites.subscribe(targetSourceId).first()
                    .mapTo(mutableSetOf()) { it.url }
                val skipCount = selectedManga.size - quickMigrateTargets(selectedManga, targetFavoriteUrls).size
                mutableState.update {
                    it.copy(
                        dialog = Dialog.QuickMigrateConfirm(
                            targetSourceId = targetSourceId,
                            sourceName = sourceManager.getOrStub(sourceId).name,
                            targetSourceName = sourceManager.getOrStub(targetSourceId).name,
                            totalCount = selectedManga.size,
                            skipCount = skipCount,
                        ),
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Quick migrate check failed" }
                _events.send(MigrationMangaEvent.FailedFetchingFavorites)
            }
        }
    }

    fun executeQuickMigrate(targetSourceId: Long, categoryName: String?) {
        screenModelScope.launchIO {
            try {
                val selectedManga = state.value.titles.filter { it.id in state.value.selection }
                val targetFavoriteUrls = getFavorites.subscribe(targetSourceId).first()
                    .mapTo(mutableSetOf()) { it.url }
                val migratedIds = mutableListOf<Long>()
                for ((manga, newUrl) in quickMigrateTargets(selectedManga, targetFavoriteUrls)) {
                    try {
                        val oldSource = sourceManager.getOrStub(manga.source)
                        val newSource = sourceManager.getOrStub(targetSourceId)
                        // Relocate source-keyed data (downloads/translations/quotes) BEFORE flipping the
                        // DB source, so a crash in between can't leave files under the old source name
                        // while the DB already points at the new one (orphaned data). Only preserves
                        // data for same-URL migrations (e.g. JS->KT); different-site moves change
                        // chapter URLs and won't line up.
                        // moveMangaToNewSource reports non-crash failures (destination collision,
                        // partial copy) via a false return, not an exception, so branch on the value:
                        // leave the manga on its old source rather than flipping the DB onto downloads
                        // that never moved. It can be retried once the conflict is resolved.
                        val downloadsMoved = runCatching {
                            downloadManager.moveMangaToNewSource(manga, oldSource, newSource)
                        }.onFailure {
                            logcat(LogPriority.ERROR, it) { "Failed to move downloads on quick migrate" }
                        }.getOrDefault(false)
                        if (!downloadsMoved) {
                            logcat(LogPriority.WARN) {
                                "Skipping quick migrate for ${manga.title}: download relocation did not complete"
                            }
                            continue
                        }
                        runCatching {
                            translatedChapterRepository.moveNovel(
                                oldSource.toString(),
                                manga.title,
                                newSource.toString(),
                                manga.title,
                            )
                        }.onFailure { logcat(LogPriority.ERROR, it) { "Failed to move translations on quick migrate" } }
                        runCatching {
                            quoteManager.moveNovel(
                                oldSource.toString(),
                                manga.title,
                                newSource.toString(),
                                manga.title,
                            )
                        }.onFailure { logcat(LogPriority.ERROR, it) { "Failed to move quotes on quick migrate" } }
                        updateManga.await(MangaUpdate(id = manga.id, source = targetSourceId, url = newUrl))
                        migratedIds.add(manga.id)
                    } catch (_: Exception) {
                        // Skip entries that fail to update; the rest still migrate.
                    }
                }
                val migrated = migratedIds.size

                if (migratedIds.isNotEmpty() && trackPreferences.migrationTriggersSourceTracker.get()) {
                    migratedIds.forEach { id ->
                        val freshManga = getManga.await(id) ?: return@forEach
                        sourceTrackerDispatcher.notifyFavorited(freshManga)
                    }
                }

                if (migratedIds.isNotEmpty() && !categoryName.isNullOrBlank()) {
                    var categoryId: Long? = null
                    val existingCategory = getCategories.await().find {
                        it.name.equals(categoryName, ignoreCase = true)
                    }
                    if (existingCategory != null) {
                        categoryId = existingCategory.id
                    } else {
                        val result = createCategoryWithName.await(categoryName)
                        if (result is tachiyomi.domain.category.interactor.CreateCategoryWithName.Result.Success) {
                            categoryId =
                                getCategories.await().find { it.name.equals(categoryName, ignoreCase = true) }?.id
                        }
                    }
                    if (categoryId != null) {
                        setMangaCategories.await(mangaIds = migratedIds, categoryIds = listOf(categoryId))
                    }
                }

                mutableState.update { it.copy(dialog = null, selection = emptySet()) }
                _events.send(MigrationMangaEvent.QuickMigrateComplete(migrated))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Quick migrate execute failed" }
                mutableState.update { it.copy(dialog = null) }
                _events.send(MigrationMangaEvent.FailedFetchingFavorites)
            }
        }
    }

    @Immutable
    data class State(
        val source: Source? = null,
        val selection: Set<Long> = emptySet(),
        val dialog: Dialog? = null,
        private val titleList: List<Manga>? = null,
    ) {

        val titles: List<Manga>
            get() = titleList ?: listOf()

        val isLoading: Boolean
            get() = source == null || titleList == null

        val isEmpty: Boolean
            get() = titles.isEmpty()

        val selectionMode = selection.isNotEmpty()
    }

    sealed interface Dialog {
        data object QuickMigrateSourcePicker : Dialog
        data class QuickMigrateConfirm(
            val targetSourceId: Long,
            val sourceName: String,
            val targetSourceName: String,
            val totalCount: Int,
            val skipCount: Int,
        ) : Dialog
    }
}

sealed interface MigrationMangaEvent {
    data object FailedFetchingFavorites : MigrationMangaEvent
    data class QuickMigrateComplete(val count: Int) : MigrationMangaEvent
}

/** Leading-slash normalization matching how source urls are stored. */
internal fun normalizeQuickMigrateUrl(url: String): String = if (url.startsWith("/")) url else "/$url"

/**
 * Pairs each selectable manga with its normalized target url, dropping the ones already favorited on
 * the target source. [existingFavoriteUrls] is the one-shot set of target-source favorite urls, so
 * duplicate detection is in-memory instead of one query per manga.
 */
internal fun quickMigrateTargets(
    selected: List<Manga>,
    existingFavoriteUrls: Set<String>,
): List<Pair<Manga, String>> =
    selected.mapNotNull { manga ->
        val newUrl = normalizeQuickMigrateUrl(manga.url)
        if (newUrl in existingFavoriteUrls) null else manga to newUrl
    }
