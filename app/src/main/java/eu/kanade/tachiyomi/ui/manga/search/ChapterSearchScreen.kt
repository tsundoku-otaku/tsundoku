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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import tachiyomi.source.local.isLocal
import tachiyomi.source.local.isLocalNovel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicInteger
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
            onResultClick = { chapter, progress ->
                context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id, progress))
            },
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
        private var rangeJob: Job? = null
        private val searchGeneration = AtomicLong()
        private var source: Source? = null

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

        fun updateRange(from: String, to: String) {
            mutableState.update { it.copy(rangeFrom = from, rangeTo = to) }
            rangeJob?.cancel()
            if (state.value.submittedQuery == null) return
            rangeJob = viewModelScope.launch {
                delay(RANGE_SEARCH_DELAY)
                search()
            }
        }

        fun search() {
            rangeJob?.cancel()
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
            val allChapters = current.chapters
            val source = source
            if (manga == null || allChapters == null || source == null) {
                // Entry still loading; run once it is ready
                mutableState.update { it.copy(pendingSearch = true) }
                return
            }

            val from = parseRangeBound(current.rangeFrom)
            val to = parseRangeBound(current.rangeTo)
            if (from == null || to == null) {
                mutableState.update { it.copy(isSearching = false, pendingSearch = false) }
                return
            }
            val chapters = if (from.isNaN() && to.isNaN()) {
                allChapters
            } else {
                allChapters.filter { chapter ->
                    (from.isNaN() || chapter.chapterNumber >= from) && (to.isNaN() || chapter.chapterNumber <= to)
                }
            }

            mutableState.update {
                it.copy(
                    submittedQuery = query,
                    pendingSearch = false,
                    isSearching = true,
                    searchTotal = chapters.size,
                    searchedCount = 0,
                    failedCount = 0,
                    results = emptyList(),
                )
            }

            val isLiteral = !current.options.isRegex && !current.options.wholeWord
            val ignoreCase = !current.options.caseSensitive
            searchJob = viewModelScope.launchIO {
                val context = currentCoroutineContext()
                val found = ConcurrentSkipListMap<Int, ChapterSearchResult>()
                val searched = AtomicInteger()
                val failed = AtomicInteger()
                val lastPublish = AtomicLong()

                fun publish(finished: Boolean) {
                    lastPublish.set(System.currentTimeMillis())
                    mutableState.update { state ->
                        if (searchGeneration.get() != generation) return@update state
                        state.copy(
                            searchedCount = searched.get(),
                            failedCount = failed.get(),
                            results = found.values.toList(),
                            isSearching = !finished,
                        )
                    }
                }

                val permits = Semaphore(SEARCH_PARALLELISM)
                coroutineScope {
                    chapters.mapIndexed { index, chapter ->
                        async {
                            permits.withPermit {
                                context.ensureActive()
                                val matches = try {
                                    readContent(manga, source, chapter)?.let { content ->
                                        val text = ChapterTextSearch.toPlainText(content)
                                        if (isLiteral) {
                                            ChapterTextSearch.findLiteralMatches(text, query, ignoreCase)
                                        } else {
                                            ChapterTextSearch.findMatches(
                                                text = text,
                                                regex = regex,
                                                isActive = { context.isActive },
                                            )
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e) { "Chapter search failed to read ${chapter.name}" }
                                    null
                                }
                                if (matches == null) failed.incrementAndGet()
                                if (matches != null && matches.count > 0) {
                                    found[index] = ChapterSearchResult(chapter, matches.count, matches.snippets)
                                }
                                val done = searched.incrementAndGet()
                                val changed = matches == null || matches.count > 0
                                val due = System.currentTimeMillis() - lastPublish.get() >= PUBLISH_INTERVAL_MS
                                if ((changed && due) || done % PROGRESS_STEP == 0) publish(finished = false)
                            }
                        }
                    }.awaitAll()
                }
                publish(finished = true)
            }
        }

        private suspend fun readContent(manga: Manga, source: Source, chapter: Chapter): String? {
            return if (source.isLocal() || manga.isLocalNovel()) {
                source.fetchPageText(Page(0, chapter.url))
            } else {
                contentReader.readDownloadedContent(manga, chapter, source)
            }
        }

        companion object {
            private val SEARCH_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            private const val PROGRESS_STEP = 16
            private const val PUBLISH_INTERVAL_MS = 150L
            private const val RANGE_SEARCH_DELAY = 600L

            /** NaN for a blank bound, null for one that isn't a number. */
            fun parseRangeBound(text: String): Double? {
                val trimmed = text.trim()
                if (trimmed.isEmpty()) return Double.NaN
                return trimmed.replace(',', '.').toDoubleOrNull()
            }

            val MANGA_ID_KEY = CreationExtras.Key<Long>()

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
        val rangeFrom: String = "",
        val rangeTo: String = "",
        val options: ChapterSearchOptions = ChapterSearchOptions(),
        val submittedQuery: String? = null,
        val pendingSearch: Boolean = false,
        val regexError: String? = null,
        val isSearching: Boolean = false,
        val searchTotal: Int = 0,
        val searchedCount: Int = 0,
        val failedCount: Int = 0,
        val results: List<ChapterSearchResult> = emptyList(),
    ) {
        val totalMatches: Int get() = results.sumOf { it.matchCount }
    }
}

@Immutable
data class ChapterSearchResult(
    val chapter: Chapter,
    val matchCount: Int,
    val snippets: List<ChapterSearchSnippet>,
)
