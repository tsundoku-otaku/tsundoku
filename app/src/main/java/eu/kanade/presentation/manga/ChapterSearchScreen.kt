package eu.kanade.presentation.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchOptions
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchResult
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchScreen
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchSnippet
import eu.kanade.tachiyomi.ui.manga.search.ChapterTextSearch
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha

@Composable
fun ChapterSearchScreenContent(
    state: ChapterSearchScreen.State,
    navigateUp: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOptionsChange: (ChapterSearchOptions) -> Unit,
    onRangeChange: (from: String, to: String) -> Unit,
    onResultClick: (Chapter, Int?) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                Box {
                    SearchToolbar(
                        searchQuery = state.query,
                        onChangeSearchQuery = { onQueryChange(it.orEmpty()) },
                        placeholderText = stringResource(TDMR.strings.chapter_search_hint),
                        onSearch = { onSearch() },
                        onClickCloseSearch = navigateUp,
                        navigateUp = navigateUp,
                        scrollBehavior = scrollBehavior,
                    )
                    val total = state.searchTotal
                    if (state.isSearching && total > 0) {
                        LinearProgressIndicator(
                            progress = { state.searchedCount / total.toFloat() },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(),
                        )
                    }
                }
                SearchOptionsRow(options = state.options, onOptionsChange = onOptionsChange)
                RangeRow(state = state, onRangeChange = onRangeChange, onSearch = onSearch)
            }
        },
    ) { contentPadding ->
        val chapters = state.chapters
        when {
            chapters == null -> LoadingScreen(Modifier.padding(contentPadding))
            chapters.isEmpty() -> EmptyScreen(
                stringRes = TDMR.strings.chapter_search_no_chapters,
                modifier = Modifier.padding(contentPadding),
            )
            state.regexError != null -> EmptyScreen(
                message = stringResource(TDMR.strings.novel_invalid_regex_format, state.regexError),
                modifier = Modifier.padding(contentPadding),
            )
            state.submittedQuery == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(MaterialTheme.padding.large),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(TDMR.strings.chapter_search_empty),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.secondaryItemAlpha(),
                )
            }
            else -> SearchResults(
                state = state,
                totalChapters = state.searchTotal,
                contentPadding = contentPadding,
                onResultClick = onResultClick,
            )
        }
    }
}

@Composable
private fun SearchOptionsRow(
    options: ChapterSearchOptions,
    onOptionsChange: (ChapterSearchOptions) -> Unit,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        FilterChip(
            selected = options.isRegex,
            onClick = { onOptionsChange(options.copy(isRegex = !options.isRegex)) },
            label = { Text(stringResource(TDMR.strings.novel_use_regex)) },
        )
        FilterChip(
            selected = options.caseSensitive,
            onClick = { onOptionsChange(options.copy(caseSensitive = !options.caseSensitive)) },
            label = { Text(stringResource(TDMR.strings.novel_case_sensitive_matching)) },
        )
        FilterChip(
            selected = options.wholeWord,
            onClick = { onOptionsChange(options.copy(wholeWord = !options.wholeWord)) },
            label = { Text(stringResource(TDMR.strings.novel_match_whole_word)) },
        )
    }
}

@Composable
private fun RangeRow(
    state: ChapterSearchScreen.State,
    onRangeChange: (from: String, to: String) -> Unit,
    onSearch: () -> Unit,
) {
    val keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Search)
    val keyboardActions = KeyboardActions(onSearch = { onSearch() })
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        OutlinedTextField(
            value = state.rangeFrom,
            onValueChange = { onRangeChange(it, state.rangeTo) },
            label = { Text(stringResource(TDMR.strings.chapter_search_range_from)) },
            isError = ChapterSearchScreen.Model.parseRangeBound(state.rangeFrom) == null,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = state.rangeTo,
            onValueChange = { onRangeChange(state.rangeFrom, it) },
            label = { Text(stringResource(TDMR.strings.chapter_search_range_to)) },
            isError = ChapterSearchScreen.Model.parseRangeBound(state.rangeTo) == null,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SearchResults(
    state: ChapterSearchScreen.State,
    totalChapters: Int,
    contentPadding: PaddingValues,
    onResultClick: (Chapter, Int?) -> Unit,
) {
    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "summary") {
            SearchSummary(state = state, totalChapters = totalChapters)
        }
        items(items = state.results, key = { it.chapter.id }) { result ->
            SearchResultItem(result = result, onResultClick = onResultClick)
        }
    }
}

@Composable
private fun SearchSummary(
    state: ChapterSearchScreen.State,
    totalChapters: Int,
) {
    val text = when {
        state.isSearching -> stringResource(TDMR.strings.chapter_search_progress, state.searchedCount, totalChapters)
        state.results.isEmpty() -> stringResource(MR.strings.no_results_found)
        else -> pluralStringResource(TDMR.plurals.chapter_search_matches, state.totalMatches, state.totalMatches) +
            " " +
            pluralStringResource(TDMR.plurals.chapter_search_matched_chapters, state.results.size, state.results.size)
    }
    Column(
        modifier = Modifier.padding(
            horizontal = MaterialTheme.padding.medium,
            vertical = MaterialTheme.padding.small,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.secondaryItemAlpha(),
        )
        if (state.failedCount > 0) {
            Text(
                text = pluralStringResource(
                    TDMR.plurals.chapter_search_failed_chapters,
                    state.failedCount,
                    state.failedCount,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SearchResultItem(
    result: ChapterSearchResult,
    onResultClick: (Chapter, Int?) -> Unit,
) {
    var expanded by rememberSaveable(result.chapter.id) { mutableStateOf(false) }
    val collapsedCount = ChapterTextSearch.COLLAPSED_SNIPPETS
    val hiddenCount = result.snippets.size - collapsedCount
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.padding.small),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onResultClick(result.chapter, null) }
                .padding(horizontal = MaterialTheme.padding.medium),
        ) {
            Text(
                text = result.chapter.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = pluralStringResource(TDMR.plurals.chapter_search_matches, result.matchCount, result.matchCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = MaterialTheme.padding.small),
            )
        }
        val shown = if (expanded) result.snippets else result.snippets.take(collapsedCount)
        shown.forEach { snippet ->
            SnippetText(
                snippet = snippet,
                onClick = { onResultClick(result.chapter, snippet.progressPercent()) },
            )
        }
        if (hiddenCount > 0) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.small),
            ) {
                Text(
                    text = if (expanded) {
                        stringResource(TDMR.strings.chapter_search_show_less)
                    } else {
                        stringResource(TDMR.strings.chapter_search_show_more, hiddenCount)
                    },
                )
            }
        }
    }
}

// 0 means no jump, so a match at the very top opens at 1%.
private fun ChapterSearchSnippet.progressPercent(): Int = (position * 100).toInt().coerceIn(1, 100)

@Composable
private fun SnippetText(snippet: ChapterSearchSnippet, onClick: () -> Unit) {
    val highlight = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    )
    val positionColor = MaterialTheme.colorScheme.onSurfaceVariant
    val text = buildAnnotatedString {
        append(snippet.text.substring(0, snippet.matchStart))
        withStyle(highlight) { append(snippet.text.substring(snippet.matchStart, snippet.matchEnd)) }
        append(snippet.text.substring(snippet.matchEnd))
        withStyle(SpanStyle(color = positionColor)) {
            append(" (%.1f%%)".format(snippet.position * 100))
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.extraSmall),
    )
}
