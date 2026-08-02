package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourcePinGroupsDialog
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.custom.CustomSourcesScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.NovelGlobalSearchScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tachiyomi.domain.source.model.Pin
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.novelSourcesTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { NovelSourcesScreenModel() }
    val state by screenModel.state.collectAsState()

    return TabContent(
        titleRes = TDMR.strings.label_novel_sources,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { navigator.push(NovelGlobalSearchScreen()) },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.action_filter),
                icon = Icons.Outlined.FilterList,
                onClick = { navigator.push(SourcesFilterScreen(isNovel = true)) },
            ),
            AppBar.Action(
                title = "Custom Sources", // TODO: Add string resource
                icon = Icons.Outlined.Edit,
                onClick = { navigator.push(CustomSourcesScreen()) },
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            val mappedDialog = when (val d = state.dialog) {
                is NovelSourcesScreenModel.Dialog.SourceOptions -> SourcesScreenModel.Dialog.SourceOptions(d.source)
                is NovelSourcesScreenModel.Dialog.PinGroups -> SourcesScreenModel.Dialog.PinGroups(d.source)
                null -> null
            }
            SourcesScreen(
                state = SourcesScreenModel.State(
                    dialog = mappedDialog,
                    isLoading = state.isLoading,
                    items = state.items,
                ),
                contentPadding = contentPadding,
                onClickItem = { source, listing ->
                    navigator.push(BrowseSourceScreen(source.id, listing.query))
                },
                onLongClickItem = { source ->
                    screenModel.showSourceDialog(source)
                },
                onClickPin = screenModel::togglePin,
                onLongClickPin = screenModel::showPinGroupsDialog,
                onRemoveFromGroup = screenModel::removeSourceFromGroup,
            )

            val currentDialog = state.dialog

            if (currentDialog != null) {
                when (currentDialog) {
                    is NovelSourcesScreenModel.Dialog.SourceOptions -> {
                        val source = currentDialog.source
                        SourceOptionsDialog(
                            source = source,
                            onClickPin = {
                                screenModel.togglePin(source)
                                screenModel.closeDialog()
                            },
                            onClickPinGroups = {
                                screenModel.showPinGroupsDialog(source)
                            },
                            onClickDisable = {
                                screenModel.toggleSource(source)
                                screenModel.closeDialog()
                            },
                            onDismiss = screenModel::closeDialog,
                        )
                    }
                    is NovelSourcesScreenModel.Dialog.PinGroups -> {
                        val source = currentDialog.source
                        SourcePinGroupsDialog(
                            source = source,
                            pinGroups = screenModel.getSourcePinGroups(source),
                            isPinned = Pin.Pinned in source.pin,
                            onTogglePin = { screenModel.togglePin(source) },
                            onConfirm = { selectedGroups ->
                                screenModel.setSourcePinGroups(source, selectedGroups)
                                screenModel.closeDialog()
                            },
                            onDeleteGroup = screenModel::deleteSourcePinGroup,
                            onDismiss = screenModel::closeDialog,
                        )
                    }
                }
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            LaunchedEffect(Unit) {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        NovelSourcesScreenModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                    }
                }
            }
        },
    )
}
