package eu.kanade.presentation.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchOptions
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchRange
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchResult
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchScreen
import eu.kanade.tachiyomi.ui.manga.search.ChapterSearchSnippet
import eu.kanade.tachiyomi.ui.manga.search.ChapterTextSearch
import eu.kanade.tachiyomi.ui.manga.search.inSearchRange
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
    onRangeChange: (ChapterSearchRange?) -> Unit,
    onResultClick: (ChapterSearchResult, ChapterSearchSnippet?) -> Unit,
    onToggleExpanded: (chapterId: Long) -> Unit,
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
                    val total = state.searchableChapters?.size ?: 0
                    if (state.isSearching && total > 0) {
                        LinearProgressIndicator(
                            progress = { state.searchedCount / total.toFloat() },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(),
                        )
                    }
                }
                SearchOptionsRow(
                    options = state.options,
                    onOptionsChange = onOptionsChange,
                    chapters = state.chapters.orEmpty(),
                    range = state.range,
                    onRangeChange = onRangeChange,
                )
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
                totalChapters = state.searchableChapters?.size ?: 0,
                contentPadding = contentPadding,
                onResultClick = onResultClick,
                onToggleExpanded = onToggleExpanded,
            )
        }
    }
}

@Composable
private fun SearchOptionsRow(
    options: ChapterSearchOptions,
    onOptionsChange: (ChapterSearchOptions) -> Unit,
    chapters: List<Chapter>,
    range: ChapterSearchRange?,
    onRangeChange: (ChapterSearchRange?) -> Unit,
) {
    var showRangeDialog by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        FilterChip(
            selected = range != null,
            onClick = { showRangeDialog = true },
            label = { Text(rangeLabel(range)) },
        )
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
    if (showRangeDialog) {
        ChapterRangeDialog(
            chapters = chapters,
            range = range,
            onDismissRequest = { showRangeDialog = false },
            onConfirm = onRangeChange,
        )
    }
}

@Composable
private fun rangeLabel(range: ChapterSearchRange?): String {
    val from = range?.from?.let(::formatChapterNumber)
    val to = range?.to?.let(::formatChapterNumber)
    return when {
        from != null && to != null -> stringResource(TDMR.strings.chapter_search_range_between, from, to)
        from != null -> stringResource(TDMR.strings.chapter_search_range_from_only, from)
        to != null -> stringResource(TDMR.strings.chapter_search_range_to_only, to)
        else -> stringResource(TDMR.strings.chapter_search_range_all)
    }
}

/**
 * Picks the chapter numbers to search between. Either end can be left empty to keep that end open.
 */
@Composable
private fun ChapterRangeDialog(
    chapters: List<Chapter>,
    range: ChapterSearchRange?,
    onDismissRequest: () -> Unit,
    onConfirm: (ChapterSearchRange?) -> Unit,
) {
    var fromText by rememberSaveable { mutableStateOf(range?.from?.let(::formatChapterNumber).orEmpty()) }
    var toText by rememberSaveable { mutableStateOf(range?.to?.let(::formatChapterNumber).orEmpty()) }
    val numbered = remember(chapters) { chapters.filter { it.isRecognizedNumber } }
    val firstNumber = numbered.minOfOrNull { it.chapterNumber }?.let(::formatChapterNumber).orEmpty()
    val lastNumber = numbered.maxOfOrNull { it.chapterNumber }?.let(::formatChapterNumber).orEmpty()
    val from = ChapterSearchRange.parseBound(fromText)
    val to = ChapterSearchRange.parseBound(toText)
    val fromInvalid = fromText.isNotBlank() && from == null
    val toInvalid = toText.isNotBlank() && to == null
    val newRange = ChapterSearchRange.of(from, to)
    val selected = remember(chapters, newRange) { chapters.inSearchRange(newRange).size }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(TDMR.strings.chapter_search_range_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                OutlinedTextField(
                    value = fromText,
                    onValueChange = { fromText = it },
                    label = { Text(stringResource(TDMR.strings.chapter_search_range_from)) },
                    placeholder = { Text(firstNumber) },
                    isError = fromInvalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = toText,
                    onValueChange = { toText = it },
                    label = { Text(stringResource(TDMR.strings.chapter_search_range_to)) },
                    placeholder = { Text(lastNumber) },
                    isError = toInvalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(TDMR.strings.chapter_search_range_summary, selected, chapters.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.secondaryItemAlpha(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !fromInvalid && !toInvalid,
                onClick = {
                    onConfirm(newRange)
                    onDismissRequest()
                },
            ) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onConfirm(null)
                    onDismissRequest()
                },
            ) {
                Text(stringResource(MR.strings.action_reset))
            }
        },
    )
}

@Composable
private fun SearchResults(
    state: ChapterSearchScreen.State,
    totalChapters: Int,
    contentPadding: PaddingValues,
    onResultClick: (ChapterSearchResult, ChapterSearchSnippet?) -> Unit,
    onToggleExpanded: (chapterId: Long) -> Unit,
) {
    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "summary") {
            SearchSummary(state = state, totalChapters = totalChapters)
        }
        items(items = state.results, key = { it.chapter.id }) { result ->
            SearchResultItem(
                result = result,
                expanded = result.chapter.id in state.expandedChapterIds,
                onClick = { snippet -> onResultClick(result, snippet) },
                onToggleExpanded = { onToggleExpanded(result.chapter.id) },
            )
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
    expanded: Boolean,
    onClick: (ChapterSearchSnippet?) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick(null) }
                .padding(
                    start = MaterialTheme.padding.medium,
                    end = MaterialTheme.padding.medium,
                    top = MaterialTheme.padding.small,
                ),
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
        val shown = if (expanded) result.snippets else result.snippets.take(ChapterTextSearch.MAX_SNIPPETS_PER_CHAPTER)
        shown.forEach { snippet ->
            SnippetText(snippet = snippet, onClick = { onClick(snippet) })
        }
        if (expanded) {
            val hidden = result.matchCount - result.snippets.size
            if (hidden > 0 && result.snippets.size >= ChapterTextSearch.MAX_EXPANDED_SNIPPETS) {
                Text(
                    text = pluralStringResource(TDMR.plurals.chapter_search_hidden_matches, hidden, hidden),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .padding(horizontal = MaterialTheme.padding.medium)
                        .secondaryItemAlpha(),
                )
            }
        }
        if (result.matchCount > ChapterTextSearch.MAX_SNIPPETS_PER_CHAPTER) {
            TextButton(
                onClick = onToggleExpanded,
                modifier = Modifier.padding(start = MaterialTheme.padding.small),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
                Text(
                    text = if (expanded) {
                        stringResource(TDMR.strings.chapter_search_show_fewer)
                    } else {
                        pluralStringResource(TDMR.plurals.chapter_search_show_all, result.matchCount, result.matchCount)
                    },
                    modifier = Modifier.padding(start = MaterialTheme.padding.extraSmall),
                )
            }
        } else {
            Spacer(modifier = Modifier.height(MaterialTheme.padding.small))
        }
    }
}

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
