package eu.kanade.tachiyomi.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.util.fastAll
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.library.DeleteLibraryMangaDialog
import eu.kanade.presentation.library.LibrarySettingsDialog
import eu.kanade.presentation.library.MarkReadConfirmationDialog
import eu.kanade.presentation.library.UpdateSelectedDialog
import eu.kanade.presentation.library.components.LibraryContent
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.MassImportDialog
import eu.kanade.presentation.manga.components.LibraryBottomActionMenu
import eu.kanade.presentation.more.onboarding.GETTING_STARTED_URL
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.library.duplicate.DuplicateDetectionScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.feature.migration.config.MigrationConfigScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.isLocal

data object LibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                index = 1u,
                title = stringResource(TDMR.strings.label_manga),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        requestOpenSettingsSheet()
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current

        val viewModel = viewModel<LibraryViewModel>(
            factory = LibraryViewModel.Factory,
            extras = CreationExtras {
                set(LibraryViewModel.TYPE_KEY, LibraryViewModel.LibraryType.Manga)
            },
        )
        val settingsViewModel = viewModel<LibrarySettingsViewModel>(
            factory = LibrarySettingsViewModel.Factory,
            extras = CreationExtras {
                set(LibrarySettingsViewModel.TYPE_KEY, LibraryViewModel.LibraryType.Manga)
            },
        )
        val state by viewModel.state.collectAsState()
        val titleMaxLines by settingsViewModel.libraryPreferences.titleMaxLines.changes().collectAsState(
            settingsViewModel.libraryPreferences.titleMaxLines.get(),
        )
        val showUrlInList by settingsViewModel.libraryPreferences.showUrlInList.changes().collectAsState(
            settingsViewModel.libraryPreferences.showUrlInList.get(),
        )

        val snackbarHostState = remember { SnackbarHostState() }

        val onClickRefresh: (Category?) -> Boolean = { category ->
            val started = LibraryUpdateJob.startNow(context, category)
            scope.launch {
                val msgRes = when {
                    !started -> MR.strings.update_already_running
                    category != null -> MR.strings.updating_category
                    else -> MR.strings.updating_library
                }
                snackbarHostState.showSnackbar(context.stringResource(msgRes))
            }
            started
        }

        Scaffold(
            topBar = { scrollBehavior ->
                val title = state.getToolbarTitle(
                    defaultTitle = stringResource(TDMR.strings.label_manga),
                    defaultCategoryTitle = stringResource(MR.strings.label_default),
                    page = state.coercedActiveCategoryIndex,
                )
                LibraryToolbar(
                    hasActiveFilters = state.hasActiveFilters,
                    selectedCount = state.selection.size,
                    title = title,
                    onClickUnselectAll = viewModel::clearSelection,
                    onClickSelectAll = viewModel::selectAll,
                    onClickInvertSelection = viewModel::invertSelection,
                    onClickFilter = viewModel::showSettingsDialog,
                    onClickRefresh = { viewModel.reloadLibraryFromDB() },
                    onClickGlobalUpdate = { onClickRefresh(null) },
                    onClickOpenRandomManga = {
                        scope.launch {
                            val randomItem = viewModel.getRandomLibraryItemForCurrentCategory()
                            if (randomItem != null) {
                                navigator.push(MangaScreen(randomItem.libraryManga.manga.id))
                            } else {
                                snackbarHostState.showSnackbar(
                                    context.stringResource(MR.strings.information_no_entries_found),
                                )
                            }
                        }
                    },
                    searchQuery = state.toolbarQuery,
                    onSearchQueryChange = viewModel::search,
                    onSearch = viewModel::commitSearch,
                    onSearchClear = viewModel::clearSearch,
                    // For scroll overlay when no tab
                    scrollBehavior = scrollBehavior.takeIf { !state.showCategoryTabs },
                    onClickMassImport = viewModel::openMassImportDialog,
                    onClickFindDuplicates = { navigator.push(DuplicateDetectionScreen()) },
                    onClickCategoryActions = {
                        state.activeCategory?.let(viewModel::openCategoryActionsDialog)
                    },
                )
            },
            bottomBar = {
                LibraryBottomActionMenu(
                    visible = state.selectionMode,
                    onChangeCategoryClicked = viewModel::openChangeCategoryDialog,
                    onMarkAsReadClicked = { viewModel.showMarkReadConfirmation(true) },
                    onMarkAsUnreadClicked = { viewModel.showMarkReadConfirmation(false) },
                    onDownloadClicked = viewModel::performDownloadAction
                        .takeIf { state.selectedManga.fastAll { !it.isLocal() } },
                    onDeleteClicked = viewModel::openDeleteMangaDialog,
                    onUpdateClicked = viewModel::openUpdateSelectedDialog,
                    onMigrateClicked = {
                        val selection = state.selection
                        viewModel.clearSelection()
                        navigator.push(MigrationConfigScreen(selection))
                    },
                    onCopyLinksClicked = {
                        val urls = viewModel.getSelectedMangaUrls()
                        if (urls.isNotEmpty()) {
                            context.copyToClipboard("Manga Links", urls.joinToString("\n"))
                        }
                        viewModel.clearSelection()
                    },
                    onTranslateClicked = viewModel::translateSelectedNovels,
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            when {
                state.isLoading -> {
                    LoadingScreen(Modifier.padding(contentPadding))
                }
                state.searchQuery.isNullOrEmpty() && !state.hasActiveFilters && state.isLibraryEmpty -> {
                    val handler = LocalUriHandler.current
                    EmptyScreen(
                        stringRes = MR.strings.information_empty_library,
                        modifier = Modifier.padding(contentPadding),
                        actions = listOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.getting_started_guide,
                                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                                onClick = { handler.openUri(GETTING_STARTED_URL) },
                            ),
                        ),
                    )
                }
                else -> {
                    LibraryContent(
                        categories = state.displayedCategories,
                        searchQuery = state.searchQuery,
                        selection = state.selection,
                        contentPadding = contentPadding,
                        currentPage = state.coercedActiveCategoryIndex,
                        hasActiveFilters = state.hasActiveFilters,
                        isQueryRunning = state.isQueryRunning,
                        showPageTabs = state.showCategoryTabs || !state.searchQuery.isNullOrEmpty(),
                        onChangeCurrentPage = viewModel::updateActiveCategoryIndex,
                        onClickManga = { navigator.push(MangaScreen(it)) },
                        onContinueReadingClicked = { it: LibraryManga ->
                            scope.launchIO {
                                val chapter = viewModel.getNextUnreadChapter(it.manga)
                                if (chapter != null) {
                                    context.startActivity(
                                        ReaderActivity.newIntent(context, chapter.mangaId, chapter.id),
                                    )
                                } else {
                                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                                }
                            }
                            Unit
                        }.takeIf { state.showMangaContinueButton },
                        onToggleSelection = viewModel::toggleSelection,
                        onToggleRangeSelection = { category, manga ->
                            viewModel.toggleRangeSelection(category, manga)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onRefresh = { onClickRefresh(state.activeCategory) },
                        onGlobalSearchClicked = {
                            navigator.push(GlobalSearchScreen(viewModel.state.value.searchQuery ?: ""))
                        },
                        getItemCountForCategory = { state.getItemCountForCategory(it) },
                        getDisplayMode = { viewModel.getDisplayMode() },
                        getColumnsForOrientation = { viewModel.getColumnsForOrientation(it) },
                        getItemsForCategory = { state.getItemsForCategory(it) },
                        titleMaxLines = titleMaxLines,
                        showUrlInList = showUrlInList,
                        paginationEnabled = viewModel.paginationEnabled,
                        onCategoryFirstVisible = viewModel::onCategoryFirstVisible,
                        onLoadMore = viewModel::loadMoreForCategory,
                        getLoadMoreKey = { state.categoryLoadKey(it) },
                        isCategoryLoading = { state.paginationLoadingCategories.contains(it.id) },
                        isCategoryLoadingMore = { state.paginationLoadingMoreCategories.contains(it.id) },
                        resetToken = state.paginationResetToken,
                    )
                }
            }
        }

        val onDismissRequest = viewModel::closeDialog
        when (val dialog = state.dialog) {
            is LibraryViewModel.Dialog.SettingsSheet -> run {
                LibrarySettingsDialog(
                    onDismissRequest = onDismissRequest,
                    viewModel = settingsViewModel,
                    category = state.activeCategory,
                )
            }
            is LibraryViewModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = {
                        viewModel.clearSelection()
                        navigator.push(CategoryScreen())
                    },
                    onConfirm = { include, exclude ->
                        viewModel.clearSelection()
                        viewModel.setMangaCategories(dialog.manga, include, exclude)
                    },
                )
            }
            is LibraryViewModel.Dialog.UpdateSelected -> {
                UpdateSelectedDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = { fetchChapters, fetchDetails, ignoreSkipRecentlyUpdated ->
                        viewModel.updateSelected(
                            dialog.manga,
                            fetchChapters,
                            fetchDetails,
                            ignoreSkipRecentlyUpdated,
                        )
                    },
                )
            }
            is LibraryViewModel.Dialog.DeleteManga -> {
                DeleteLibraryMangaDialog(
                    containsLocalManga = dialog.manga.any(Manga::isLocal),
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                            deleteManga,
                            deleteChapter,
                            clearChaptersFromDb,
                            deleteTranslations,
                            clearCovers,
                            clearDescriptions,
                            clearTags,
                        ->
                        viewModel.removeMangas(
                            dialog.manga,
                            deleteManga,
                            deleteChapter,
                            clearChaptersFromDb,
                            deleteTranslations,
                            clearCovers,
                            clearDescriptions,
                            clearTags,
                        )
                        viewModel.clearSelection()
                    },
                )
            }
            is LibraryViewModel.Dialog.CategoryAction -> {
                DeleteLibraryMangaDialog(
                    containsLocalManga = false,
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                            deleteManga,
                            deleteChapter,
                            clearChaptersFromDb,
                            deleteTranslations,
                            clearCovers,
                            clearDescriptions,
                            clearTags,
                        ->
                        viewModel.removeCategoryMangas(
                            categoryId = dialog.category.id,
                            deleteFromLibrary = deleteManga,
                            deleteChapters = deleteChapter,
                            clearChaptersFromDb = clearChaptersFromDb,
                            deleteTranslations = deleteTranslations,
                            clearCovers = clearCovers,
                            clearDescriptions = clearDescriptions,
                            clearTags = clearTags,
                        )
                    },
                )
            }
            is LibraryViewModel.Dialog.MarkReadConfirmation -> {
                MarkReadConfirmationDialog(
                    read = dialog.read,
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.markReadSelection(dialog.read)
                    },
                )
            }
            is LibraryViewModel.Dialog.MassImport -> {
                MassImportDialog(
                    onDismissRequest = onDismissRequest,
                    isNovelMode = false, // Manga mode for LibraryTab
                )
            }
            is LibraryViewModel.Dialog.ImportEpub -> {
                // Import EPUB is only used in NovelsTab, not here
            }
            is LibraryViewModel.Dialog.ExportEpub -> {
                // Export EPUB is only used in NovelsTab, not here
            }
            is LibraryViewModel.Dialog.DuplicateDetection -> {
                // Navigate to DuplicateDetectionScreen instead of showing dialog
                onDismissRequest()
            }
            null -> {}
        }

        BackHandler(enabled = state.selectionMode || state.toolbarQuery != null) {
            when {
                state.selectionMode -> viewModel.clearSelection()
                state.toolbarQuery != null -> viewModel.clearSearch()
            }
        }

        LaunchedEffect(state.selectionMode, state.dialog) {
            HomeScreen.showBottomNav(!state.selectionMode)
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            launch { queryEvent.receiveAsFlow().collect(viewModel::search) }
            launch { requestSettingsSheetEvent.receiveAsFlow().collectLatest { viewModel.showSettingsDialog() } }
        }
    }

    // For invoking search from other screen
    private val queryEvent = Channel<String>()
    suspend fun search(query: String) = queryEvent.send(query)

    // For opening settings sheet in LibraryController
    private val requestSettingsSheetEvent = Channel<Unit>()
    private suspend fun requestOpenSettingsSheet() = requestSettingsSheetEvent.send(Unit)
}
