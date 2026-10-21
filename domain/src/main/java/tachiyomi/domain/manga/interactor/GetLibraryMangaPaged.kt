package tachiyomi.domain.manga.interactor

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibraryPageSpec
import tachiyomi.domain.library.paging.LibraryPagingSource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.repository.MangaRepository
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class GetLibraryMangaPaged(
    private val mangaRepository: MangaRepository,
    private val getLibraryManga: GetLibraryManga,
    private val libraryPreferences: LibraryPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val indexMutex = Mutex()
    private var indexesReady = false
    private val indexBuildState = MutableStateFlow(false)

    val pageSize: Int
        get() = libraryPreferences.experimentalLibraryPageSize.get().coerceAtLeast(MIN_PAGE_SIZE)

    fun changes(): Flow<Unit> = merge(mangaRepository.libraryTableChanges(), getLibraryManga.pagedChanges())

    fun subscribe(
        categoryId: Long,
        isNovel: Boolean?,
        spec: LibraryPageSpec,
        invalidations: Flow<Unit>,
    ): Flow<PagingData<LibraryManga>> = subscribe(invalidations) { QueryGeneration(categoryId, isNovel, spec)::fetch }

    /** Pages a list SQL can't order or filter. [load] runs once per generation. */
    fun subscribeLoaded(
        invalidations: Flow<Unit>,
        load: suspend () -> List<LibraryManga>,
    ): Flow<PagingData<LibraryManga>> = subscribe(invalidations) {
        val mutex = Mutex()
        var list: List<LibraryManga>? = null
        val fetch: suspend (Int, Int) -> LibraryPagingSource.Chunk = { limit, offset ->
            val rows = withContext(ioDispatcher) { mutex.withLock { list ?: load().also { list = it } } }
            val page = rows.subList(offset.coerceAtMost(rows.size), (offset + limit).coerceAtMost(rows.size))
            LibraryPagingSource.Chunk.of(page, limit, offset)
        }
        fetch
    }

    private fun subscribe(
        invalidations: Flow<Unit>,
        fetchFactory: () -> suspend (limit: Int, offset: Int) -> LibraryPagingSource.Chunk,
    ): Flow<PagingData<LibraryManga>> = channelFlow {
        val current = AtomicReference<PagingSource<Int, LibraryManga>?>()
        val pager = Pager(config()) { LibraryPagingSource(fetchFactory()).also(current::set) }
        launch { invalidations.collect { current.get()?.invalidate() } }
        pager.flow.collect { send(it) }
    }

    /**
     * With few matches every walked page passes everything before it, so a filtered generation walks
     * its first page, then reads up to [SNAPSHOT_SIZE] ids in one walk and pages through them.
     */
    internal inner class QueryGeneration(
        private val categoryId: Long,
        private val isNovel: Boolean?,
        private val spec: LibraryPageSpec,
    ) {
        private val mutex = Mutex()
        private var resolvedMode: Mode? = null
        private var snapshot: Snapshot? = null

        suspend fun fetch(limit: Int, offset: Int): LibraryPagingSource.Chunk = withContext(ioDispatcher) {
            val mode = mutex.withLock { resolvedMode ?: resolveMode().also { resolvedMode = it } }
            val snapshot = if (mode == Mode.SOURCE || (mode == Mode.NARROWED && offset > 0)) {
                mutex.withLock { snapshot ?: takeSnapshot(mode).also { snapshot = it } }
            } else {
                null
            }
            if (snapshot != null && offset < snapshot.ids.size) {
                val slice = snapshot.ids.subList(offset, (offset + limit).coerceAtMost(snapshot.ids.size))
                val end = offset + slice.size
                return@withContext LibraryPagingSource.Chunk(
                    rowsInOrder(slice),
                    end.takeUnless { snapshot.complete && end == snapshot.ids.size },
                )
            }
            if (snapshot?.complete == true) return@withContext LibraryPagingSource.Chunk(emptyList(), null)
            val rows = mangaRepository.getLibraryMangaPage(
                categoryId,
                isNovel,
                limit.toLong(),
                offset.toLong(),
                spec,
                mode == Mode.SMALL,
            )
            LibraryPagingSource.Chunk.of(rows, limit, offset)
        }

        private suspend fun resolveMode(): Mode = when {
            isSmallCategory(categoryId) -> Mode.SMALL
            isSourceDriven(spec) -> Mode.SOURCE
            spec.isNarrowed -> Mode.NARROWED
            else -> Mode.WALK
        }

        private suspend fun takeSnapshot(mode: Mode): Snapshot {
            val bySource = mode == Mode.SOURCE
            val limit = if (bySource) -1L else SNAPSHOT_SIZE + 1L
            val ids = mangaRepository.getLibraryMangaPageIds(categoryId, isNovel, limit, spec, bySource = bySource)
            return if (bySource || ids.size <= SNAPSHOT_SIZE) {
                Snapshot(ids, complete = true)
            } else {
                Snapshot(ids.subList(0, SNAPSHOT_SIZE), complete = false)
            }
        }
    }

    private enum class Mode { SMALL, SOURCE, NARROWED, WALK }

    private class Snapshot(val ids: List<Long>, val complete: Boolean)

    suspend fun awaitAll(categoryId: Long, isNovel: Boolean?, spec: LibraryPageSpec): List<LibraryManga> {
        val small = isSmallCategory(categoryId)
        if (small || !isSourceDriven(spec)) {
            return mangaRepository.getLibraryMangaPage(categoryId, isNovel, null, 0, spec, small)
        }
        val ids = mangaRepository.getLibraryMangaPageIds(categoryId, isNovel, -1, spec, bySource = true)
        return rowsInOrder(ids)
    }

    suspend fun awaitCount(categoryId: Long, isNovel: Boolean?, spec: LibraryPageSpec): Long {
        val small = isSmallCategory(categoryId)
        val bySource = !small && isSourceDriven(spec)
        return mangaRepository.countLibraryMangaPage(categoryId, isNovel, spec, small, bySource)
    }

    suspend fun awaitRandom(categoryId: Long, isNovel: Boolean?, spec: LibraryPageSpec): LibraryManga? {
        val small = isSmallCategory(categoryId)
        if (!small && isSourceDriven(spec)) {
            val id = mangaRepository.getLibraryMangaPageIds(categoryId, isNovel, -1, spec, bySource = true)
                .randomOrNull() ?: return null
            return mangaRepository.getLibraryMangaPageRows(listOf(id)).firstOrNull()
        }
        val count = mangaRepository.countLibraryMangaPage(categoryId, isNovel, spec, small)
        if (count <= 0L) return null
        val offset = Random.nextLong(count)
        return mangaRepository.getLibraryMangaPage(categoryId, isNovel, 1, offset, spec, small).firstOrNull()
    }

    private suspend fun isSourceDriven(spec: LibraryPageSpec): Boolean =
        mangaRepository.isLibraryPageSourceDriven(spec, SOURCE_DRIVEN_ROWS)

    private suspend fun rowsInOrder(ids: List<Long>): List<LibraryManga> {
        val byId = HashMap<Long, LibraryManga>(ids.size)
        ids.chunked(ROWS_PER_QUERY).forEach { chunk ->
            mangaRepository.getLibraryMangaPageRows(chunk).associateByTo(byId) { it.id }
        }
        return ids.mapNotNull(byId::get)
    }

    suspend fun awaitFavoriteCount(isNovel: Boolean?): Long = mangaRepository.countFavorites(isNovel)

    suspend fun awaitHasUncategorized(isNovel: Boolean?): Boolean =
        mangaRepository.hasUncategorizedFavorites(isNovel)

    // Built lazily so only paged mode pays for the indexes.
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

        // Keeps small page sizes from dropping pages still on screen.
        private const val MIN_MAX_SIZE = 500

        // Above this, walking the sort index beats reading and sorting all members per page.
        private const val SMALL_CATEGORY_MEMBERS = 30_000L

        internal const val SNAPSHOT_SIZE = 10_000

        private const val SOURCE_DRIVEN_ROWS = 50_000L

        private const val ROWS_PER_QUERY = 5_000
    }
}
