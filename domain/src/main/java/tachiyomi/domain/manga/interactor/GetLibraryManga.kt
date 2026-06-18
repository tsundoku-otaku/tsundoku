@file:Suppress("ktlint:standard:backing-property-naming")

package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibraryMangaForUpdate
import tachiyomi.domain.library.model.LibraryPageSpec
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Interactor for getting library manga.
 *
 * This class uses a manual StateFlow that is ONLY refreshed when explicitly requested,
 * preventing the query from running hundreds of times during normal app usage.
 */
class GetLibraryManga(
    private val mangaRepository: MangaRepository,
    private val libraryPreferences: LibraryPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    // Experimental pagination: per (category, content type) page bookkeeping. The enable pref is
    // restart-gated, so it isn't observed.
    private data class PageKey(val categoryId: Long, val isNovel: Boolean)
    private val loadedPageCount = mutableMapOf<PageKey, Int>()
    private val pageHasMore = mutableMapOf<PageKey, Boolean>()
    // Per-key query spec (global filters/search + that category's sort), reconciled by
    // [applyPageSpecs]. A change there triggers a full reset and reload.
    private val keySpec = mutableMapOf<PageKey, LibraryPageSpec>()

    private val paginationEnabled: Boolean
        get() = libraryPreferences.experimentalLibraryPagination.get() &&
            libraryPreferences.experimentalLibraryPageSize.get() > 0

    private val pageSize: Int
        get() = libraryPreferences.experimentalLibraryPageSize.get().coerceAtLeast(1)

    private val _libraryState = MutableStateFlow<List<LibraryManga>>(emptyList())

    @Suppress("ktlint:standard:backing-property-naming")
    private val _isLoading = MutableStateFlow(true)
    private var isInitialized = false
    private var lastRefreshTime = 0L

    // Minimum time between refreshes
    private val minRefreshIntervalMs = 2000L

    private var _version = 0

    init {
        scope.launch {
            logcat(LogPriority.INFO) { "GetLibraryManga: Loading library from mangas table" }
            refreshInternal(force = false)
        }
    }

    /**
     * Get the current cached library synchronously (may be empty if not yet loaded).
     */
    suspend fun await(): List<LibraryManga> {
        val caller =
            Thread.currentThread().stackTrace.getOrNull(3)?.let { "${it.className}.${it.methodName}" } ?: "unknown"
        logcat(LogPriority.DEBUG) { "GetLibraryManga.await() called by: $caller" }
        // If not initialized, wait for initial load
        if (!isInitialized) {
            logcat(LogPriority.INFO) { "GetLibraryManga.await() triggering initial load (called by $caller)" }
            refreshInternal(force = true)
        }
        return _libraryState.value
    }

    /**
     * Check if library is currently loading
     */
    fun isLoading(): Flow<Boolean> = _isLoading.asStateFlow()

    /**
     * Force a refresh of the library cache.
     */
    fun refresh() {
        val stackTrace = Thread.currentThread().stackTrace
            .drop(2).take(8)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        logcat(LogPriority.WARN) { "GetLibraryManga.refresh() called! Stack: $stackTrace" }
        scope.launch {
            refreshInternal(force = false)
        }
    }

    /**
     * Force the library StateFlow to re-emit its current value.
     * This triggers downstream recomputation (e.g., download badges) without a full DB refresh.
     */
    fun notifyChanged() {
        _version++
        _libraryState.value = _libraryState.value.toList()
    }

    /**
     * Force a refresh and bypass the minimum refresh interval.
     * Use when user explicitly requests a reload (e.g., after backup restore).
     * Returns the updated library list.
     *
     * This is a suspend function that waits for the refresh to complete.
     */
    suspend fun refreshForced(): List<LibraryManga> {
        val stackTrace = Thread.currentThread().stackTrace
            .drop(2).take(8)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        logcat(LogPriority.WARN) { "GetLibraryManga.refreshForced() called! Stack: $stackTrace" }
        refreshInternal(force = true)
        return _libraryState.value
    }

    /**
     * Await refresh - waits for the current refresh to complete.
     * Use this when you need to ensure the library is up-to-date before proceeding.
     */
    suspend fun awaitRefresh() {
        refreshInternal(force = false)
    }

    /**
     * Apply category updates to the in-memory library list without a full DB refresh.
     * This keeps UI responsive for small, targeted changes.
     */
    suspend fun applyCategoryUpdates(
        mangaIds: List<Long>,
        addCategories: List<Long>,
        removeCategories: List<Long>,
    ) {
        if (mangaIds.isEmpty()) return
        mutex.withLock {
            val idSet = mangaIds.toSet()
            val current = _libraryState.value
            val result = ArrayList<LibraryManga>(current.size)
            var changed = false
            for (item in current) {
                if (item.id !in idSet) {
                    result.add(item)
                } else {
                    val updated = item.categories.toMutableSet()
                    addCategories.forEach { updated.add(it) }
                    removeCategories.forEach { updated.remove(it) }
                    if (addCategories.any { it != 0L } && updated.any { it != 0L }) {
                        updated.remove(0L)
                    }
                    if (updated.isEmpty() || updated.all { it == 0L }) {
                        updated.clear()
                        updated.add(0L)
                    }
                    result.add(item.copy(categories = updated.toList()))
                    changed = true
                }
            }
            if (changed) _libraryState.value = result
        }
    }

    /**
     * Replace all categories for the given manga in-memory.
     * Use for "set" operations where the full category list is replaced.
     */
    suspend fun setCategoriesForManga(mangaIds: List<Long>, categoryIds: List<Long>) {
        if (mangaIds.isEmpty()) return
        // When no categories are specified, use the default (uncategorized) category
        val effectiveCategories = categoryIds.ifEmpty { listOf(0L) }
        mutex.withLock {
            val idSet = mangaIds.toSet()
            val current = _libraryState.value
            val result = ArrayList<LibraryManga>(current.size)
            var changed = false
            for (item in current) {
                if (item.id !in idSet) {
                    result.add(item)
                } else {
                    result.add(item.copy(categories = effectiveCategories))
                    changed = true
                }
            }
            if (changed) _libraryState.value = result
        }
    }

    /**
     * Apply chapter count/read updates to the in-memory library list without a full DB refresh.
     * Call this after chapters are read, downloaded, or deleted to keep badges accurate.
     */
    suspend fun applyChapterUpdates(
        mangaId: Long,
        totalChapters: Long? = null,
        readCount: Long? = null,
        bookmarkCount: Long? = null,
        lastRead: Long? = null,
    ) {
        mutex.withLock {
            val current = _libraryState.value
            val result = ArrayList<LibraryManga>(current.size)
            var changed = false
            for (item in current) {
                if (item.id != mangaId) {
                    result.add(item)
                } else {
                    result.add(
                        item.copy(
                            totalChapters = totalChapters ?: item.totalChapters,
                            readCount = readCount ?: item.readCount,
                            bookmarkCount = bookmarkCount ?: item.bookmarkCount,
                            lastRead = lastRead ?: item.lastRead,
                        ),
                    )
                    changed = true
                }
            }
            if (changed) _libraryState.value = result
        }
    }

    /**
     * Apply batch chapter count updates for multiple manga at once.
     * More efficient than calling applyChapterUpdates individually.
     */
    suspend fun applyBatchChapterUpdates(updates: Map<Long, LibraryManga.() -> LibraryManga>) {
        if (updates.isEmpty()) return
        mutex.withLock {
            val current = _libraryState.value
            val result = ArrayList<LibraryManga>(current.size)
            var changed = false
            for (item in current) {
                val updater = updates[item.id]
                if (updater != null) {
                    result.add(updater(item))
                    changed = true
                } else {
                    result.add(item)
                }
            }
            if (changed) _libraryState.value = result
        }
    }

    /**
     * Apply manga detail updates (title, cover URL, status, etc.) to the in-memory list.
     * This avoids a full DB refresh when a single manga's metadata changes.
     */
    suspend fun applyMangaDetailUpdate(
        mangaId: Long,
        updater: (tachiyomi.domain.manga.model.Manga) -> tachiyomi.domain.manga.model.Manga,
    ) {
        mutex.withLock {
            val current = _libraryState.value
            val result = ArrayList<LibraryManga>(current.size)
            var changed = false
            for (item in current) {
                if (item.id != mangaId) {
                    result.add(item)
                } else {
                    result.add(item.copy(manga = updater(item.manga)))
                    changed = true
                }
            }
            if (changed) _libraryState.value = result
        }
    }

    /**
     * Non-suspend version of applyMangaDetailUpdate for use in onDispose() callbacks
     * where the calling scope is about to be cancelled.
     */
    fun applyMangaDetailUpdateSync(
        mangaId: Long,
        updater: (tachiyomi.domain.manga.model.Manga) -> tachiyomi.domain.manga.model.Manga,
    ) {
        // Direct update without mutex since this is called from onDispose
        val current = _libraryState.value
        val result = ArrayList<LibraryManga>(current.size)
        var changed = false
        for (item in current) {
            if (item.id != mangaId) {
                result.add(item)
            } else {
                result.add(item.copy(manga = updater(item.manga)))
                changed = true
            }
        }
        if (changed) _libraryState.value = result
    }

    /**
     * Add a single manga to the in-memory library list after it becomes a favorite.
     * Fetches its LibraryManga data from the DB (requires library_cache row to exist).
     */
    suspend fun addToLibrary(mangaId: Long) {
        mangaRepository.refreshLibraryCacheForManga(mangaId)
        val libraryManga = mangaRepository.getLibraryMangaById(mangaId) ?: run {
            logcat(LogPriority.WARN) {
                "GetLibraryManga.addToLibrary: Could not fetch LibraryManga for $mangaId, falling back to refresh"
            }
            refresh()
            return
        }
        mutex.withLock {
            val existing = _libraryState.value.any { it.id == mangaId }
            if (!existing) {
                _libraryState.value = _libraryState.value + libraryManga
            }
        }
    }

    /**
     * Add multiple manga to the in-memory library list in bulk.
     * Much more efficient than calling addToLibrary individually for batch operations.
     */
    suspend fun addToLibraryBulk(mangaIds: List<Long>) {
        if (mangaIds.isEmpty()) return
        for (id in mangaIds) {
            mangaRepository.refreshLibraryCacheForManga(id)
        }
        val newItems = mangaRepository.getLibraryMangaByIds(mangaIds)
        if (newItems.isEmpty()) {
            logcat(LogPriority.WARN) {
                "GetLibraryManga.addToLibraryBulk: Could not fetch any LibraryManga for ${mangaIds.size} ids, falling back to refresh"
            }
            refresh()
            return
        }
        mutex.withLock {
            val existingIds = _libraryState.value.map { it.id }.toSet()
            val toAdd = newItems.filter { it.id !in existingIds }
            if (toAdd.isNotEmpty()) {
                _libraryState.value = _libraryState.value + toAdd
            }
        }
    }

    /**
     * Remove a single manga from the in-memory library list after it's unfavorited.
     */
    suspend fun removeFromLibrary(mangaId: Long) {
        mutex.withLock {
            _libraryState.value = _libraryState.value.filter { it.id != mangaId }
        }
    }

    /**
     * Remove multiple manga from the in-memory library list.
     */
    suspend fun removeFromLibrary(mangaIds: List<Long>) {
        if (mangaIds.isEmpty()) return
        val idSet = mangaIds.toSet()
        mutex.withLock {
            _libraryState.value = _libraryState.value.filter { it.id !in idSet }
        }
    }

    private suspend fun refreshInternal(force: Boolean) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && (now - lastRefreshTime) < minRefreshIntervalMs) {
                logcat(LogPriority.DEBUG) {
                    "GetLibraryManga: Skipping refresh (too soon, ${now - lastRefreshTime}ms since last)"
                }
                return
            }

            _isLoading.value = true

            val paginated = paginationEnabled
            if (!paginated) {
                if (force) {
                    logcat(LogPriority.INFO) { "GetLibraryManga: Rebuilding library_cache table (forced)" }
                    mangaRepository.refreshLibraryCache()
                } else {
                    mangaRepository.invalidateLibraryCache()
                }
            }
            val startTime = System.currentTimeMillis()

            try {
                if (paginated) {
                    // Pages are loaded on demand by the UI per (category, content type). On refresh
                    // rebuild whatever the user had already scrolled through, otherwise start empty
                    // and let the visible categories request their first page.
                    if (loadedPageCount.isEmpty()) {
                        _libraryState.value = emptyList()
                    } else {
                        reloadLoadedPagesLocked()
                    }
                } else {
                    _libraryState.value = mangaRepository.getLibraryManga()
                }
                isInitialized = true
                lastRefreshTime = System.currentTimeMillis()

                val duration = System.currentTimeMillis() - startTime
                logcat(LogPriority.INFO) {
                    "GetLibraryManga: Refresh complete in ${duration}ms, ${_libraryState.value.size} items (paginated=$paginated)"
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "GetLibraryManga: Failed to refresh library" }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Whether the experimental per-category pagination mode is active. */
    fun isPaginationEnabled(): Boolean = paginationEnabled

    /**
     * Reconcile the desired per-(category, content type) page specs (global filters/search plus
     * that category's sort). If anything changed, fully reset pagination and reload the first page
     * of each key, otherwise just load the first page of any newly added key. Single entry point
     * that both kicks the initial load and reacts to filter/sort/search changes.
     */
    suspend fun applyPageSpecs(desired: Map<Pair<Long, Boolean>, LibraryPageSpec>) {
        if (!paginationEnabled) return
        val desiredKeys = desired.entries.associate { (k, v) -> PageKey(k.first, k.second) to v }
        mutex.withLock {
            if (desiredKeys != keySpec) {
                keySpec.clear()
                keySpec.putAll(desiredKeys)
                loadedPageCount.clear()
                pageHasMore.clear()
                _libraryState.value = emptyList()
                for (key in desiredKeys.keys) loadPageLocked(key)
            } else {
                for (key in desiredKeys.keys) {
                    if (!loadedPageCount.containsKey(key)) loadPageLocked(key)
                }
            }
            isInitialized = true
            _isLoading.value = false
        }
    }

    /**
     * Load the first page for a (category, content type) if nothing has been loaded yet.
     * Pager safety net. The spec must already be known via [applyPageSpecs].
     */
    suspend fun loadCategoryPageIfNeeded(categoryId: Long, isNovel: Boolean) {
        if (!paginationEnabled) return
        mutex.withLock {
            val key = PageKey(categoryId, isNovel)
            if (keySpec.containsKey(key) && !loadedPageCount.containsKey(key)) loadPageLocked(key)
        }
    }

    /**
     * Load the next page for a (category, content type). No-op once the category is exhausted.
     * Returns true if a page was actually fetched, false when there was nothing left to load.
     */
    suspend fun loadCategoryNextPage(categoryId: Long, isNovel: Boolean): Boolean {
        if (!paginationEnabled) return false
        return mutex.withLock {
            val key = PageKey(categoryId, isNovel)
            if (keySpec.containsKey(key) && pageHasMore[key] != false) {
                loadPageLocked(key)
                true
            } else {
                false
            }
        }
    }

    /** Caller must hold [mutex]. Loads the next unread page for [key] and appends deduped. */
    private suspend fun loadPageLocked(key: PageKey) {
        val loaded = loadedPageCount[key] ?: 0
        val size = pageSize
        // Fetch one extra row to peek past the page boundary: its presence means there is a next
        // page, and its absence means there isn't. Avoids reporting hasMore=true for an exactly
        // full final page (which would later fire a wasted empty load).
        val fetched = mangaRepository.getLibraryMangaPage(
            categoryId = key.categoryId,
            isNovel = key.isNovel,
            limit = size.toLong() + 1,
            offset = loaded.toLong() * size,
            spec = keySpec[key] ?: LibraryPageSpec(),
        )
        val hasMore = fetched.size > size
        val page = if (hasMore) fetched.take(size) else fetched
        loadedPageCount[key] = loaded + 1
        pageHasMore[key] = hasMore
        if (page.isNotEmpty()) {
            val existing = _libraryState.value.mapTo(HashSet(_libraryState.value.size)) { it.id }
            val fresh = page.filterNot { it.id in existing }
            if (fresh.isNotEmpty()) _libraryState.value = _libraryState.value + fresh
        }
        isInitialized = true
        _isLoading.value = false
    }

    /** Caller must hold [mutex]. Rebuilds the resident list from all previously loaded pages. */
    private suspend fun reloadLoadedPagesLocked() {
        val size = pageSize
        val keys = loadedPageCount.toMap()
        val rebuilt = ArrayList<LibraryManga>()
        val seen = HashSet<Long>()
        for ((key, count) in keys) {
            val total = count.toLong() * size
            // Same N+1 peek as loadPageLocked, scaled to the number of pages already loaded.
            val fetched = mangaRepository.getLibraryMangaPage(
                categoryId = key.categoryId,
                isNovel = key.isNovel,
                limit = total + 1,
                offset = 0,
                spec = keySpec[key] ?: LibraryPageSpec(),
            )
            pageHasMore[key] = fetched.size > total
            val page = if (fetched.size > total) fetched.take(total.toInt()) else fetched
            for (m in page) if (seen.add(m.id)) rebuilt.add(m)
        }
        _libraryState.value = rebuilt
    }

    /**
     * Get a lightweight list of library manga for update filtering.
     * This query is faster as it skips heavy fields like description, genre, etc.
     */
    suspend fun awaitForUpdate(): List<LibraryMangaForUpdate> {
        return mangaRepository.getLibraryMangaForUpdate()
    }

    /**
     * Get only genres for tag counting - much faster than await().
     * This avoids the expensive libraryView JOIN and only fetches _id + genre from mangas table.
     */
    suspend fun awaitGenresOnly(): List<Pair<Long, List<String>?>> {
        return mangaRepository.getFavoriteGenres()
    }

    /**
     * Get genres with source ID for tag counting filtered by content type.
     * This avoids the expensive libraryView JOIN and only fetches _id + source + genre from mangas table.
     */
    suspend fun awaitGenresWithSource(): List<Triple<Long, Long, List<String>?>> {
        return mangaRepository.getFavoriteGenresWithSource()
    }

    /**
     * Memory-safe tag counting: aggregates in the DB layer instead of loading every favorite's
     * genres into memory at once. Returns (rawTag -> count) and the no-tags count.
     */
    suspend fun awaitGenreTagCounts(
        novelSourceIds: Set<Long>,
        wantNovel: Boolean?,
    ): Pair<Map<String, Int>, Int> {
        return mangaRepository.getFavoriteGenreTagCounts(novelSourceIds, wantNovel)
    }

    /**
     * Get just the distinct source IDs from favorites - ultra-lightweight for extension listing.
     * This avoids the expensive libraryView JOIN and only fetches source IDs.
     */
    suspend fun awaitSourceIds(): List<Long> {
        return mangaRepository.getFavoriteSourceIds()
    }

    /**
     * Subscribe to library changes. Returns a StateFlow that is ONLY updated when refresh() is called.
     * This prevents SQLDelight from re-running the expensive query on every table change.
     */
    fun subscribe(): Flow<List<LibraryManga>> {
        return _libraryState.asStateFlow()
    }
}
