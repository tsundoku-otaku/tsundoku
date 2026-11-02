package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.manga.ChapterSettingsDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.presentation.manga.MangaScreen
import eu.kanade.presentation.manga.SimilarNovelsDialog
import eu.kanade.presentation.manga.components.DeleteChaptersDialog
import eu.kanade.presentation.manga.components.MangaCoverDialog
import eu.kanade.presentation.manga.components.RemoveChaptersFromDbDialog
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import eu.kanade.presentation.manga.components.SetIntervalDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.NovelGlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackInfoDialogHomeScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.source.getMangaUrlOrNull
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.feature.migration.dialog.MigrateMangaDialog
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.isLocalNovel

class MangaScreen(
    private val mangaId: Long,
    val fromSource: Boolean = false,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val viewModel = viewModel<MangaViewModel>(
            factory = MangaViewModel.Factory,
            extras = CreationExtras {
                set(MangaViewModel.MANGA_ID_KEY, mangaId)
                set(MangaViewModel.IS_FROM_SOURCE_KEY, fromSource)
            },
        )

        val state by viewModel.state.collectAsStateWithLifecycle()

        if (state is MangaViewModel.State.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as MangaViewModel.State.Success
        val hasWebViewSupport = remember { successState.source is HttpSource || successState.source is JsSource }

        LaunchedEffect(successState.manga, viewModel.source) {
            if (hasWebViewSupport) {
                try {
                    withIOContext {
                        assistUrl = getMangaUrl(viewModel.manga, viewModel.source)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to get manga URL" }
                }
            }
        }

        MangaScreen(
            state = successState,
            snackbarHostState = viewModel.snackbarHostState,
            nextUpdate = successState.manga.expectedNextUpdate,
            isTabletUi = isTabletUi(),
            chapterSwipeStartAction = viewModel.chapterSwipeStartAction,
            chapterSwipeEndAction = viewModel.chapterSwipeEndAction,
            navigateUp = navigator::pop,
            onChapterClicked = { openChapter(context, it) },
            onDownloadChapter = viewModel::runChapterDownloadActions.takeIf { !successState.source.isLocalOrStub() },
            onAddToLibraryClicked = {
                viewModel.toggleFavorite()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onWebViewClicked = {
                openMangaInWebView(
                    navigator,
                    viewModel.manga,
                    viewModel.source,
                )
            }.takeIf { hasWebViewSupport },
            onWebViewLongClicked = {
                copyMangaUrl(
                    context,
                    viewModel.manga,
                    viewModel.source,
                )
            }.takeIf { hasWebViewSupport },
            onTrackingClicked = {
                if (!successState.hasLoggedInTrackers) {
                    navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                } else {
                    viewModel.showTrackDialog()
                }
            },
            onTagSearch = { scope.launch { performGenreSearch(navigator, it, viewModel.source!!) } },
            onFilterButtonClicked = viewModel::showSettingsDialog,
            onRefresh = viewModel::fetchAllFromSource,
            onForceRefresh = { viewModel.fetchAllFromSource(forceRefresh = true) },
            onContinueReading = { continueReading(context, viewModel.getNextUnreadChapter()) },
            onSearch = { query, global ->
                scope.launch {
                    val source = viewModel.source
                    // If clicking the source name, navigate directly to BrowseSourceScreen
                    if (!global && source != null && query == source.getNameForMangaInfo()) {
                        navigator.push(BrowseSourceScreen(source.id, null))
                    } else {
                        performSearch(navigator, query, global, successState.isNovel)
                    }
                }
            },
            onCoverClicked = viewModel::showCoverDialog,
            onShareClicked = {
                shareManga(context, viewModel.manga, viewModel.source)
            }.takeIf { hasWebViewSupport },
            onDownloadActionClicked = viewModel::runDownloadAction.takeIf { !successState.source.isLocalOrStub() },
            onEditCategoryClicked = viewModel::showChangeCategoryDialog.takeIf { successState.manga.favorite },
            onEditFetchIntervalClicked = viewModel::showSetFetchIntervalDialog.takeIf {
                successState.manga.favorite
            },
            onMigrateClicked = {
                navigator.push(MigrationConfigScreen(successState.manga.id))
            }.takeIf { successState.manga.favorite },
            onSimilarNovelsClicked = viewModel::showSimilarNovelsDialog.takeIf {
                successState.manga.favorite && successState.similarNovels.isNotEmpty()
            },
            onFindDuplicatesClicked = viewModel::showFindDuplicatesDialog.takeIf {
                successState.manga.favorite
            },
            onEditNotesClicked = { navigator.push(MangaNotesScreen(manga = successState.manga)) },
            onEditClicked = viewModel::showEditDialog,
            onClearCustomInfoClicked = if (
                successState.manga.favorite && CustomMangaInfo.from(successState.manga.memo) != null
            ) {
                viewModel::showClearCustomInfoDialog
            } else {
                null
            },
            onTranslateClicked = viewModel::translateMangaDetails,
            onTranslateDownloadedClicked = { viewModel.translateDownloadedChapters() }.takeIf { successState.isNovel },
            onExportEpubClicked = viewModel::showExportEpubDialog.takeIf { successState.isNovel },
            onSearchChaptersClicked = {
                navigator.push(ChapterSearchScreen(successState.manga.id))
            }.takeIf { successState.isNovel || successState.manga.isLocalNovel() },
            onWordCountClicked = viewModel::showWordCountDialog.takeIf { successState.isNovel },
            showSourceName = successState.showSourceName,
            onToggleSourceNameVisibility = viewModel::toggleSourceNameVisibility,
            onMultiBookmarkClicked = viewModel::bookmarkChapters,
            onMultiMarkAsReadClicked = viewModel::markChaptersRead,
            onMarkPreviousAsReadClicked = viewModel::markPreviousChapterRead,
            onMultiDeleteClicked = viewModel::showDeleteChapterDialog,
            onMultiRemoveFromDbClicked = viewModel::showRemoveChaptersFromDbDialog,
            onMultiDeleteTranslationClicked = viewModel::deleteTranslations,
            onTranslateSelectedClicked = { chapters ->
                viewModel.translateSelectedChapters(chapters, forceRetranslate = false)
            },
            onRetranslateSelectedClicked = { chapters ->
                viewModel.translateSelectedChapters(chapters, forceRetranslate = true)
            },
            onChapterSwipe = viewModel::chapterSwipe,
            onChapterSelected = viewModel::toggleSelection,
            onAllChapterSelected = viewModel::toggleAllSelection,
            onInvertSelection = viewModel::invertSelection,
        )

        var showScanlatorsDialog by remember { mutableStateOf(false) }

        val onDismissRequest = { viewModel.dismissDialog() }
        when (val dialog = successState.dialog) {
            null -> {}
            is MangaViewModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { addCategories, removeCategories ->
                        val currentSelected = dialog.initialSelection
                            .filter {
                                it is tachiyomi.core.common.preference.CheckboxState.State.Checked ||
                                    it is tachiyomi.core.common.preference.CheckboxState.TriState.Include
                            }
                            .map { it.value.id }
                            .toMutableSet()
                        currentSelected.addAll(addCategories)
                        currentSelected.removeAll(removeCategories.toSet())
                        viewModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, currentSelected.toList())
                    },
                )
            }
            is MangaViewModel.Dialog.DeleteChapters -> {
                DeleteChaptersDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.toggleAllSelection(false)
                        viewModel.deleteChapters(dialog.chapters)
                    },
                )
            }
            is MangaViewModel.Dialog.RemoveChaptersFromDb -> {
                RemoveChaptersFromDbDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.toggleAllSelection(false)
                        viewModel.removeChaptersFromDb(dialog.chapters)
                    },
                )
            }

            is MangaViewModel.Dialog.DuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.toggleFavorite(onRemoved = {}, skipDuplicateCheck = true) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { viewModel.showMigrateDialog(it) },
                    canAddAnyway = dialog.canAddAnyway,
                )
            }

            is MangaViewModel.Dialog.SimilarNovels -> {
                SimilarNovelsDialog(
                    similarNovels = dialog.similarNovels,
                    categories = dialog.categories,
                    onDismissRequest = onDismissRequest,
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { viewModel.showMigrateDialog(it) },
                )
            }

            is MangaViewModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    onClickTitle = {
                        val other = if (dialog.target.id == successState.manga.id) dialog.current else dialog.target
                        navigator.push(MangaScreen(other.id))
                    },
                    onDismissRequest = onDismissRequest,
                )
            }
            MangaViewModel.Dialog.SettingsSheet -> ChapterSettingsDialog(
                onDismissRequest = onDismissRequest,
                manga = successState.manga,
                onDownloadFilterChanged = viewModel::setDownloadedFilter,
                onUnreadFilterChanged = viewModel::setUnreadFilter,
                onBookmarkedFilterChanged = viewModel::setBookmarkedFilter,
                onSortModeChanged = viewModel::setSorting,
                onDisplayModeChanged = viewModel::setDisplayMode,
                onSetAsDefault = viewModel::setCurrentSettingsAsDefault,
                onResetToDefault = viewModel::resetToDefaultSettings,
                scanlatorFilterActive = successState.scanlatorFilterActive,
                onScanlatorFilterClicked = { showScanlatorsDialog = true },
            )
            MangaViewModel.Dialog.TrackSheet -> {
                NavigatorAdaptiveSheet(
                    screen = TrackInfoDialogHomeScreen(
                        mangaId = successState.manga.id,
                        mangaTitle = successState.manga.title,
                        sourceId = successState.source.id,
                    ),
                    enableSwipeDismiss = { it.lastItem is TrackInfoDialogHomeScreen },
                    onDismissRequest = onDismissRequest,
                )
            }
            MangaViewModel.Dialog.FullCover -> {
                val sm = viewModel<MangaCoverViewModel>(
                    factory = MangaCoverViewModel.Factory,
                    extras = CreationExtras {
                        set(MangaCoverViewModel.MANGA_ID_KEY, successState.manga.id)
                    },
                )
                val manga by sm.state.collectAsState()
                if (manga != null) {
                    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
                        if (it == null) return@rememberLauncherForActivityResult
                        sm.editCover(context, it)
                    }
                    MangaCoverDialog(
                        manga = manga!!,
                        snackbarHostState = sm.snackbarHostState,
                        isCustomCover = remember(manga) { manga!!.hasCustomCover() },
                        onShareClick = { sm.shareCover(context) },
                        onSaveClick = { sm.saveCover(context) },
                        onEditClick = {
                            when (it) {
                                EditCoverAction.EDIT -> getContent.launch("image/*")
                                EditCoverAction.DELETE -> sm.deleteCustomCover(context)
                                EditCoverAction.CLEAR -> sm.clearCover(context)
                            }
                        },
                        onDismissRequest = onDismissRequest,
                    )
                } else {
                    LoadingScreen(Modifier.systemBarsPadding())
                }
            }
            is MangaViewModel.Dialog.SetFetchInterval -> {
                SetIntervalDialog(
                    interval = dialog.manga.fetchInterval,
                    nextUpdate = dialog.manga.expectedNextUpdate,
                    onDismissRequest = onDismissRequest,
                    onValueChanged = { interval: Int -> viewModel.setFetchInterval(dialog.manga, interval) }
                        .takeIf { viewModel.isUpdateIntervalEnabled },
                )
            }
            is MangaViewModel.Dialog.Edit -> {
                eu.kanade.presentation.manga.components.EditMangaDialog(
                    manga = dialog.manga,
                    sourceInfo = CustomMangaInfo.fromSource(dialog.manga.memo),
                    onDismissRequest = onDismissRequest,
                    onSaveTitle = { viewModel.updateTitle(it) },
                    onSaveUrl = { viewModel.updateUrl(it) },
                    onSaveAltTitles = { viewModel.updateAlternativeTitles(it) },
                    onSaveInfo = { description, tags, author, artist, status ->
                        viewModel.updateMangaInfo(description, tags, author, artist, status)
                    },
                    onSwapMainTitle = { newMain, updatedAlts ->
                        viewModel.swapMainTitle(newMain, updatedAlts)
                    },
                )
            }
            MangaViewModel.Dialog.ClearCustomInfo -> {
                AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { Text(stringResource(TDMR.strings.action_clear_custom_metadata)) },
                    text = { Text(stringResource(TDMR.strings.clear_custom_metadata_confirm)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.clearCustomInfo()
                                onDismissRequest()
                            },
                        ) {
                            Text(stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismissRequest) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
            is MangaViewModel.Dialog.TranslateMangaDetails -> {
                eu.kanade.presentation.manga.components.TranslateMangaDetailsDialog(
                    manga = dialog.manga,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { details ->
                        viewModel.applyTranslatedDetails(details)
                    },
                )
            }
            is MangaViewModel.Dialog.ExportEpub -> {
                eu.kanade.presentation.manga.components.ExportEpubDialog(
                    manga = dialog.manga,
                    chapters = dialog.chapters,
                    onDismissRequest = onDismissRequest,
                    onExport = { uri, options ->
                        viewModel.exportAsEpub(dialog.manga, dialog.chapters, uri, options)
                    },
                )
            }
            is MangaViewModel.Dialog.WordCount -> {
                eu.kanade.presentation.manga.components.WordCountDialog(
                    checkedChapters = dialog.checkedChapters,
                    chaptersToCount = dialog.chaptersToCount,
                    result = dialog.result,
                    onDismissRequest = onDismissRequest,
                )
            }
        }

        if (showScanlatorsDialog) {
            ScanlatorFilterDialog(
                availableScanlators = successState.availableScanlators,
                excludedScanlators = successState.excludedScanlators,
                onDismissRequest = { showScanlatorsDialog = false },
                onConfirm = viewModel::setExcludedScanlators,
            )
        }
    }

    private fun continueReading(context: Context, unreadChapter: Chapter?) {
        if (unreadChapter != null) openChapter(context, unreadChapter)
    }

    private fun openChapter(context: Context, chapter: Chapter) {
        context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
    }

    private fun getMangaUrl(manga_: Manga?, source_: Source?): String? {
        val manga = manga_ ?: return null
        val source = source_ ?: return null

        return try {
            source.getMangaUrlOrNull(manga.toSManga())
        } catch (e: Exception) {
            null
        }
    }

    private fun openMangaInWebView(navigator: Navigator, manga_: Manga?, source_: Source?) {
        getMangaUrl(manga_, source_)?.let { url ->
            navigator.push(
                WebViewScreen(
                    url = url,
                    initialTitle = manga_?.title,
                    sourceId = source_?.id,
                ),
            )
        }
    }

    private fun shareManga(context: Context, manga_: Manga?, source_: Source?) {
        try {
            getMangaUrl(manga_, source_)?.let { url ->
                val intent = url.toUri().toShareIntent(context, type = "text/plain")
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    /**
     * Perform a search using the provided query.
     *
     * @param query the search query to the parent controller
     */
    private suspend fun performSearch(
        navigator: Navigator,
        query: String,
        global: Boolean,
        isNovel: Boolean = false,
    ) {
        if (global) {
            if (isNovel) {
                navigator.push(NovelGlobalSearchScreen(query))
            } else {
                navigator.push(GlobalSearchScreen(query))
            }
            return
        }

        if (navigator.size < 2) {
            return
        }

        when (val previousController = navigator.items[navigator.size - 2]) {
            is HomeScreen -> {
                navigator.pop()
                previousController.search(query)
            }
            is BrowseSourceScreen -> {
                navigator.pop()
                previousController.search(query)
            }
        }
    }

    /**
     * Performs a genre search using the provided genre name.
     *
     * @param genreName the search genre to the parent controller
     */
    private suspend fun performGenreSearch(navigator: Navigator, genreName: String, source: Source) {
        if (navigator.size < 2) {
            return
        }

        val previousController = navigator.items[navigator.size - 2]
        if (previousController is BrowseSourceScreen && source is HttpSource) {
            navigator.pop()
            previousController.searchGenre(genreName)
        } else {
            performSearch(navigator, genreName, global = false)
        }
    }

    /**
     * Copy Manga URL to Clipboard
     */
    private fun copyMangaUrl(context: Context, manga_: Manga?, source_: Source?) {
        val url = getMangaUrl(manga_, source_) ?: return
        context.copyToClipboard(url, url)
    }
}
