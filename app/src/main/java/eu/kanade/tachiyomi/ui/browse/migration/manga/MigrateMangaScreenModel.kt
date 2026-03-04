package eu.kanade.tachiyomi.ui.browse.migration.manga

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isNovelSource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.common.utils.mutate
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
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
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
) : StateScreenModel<MigrateMangaScreenModel.State>(State()) {

    private val _events: Channel<MigrationMangaEvent> = Channel()
    val events: Flow<MigrationMangaEvent> = _events.receiveAsFlow()

    val availableSources: List<CatalogueSource>
        get() = sourceManager.getCatalogueSources()
            .filter { it.id != sourceId && it.isNovelSource() }
            .sortedBy { it.name }

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
                        state.copy(titleList = persistentListOf())
                    }
                }
                .map { manga ->
                    manga
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                        .toImmutableList()
                }
                .collectLatest { list ->
                    mutableState.update { it.copy(titleList = list) }
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
                var skipCount = 0
                for (manga in selectedManga) {
                    try {
                        val existing = getMangaByUrlAndSourceId.await(manga.url, targetSourceId)
                        if (existing?.favorite == true) {
                            skipCount++
                        }
                    } catch (_: Exception) {
                        // If we can't check, assume no duplicate
                    }
                }
                mutableState.update {
                    it.copy(
                        dialog = Dialog.QuickMigrateConfirm(
                            targetSourceId = targetSourceId,
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

    fun executeQuickMigrate(targetSourceId: Long) {
        screenModelScope.launchIO {
            try {
                val selectedManga = state.value.titles.filter { it.id in state.value.selection }
                var migrated = 0
                for (manga in selectedManga) {
                    try {
                        val existing = getMangaByUrlAndSourceId.await(manga.url, targetSourceId)
                        if (existing?.favorite == true) continue
                    } catch (_: Exception) {
                        // If check fails, proceed with migration
                    }
                    updateManga.await(MangaUpdate(id = manga.id, source = targetSourceId))
                    migrated++
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
        private val titleList: ImmutableList<Manga>? = null,
    ) {

        val titles: ImmutableList<Manga>
            get() = titleList ?: persistentListOf()

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
