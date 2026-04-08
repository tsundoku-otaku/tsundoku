package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.net.Uri
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.reader.appbars.BottomBarItemState
import eu.kanade.presentation.reader.appbars.deserializeBottomBarItems
import eu.kanade.presentation.reader.appbars.serialize
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.data.translation.TranslationService
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.quote.Quote
import eu.kanade.tachiyomi.ui.reader.quote.QuoteManager
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.text.NovelViewer
import eu.kanade.tachiyomi.ui.reader.viewer.text.NovelWebViewViewer
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.source.local.isLocal
import tachiyomi.source.local.isLocalNovel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.util.Date
import tachiyomi.domain.chapter.model.Chapter as DomainChapter

/**
 * Presenter used by the activity to perform background operations.
 */
class ReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),
    val readerPreferences: ReaderPreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val setMangaViewerFlags: SetMangaViewerFlags = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val translationService: TranslationService = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
) : ViewModel() {
    private val quoteManager: QuoteManager by lazy {
        QuoteManager(Injekt.get<Application>())
    }

    private val mutableState = MutableStateFlow(
        State(
            isTranslating = translationPreferences.translationEnabled().get() &&
                translationPreferences.smartAutoTranslate().get(),
        ),
    )
    val state = mutableState.asStateFlow()

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    /**
     * Novel scroll progress (0-100%) via SavedState. Persists synchronously before process death,
     */
    private var novelScrollProgress = savedState.get<Int>("novel_scroll_progress") ?: -1
        set(value) {
            savedState["novel_scroll_progress"] = value
            field = value
        }

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    private var chapterToDownload: Download? = null

    private val unfilteredChapterList by lazy {
        val manga = manga!!
        runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = false) }
    }

    /**
     * Chapter list for the active manga. It's retrieved lazily and should be accessed for the first
     * time in a background thread to avoid blocking the UI.
     */
    private val chapterList by lazy {
        val manga = manga!!
        val chapters = runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) }

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader = when {
            (readerPreferences.skipRead.get() || readerPreferences.skipFiltered.get()) -> {
                val filteredChapters = chapters.filterNot {
                    when {
                        readerPreferences.skipRead.get() && it.read -> true
                        readerPreferences.skipFiltered.get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                                        !downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                                        downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark)
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        val sortedChapters = chaptersForReader.sortedWith(getChapterSort(manga, sortDescending = false))

        sortedChapters
            .run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(selectedChapter)
                } else {
                    this
                }
            }
            .run {
                if (basePreferences.downloadedOnly.get()) {
                    filterDownloaded(manga)
                } else {
                    this
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    private val incognitoMode: Boolean by lazy { getIncognitoState.await(manga?.source) }
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading.get()

    /** Serializes novel progress saves to prevent concurrent saves racing each other. */
    private val novelProgressMutex = Mutex()

    init {
        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { currentChapter ->
                if (chapterPageIndex >= 0) {
                    // Restore from SavedState
                    currentChapter.requestedPage = chapterPageIndex
                } else if (!currentChapter.chapter.read) {
                    currentChapter.requestedPage = currentChapter.chapter.last_page_read
                } else {
                    // Chapter already read: restore last position if preference is enabled
                    val isNovel = manga?.isNovel == true
                    val restorePosition = if (isNovel) {
                        libraryPreferences.novelReadProgress100.get()
                    } else {
                        libraryPreferences.mangaReadProgress100.get()
                    }
                    if (restorePosition) {
                        currentChapter.requestedPage = currentChapter.chapter.last_page_read
                    }
                }

                chapterId = currentChapter.chapter.id!!

                // For novels: override with SavedState progress if available (survives process death
                // even when the mutex-serialized DB write hasn't completed yet)
                if (novelScrollProgress > 0) {
                    currentChapter.requestedPage = novelScrollProgress
                    novelScrollProgress = -1
                }
            }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        deletePendingChapters()
    }

    /**
     * Whether this presenter is initialized yet.
     */
    fun needsInit(): Boolean {
        return manga == null
    }

    /**
     * Initializes this presenter with the given [mangaId] and [initialChapterId]. This method will
     * fetch the manga from the database and initialize the initial chapter.
     */
    suspend fun init(mangaId: Long, initialChapterId: Long): Result<Boolean> {
        if (!needsInit()) return Result.success(true)
        return withIOContext {
            try {
                val manga = getManga.await(mangaId)
                if (manga != null) {
                    sourceManager.isInitialized.first { it }
                    mutableState.update { it.copy(manga = manga) }
                    if (chapterId == -1L) chapterId = initialChapterId

                    val context = Injekt.get<Application>()
                    val source = sourceManager.getOrStub(manga.source)
                    loader = ChapterLoader(context, downloadManager, downloadProvider, manga, source)

                    loadChapter(loader!!, chapterList.first { chapterId == it.chapter.id })
                    Result.success(true)
                } else {
                    // Unlikely but okay
                    Result.success(false)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                Result.failure(e)
            }
        }
    }

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter,
        forceFromSource: Boolean = false,
    ): ViewerChapters {
        loader.loadChapter(chapter, forceFromSource)

        val chapterPos = chapterList.indexOf(chapter)
        val newChapters = ViewerChapters(
            chapter,
            chapterList.getOrNull(chapterPos - 1),
            chapterList.getOrNull(chapterPos + 1),
        )

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
                it.copy(
                    viewerChapters = newChapters,
                    bookmarked = newChapters.currChapter.chapter.bookmark,
                )
            }
        }

        // Prioritize this chapter for translation if it's a novel and translation is enabled
        enqueueTranslationIfNeeded(chapter)

        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            flushReadTimer()
            restartReadTimer()

            try {
                loadChapter(loader, chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    /**
     * Prepares the next chapter for novel infinite-scroll without changing the active chapter.
     * This loads the chapter's page list (via [ChapterLoader]) but does not update [State.viewerChapters].
     */
    suspend fun prepareNextChapterForInfiniteScroll(): ReaderChapter? {
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return null
        prepareChapterForInfiniteScroll(nextChapter)
        return nextChapter
    }

    /**
     * Prepares the next chapter after [anchor] for novel infinite-scroll without changing the active chapter.
     * This allows multi-chapter append without requiring the active chapter to change.
     */
    suspend fun prepareNextChapterForInfiniteScroll(anchor: ReaderChapter): ReaderChapter? {
        val anchorPos = chapterList.indexOfFirst { it.chapter.id == anchor.chapter.id }
        if (anchorPos < 0) return null
        val nextChapter = chapterList.getOrNull(anchorPos + 1) ?: return null
        prepareChapterForInfiniteScroll(nextChapter)
        return nextChapter
    }

    /**
     * Prepares the previous chapter for novel infinite-scroll without changing the active chapter.
     */
    suspend fun preparePreviousChapterForInfiniteScroll(): ReaderChapter? {
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return null
        prepareChapterForInfiniteScroll(prevChapter)
        return prevChapter
    }

    /**
     * Prepares the previous chapter before [anchor] for novel infinite-scroll without changing the active chapter.
     */
    suspend fun preparePreviousChapterForInfiniteScroll(anchor: ReaderChapter): ReaderChapter? {
        val anchorPos = chapterList.indexOfFirst { it.chapter.id == anchor.chapter.id }
        if (anchorPos < 0) return null
        val prevChapter = chapterList.getOrNull(anchorPos - 1) ?: return null
        prepareChapterForInfiniteScroll(prevChapter)
        return prevChapter
    }

    private suspend fun prepareChapterForInfiniteScroll(chapter: ReaderChapter) {
        val loader = loader ?: return
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            logcat(LogPriority.DEBUG) {
                "ReaderViewModel: prepare skipped for chapter ${chapter.chapter.id}/${chapter.chapter.name}, already state=${chapter.state}"
            }
            return
        }
        logcat(LogPriority.DEBUG) {
            "ReaderViewModel: prepare starting for chapter ${chapter.chapter.id}/${chapter.chapter.name}, state=${chapter.state}"
        }
        try {
            withIOContext {
                loader.loadChapter(chapter)
            }
            logcat(LogPriority.INFO) {
                "ReaderViewModel: prepare finished for chapter ${chapter.chapter.id}/${chapter.chapter.name}, state=${chapter.state}, pages=${chapter.pages?.size ?: 0}"
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) {
                "ReaderViewModel: prepare failed for chapter ${chapter.chapter.id}/${chapter.chapter.name}"
            }
        }
    }

    private fun shouldSetActiveWithoutReload(chapter: ReaderChapter): Boolean {
        val isNovelViewer = state.value.viewer is NovelViewer || state.value.viewer is NovelWebViewViewer
        if (!isNovelViewer) return false
        if (!readerPreferences.novelInfiniteScroll.get()) return false

        if (chapter.state !is ReaderChapter.State.Loaded) return false
        val pages = chapter.pages ?: return false
        return pages.isNotEmpty() && pages.all { it.status == Page.State.Ready }
    }

    /**
     * Updates the active chapter pointers without calling [ChapterLoader.loadChapter].
     * Used when switching between already-appended chapters during novel infinite-scroll.
     */
    private fun setActiveChapterWithoutReload(chapter: ReaderChapter) {
        viewModelScope.launchIO {
            flushReadTimer()
            restartReadTimer()

            val chapterPos = chapterList.indexOfFirst { it.chapter.id == chapter.chapter.id }
            if (chapterPos < 0) return@launchIO

            val newChapters = ViewerChapters(
                chapter,
                chapterList.getOrNull(chapterPos - 1),
                chapterList.getOrNull(chapterPos + 1),
            )

            withUIContext {
                mutableState.update {
                    newChapters.ref()
                    it.viewerChapters?.unref()
                    chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
                    it.copy(
                        viewerChapters = newChapters,
                        bookmarked = newChapters.currChapter.chapter.bookmark,
                    )
                }
            }

            enqueueTranslationIfNeeded(chapter)
        }
    }

    /**
     * Enqueue a chapter for translation if the manga is a novel source and translation is enabled.
     * Manually read chapters get [TranslationService.PRIORITY_MANUAL_READ].
     */
    private fun enqueueTranslationIfNeeded(chapter: ReaderChapter) {
        val currentManga = manga ?: return
        if (
            sourceManager.get(currentManga.source)?.isNovelSource() == true &&
            translationPreferences.translationEnabled().get() &&
            translationPreferences.smartAutoTranslate().get()
        ) {
            translationService.enqueue(
                manga = currentManga,
                chapter = chapter.chapter.toDomainChapter()!!,
                priority = TranslationService.PRIORITY_MANUAL_READ,
            )
        }
    }

    /**
     * Reloads the current chapter. If fromSource is true, it will fetch from the source,
     * otherwise it will reload the local/downloaded version.
     */
    fun reloadChapter(fromSource: Boolean = false) {
        val currChapter = state.value.viewerChapters?.currChapter ?: return
        val loader = loader ?: return

        viewModelScope.launchIO {
            try {
                // Reset chapter state to force reload
                currChapter.state = ReaderChapter.State.Wait

                loadChapter(loader, currChapter, forceFromSource = fromSource)

                // Notify the viewer to refresh
                withUIContext {
                    state.value.viewer?.setChapters(state.value.viewerChapters!!)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Failed to reload chapter" }
            }
        }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            return
        }

        if (chapter.pageLoader?.isLocal == false) {
            val manga = manga ?: return
            val dbChapter = chapter.chapter
            val isDownloaded = downloadManager.isChapterDownloaded(
                dbChapter.name,
                dbChapter.scanlator,
                dbChapter.url,
                manga.title,
                manga.source,
                skipCache = true,
            )
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    fun onViewerLoaded(viewer: Viewer?) {
        mutableState.update {
            it.copy(viewer = viewer)
        }
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage) {
        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return

        // Save last page read and mark as read if needed
        viewModelScope.launchNonCancellable {
            updateChapterProgress(selectedChapter, page)
        }

        if (selectedChapter != getCurrentChapter()) {
            if (shouldSetActiveWithoutReload(selectedChapter)) {
                logcat { "Setting ${selectedChapter.chapter.url} as active (no reload)" }
                setActiveChapterWithoutReload(selectedChapter)
            } else {
                logcat { "Setting ${selectedChapter.chapter.url} as active" }
                loadNewChapter(selectedChapter)
            }
        }

        val inDownloadRange = page.number.toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        eventChannel.trySend(Event.PageChanged)
    }

    /**
     * Updates the chapter shown in the novel top app bar based on scroll position.
     * This does NOT change the active chapter or reload the viewer.
     * Also triggers auto-download of ahead chapters on chapter change.
     */
    fun setNovelVisibleChapter(chapter: Chapter?) {
        mutableState.update { it.copy(novelVisibleChapter = chapter) }
        downloadNextChapters()
    }

    /**
     * Saves reading progress for novel chapters using percentage (0-100).
     * Used by NovelViewer to save scroll position.
     */
    fun saveNovelProgress(page: ReaderPage, progressPercentage: Int) {
        val selectedChapter = page.chapter

        if (incognitoMode) return

        viewModelScope.launchNonCancellable {
            // Serialize saves so concurrent calls don't race each other and save
            // old progress values over newer ones (e.g. multiple chapters in flight).
            novelProgressMutex.withLock {
                val clampedProgress = progressPercentage.coerceIn(0, 100)
                val currentProgress = selectedChapter.chapter.last_page_read

                // Skip save if progress hasn't changed at all
                if (clampedProgress == currentProgress) return@withLock

                // Don't decrease progress unless user scrolled back significantly (>10%)
                if (clampedProgress < currentProgress - 10 && clampedProgress > 0) {
                    logcat(LogPriority.DEBUG) {
                        "NovelProgress: Skipping save - new progress $clampedProgress% is much less than current $currentProgress%"
                    }
                    return@withLock
                }

                selectedChapter.chapter.last_page_read = clampedProgress

                // Mark as read if at the configured threshold or more
                val markAsReadThreshold = readerPreferences.novelMarkAsReadThreshold.get()
                val wasRead = selectedChapter.chapter.read
                if (clampedProgress >= markAsReadThreshold && !wasRead) {
                    selectedChapter.chapter.read = true
                    updateTrackChapterRead(selectedChapter)
                    deleteChapterIfNeeded(selectedChapter)
                }

                updateChapter.await(
                    ChapterUpdate(
                        id = selectedChapter.chapter.id!!,
                        read = selectedChapter.chapter.read,
                        lastPageRead = selectedChapter.chapter.last_page_read.toLong(),
                    ),
                )

                // Notify library of badge changes (important for unread count accuracy)
                if (selectedChapter.chapter.read != wasRead) {
                    manga?.let { m ->
                        val chapters = getChaptersByMangaId.await(m.id)
                        val readCount = chapters.count { it.read }.toLong()
                        val totalCount = chapters.size.toLong()
                        getLibraryManga.applyChapterUpdates(
                            mangaId = m.id,
                            totalChapters = totalCount,
                            readCount = readCount,
                        )
                    }
                }

                logcat(LogPriority.DEBUG) {
                    "NovelProgress: Saved $clampedProgress% for ${selectedChapter.chapter.name}"
                }
            } // end mutex
        }
    }

    /**
     * Update the novel progress percentage in the state for UI display (e.g., slider).
     */
    fun updateNovelProgressPercent(progress: Int) {
        val clamped = progress.coerceIn(0, 100)
        mutableState.update { it.copy(novelProgressPercent = clamped) }
        novelScrollProgress = clamped
    }

    private fun downloadNextChapters() {
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return
        if (manga.isLocalNovel()) return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (!manga.isNovel && getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        viewModelScope.launchIO {
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                nextChapter.url,
                manga.title,
                manga.source,
            )
            if (!manga.isNovel && !isNextChapterDownloaded) return@launchIO

            val chaptersToDownload = getNextChapters.await(manga.id, nextChapter.id!!).run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(nextChapter.toDomainChapter()!!)
                } else {
                    this
                }
            }.take(downloadAheadAmount)

            downloadManager.downloadChapters(
                manga,
                chaptersToDownload,
            )
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots.get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        val currentChapterPosition = chapterList.indexOf(currentChapter)
        val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete)
        }
    }

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(readerChapter: ReaderChapter, page: Page) {
        val pageIndex = page.index

        mutableState.update {
            it.copy(currentPage = pageIndex + 1)
        }
        readerChapter.requestedPage = pageIndex
        chapterPageIndex = pageIndex

        if (!incognitoMode && page.status !is Page.State.Error) {
            readerChapter.chapter.last_page_read = pageIndex

            // For novel chapters each chapter has exactly 1 page (the full text).
            // Marking complete via page-index would always fire on selection.
            // Novel completion is handled by saveNovelProgress (marks at >=95%).
            val isNovelChapter = manga?.isNovel == true && (readerChapter.pages?.size ?: 0) <= 1
            if (!isNovelChapter && readerChapter.pages?.lastIndex == pageIndex) {
                updateChapterProgressOnComplete(readerChapter)
            }

            updateChapter.await(
                ChapterUpdate(
                    id = readerChapter.chapter.id!!,
                    read = readerChapter.chapter.read,
                    lastPageRead = readerChapter.chapter.last_page_read.toLong(),
                ),
            )
        }
    }

    private suspend fun updateChapterProgressOnComplete(readerChapter: ReaderChapter) {
        readerChapter.chapter.read = true
        updateTrackChapterRead(readerChapter)
        deleteChapterIfNeeded(readerChapter)

        // Notify library of badge changes so unread counts update immediately
        manga?.let { m ->
            val chapters = getChaptersByMangaId.await(m.id)
            val readCount = chapters.count { it.read }.toLong()
            val totalCount = chapters.size.toLong()
            getLibraryManga.applyChapterUpdates(
                mangaId = m.id,
                totalChapters = totalCount,
                readCount = readCount,
            )
        }

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return

        val duplicateUnreadChapters = unfilteredChapterList
            .mapNotNull { chapter ->
                if (
                    !chapter.read &&
                    chapter.isRecognizedNumber &&
                    chapter.chapterNumber.toFloat() == readerChapter.chapter.chapter_number
                ) {
                    ChapterUpdate(id = chapter.id, read = true)
                } else {
                    null
                }
            }
        updateChapter.awaitAll(duplicateUnreadChapters)
    }

    fun restartReadTimer() {
        chapterReadStartTime = Instant.now().toEpochMilli()
    }

    fun flushReadTimer() {
        getCurrentChapter()?.let {
            viewModelScope.launchNonCancellable {
                updateHistory(it)
            }
        }
    }

    suspend fun updateHistory() {
        getCurrentChapter()?.let { updateHistory(it) }
    }

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    private suspend fun updateHistory(readerChapter: ReaderChapter) {
        if (incognitoMode) return

        val chapterId = readerChapter.chapter.id!!
        val endTime = Date()
        val sessionReadDuration = chapterReadStartTime?.let { endTime.time - it } ?: 0

        upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
        chapterReadStartTime = null
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter() {
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return
        loadAdjacent(nextChapter)
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter() {
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    fun getSource() = manga?.source?.let { sourceManager.getOrStub(it) } as? HttpSource

    fun getChapterUrl(): String? {
        val sChapter = getCurrentChapter()?.chapter ?: return null
        val source = getSource() ?: return null

        return try {
            source.getChapterUrl(sChapter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    /**
     * Bookmarks the currently active chapter.
     */
    fun toggleChapterBookmark() {
        val chapter = getCurrentChapter()?.chapter ?: return
        val bookmarked = !chapter.bookmark
        chapter.bookmark = bookmarked

        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(
                    id = chapter.id!!,
                    bookmark = bookmarked,
                ),
            )
        }

        mutableState.update {
            it.copy(
                bookmarked = bookmarked,
            )
        }
    }

    /**
     * Toggles translation mode for the current chapter.
     * When toggled on, triggers reload to translate content.
     * When toggled off, triggers reload to show original content.
     */
    fun toggleTranslation() {
        val newState = !state.value.isTranslating
        mutableState.update {
            it.copy(isTranslating = newState)
        }

        // Send event to reload content with new translation state
        viewModelScope.launchIO {
            try {
                eventChannel.send(Event.ReloadWithTranslation)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Error reloading chapter for translation" }
            }
        }
    }

    /**
     * Force retranslate the current chapter.
     * Deletes existing translation and re-enqueues for translation.
     */
    fun retranslateCurrentChapter() {
        val currentManga = manga ?: return
        val chapter = getCurrentChapter()?.chapter?.toDomainChapter() ?: return

        viewModelScope.launchIO {
            translationService.enqueue(
                manga = currentManga,
                chapter = chapter,
                priority = TranslationService.PRIORITY_MANUAL_READ,
                forceRetranslate = true,
            )
            // Enable translation mode and reload
            mutableState.update { it.copy(isTranslating = true) }
            try {
                eventChannel.send(Event.ReloadWithTranslation)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Error reloading chapter for retranslation" }
            }
        }
    }

    /**
     * Get the current target translation language.
     */
    fun getTargetTranslationLanguage(): String {
        return translationService.getLastTargetLanguage()
    }

    /**
     * Set the target translation language.
     */
    fun setTargetTranslationLanguage(language: String) {
        translationService.setTargetLanguage(language)
    }

    /**
     * Translate text content using the translation service.
     */
    suspend fun translateContent(content: String): String {
        val chapter = getCurrentChapter()
        val chapterId = chapter?.chapter?.id
        val mangaId = manga?.id

        if (translationPreferences.smartAutoTranslate().get()) {
            val detected = translationService.detectLanguage(content, mangaId)
            val target = translationPreferences.targetLanguage().get()

            if (detected != null && detected.equals(target, ignoreCase = true)) {
                return content
            }
        }
        return translationService.translateChapterContent(
            content = content,
            chapterId = chapterId,
            mangaId = mangaId,
        )
    }

    /**
     * Returns the viewer position used by this manga or the default one.
     * For novel sources, always returns NOVEL mode.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        // For novel sources, always use novel reader
        val source = manga?.source?.let { sourceManager.getOrStub(it) }
        if (source?.isNovelSource() == true) {
            return ReadingMode.NOVEL.flagValue
        }

        val default = readerPreferences.defaultReadingMode.get()
        val readingMode = ReadingMode.fromPreference(manga?.readingMode?.toInt())
        return when {
            resolveDefault && readingMode == ReadingMode.DEFAULT -> default
            else -> manga?.readingMode?.toInt() ?: default
        }
    }

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingMode: ReadingMode) {
        val manga = manga ?: return
        runBlocking(Dispatchers.IO) {
            setMangaViewerFlags.awaitSetReadingMode(manga.id, readingMode.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType.get()
        val orientation = ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt())
        return when {
            resolveDefault && orientation == ReaderOrientation.DEFAULT -> default
            else -> manga?.readerOrientation?.toInt() ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val manga = manga ?: return
        viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetOrientation(manga.id, orientation.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.SetOrientation(getMangaOrientation()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    fun toggleCropBorders(): Boolean {
        val isPagerType = ReadingMode.isPagerType(getMangaReadingMode())
        return if (isPagerType) {
            readerPreferences.cropBorders.toggle()
        } else {
            readerPreferences.cropBordersWebtoon.toggle()
        }
    }

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    fun showLoadingDialog() {
        mutableState.update { it.copy(dialog = Dialog.Loading) }
    }

    fun openReadingModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.ReadingModeSelect) }
    }

    fun openOrientationModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.OrientationModeSelect) }
    }

    fun openPageDialog(page: ReaderPage) {
        mutableState.update { it.copy(dialog = Dialog.PageActions(page)) }
    }

    fun openSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Settings) }
    }

    fun openTranslationLanguageDialog() {
        mutableState.update { it.copy(dialog = Dialog.TranslationLanguageSelect) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, page)

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga.get()) {
            DiskUtil.buildValidFilename(
                manga.title,
            )
        } else {
            ""
        }

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(copyToClipboard: Boolean) {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, page)

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                manga.editCover(Injekt.get(), stream())
                if (manga.isLocal() || manga.favorite) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack.get()) return

        val manga = manga ?: return
        val context = Injekt.get<Application>()

        viewModelScope.launchNonCancellable {
            trackChapter.await(context, manga.id, readerChapter.chapter.chapter_number.toDouble())
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        val manga = manga ?: return

        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), manga)
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingChapters()
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val viewerChapters: ViewerChapters? = null,
        val bookmarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        val currentPage: Int = -1,
        /**
         * Chapter currently visible in the novel viewer (for app bar display only).
         */
        val novelVisibleChapter: Chapter? = null,
        /**
         * Current reading progress for novel viewer (0-100 percentage).
         */
        val novelProgressPercent: Int = 0,

        /**
         * Whether translation is enabled for the current chapter.
         */
        val isTranslating: Boolean = false,

        /**
         * Viewer used to display the pages (pager, webtoon, ...).
         */
        val viewer: Viewer? = null,
        val dialog: Dialog? = null,
        val menuVisible: Boolean = false,
        val hasUnsavedChanges: Boolean = false,
        @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    val bottomBarItems: StateFlow<List<BottomBarItemState>> = readerPreferences
        .novelBottomBarItems
        .changes()
        .map { it.deserializeBottomBarItems() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = readerPreferences.novelBottomBarItems.get().deserializeBottomBarItems(),
        )

    fun setHasUnsavedChanges(hasUnsaved: Boolean) {
        mutableState.update { it.copy(hasUnsavedChanges = hasUnsaved) }
    }

    fun saveBottomBarItems(items: List<BottomBarItemState>) {
        readerPreferences.novelBottomBarItems.set(items.serialize())
    }

    fun saveEditedChapterContent(json: String) {
        viewModelScope.launchIO {
            try {
                val array = kotlinx.serialization.json.Json.decodeFromString<kotlinx.serialization.json.JsonArray>(json)
                val m = manga ?: return@launchIO
                val s = sourceManager.getOrStub(m.source)

                for (item in array) {
                    val jsonObj = item as? kotlinx.serialization.json.JsonObject ?: continue
                    val idStr = (jsonObj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (idStr == "-1") continue
                    val id = idStr?.toLongOrNull() ?: continue
                    val htmlContent = (jsonObj["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue

                    val chapter = chapterList.find { it.chapter.id == id }?.chapter?.toDomainChapter() ?: continue
                    saveSingleChapterEdits(m, chapter, s, htmlContent)
                }
                setHasUnsavedChanges(false)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to decode edited content json" }
                Injekt.get<Application>().let { app ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        app.toast(tachiyomi.i18n.novel.TDMR.strings.error_decoding_edits)
                    }
                }
            }
        }
    }

    /**
     * Replaces the local downloaded file for a given chapter with the specified edited [htmlContent].
     * Supports both plain directory (.html) and CBZ packed (.cbz) chapter formats.
     */
    private suspend fun saveSingleChapterEdits(
        m: Manga,
        chapter: DomainChapter,
        s: Source,
        htmlContent: String,
    ) {
        val isDownloaded = downloadManager.isChapterDownloaded(chapter.name, chapter.scanlator, chapter.url, m.title, m.source)
        val mangaDir = downloadProvider.getMangaDir(m.title, s).getOrNull() ?: return
        val validName = downloadProvider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).first()

        try {
            val tmpDir = mangaDir.createDirectory(validName + "_tmp") ?: return
            val existingDir = if (isDownloaded) downloadProvider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, m.title, s) else null
            val context: android.app.Application = uy.kohesive.injekt.Injekt.get()

            if (existingDir != null) {
                if (existingDir.isFile) {
                    existingDir.archiveReader(context).use { archiveReader ->
                        archiveReader.useEntries { entries ->
                            entries.filter { it.isFile && it.name?.endsWith(".html") == false }.forEach { entry ->
                                tmpDir.createFile(entry.name)?.openOutputStream()?.use { os ->
                                    archiveReader.getInputStream(entry.name)?.use { it.copyTo(os) }
                                }
                            }
                        }
                    }
                } else if (existingDir.isDirectory) {
                    existingDir.listFiles()?.filter { it.isFile && it.name?.endsWith(".html") == false }?.forEach { file ->
                        tmpDir.createFile(file.name!!)?.openOutputStream()?.use { os ->
                            file.openInputStream().use { it.copyTo(os) }
                        }
                    }
                }
            }

            // Process HTML to include images
            val embedder = eu.kanade.tachiyomi.util.chapter.ChapterImageEmbedder()
            val baseUrl = (s as? eu.kanade.tachiyomi.source.online.HttpSource)?.baseUrl ?: chapter.url.takeIf { it.startsWith("http") }
            val processedHtml = embedder.processHtml(htmlContent, baseUrl, tmpDir)

            val targetFile = tmpDir.createFile("001.html") ?: return
            targetFile.openOutputStream().bufferedWriter().use { it.write(processedHtml) }

            if (downloadPreferences.saveChaptersAsCBZ.get()) {
                val zip = mangaDir.createFile(validName + ".cbz.tmp")!!
                val compressionLevel = if (m.isNovel) uy.kohesive.injekt.Injekt.get<tachiyomi.domain.download.service.NovelDownloadPreferences>().zipCompressionLevel().get() else 0
                mihon.core.archive.ZipWriter(context, zip, compressionLevel).use { writer ->
                    tmpDir.listFiles()?.forEach { file ->
                        writer.write(file)
                    }
                }

                if (existingDir != null) {
                    existingDir.delete()
                }

                val currentCbz = mangaDir.findFile(validName + ".cbz")
                currentCbz?.delete()
                zip.renameTo(validName + ".cbz")
                tmpDir.delete()
            } else {
                if (existingDir != null) {
                    existingDir.delete()
                }
                val currentDir = mangaDir.findFile(validName)
                if (currentDir != null && currentDir.isDirectory) {
                    currentDir.delete()
                }
                tmpDir.renameTo(validName)
            }

            if (!isDownloaded) {
                val dlCache: eu.kanade.tachiyomi.data.download.DownloadCache = uy.kohesive.injekt.Injekt.get()
                dlCache.addChapter(validName, mangaDir, m)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to save edited chapter" }
            Injekt.get<Application>().let { app ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    app.toast(tachiyomi.i18n.novel.TDMR.strings.error_saving_edits)
                }
            }
        }
    }

    // Quotes functionality
    fun getQuotes(): List<Quote> {
        val manga = manga ?: return emptyList()
        return quoteManager.getQuotes(manga.id)
    }

    fun saveQuote(text: String, chapterName: String) {
        val manga = manga ?: return
        val chapter = getCurrentChapter()?.chapter ?: return
        val quote = Quote(
            novelName = manga.title,
            chapterName = chapterName,
            content = text,
            timestamp = System.currentTimeMillis(),
        )
        quoteManager.addQuote(manga.id, quote)
    }

    fun deleteQuote(quote: Quote) {
        val manga = manga ?: return
        quoteManager.removeQuote(manga.id, quote.id)
    }

    fun updateQuote(quote: Quote) {
        val manga = manga ?: return
        quoteManager.updateQuote(manga.id, quote)
    }

    fun reorderQuotes(quotes: List<Quote>) {
        val manga = manga ?: return
        quoteManager.reorderQuotes(manga.id, quotes)
    }

    sealed interface Dialog {
        data object Loading : Dialog
        data object Settings : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog
        data object TranslationLanguageSelect : Dialog
        data class PageActions(val page: ReaderPage) : Dialog
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        data object PageChanged : Event
        data object ReloadWithTranslation : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(val uri: Uri, val page: ReaderPage) : Event
        data class CopyImage(val uri: Uri) : Event
    }
}
