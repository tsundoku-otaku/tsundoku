package tachiyomi.domain.manga.interactor

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibraryPageSpec
import tachiyomi.domain.library.paging.LibraryPagingSource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.repository.MangaRepository
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Experimental paged library: Paging 3 flows over the per-category library query. Only the library
 * screen uses this; everything else keeps reading [GetLibraryManga].
 */
class GetLibraryMangaPaged(
    private val mangaRepository: MangaRepository,
    private val getLibraryManga: GetLibraryManga,
    private val libraryPreferences: LibraryPreferences,
) {
    private val indexMutex = Mutex()
    private var indexesReady = false
    private val indexBuildState = MutableStateFlow(false)

    val pageSize: Int
        get() = libraryPreferences.experimentalLibraryPageSize.get().coerceAtLeast(MIN_PAGE_SIZE)

    /** Raw change signals for the paged queries: DB writes plus explicit library reloads. */
    fun changes(): Flow<Unit> = merge(mangaRepository.libraryTableChanges(), getLibraryManga.pagedChanges())

    fun subscribe(
        categoryId: Long,
        isNovel: Boolean?,
        spec: LibraryPageSpec,
        invalidations: Flow<Unit>,
    ): Flow<PagingData<LibraryManga>> = subscribe(invalidations) {
        var small: Boolean? = null
        val fetch: suspend (Int, Int) -> List<LibraryManga> = { limit, offset ->
            val smallCategory = small ?: isSmallCategory(categoryId).also { small = it }
            mangaRepository.getLibraryMangaPage(
                categoryId,
                isNovel,
                limit.toLong(),
                offset.toLong(),
                spec,
                smallCategory,
            )
        }
        fetch
    }

    /**
     * Pages a list that can't be queried page by page (a sort or search the DB can't express). Each
     * source generation calls [load] once and serves its pages from the result.
     */
    fun subscribeLoaded(
        invalidations: Flow<Unit>,
        load: suspend () -> List<LibraryManga>,
    ): Flow<PagingData<LibraryManga>> = subscribe(invalidations) {
        val mutex = Mutex()
        var list: List<LibraryManga>? = null
        val fetch: suspend (Int, Int) -> List<LibraryManga> = { limit, offset ->
            val rows = mutex.withLock { list ?: load().also { list = it } }
            rows.subList(offset.coerceAtMost(rows.size), (offset + limit).coerceAtMost(rows.size))
        }
        fetch
    }

    private fun subscribe(
        invalidations: Flow<Unit>,
        fetchFactory: () -> suspend (limit: Int, offset: Int) -> List<LibraryManga>,
    ): Flow<PagingData<LibraryManga>> = channelFlow {
        val current = AtomicReference<PagingSource<Int, LibraryManga>?>()
        val pager = Pager(config()) { LibraryPagingSource(fetchFactory()).also(current::set) }
        launch { invalidations.collect { current.get()?.invalidate() } }
        pager.flow.collect { send(it) }
    }

    suspend fun awaitAll(categoryId: Long, isNovel: Boolean?, spec: LibraryPageSpec): List<LibraryManga> =
        mangaRepository.getLibraryMangaPage(categoryId, isNovel, null, 0, spec, isSmallCategory(categoryId))

    suspend fun awaitCount(categoryId: Long, isNovel: Boolean?, spec: LibraryPageSpec): Long =
        mangaRepository.countLibraryMangaPage(categoryId, isNovel, spec, isSmallCategory(categoryId))

    suspend fun awaitRandom(categoryId: Long, isNovel: Boolean?, spec: LibraryPageSpec): LibraryManga? {
        val count = awaitCount(categoryId, isNovel, spec)
        if (count <= 0L) return null
        val offset = Random.nextLong(count)
        return mangaRepository.getLibraryMangaPage(categoryId, isNovel, 1, offset, spec, isSmallCategory(categoryId))
            .firstOrNull()
    }

    suspend fun awaitFavoriteCount(isNovel: Boolean?): Long = mangaRepository.countFavorites(isNovel)

    suspend fun awaitHasUncategorized(isNovel: Boolean?): Boolean =
        mangaRepository.hasUncategorizedFavorites(isNovel)

    /**
     * Creates the per-sort indexes the paged queries walk. Built lazily so only paged mode pays. On a
     * large library the first build takes minutes and holds up every other query meanwhile.
     */
    suspend fun ensureIndexes() {
        indexMutex.withLock {
            if (indexesReady) return
            indexBuildState.value = true
            try {
                val start = System.currentTimeMillis()
                val built = mangaRepository.ensureLibraryPagingIndexes()
                indexesReady = true
                logcat(LogPriority.INFO) {
                    "GetLibraryMangaPaged: paging indexes ready in ${System.currentTimeMillis() - start}ms (built=$built)"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "GetLibraryMangaPaged: failed to create paging indexes" }
            } finally {
                indexBuildState.value = false
            }
        }
    }

    fun buildingIndexes(): StateFlow<Boolean> = indexBuildState.asStateFlow()

    private suspend fun isSmallCategory(categoryId: Long): Boolean =
        categoryId != 0L && mangaRepository.countCategoryMembers(categoryId) <= SMALL_CATEGORY_MEMBERS

    private fun config(): PagingConfig {
        val size = pageSize
        val prefetch = (size / 3).coerceAtLeast(MIN_PAGE_SIZE / 2)
        return PagingConfig(
            pageSize = size,
            prefetchDistance = prefetch,
            enablePlaceholders = false,
            initialLoadSize = size * 2,
            maxSize = (size * 6).coerceAtLeast(MIN_MAX_SIZE),
        )
    }

    companion object {
        private const val MIN_PAGE_SIZE = 20

        // Room for a full screen of large-column grid plus prefetch, so small page sizes don't drop
        // pages that are still on screen.
        private const val MIN_MAX_SIZE = 500

        // Above this many members a category is a big enough share of the library that walking the
        // sort index beats reading and sorting all its members per page.
        private const val SMALL_CATEGORY_MEMBERS = 30_000L
    }
}
