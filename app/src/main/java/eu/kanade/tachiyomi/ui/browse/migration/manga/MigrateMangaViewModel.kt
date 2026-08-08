package eu.kanade.tachiyomi.ui.browse.migration.manga

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.source.SourceTrackerDispatcher
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.filterEnabledLanguages
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.nameWithTypeTag
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
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrateMangaViewModel(
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

    fun executeQuickMigrate(targetSourceId: Long, categoryName: String?, removeSkipped: Boolean) {
        viewModelScope.launchIO {
            try {
                val selectedManga = state.value.titles.filter { it.id in state.value.selection }
                val newSource = sourceManager.getOrStub(targetSourceId)
                val targetFavorites = getFavorites.subscribe(targetSourceId).first()
                val targetFavoriteUrls = targetFavorites.mapTo(mutableSetOf()) { it.url }
                val targets = quickMigrateTargets(selectedManga, targetFavoriteUrls, newSource)
                val skipped = if (removeSkipped) {
                    quickMigrateSkipped(selectedManga, targetFavoriteUrls, newSource)
                } else {
                    emptyList()
                }
                val migratedIds = mutableListOf<Long>()
                if (targets.isEmpty() && skipped.isEmpty()) {
                    mutableState.update { it.copy(dialog = null, selection = emptySet()) }
                    _events.send(MigrationMangaEvent.QuickMigrateComplete(0, 0))
                    return@launchIO
                }

                val oldSource = sourceManager.getOrStub(sourceId)
                val targetTitles = targets.mapTo(mutableSetOf()) { it.first.title }
                // The entry that stays behind for a skipped one, matched the same way the skip was:
                // on the normalized url, not the title, which the two can disagree on.
                val keptByUrl = targetFavorites.associateBy { it.url }
                val keptForSkipped = skipped.associate {
                    it.id to keptByUrl[normalizeQuickMigrateUrl(it.url, newSource)]
                }

                val countsUsable = downloadManager.awaitDownloadCacheReady()
                val downloadCounts = if (countsUsable) {
                    downloadManager.getDownloadCounts(
                        (targets.map { it.first } + skipped + keptForSkipped.values.filterNotNull())
                            .distinctBy { it.id },
                    )
                } else {
                    emptyMap()
                }

                val queuedMangaIds = downloadManager.queueState.value.mapTo(mutableSetOf()) { it.mangaId }

                val withTranslations = runCatching {
                    translatedChapterRepository.filterNovelsWithTranslations(oldSource.toString(), targetTitles)
                }.onFailure {
                    logcat(LogPriority.ERROR, it) { "Failed to list translations on quick migrate" }
                }.getOrNull()
                val withQuotes = runCatching {
                    quoteManager.filterNovelsWithQuotes(oldSource.toString(), targetTitles)
                }.onFailure {
                    logcat(LogPriority.ERROR, it) { "Failed to list quotes on quick migrate" }
                }.getOrNull()
                val pendingUpdates = mutableListOf<MangaUpdate>()
                val pendingIds = mutableListOf<Long>()
                suspend fun flushUpdates(force: Boolean) {
                    if (pendingUpdates.isEmpty() || (!force && pendingUpdates.size < UPDATE_CHUNK_SIZE)) return
                    val chunk = pendingUpdates.toList()
                    val chunkIds = pendingIds.toList()
                    pendingUpdates.clear()
                    pendingIds.clear()

                    if (updateManga.awaitAll(chunk)) {
                        migratedIds.addAll(chunkIds)
                    } else {
                        logcat(LogPriority.ERROR) {
                            "Quick migrate: failed to write a chunk of ${chunk.size} entries"
                        }
                    }
                }
                var attemptedDownloadMove = false
                val removal = relocateAndRemoveSkipped(
                    skipped = skipped,
                    keptForSkipped = keptForSkipped,
                    oldSource = oldSource,
                    newSource = newSource,
                    downloadCounts = downloadCounts,
                    countsUsable = countsUsable,
                    queuedMangaIds = queuedMangaIds,
                )
                if (removal.touchedDownloads) attemptedDownloadMove = true
                for ((manga, newUrl) in targets) {
                    try {
                        // Relocate source-keyed data (downloads/translations/quotes) BEFORE flipping the
                        // DB source, so a crash in between can't leave files under the old source name
                        // while the DB already points at the new one. Only preserves data for same-URL
                        // migrations (e.g. JS->KT); different-site moves change chapter URLs.
                        // moveMangaToNewSource reports non-crash failures (destination collision,
                        // partial copy) with false rather than an exception, so branch on the value:
                        // leave the manga on its old source rather than flipping the DB onto downloads
                        // that never moved. It can be retried once the conflict is resolved.
                        val hasDownloads = !countsUsable ||
                            (downloadCounts[manga.id] ?: 0) > 0 ||
                            manga.id in queuedMangaIds
                        if (hasDownloads) attemptedDownloadMove = true
                        val downloadsMoved = !hasDownloads || runCatching {
                            downloadManager.moveMangaToNewSource(manga, oldSource, newSource, invalidateCache = false)
                        }.onFailure {
                            logcat(LogPriority.ERROR, it) { "Failed to move downloads on quick migrate" }
                        }.getOrDefault(false)
                        if (!downloadsMoved) {
                            logcat(LogPriority.WARN) {
                                "Skipping quick migrate for ${manga.title}: download relocation did not complete"
                            }
                            continue
                        }
                        if (withTranslations == null || manga.title in withTranslations) {
                            runCatching {
                                translatedChapterRepository.moveNovel(
                                    oldSource.toString(),
                                    manga.title,
                                    newSource.toString(),
                                    manga.title,
                                )
                            }.onFailure {
                                logcat(LogPriority.ERROR, it) { "Failed to move translations on quick migrate" }
                            }
                        }
                        if (withQuotes == null || manga.title in withQuotes) {
                            runCatching {
                                quoteManager.moveNovel(
                                    oldSource.toString(),
                                    manga.title,
                                    newSource.toString(),
                                    manga.title,
                                )
                            }.onFailure {
                                logcat(LogPriority.ERROR, it) { "Failed to move quotes on quick migrate" }
                            }
                        }
                        pendingUpdates.add(MangaUpdate(id = manga.id, source = targetSourceId, url = newUrl))
                        pendingIds.add(manga.id)
                        flushUpdates(force = false)
                    } catch (_: Exception) {
                        // Skip entries that fail to update; the rest still migrate.
                    }
                }
                flushUpdates(force = true)
                // One rebuild for the whole run instead of one per relocated entry, each of which
                // deletes the on-disk index and restarts the full SAF scan.
                if (attemptedDownloadMove) downloadManager.invalidateDownloadCache()
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
                _events.send(MigrationMangaEvent.QuickMigrateComplete(migrated, removal.removed))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Quick migrate execute failed" }
                mutableState.update { it.copy(dialog = null) }
                _events.send(MigrationMangaEvent.FailedFetchingFavorites)
            }
        }
    }

    /**
     * Hands a skipped entry's data to the entry that stays and then takes the skipped one out of the
     * library, in the same chunks the migration writes with.
     *
     * A skipped entry is one the target source already has, so its downloads, translations and
     * quotes are relocated exactly like a migrated entry's, under the kept entry's title, which is
     * where that entry looks for them. The old copy is deleted only when the kept one already holds
     * downloads of its own: every other relocation failure leaves the only copy on disk.
     */
    private suspend fun relocateAndRemoveSkipped(
        skipped: List<Manga>,
        keptForSkipped: Map<Long, Manga?>,
        oldSource: Source,
        newSource: Source,
        downloadCounts: Map<Long, Int>,
        countsUsable: Boolean,
        queuedMangaIds: Set<Long>,
    ): SkippedRemoval {
        if (skipped.isEmpty()) return SkippedRemoval(removed = 0, touchedDownloads = false)
        val titles = skipped.mapTo(mutableSetOf()) { it.title }
        val withTranslations = runCatching {
            translatedChapterRepository.filterNovelsWithTranslations(oldSource.toString(), titles)
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to list translations for skipped entries" }
        }.getOrNull()
        val withQuotes = runCatching {
            quoteManager.filterNovelsWithQuotes(oldSource.toString(), titles)
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Failed to list quotes for skipped entries" }
        }.getOrNull()

        var removed = 0
        var touchedDownloads = false
        skipped.chunked(UPDATE_CHUNK_SIZE).forEach { chunk ->
            chunk.forEach { manga ->
                val kept = keptForSkipped[manga.id]
                val keptTitle = kept?.title ?: manga.title
                val hasDownloads = !countsUsable ||
                    (downloadCounts[manga.id] ?: 0) > 0 ||
                    manga.id in queuedMangaIds
                if (hasDownloads) {
                    touchedDownloads = true
                    val keptHasDownloads = if (countsUsable && kept != null) {
                        (downloadCounts[kept.id] ?: 0) > 0
                    } else {
                        downloadManager.hasDownloadedChapters(keptTitle, newSource)
                    }
                    if (keptHasDownloads) {
                        downloadManager.deleteManga(manga, oldSource)
                    } else {
                        runCatching {
                            if (keptTitle != manga.title) {
                                downloadManager.renameManga(manga, keptTitle)
                            }
                            downloadManager.moveMangaToNewSource(
                                manga.copy(title = keptTitle),
                                oldSource,
                                newSource,
                                invalidateCache = false,
                            )
                        }.onFailure {
                            logcat(LogPriority.ERROR, it) { "Failed to move downloads for a skipped entry" }
                        }
                    }
                }
                if (withTranslations == null || manga.title in withTranslations) {
                    runCatching {
                        translatedChapterRepository.moveNovel(
                            oldSource.toString(),
                            manga.title,
                            newSource.toString(),
                            keptTitle,
                        )
                    }.onFailure {
                        logcat(LogPriority.ERROR, it) { "Failed to move translations for a skipped entry" }
                    }
                }
                if (withQuotes == null || manga.title in withQuotes) {
                    runCatching {
                        quoteManager.moveNovel(
                            oldSource.toString(),
                            manga.title,
                            newSource.toString(),
                            keptTitle,
                        )
                    }.onFailure {
                        logcat(LogPriority.ERROR, it) { "Failed to move quotes for a skipped entry" }
                    }
                }
            }
            if (updateManga.awaitAll(chunk.map { MangaUpdate(id = it.id, favorite = false) })) {
                removed += chunk.size
            } else {
                logcat(LogPriority.ERROR) { "Failed to remove a chunk of ${chunk.size} skipped entries" }
            }
        }
        return SkippedRemoval(removed = removed, touchedDownloads = touchedDownloads)
    }

    private data class SkippedRemoval(val removed: Int, val touchedDownloads: Boolean)

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
    data class QuickMigrateComplete(val count: Int, val removedCount: Int = 0) : MigrationMangaEvent
}

