package eu.kanade.presentation.browse.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun GlobalSearchToolbar(
    searchQuery: String?,
    progress: Int,
    total: Int,
    navigateUp: () -> Unit,
    onChangeSearchQuery: (String?) -> Unit,
    onSearch: (String) -> Unit,
    hideSourceFilter: Boolean,
    sourceFilter: SourceFilter,
    pinGroups: List<String> = emptyList(),
    onChangeSearchFilter: (SourceFilter) -> Unit,
    onlyShowHasResults: Boolean,
    onToggleResults: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var showGroupRow by remember { mutableStateOf(false) }

    val arrowRotation by animateFloatAsState(
        targetValue = if (showGroupRow) -90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "ArrowRotation",
    )

    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
        Box {
            SearchToolbar(
                searchQuery = searchQuery,
                onChangeSearchQuery = onChangeSearchQuery,
                onSearch = onSearch,
                onClickCloseSearch = navigateUp,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
            if (progress in 1..<total) {
                LinearProgressIndicator(
                    progress = { progress / total.toFloat() },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                )
            }
        }

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.padding.small),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            if (!hideSourceFilter) {
                val isPinnedOrGroup = sourceFilter == SourceFilter.PinnedOnly || sourceFilter is SourceFilter.Group
                val pinnedLabel = when (sourceFilter) {
                    is SourceFilter.Group -> sourceFilter.name
                    else -> stringResource(MR.strings.pinned_sources)
                }
                val pinnedIcon = when (sourceFilter) {
                    is SourceFilter.Group -> Icons.AutoMirrored.Outlined.Label
                    else -> Icons.Outlined.PushPin
                }

                FilterChip(
                    selected = isPinnedOrGroup,
                    onClick = {
                        if (!isPinnedOrGroup) {
                            onChangeSearchFilter(SourceFilter.PinnedOnly)
                        } else if (pinGroups.isNotEmpty()) {
                            showGroupRow = !showGroupRow
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = pinnedIcon,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                    trailingIcon = if (pinGroups.isNotEmpty()) {
                        {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(FilterChipDefaults.IconSize)
                                    .rotate(arrowRotation),
                            )
                        }
                    } else null,
                    label = {
                        Text(text = pinnedLabel)
                    },
                )
                FilterChip(
                    selected = sourceFilter == SourceFilter.All,
                    onClick = {
                        onChangeSearchFilter(SourceFilter.All)
                        showGroupRow = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.DoneAll,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                    label = {
                        Text(text = stringResource(MR.strings.all))
                    },
                )

                VerticalDivider()
            }

            FilterChip(
                selected = onlyShowHasResults,
                onClick = { onToggleResults() },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.FilterList,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                label = {
                    Text(text = stringResource(MR.strings.has_results))
                },
            )
        }

        AnimatedVisibility(visible = showGroupRow && pinGroups.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = MaterialTheme.padding.small, vertical = MaterialTheme.padding.extraSmall),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                pinGroups.forEach { group ->
                    val isGroupSelected = sourceFilter == SourceFilter.Group(group)
                    FilterChip(
                        selected = isGroupSelected,
                        onClick = {
                            if (isGroupSelected) {
                                onChangeSearchFilter(SourceFilter.PinnedOnly)
                            } else {
                                onChangeSearchFilter(SourceFilter.Group(group))
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Label,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        },
                        label = {
                            Text(text = group)
                        },
                    )
                }
            }
        }

        HorizontalDivider()
    }
}
