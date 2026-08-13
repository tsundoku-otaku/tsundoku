package eu.kanade.tachiyomi.ui.library.duplicate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.library.DeleteLibraryMangaDialog
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.source.getMangaUrlOrNull
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.manga.interactor.BlankTitleFilter
import tachiyomi.domain.manga.interactor.DuplicateMatchMode
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.i18n.stringResource as ctxStringResource
import tachiyomi.domain.category.model.Category as CategoryModel

class DuplicateDetectionScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val clipboardManager = LocalClipboardManager.current
        val snackbarHostState = remember { SnackbarHostState() }

        val screenModel = viewModel<DuplicateDetectionViewModel>()
        val state by screenModel.state.collectAsState()

        // Preserve scroll position across navigation
        val listState = rememberLazyListState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(MR.strings.duplicate_find_duplicates))
                            if (state.selection.isNotEmpty()) {
                                Text(
                                    stringResource(MR.strings.duplicate_n_selected, state.selection.size),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(MR.strings.action_webview_back),
                            )
                        }
                    },
                    actions = {
                        if (state.selection.isNotEmpty()) {
                            // Copy links
                            IconButton(onClick = {
                                scope.launch {
                                    val urls = screenModel.getSelectedUrls()
                                    clipboardManager.setText(AnnotatedString(urls.joinToString("\n")))
                                    snackbarHostState.showSnackbar(
                                        context.ctxStringResource(MR.strings.duplicate_urls_copied, urls.size),
                                    )
                                }
                            }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(MR.strings.duplicate_copy_links),
                                )
                            }
                            // Delete selected
                            IconButton(onClick = {
                                screenModel.openDeleteDialog()
                            }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(MR.strings.duplicate_delete_selected),
                                )
                            }
                            // Move to category
                            IconButton(onClick = {
                                screenModel.openMoveToCategoryDialog()
                            }) {
                                Icon(
                                    Icons.Filled.DriveFileMove,
                                    contentDescription = stringResource(MR.strings.duplicate_move_to_category),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                if (state.filteredDuplicateGroups.isNotEmpty()) {
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        FloatingActionButton(
                            onClick = { showMenu = true },
                        ) {
                            Icon(
                                Icons.Filled.SelectAll,
                                contentDescription = stringResource(MR.strings.duplicate_selection_options),
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_select_all)) },
                                onClick = {
                                    screenModel.selectAllDuplicates()
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_select_all_except_first)) },
                                onClick = {
                                    screenModel.selectAllExceptFirst()
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_select_lowest_ch)) },
                                onClick = {
                                    screenModel.selectLowestChapterCount()
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_select_highest_ch)) },
                                onClick = {
                                    screenModel.selectHighestChapterCount()
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_select_highest_dl)) },
                                onClick = {
                                    screenModel.selectHighestDownloadCount()
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_select_lowest_dl)) },
                                onClick = {
                                    screenModel.selectLowestDownloadCount()
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_select_highest_read)) },
                                onClick = {
                                    screenModel.selectHighestReadCount()
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_select_lowest_read)) },
                                onClick = {
                                    screenModel.selectLowestReadCount()
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_select_lowest_priority)) },
                                onClick = {
                                    screenModel.selectLowestSourcePriority()
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_select_highest_priority)) },
                                onClick = {
                                    screenModel.selectHighestSourcePriority()
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_select_pinned)) },
                                leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                                onClick = {
                                    screenModel.selectPinnedInGroups()
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_select_non_pinned)) },
                                leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                                onClick = {
                                    screenModel.selectNonPinnedInGroups()
                                    showMenu = false
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_clear_selection)) },
                                onClick = {
                                    screenModel.clearSelection()
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.duplicate_invert_selection)) },
                                onClick = {
                                    screenModel.invertSelection()
                                    showMenu = false
                                },
                            )
                        }
                    }
                }
            },
        ) { contentPadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                item(key = "match_mode_row") {
                    // Match mode selector
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.matchMode == DuplicateMatchMode.EXACT,
                            enabled = !state.listingMode,
                            onClick = { screenModel.setMatchMode(DuplicateMatchMode.EXACT) },
                            label = { Text(stringResource(MR.strings.duplicate_match_exact)) },
                            leadingIcon = if (state.matchMode == DuplicateMatchMode.EXACT) {
                                { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                        FilterChip(
                            selected = state.matchMode == DuplicateMatchMode.CONTAINS,
                            enabled = !state.listingMode,
                            onClick = { screenModel.setMatchMode(DuplicateMatchMode.CONTAINS) },
                            label = { Text(stringResource(MR.strings.duplicate_match_contains)) },
                            leadingIcon = if (state.matchMode == DuplicateMatchMode.CONTAINS) {
                                { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                        FilterChip(
                            selected = state.matchMode == DuplicateMatchMode.URL,
                            enabled = !state.listingMode,
                            onClick = { screenModel.setMatchMode(DuplicateMatchMode.URL) },
                            label = { Text(stringResource(MR.strings.duplicate_match_url)) },
                            leadingIcon = if (state.matchMode == DuplicateMatchMode.URL) {
                                { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                        FilterChip(
                            selected = state.listingMode,
                            onClick = { screenModel.setListingMode(!state.listingMode) },
                            label = { Text(stringResource(MR.strings.duplicate_listing_mode)) },
                            leadingIcon = if (state.listingMode) {
                                { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }

                if (state.listingMode && state.listingTruncated) {
                    item(key = "listing_truncated") {
                        Text(
                            text = stringResource(MR.strings.duplicate_listing_truncated, state.listingTotalMatches),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                if (state.hasStartedAnalysis && state.duplicateGroups.isNotEmpty()) {
                    item(key = "search_field") {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { screenModel.setSearchQuery(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            placeholder = { Text(stringResource(MR.strings.action_search)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (state.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { screenModel.setSearchQuery("") }) {
                                        Icon(Icons.Filled.Close, contentDescription = null)
                                    }
                                }
                            },
                            singleLine = true,
                        )
                    }
                }

                item(key = "filters_header") {
                    // Collapsible filter section
                    var filtersExpanded by rememberSaveable { mutableStateOf(true) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { filtersExpanded = !filtersExpanded }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(MR.strings.duplicate_filters_sort),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (filtersExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (filtersExpanded) {
                                stringResource(
                                    MR.strings.action_collapse,
                                )
                            } else {
                                stringResource(MR.strings.action_expand)
                            },
                        )
                    }

                    @OptIn(ExperimentalLayoutApi::class)
                    AnimatedVisibility(visible = filtersExpanded) {
                        Column {
                            // Content type selector (Manga/Novel/Both)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(MR.strings.duplicate_type_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                                FilterChip(
                                    selected = state.contentType == DuplicateDetectionViewModel.ContentType.ALL,
                                    onClick = {
                                        screenModel.setContentType(DuplicateDetectionViewModel.ContentType.ALL)
                                    },
                                    label = { Text(stringResource(MR.strings.duplicate_type_all)) },
                                    leadingIcon = if (state.contentType ==
                                        DuplicateDetectionViewModel.ContentType.ALL
                                    ) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                                FilterChip(
                                    selected = state.contentType == DuplicateDetectionViewModel.ContentType.MANGA,
                                    onClick = {
                                        screenModel.setContentType(DuplicateDetectionViewModel.ContentType.MANGA)
                                    },
                                    label = { Text(stringResource(MR.strings.duplicate_type_manga)) },
                                    leadingIcon = if (state.contentType ==
                                        DuplicateDetectionViewModel.ContentType.MANGA
                                    ) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                                FilterChip(
                                    selected = state.contentType == DuplicateDetectionViewModel.ContentType.NOVEL,
                                    onClick = {
                                        screenModel.setContentType(DuplicateDetectionViewModel.ContentType.NOVEL)
                                    },
                                    label = { Text(stringResource(MR.strings.duplicate_type_novel)) },
                                    leadingIcon = if (state.contentType ==
                                        DuplicateDetectionViewModel.ContentType.NOVEL
                                    ) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                            }

                            // Compact toggle pills: full URLs, flexible group matching, library filters
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = state.showFullUrls,
                                    onClick = { screenModel.toggleShowFullUrls() },
                                    label = { Text(stringResource(TDMR.strings.duplicate_full_urls_short)) },
                                    leadingIcon = if (state.showFullUrls) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                                FilterChip(
                                    selected = state.filterByGroupCategory,
                                    onClick = { screenModel.setFilterByGroupCategory(!state.filterByGroupCategory) },
                                    label = { Text(stringResource(TDMR.strings.duplicate_flexible_group_matching)) },
                                    leadingIcon = if (state.filterByGroupCategory) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                                FilterChip(
                                    selected = state.applyLibraryFilters,
                                    onClick = { screenModel.setApplyLibraryFilters(!state.applyLibraryFilters) },
                                    label = { Text(stringResource(TDMR.strings.duplicate_apply_library_filters)) },
                                    leadingIcon = if (state.applyLibraryFilters) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                            }

                            val blankFiltersEnabled = !state.listingMode &&
                                state.matchMode != DuplicateMatchMode.CONTAINS
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(MR.strings.duplicate_blank_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                                FilterChip(
                                    selected = state.blankTitleFilter == BlankTitleFilter.EXCLUDE,
                                    enabled = blankFiltersEnabled,
                                    onClick = { screenModel.setBlankTitleFilter(BlankTitleFilter.EXCLUDE) },
                                    label = { Text(stringResource(MR.strings.duplicate_blank_exclude)) },
                                    leadingIcon = if (state.blankTitleFilter == BlankTitleFilter.EXCLUDE) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                                FilterChip(
                                    selected = state.blankTitleFilter == BlankTitleFilter.INCLUDE,
                                    enabled = blankFiltersEnabled,
                                    onClick = { screenModel.setBlankTitleFilter(BlankTitleFilter.INCLUDE) },
                                    label = { Text(stringResource(MR.strings.duplicate_blank_include)) },
                                    leadingIcon = if (state.blankTitleFilter == BlankTitleFilter.INCLUDE) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                                FilterChip(
                                    selected = state.blankTitleFilter == BlankTitleFilter.ONLY,
                                    enabled = blankFiltersEnabled,
                                    onClick = { screenModel.setBlankTitleFilter(BlankTitleFilter.ONLY) },
                                    label = { Text(stringResource(MR.strings.duplicate_blank_only)) },
                                    leadingIcon = if (state.blankTitleFilter == BlankTitleFilter.ONLY) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                            }

                            // Sort mode selector
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(MR.strings.duplicate_sort_label),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                FilterChip(
                                    selected = state.sortMode == DuplicateDetectionViewModel.SortMode.NAME,
                                    onClick = { screenModel.setSortMode(DuplicateDetectionViewModel.SortMode.NAME) },
                                    label = { Text(stringResource(MR.strings.duplicate_sort_name)) },
                                    leadingIcon = if (state.sortMode == DuplicateDetectionViewModel.SortMode.NAME) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                                FilterChip(
                                    selected = state.sortMode == DuplicateDetectionViewModel.SortMode.LATEST_ADDED,
                                    onClick = {
                                        screenModel.setSortMode(DuplicateDetectionViewModel.SortMode.LATEST_ADDED)
                                    },
                                    label = { Text(stringResource(MR.strings.duplicate_sort_latest)) },
                                    leadingIcon = if (state.sortMode ==
                                        DuplicateDetectionViewModel.SortMode.LATEST_ADDED
                                    ) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                                FilterChip(
                                    selected = state.sortMode ==
                                        DuplicateDetectionViewModel.SortMode.CHAPTER_COUNT_DESC,
                                    onClick = {
                                        screenModel.setSortMode(
                                            DuplicateDetectionViewModel.SortMode.CHAPTER_COUNT_DESC,
                                        )
                                    },
                                    label = { Text(stringResource(MR.strings.duplicate_sort_ch_desc)) },
                                    leadingIcon = if (state.sortMode ==
                                        DuplicateDetectionViewModel.SortMode.CHAPTER_COUNT_DESC
                                    ) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                                FilterChip(
                                    selected = state.sortMode ==
                                        DuplicateDetectionViewModel.SortMode.DOWNLOAD_COUNT_DESC,
                                    onClick = {
                                        screenModel.setSortMode(
                                            DuplicateDetectionViewModel.SortMode.DOWNLOAD_COUNT_DESC,
                                        )
                                    },
                                    label = { Text(stringResource(MR.strings.duplicate_sort_dl_desc)) },
                                    leadingIcon = if (state.sortMode ==
                                        DuplicateDetectionViewModel.SortMode.DOWNLOAD_COUNT_DESC
                                    ) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                                FilterChip(
                                    selected = state.sortMode == DuplicateDetectionViewModel.SortMode.READ_COUNT_DESC,
                                    onClick = {
                                        screenModel.setSortMode(DuplicateDetectionViewModel.SortMode.READ_COUNT_DESC)
                                    },
                                    label = { Text(stringResource(MR.strings.duplicate_sort_read_desc)) },
                                    leadingIcon = if (state.sortMode ==
                                        DuplicateDetectionViewModel.SortMode.READ_COUNT_DESC
                                    ) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                                FilterChip(
                                    selected = state.sortMode == DuplicateDetectionViewModel.SortMode.PINNED_SOURCE,
                                    onClick = {
                                        screenModel.setSortMode(DuplicateDetectionViewModel.SortMode.PINNED_SOURCE)
                                    },
                                    label = {
                                        Icon(
                                            imageVector = Icons.Filled.PushPin,
                                            contentDescription = stringResource(MR.strings.duplicate_select_pinned),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                    leadingIcon = if (state.sortMode ==
                                        DuplicateDetectionViewModel.SortMode.PINNED_SOURCE
                                    ) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                                FilterChip(
                                    selected = state.sortMode == DuplicateDetectionViewModel.SortMode.SOURCE_PRIORITY,
                                    onClick = {
                                        screenModel.setSortMode(DuplicateDetectionViewModel.SortMode.SOURCE_PRIORITY)
                                    },
                                    label = { Text(stringResource(MR.strings.duplicate_sort_priority)) },
                                    leadingIcon = if (state.sortMode ==
                                        DuplicateDetectionViewModel.SortMode.SOURCE_PRIORITY
                                    ) {
                                        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                                    } else {
                                        null
                                    },
                                )
                            }

                            // Tristate Category filters
                            if (state.categories.isNotEmpty()) {
                                val relevantCategories = state.categories.filter { category ->
                                    when (state.contentType) {
                                        DuplicateDetectionViewModel.ContentType.ALL -> true
                                        DuplicateDetectionViewModel.ContentType.NOVEL ->
                                            category.contentType == CategoryModel.CONTENT_TYPE_ALL ||
                                                category.contentType == CategoryModel.CONTENT_TYPE_NOVEL
                                        DuplicateDetectionViewModel.ContentType.MANGA ->
                                            category.contentType == CategoryModel.CONTENT_TYPE_ALL ||
                                                category.contentType == CategoryModel.CONTENT_TYPE_MANGA
                                    }
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            stringResource(MR.strings.duplicate_category_label),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        FilterChip(
                                            selected = state.categoryIncludeMode ==
                                                DuplicateDetectionViewModel.CategoryIncludeMode.ANY,
                                            onClick = {
                                                screenModel.setCategoryIncludeMode(
                                                    DuplicateDetectionViewModel.CategoryIncludeMode.ANY,
                                                )
                                            },
                                            label = {
                                                Text(stringResource(TDMR.strings.duplicate_category_include_or))
                                            },
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        FilterChip(
                                            selected = state.categoryIncludeMode ==
                                                DuplicateDetectionViewModel.CategoryIncludeMode.ALL,
                                            onClick = {
                                                screenModel.setCategoryIncludeMode(
                                                    DuplicateDetectionViewModel.CategoryIncludeMode.ALL,
                                                )
                                            },
                                            label = {
                                                Text(stringResource(TDMR.strings.duplicate_category_include_and))
                                            },
                                        )
                                        if (state.selectedCategoryFilters.isNotEmpty() ||
                                            state.excludedCategoryFilters.isNotEmpty()
                                        ) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            FilterChip(
                                                selected = true,
                                                onClick = { screenModel.clearCategoryFilters() },
                                                label = { Text(stringResource(MR.strings.duplicate_category_clear)) },
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        relevantCategories.forEach { category ->
                                            val isIncluded = category.id in state.selectedCategoryFilters
                                            val isExcluded = category.id in state.excludedCategoryFilters
                                            val displayName = category.name.ifBlank {
                                                stringResource(MR.strings.label_default)
                                            }
                                            FilterChip(
                                                selected = isIncluded || isExcluded,
                                                onClick = { screenModel.toggleCategoryFilter(category.id) },
                                                label = { Text(displayName) },
                                                leadingIcon = if (isIncluded) {
                                                    {
                                                        Icon(
                                                            Icons.Filled.Check,
                                                            contentDescription = null,
                                                            Modifier.size(18.dp),
                                                        )
                                                    }
                                                } else if (isExcluded) {
                                                    {
                                                        Icon(
                                                            Icons.Filled.Close,
                                                            contentDescription = null,
                                                            Modifier.size(18.dp),
                                                        )
                                                    }
                                                } else {
                                                    null
                                                },
                                                colors = if (isExcluded) {
                                                    FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor =
                                                        MaterialTheme.colorScheme.errorContainer,
                                                        selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                                                    )
                                                } else {
                                                    FilterChipDefaults.filterChipColors()
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navigator.push(SourcePriorityScreen) }
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FilterList,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(MR.strings.duplicate_source_priority),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                }

                when {
                    !state.hasStartedAnalysis -> {
                        item(key = "initial_state") {
                            // Initial state - show start analysis button
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(MR.strings.duplicate_initial_title),
                                        style = MaterialTheme.typography.headlineSmall,
                                    )
                                    Text(
                                        text = stringResource(MR.strings.duplicate_initial_description),
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(
                                        onClick = { screenModel.loadDuplicates() },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(MR.strings.duplicate_start_analysis))
                                    }
                                }
                            }
                        }
                    }
                    state.isLoading -> {
                        item(key = "loading_state") {
                            val progress = state.precheckProgress
                            if (progress != null) {
                                Box(
                                    modifier = Modifier.fillParentMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        androidx.compose.material3.CircularProgressIndicator()
                                        Text(
                                            text = stringResource(
                                                TDMR.strings.duplicate_checking_progress,
                                                progress.first,
                                                progress.second,
                                            ),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                LoadingScreen(modifier = Modifier.fillParentMaxSize())
                            }
                        }
                    }
                    state.filteredDuplicateGroups.isEmpty() -> {
                        item(key = "empty_state") {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Text(
                                        if (state.duplicateGroups.isEmpty()) {
                                            stringResource(
                                                MR.strings.duplicate_no_duplicates,
                                            )
                                        } else {
                                            stringResource(MR.strings.duplicate_no_matches_filter)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(
                                        onClick = { screenModel.loadDuplicates() },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(MR.strings.duplicate_reanalyze))
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        item(key = "results_summary") {
                            // Results summary
                            Text(
                                text =
                                stringResource(
                                    MR.strings.duplicate_results_summary,
                                    state.filteredDuplicateGroups.size,
                                    state.filteredDuplicateGroups.values.sumOf {
                                        it.size
                                    },
                                ) +
                                    if (state.selectedCategoryFilters.isNotEmpty() ||
                                        state.excludedCategoryFilters.isNotEmpty()
                                    ) {
                                        stringResource(MR.strings.duplicate_results_filtered)
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }

                        items(
                            state.filteredDuplicateGroups.toList(),
                            key = { it.first },
                        ) { (title, mangaList) ->
                            val materializedGroupCount = state.duplicateGroups[title]?.size ?: mangaList.size
                            val scanFullCount = state.fullGroupIds[title]?.size ?: materializedGroupCount
                            val fullGroupCount = if (scanFullCount > materializedGroupCount) {
                                scanFullCount
                            } else {
                                mangaList.size
                            }
                            val canSelectHiddenTail = state.canIncludeHiddenTail
                            DuplicateGroupCard(
                                groupTitle = title,
                                mangaList = mangaList,
                                fullGroupCount = fullGroupCount,
                                canSelectHiddenTail = canSelectHiddenTail,
                                selection = state.selection,
                                mangaCategories = state.mangaCategories,
                                showFullUrls = state.showFullUrls,
                                onToggleSelection = { screenModel.toggleSelection(it) },
                                onSelectGroup = { screenModel.selectGroup(title) },
                                onDismissGroup = { screenModel.dismissGroup(title) },
                                onClickManga = { navigator.push(MangaScreen(it)) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        // Delete confirmation dialog
        if (state.showDeleteDialog) {
            DeleteLibraryMangaDialog(
                containsLocalManga = screenModel.selectionContainsLocalManga(),
                onDismissRequest = { screenModel.closeDeleteDialog() },
                onConfirm = {
                        removeFromLibrary,
                        deleteDownloads,
                        clearChaptersFromDb,
                        deleteTranslations,
                        clearCovers,
                        clearDescriptions,
                        clearTags,
                    ->
                    val count = state.selection.size
                    scope.launch {
                        screenModel.deleteSelected(
                            removeFromLibrary = removeFromLibrary,
                            deleteDownloads = deleteDownloads,
                            clearChaptersFromDb = clearChaptersFromDb,
                            deleteTranslations = deleteTranslations,
                            clearCovers = clearCovers,
                            clearDescriptions = clearDescriptions,
                            clearTags = clearTags,
                        )
                        snackbarHostState.showSnackbar(
                            context.ctxStringResource(MR.strings.duplicate_deleted_count, count),
                        )
                    }
                },
            )
        }

        // Move to category dialog
        if (state.showMoveToCategoryDialog) {
            ChangeCategoryDialog(
                initialSelection = remember(state.selection, state.mangaCategoryIdSets, state.categories) {
                    categorySelectionFor(state)
                },
                onDismissRequest = { screenModel.closeMoveToCategoryDialog() },
                onEditCategories = {
                    screenModel.closeMoveToCategoryDialog()
                    navigator.push(CategoryScreen())
                },
                onConfirm = { addCategories, removeCategories ->
                    if (addCategories.isNotEmpty() || removeCategories.isNotEmpty()) {
                        val count = state.selection.size
                        scope.launch {
                            screenModel.moveSelectedToCategories(addCategories, removeCategories)
                            snackbarHostState.showSnackbar(
                                context.ctxStringResource(MR.strings.duplicate_moved_count, count),
                            )
                        }
                    }
                },
            )
        }
    }
}

private fun categorySelectionFor(
    state: DuplicateDetectionViewModel.State,
): List<CheckboxState<CategoryModel>> {
    val selectedIds = state.selection
    if (selectedIds.isEmpty()) return state.categories.map { CheckboxState.State.None(it) }

    val perManga = selectedIds.map { state.mangaCategoryIdSets[it] ?: setOf(0L) }
    val common = perManga.reduce { a, b -> a intersect b }
    val union = perManga.flatten().toSet()

    return state.categories.map {
        when {
            it.id in common -> CheckboxState.State.Checked(it)
            it.id in union -> CheckboxState.TriState.None(it)
            else -> CheckboxState.State.None(it)
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    groupTitle: String,
    mangaList: List<MangaWithChapterCount>,
    fullGroupCount: Int,
    canSelectHiddenTail: Boolean,
    selection: Set<Long>,
    mangaCategories: Map<Long, List<CategoryModel>>,
    showFullUrls: Boolean,
    onToggleSelection: (Long) -> Unit,
    onSelectGroup: () -> Unit,
    onDismissGroup: () -> Unit,
    onClickManga: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    val allSelected = mangaList.isNotEmpty() && mangaList.all { it.manga.id in selection }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Group header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = groupTitle.replaceFirstChar { it.uppercaseChar() },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(MR.strings.duplicate_n_in_group, mangaList.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (fullGroupCount > mangaList.size) {
                        Text(
                            text = if (canSelectHiddenTail) {
                                stringResource(
                                    MR.strings.duplicate_group_truncated_selectable,
                                    mangaList.size,
                                    fullGroupCount,
                                )
                            } else {
                                stringResource(
                                    MR.strings.duplicate_group_truncated_filtered,
                                    mangaList.size,
                                    fullGroupCount,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                IconButton(onClick = onSelectGroup) {
                    Icon(
                        imageVector = if (allSelected) Icons.Outlined.CheckBox else Icons.Filled.SelectAll,
                        contentDescription = if (allSelected) {
                            stringResource(TDMR.strings.duplicate_deselect_group)
                        } else {
                            stringResource(MR.strings.duplicate_select_group)
                        },
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDismissGroup) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(TDMR.strings.duplicate_dismiss_group),
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) {
                            stringResource(
                                MR.strings.action_collapse,
                            )
                        } else {
                            stringResource(MR.strings.action_expand)
                        },
                    )
                }
            }

            if (expanded) {
                HorizontalDivider()
                mangaList.forEachIndexed { index, mangaWithCount ->
                    DuplicateItem(
                        manga = mangaWithCount,
                        categories = mangaCategories[mangaWithCount.manga.id] ?: emptyList(),
                        isSelected = mangaWithCount.manga.id in selection,
                        isFirst = index == 0,
                        showFullUrl = showFullUrls,
                        onToggleSelection = { onToggleSelection(mangaWithCount.manga.id) },
                        onClick = { onClickManga(mangaWithCount.manga.id) },
                    )
                    if (index < mangaList.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateItem(
    manga: MangaWithChapterCount,
    categories: List<CategoryModel>,
    isSelected: Boolean,
    isFirst: Boolean,
    showFullUrl: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
) {
    val sourceManager: SourceManager = remember { Injekt.get() }
    val downloadManager: DownloadManager = remember { Injekt.get() }
    val sourceName = remember(manga.manga.source) {
        sourceManager.getOrStub(manga.manga.source).getNameForMangaInfo()
    }
    val downloadedCount = remember(manga.manga.id) {
        downloadManager.getDownloadCount(manga.manga)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggleSelection() },
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = manga.manga.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isFirst) FontWeight.Bold else FontWeight.Normal,
                    color = if (isFirst) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isFirst) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(MR.strings.duplicate_original),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // First row: chapters and source
            Row {
                Text(
                    text = stringResource(MR.strings.duplicate_n_chapters, manga.chapterCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloadedCount > 0) {
                    Text(
                        text = " • ${stringResource(MR.strings.duplicate_n_downloads, downloadedCount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (manga.readCount > 0) {
                    Text(
                        text = " • ${stringResource(MR.strings.duplicate_n_read, manga.readCount.toInt())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // Show source/plugin name
                Text(
                    text = " • $sourceName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Show author if available
                manga.manga.author?.let { author ->
                    Text(
                        text = " • $author",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Second row: categories
            if (categories.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                val defaultLabel = stringResource(MR.strings.label_default)
                Row {
                    Text(
                        text = stringResource(
                            MR.strings.duplicate_categories_label,
                            categories.joinToString(", ") {
                                it.name.ifBlank { defaultLabel }
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Third row: full URL (when enabled)
            if (showFullUrl) {
                Spacer(modifier = Modifier.height(2.dp))
                val source = remember(manga.manga.source) { sourceManager.getOrStub(manga.manga.source) }
                val fullUrl = remember(manga.manga.url, source) {
                    source.getMangaUrlOrNull(manga.manga.toSManga()) ?: manga.manga.url
                }
                Text(
                    text = fullUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

