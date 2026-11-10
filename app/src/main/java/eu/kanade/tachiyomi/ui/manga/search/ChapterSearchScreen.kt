package eu.kanade.tachiyomi.ui.manga.search

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.manga.ChapterSearchScreenContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.ChapterContentReader
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalNovelSource
import tachiyomi.source.local.isLocal
import tachiyomi.source.local.isLocalNovel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.PatternSyntaxException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Searches the text of every downloaded chapter (or every chapter of an imported entry) of one entry.
 */
class ChapterSearchScreen(
    private val mangaId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        val viewModel = viewModel<Model>(
            factory = Model.Factory,
            extras = CreationExtras {
                set(Model.MANGA_ID_KEY, mangaId)
            },
        )
        val state by viewModel.state.collectAsStateWithLifecycle()

        ChapterSearchScreenContent(
            state = state,
            navigateUp = navigator::pop,
            onQueryChange = viewModel::updateQuery,
            onSearch = { viewModel.search() },
            onOptionsChange = viewModel::updateOptions,
            onRangeChange = viewModel::updateRange,
            onResultClick = { result, snippet ->
                val chapter = result.chapter
                val target = viewModel.readerTarget(result, snippet)
                context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id, target))
            },
            onToggleExpanded = viewModel::toggleExpanded,
        )
    }

    class Model(
        private val mangaId: Long,
        private val getManga: GetManga = Injekt.get(),
        private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
        private val sourceManager: SourceManager = Injekt.get(),
        private val downloadManager: DownloadManager = Injekt.get(),
        private val contentReader: ChapterContentReader = ChapterContentReader(
            Injekt.get<Application>(),
            Injekt.get<DownloadProvider>(),
        ),
    ) : StateViewModel<State>(State()) {

        private var searchJob: Job? = null
        private val searchGeneration = AtomicLong()
        private var source: Source? = null

        /** The regex behind the current results. */
        private var searchedRegex: Regex? = null

        init {
            viewModelScope.launchIO {
                val manga = getManga.await(mangaId) ?: return@launchIO
                val source = sourceManager.getOrStub(manga.source).also { source = it }
                val readsLocally = source.isLocal() || manga.isLocalNovel()
                if (!readsLocally) downloadManager.awaitDownloadCacheReady()
                val chapters = getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true)
                    .sortedWith(getChapterSort(manga, sortDescending = false))
                    .filter { chapter ->
                        readsLocally || downloadManager.isChapterDownloaded(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                            manga.title,
                            manga.source,
                        )
                    }
                mutableState.update { it.copy(manga = manga, chapters = chapters) }
                if (state.value.pendingSearch) search()
            }
        }

        fun updateQuery(query: String) {
            mutableState.update { it.copy(query = query, regexError = null) }
        }

        fun updateOptions(options: ChapterSearchOptions) {
            mutableState.update { it.copy(options = options, regexError = null) }
            if (state.value.submittedQuery != null) search()
        }

        fun updateRange(range: ChapterSearchRange?) {
            mutableState.update { it.copy(range = range) }
            if (state.value.submittedQuery != null) search()
        }

        /** Where the reader should open for [snippet] of [result], or its first match when null. */
        fun readerTarget(result: ChapterSearchResult, snippet: ChapterSearchSnippet?): ChapterSearchTarget? {
            val current = state.value
            val query = current.submittedQuery ?: return null
            val match = snippet ?: result.snippets.firstOrNull() ?: return null
            return ChapterSearchTarget(
                query = query,
                options = current.submittedOptions,
                matchIndex = match.matchIndex,
                matchCount = result.matchCount,
                position = match.position,
            )
        }

        /**
         * Shows or hides every excerpt of one result. Only the first few excerpts of each chapter
         * are kept while searching, so expanding re-reads that one chapter for the rest.
         */
        fun toggleExpanded(chapterId: Long) {
            val current = state.value
            if (chapterId in current.expandedChapterIds) {
                mutableState.update { it.copy(expandedChapterIds = it.expandedChapterIds - chapterId) }
                return
            }
            mutableState.update { it.copy(expandedChapterIds = it.expandedChapterIds + chapterId) }

            val result = current.results.firstOrNull { it.chapter.id == chapterId } ?: return
            val wanted = minOf(result.matchCount, ChapterTextSearch.MAX_EXPANDED_SNIPPETS)
            val manga = current.manga ?: return
            val source = source ?: return
            val regex = searchedRegex ?: return
            if (result.snippets.size >= wanted) return
            val generation = searchGeneration.get()
            viewModelScope.launchIO {
                val matches = openTextReader(manga, source).use { reader ->
                    scanChapter(reader, result.chapter, regex, ChapterTextSearch.MAX_EXPANDED_SNIPPETS)
                } ?: return@launchIO
                mutableState.update { state ->
                    if (searchGeneration.get() != generation) return@update state
                    state.copy(
                        results = state.results.map {
                            if (it.chapter.id == chapterId) it.copy(snippets = matches.snippets) else it
                        },
                    )
                }
            }
        }

        fun search() {
            searchJob?.cancel()
            val generation = searchGeneration.incrementAndGet()
            val current = state.value
            val query = current.query
            if (query.isEmpty()) {
                mutableState.update { it.copy(isSearching = false, pendingSearch = false) }
                return
            }

            val regex = try {
                ChapterTextSearch.buildRegex(query, current.options)
            } catch (e: PatternSyntaxException) {
                mutableState.update { it.copy(regexError = e.description, isSearching = false) }
                return
            }

            val manga = current.manga
            val chapters = current.searchableChapters
            val source = source
            if (manga == null || chapters == null || source == null) {
                // Entry still loading; run once it is ready
                mutableState.update { it.copy(pendingSearch = true) }
                return
            }

            searchedRegex = regex
            mutableState.update {
                it.copy(
                    submittedQuery = query,
                    submittedOptions = current.options,
                    pendingSearch = false,
                    isSearching = true,
                    searchedCount = 0,
                    failedCount = 0,
                    results = emptyList(),
                    expandedChapterIds = emptySet(),
                )
            }

            searchJob = viewModelScope.launchIO {
                // Chapters are read and scanned a few at a time, each worker with its own reader (the
                // readers are not thread-safe), while results are collected in chapter order.
                val readers = List(SEARCH_PARALLELISM) { openTextReader(manga, source) }
                val idleReaders = Channel<SearchTextReader>(readers.size).apply { readers.forEach(::trySend) }
                try {
                    coroutineScope {
                        chapters.asFlow()
                            .map { chapter ->
                                async {
                                    val reader = idleReaders.receive()
                                    try {
                                        chapter to
                                            scanChapter(
                                                reader,
                                                chapter,
                                                regex,
                                                ChapterTextSearch.MAX_SNIPPETS_PER_CHAPTER,
                                            )
                                    } finally {
                                        idleReaders.trySend(reader)
                                    }
                                }
                            }
                            .buffer(SEARCH_PARALLELISM)
                            .collect { scan ->
                                val (chapter, matches) = scan.await()
                                mutableState.update { state ->
                                    if (searchGeneration.get() != generation) return@update state
                                    state.copy(
                                        searchedCount = state.searchedCount + 1,
                                        failedCount = state.failedCount + if (matches == null) 1 else 0,
                                        results = if (matches != null && matches.count > 0) {
                                            state.results +
                                                ChapterSearchResult(chapter, matches.count, matches.snippets)
                                        } else {
                                            state.results
                                        },
                                    )
                                }
                            }
                    }
                } finally {
                    readers.forEach(SearchTextReader::close)
                }
                mutableState.update {
                    if (searchGeneration.get() != generation) it else it.copy(isSearching = false)
                }
            }
        }

        /** Returns the matches in [chapter], or null when its text could not be read. */
        private suspend fun scanChapter(
            reader: SearchTextReader,
            chapter: Chapter,
            regex: Regex,
            maxSnippets: Int,
        ): ChapterTextMatches? {
            val context = currentCoroutineContext()
            context.ensureActive()
            return try {
                reader.read(chapter)?.let { content ->
                    ChapterTextSearch.findMatches(
                        text = ChapterTextSearch.toPlainText(content),
                        regex = regex,
                        maxSnippets = maxSnippets,
                        isActive = { context.isActive },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Chapter search failed to read ${chapter.name}" }
                null
            }
        }

        /** Reads chapters through the batch readers, which look the novel's storage up only once. */
        private fun openTextReader(manga: Manga, source: Source): SearchTextReader = when {
            source is LocalNovelSource -> {
                val reader = source.openChapterTextReader()
                SearchTextReader(read = { reader.read(it.url) }, onClose = reader::close)
            }
            source.isLocal() || manga.isLocalNovel() -> {
                SearchTextReader(read = { source.fetchPageText(Page(0, it.url)) })
            }
            else -> {
                val reader = contentReader.openDownloadedContentReader(manga, source)
                SearchTextReader(read = { reader.read(it) }, onClose = reader::close)
            }
        }

        companion object {
            val MANGA_ID_KEY = CreationExtras.Key<Long>()

            /** Chapters read and scanned at the same time. */
            private val SEARCH_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

            val Factory = viewModelFactory {
                initializer {
                    Model(mangaId = get(MANGA_ID_KEY)!!)
                }
            }
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        /** Chapters whose text can be searched, in reading order; null while loading. */
        val chapters: List<Chapter>? = null,
        val query: String = "",
        val options: ChapterSearchOptions = ChapterSearchOptions(),
        /** Chapters to search; null searches every chapter. */
        val range: ChapterSearchRange? = null,
        val submittedQuery: String? = null,
        /** The options [submittedQuery] was searched with. */
        val submittedOptions: ChapterSearchOptions = ChapterSearchOptions(),
        val pendingSearch: Boolean = false,
        val regexError: String? = null,
        val isSearching: Boolean = false,
        val searchedCount: Int = 0,
        val failedCount: Int = 0,
        val results: List<ChapterSearchResult> = emptyList(),
        val expandedChapterIds: Set<Long> = emptySet(),
    ) {
        /** [chapters] inside [range], in reading order; null while loading. */
        val searchableChapters: List<Chapter>? by lazy { chapters?.inSearchRange(range) }

        val totalMatches: Int get() = results.sumOf { it.matchCount }
    }
}

@Immutable
data class ChapterSearchResult(
    val chapter: Chapter,
    val matchCount: Int,
    val snippets: List<ChapterSearchSnippet>,
)

/** Reads chapter text for one search, keeping whatever it opened until it is closed. */
private class SearchTextReader(
    val read: suspend (Chapter) -> String?,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() = onClose()
}
