package eu.kanade.tachiyomi.ui.browse.migration.manga

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.filterEnabledLanguages
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.nameWithTypeTag
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.common.utils.mutate
import mihon.core.viewmodel.StateViewModel
import mihon.domain.migration.QuickMigrateJob
import mihon.domain.migration.quickMigrateTargets
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrateMangaViewModel(
    private val sourceId: Long,
    private val context: Context = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
) : StateViewModel<MigrateMangaViewModel.State>(State()) {

    companion object {
        val SOURCE_ID_KEY = CreationExtras.Key<Long>()

        val Factory = viewModelFactory {
            initializer {
                MigrateMangaViewModel(
                    sourceId = get(SOURCE_ID_KEY)!!,
                )
            }
        }
    }

    private val _events: Channel<MigrationMangaEvent> = Channel()
    val events: Flow<MigrationMangaEvent> = _events.receiveAsFlow()

    private var quickMigrateJob: Job? = null

    val isSourceNovel: Boolean
        get() = sourceManager.get(sourceId)?.isNovelSource() == true

    fun getAvailableSources(filterNovel: Boolean): List<CatalogueSource> {
        return sourceManager.getAll().filterIsInstance<CatalogueSource>()
            .filterEnabledLanguages()
            .filter { it.id != sourceId && it.isNovelSource() == filterNovel }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nameWithTypeTag() })
    }

    init {
        viewModelScope.launch {
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

        // isRunning() does a blocking WorkManager query; keep it off the main dispatcher.
        viewModelScope.launchIO {
            if (QuickMigrateJob.isRunning(context)) {
                observeQuickMigrateJob(estimatedTotal = 0)
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
        viewModelScope.launchIO {
            try {
                val selectedManga = state.value.titles.filter { it.id in state.value.selection }
                val targetSource = sourceManager.getOrStub(targetSourceId)
                val targetFavoriteUrls = getFavorites.subscribe(targetSourceId).first()
                    .mapTo(mutableSetOf()) { it.url }
                val skipCount = selectedManga.size -
                    quickMigrateTargets(selectedManga, targetFavoriteUrls, targetSource).size
                mutableState.update {
                    it.copy(
                        dialog = Dialog.QuickMigrateConfirm(
                            targetSourceId = targetSourceId,
                            sourceName = sourceManager.getOrStub(sourceId).nameWithTypeTag(),
                            targetSourceName = targetSource.nameWithTypeTag(),
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

    // Backed by a durable WorkManager job (with its own notification) rather than this ViewModel's
    // coroutine, so an accidental app kill mid-run - e.g. after the phone was dropped - no longer
    // loses whatever hadn't reached the DB-flip step yet. The confirm dialog also used to just sit
    // there frozen for however long a large batch took; this drives a real progress dialog instead.
    //
    // QuickMigrateJob.start() enqueues with ExistingWorkPolicy.KEEP, so it's a silent no-op while a
    // previous quick migrate is still running. Without the isRunning guard below, this selection
    // would still attach to and report completion of that unrelated, already-running job instead of
    // ever actually migrating.
    fun executeQuickMigrate(targetSourceId: Long, categoryName: String?, removeSkipped: Boolean) {
        // Guards against a fast double-tap racing two calls in before either has enqueued its
        // WorkManager job: QuickMigrateJob.isRunning() below queries WorkManager state, which
        // isn't guaranteed visible immediately after enqueueUniqueWork() returns, so the second
        // call's own isRunning() check could still see "not running" and attach an observer using
        // its own (wrong) mangaIds/estimatedTotal to the first call's job.
        if (state.value.dialog is Dialog.QuickMigrateProgress) return

        val mangaIds = state.value.titles.filter { it.id in state.value.selection }.map { it.id }
        if (mangaIds.isEmpty()) {
            mutableState.update { it.copy(dialog = null, selection = emptySet()) }
            return
        }

        mutableState.update { it.copy(dialog = Dialog.QuickMigrateProgress(0f)) }
        viewModelScope.launchIO {
            if (QuickMigrateJob.isRunning(context)) {
                mutableState.update { it.copy(dialog = null) }
                _events.send(MigrationMangaEvent.QuickMigrateAlreadyRunning)
                return@launchIO
            }
            val started = try {
                QuickMigrateJob.start(context, sourceId, targetSourceId, mangaIds, categoryName, removeSkipped)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to start quick migrate" }
                mutableState.update { it.copy(dialog = null) }
                _events.send(MigrationMangaEvent.FailedFetchingFavorites)
                return@launchIO
            }
            if (!started) {
                mutableState.update { it.copy(dialog = null) }
                _events.send(MigrationMangaEvent.QuickMigrateAlreadyRunning)
                return@launchIO
            }
            observeQuickMigrateJob(estimatedTotal = mangaIds.size)
        }
    }

    // Also used to reattach from init() when the app was reopened while a quick migrate started
    // in a previous process was still running - the WorkManager job survives process death, but
    // nothing was resubscribing to it, so the screen showed idle state instead of progress.
    private fun observeQuickMigrateJob(estimatedTotal: Int) {
        mutableState.update { it.copy(dialog = Dialog.QuickMigrateProgress(0f)) }
        quickMigrateJob = context.workManager.getWorkInfosForUniqueWorkFlow(QuickMigrateJob.TAG)
            .mapNotNull { it.firstOrNull() }
            .onEach { workInfo ->
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        val current = workInfo.progress.getInt(QuickMigrateJob.KEY_PROGRESS_CURRENT, 0)
                        val total = workInfo.progress.getInt(QuickMigrateJob.KEY_PROGRESS_TOTAL, estimatedTotal)
                        val fraction = if (total > 0) current.toFloat() / total else 0f
                        mutableState.update {
                            it.copy(dialog = Dialog.QuickMigrateProgress(fraction.coerceIn(0f, 1f)))
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val migrated = workInfo.outputData.getInt(QuickMigrateJob.KEY_RESULT_MIGRATED, 0)
                        val removed = workInfo.outputData.getInt(QuickMigrateJob.KEY_RESULT_REMOVED, 0)
                        mutableState.update { it.copy(dialog = null, selection = emptySet()) }
                        quickMigrateJob = null
                        _events.send(MigrationMangaEvent.QuickMigrateComplete(migrated, removed))
                    }
                    WorkInfo.State.FAILED -> {
                        mutableState.update { it.copy(dialog = null) }
                        quickMigrateJob = null
                        _events.send(MigrationMangaEvent.FailedFetchingFavorites)
                    }
                    WorkInfo.State.CANCELLED -> {
                        mutableState.update { it.copy(dialog = null) }
                        quickMigrateJob = null
                    }
                    else -> {}
                }
            }
            .catch {
                logcat(LogPriority.ERROR, it) { "Quick migrate progress tracking failed" }
                mutableState.update { it.copy(dialog = null) }
                quickMigrateJob = null
                _events.send(MigrationMangaEvent.FailedFetchingFavorites)
            }
            .launchIn(viewModelScope)
    }

    fun cancelQuickMigrate() {
        QuickMigrateJob.stop(context)
        quickMigrateJob?.cancel()
        quickMigrateJob = null
        mutableState.update { it.copy(dialog = null) }
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
        data class QuickMigrateProgress(@androidx.annotation.FloatRange(0.0, 1.0) val progress: Float) : Dialog
    }
}

sealed interface MigrationMangaEvent {
    data object FailedFetchingFavorites : MigrationMangaEvent
    data object QuickMigrateAlreadyRunning : MigrationMangaEvent
    data class QuickMigrateComplete(val count: Int, val removedCount: Int = 0) : MigrationMangaEvent
}
