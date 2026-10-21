package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction

/** What the library pager needs to show categories from the experimental paged library. */
@Immutable
class LibraryPaging(
    val items: (Category) -> Flow<PagingData<LibraryManga>>?,
    val isInMemory: (Category) -> Boolean,
    val toItem: (LibraryManga) -> LibraryItem,
    // Changes whenever badges must be rebuilt, since rows are decorated while rendering.
    val badgeVersion: Int,
    val buildingIndexes: Boolean,
    val onActiveChange: (Boolean) -> Unit,
    val onLongClick: (Category, LibraryManga, loaded: List<LibraryManga>) -> Unit,
)

/** Lets the pagers reload only while the library is on screen. */
@Composable
internal fun LibraryPagingActiveEffect(paging: LibraryPaging) {
    LifecycleStartEffect(paging.onActiveChange) {
        paging.onActiveChange(true)
        onStopOrDispose { paging.onActiveChange(false) }
    }
}

@Composable
internal fun LibraryPagedPage(
    category: Category,
    paging: LibraryPaging,
    contentPadding: PaddingValues,
    hasActiveFilters: Boolean,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    content: @Composable (
        items: List<LibraryItem>,
        isLoadingMore: Boolean,
        onLongClick: (Category, LibraryManga) -> Unit,
    ) -> Unit,
) {
    val flow = paging.items(category)
    if (flow == null) {
        LibraryPagedLoading(contentPadding, paging.buildingIndexes)
        return
    }
    val lazyItems = flow.collectAsLazyPagingItems()
    val items = remember(lazyItems, paging.badgeVersion, paging.toItem) {
        PagedLibraryItems(lazyItems, paging.toItem)
    }
    val loadState = lazyItems.loadState

    if (lazyItems.itemCount == 0) {
        val refresh = loadState.refresh
        when {
            refresh is LoadState.Error -> {
                EmptyScreen(
                    message = refresh.error.message ?: refresh.error.toString(),
                    modifier = Modifier.padding(contentPadding),
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.action_retry,
                            icon = Icons.Outlined.Refresh,
                            onClick = lazyItems::retry,
                        ),
                    ),
                )
            }
            // In-memory filters can empty whole pages while later ones still have rows.
            refresh is LoadState.Loading || !loadState.append.endOfPaginationReached -> {
                LibraryPagedLoading(contentPadding, paging.buildingIndexes)
            }
            else -> {
                LibraryPagerEmptyScreen(
                    searchQuery = searchQuery,
                    hasActiveFilters = hasActiveFilters,
                    contentPadding = contentPadding,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                )
            }
        }
        return
    }

    Column {
        if (paging.isInMemory(category)) {
            Text(
                text = stringResource(TDMR.strings.library_paging_bypassed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        content(
            items,
            loadState.append is LoadState.Loading,
        ) { cat, manga -> paging.onLongClick(cat, manga, lazyItems.itemSnapshotList.items) }
    }
}

@Composable
private fun LibraryPagedLoading(contentPadding: PaddingValues, buildingIndexes: Boolean) {
    Column(
        modifier = Modifier
            .padding(contentPadding)
            .padding(horizontal = 32.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        if (buildingIndexes) {
            Text(
                text = stringResource(TDMR.strings.library_paging_building_indexes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Item key that doesn't count as an access: lazy lists compute keys well past the viewport, and
 * reading those rows through Paging would load pages nobody is looking at.
 */
internal fun List<LibraryItem>.libraryItemKey(index: Int): Any =
    (this as? PagedLibraryItems)?.keyAt(index) ?: this[index].id

/**
 * The loaded rows as a list for the shared grids. Reading an index goes through [LazyPagingItems.get],
 * which is what tells Paging where the user is and triggers the next load.
 */
internal class PagedLibraryItems(
    private val items: LazyPagingItems<LibraryManga>,
    private val toItem: (LibraryManga) -> LibraryItem,
) : AbstractList<LibraryItem>() {
    // Bounded so a deep scroll doesn't keep every row it passed alive after Paging drops it.
    private val decorated = object : LinkedHashMap<Long, Pair<LibraryManga, LibraryItem>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<LibraryManga, LibraryItem>>) =
            size > DECORATED_CACHE_SIZE
    }

    override val size: Int get() = items.itemCount

    fun keyAt(index: Int): Any = items.peek(index)?.id ?: index

    override fun get(index: Int): LibraryItem {
        val manga = items[index] ?: error("Library paging has placeholders disabled")
        decorated[manga.id]?.let { (row, item) -> if (row === manga) return item }
        return toItem(manga).also { decorated[manga.id] = manga to it }
    }
}

private const val DECORATED_CACHE_SIZE = 256
