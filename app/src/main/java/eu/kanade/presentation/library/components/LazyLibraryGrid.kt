package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.util.plus

@Composable
internal fun LazyLibraryGrid(
    modifier: Modifier = Modifier,
    columns: Int,
    contentPadding: PaddingValues,
    content: LazyGridScope.() -> Unit,
) {
    FastScrollLazyVerticalGrid(
        columns = if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns),
        modifier = modifier,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
        content = content,
    )
}

/**
 * Invisible trailing item that fires [onLoadMore] when it enters composition (scrolled to end).
 * Keyed on [loadKey] (the per-category pagination generation) so it re-fires on every fetched page
 * even when in-memory filters hid the whole page, so a filtered-out page can't dead-end the
 * category. The key stops changing once the category is exhausted. No-op when [onLoadMore] null.
 */
internal fun LazyGridScope.loadMoreSentinel(loadKey: Int, onLoadMore: (() -> Unit)?) {
    if (onLoadMore == null) return
    item(
        span = { GridItemSpan(maxLineSpan) },
        contentType = { "library_load_more" },
    ) {
        LaunchedEffect(loadKey) { onLoadMore() }
    }
}

internal fun LazyListScope.loadMoreSentinel(loadKey: Int, onLoadMore: (() -> Unit)?) {
    if (onLoadMore == null) return
    item(contentType = "library_load_more") {
        LaunchedEffect(loadKey) { onLoadMore() }
    }
}

internal fun LazyGridScope.globalSearchItem(
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
) {
    if (!searchQuery.isNullOrEmpty()) {
        item(
            span = { GridItemSpan(maxLineSpan) },
            contentType = { "library_global_search_item" },
        ) {
            GlobalSearchItem(
                searchQuery = searchQuery,
                onClick = onGlobalSearchClicked,
            )
        }
    }
}
