package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.BrowseSourceLoadingItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.i18n.stringResource
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

internal fun LazyGridScope.loadMoreFooter(isLoading: Boolean, onRetry: (() -> Unit)?) {
    if (!isLoading && onRetry == null) return
    item(
        span = { GridItemSpan(maxLineSpan) },
        contentType = "library_load_more_footer",
    ) {
        LoadMoreFooterItem(isLoading, onRetry)
    }
}

internal fun LazyListScope.loadMoreFooter(isLoading: Boolean, onRetry: (() -> Unit)?) {
    if (!isLoading && onRetry == null) return
    item(contentType = "library_load_more_footer") {
        LoadMoreFooterItem(isLoading, onRetry)
    }
}

@Composable
private fun LoadMoreFooterItem(isLoading: Boolean, onRetry: (() -> Unit)?) {
    if (isLoading || onRetry == null) {
        BrowseSourceLoadingItem()
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onRetry) {
            Text(stringResource(MR.strings.action_retry))
        }
    }
}

internal fun LazyGridScope.globalSearchItem(
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
) {
    if (!searchQuery.isNullOrEmpty()) {
        item(
            span = { GridItemSpan(maxLineSpan) },
            contentType = "library_global_search_item",
        ) {
            GlobalSearchItem(
                searchQuery = searchQuery,
                onClick = onGlobalSearchClicked,
            )
        }
    }
}