// Small enough that a crash leaves at most this many entries with their files already relocated
// but their row not yet flipped, large enough to keep the transaction count down on a bulk migrate.
private const val UPDATE_CHUNK_SIZE = 200

/** Leading-slash normalization matching how source urls are stored, for [targetSource]'s convention. */
internal fun normalizeQuickMigrateUrl(url: String, targetSource: Source): String =
    eu.kanade.tachiyomi.util.source.normalizeSourcePath(targetSource, url)

/**
 * Pairs each selectable manga with its normalized target url, dropping the ones already favorited on
 * the target source. [existingFavoriteUrls] is the one-shot set of target-source favorite urls, so
 * duplicate detection is in-memory instead of one query per manga.
 */
internal fun quickMigrateTargets(
    selected: List<Manga>,
    existingFavoriteUrls: Set<String>,
    targetSource: Source,
): List<Pair<Manga, String>> =
    selected.mapNotNull { manga ->
        val newUrl = normalizeQuickMigrateUrl(manga.url, targetSource)
        if (newUrl in existingFavoriteUrls) null else manga to newUrl
    }

/** The other half of [quickMigrateTargets]: the entries the target source already has. */
internal fun quickMigrateSkipped(
    selected: List<Manga>,
    existingFavoriteUrls: Set<String>,
    targetSource: Source,
): List<Manga> = selected.filter { normalizeQuickMigrateUrl(it.url, targetSource) in existingFavoriteUrls }
