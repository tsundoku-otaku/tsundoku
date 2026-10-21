package eu.kanade.tachiyomi.ui.library

import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibraryPageSpec
import tachiyomi.domain.library.paging.coalesceInvalidations
import tachiyomi.domain.manga.interactor.GetLibraryMangaPaged
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How one category of the paged library is loaded. Equality is by [key] only, so rebuilding a plan
 * from unchanged inputs keeps the existing pager.
 */
class LibraryPagedPlan(
    private val key: Any,
    val categoryId: Long,
    val isNovel: Boolean?,
    val spec: LibraryPageSpec,
    /** Sort or search the DB can't express: the category is loaded whole by [loadInMemory]. */
    val inMemory: Boolean,
    /** In-memory filters left after the SQL ones; null when SQL already filters everything. */
    val filter: ((LibraryManga) -> Boolean)?,
    val loadInMemory: suspend () -> List<LibraryManga>,
) {
    /** The SQL count matches what is shown. */
    val hasExactCount: Boolean get() = !inMemory && filter == null

    override fun equals(other: Any?): Boolean = other is LibraryPagedPlan && other.key == key

    override fun hashCode(): Int = key.hashCode()
}

/**
 * Owns the Paging 3 flows of one library screen. Pagers are kept for the few most recently shown
 * categories only, and table changes reach them coalesced and only while the screen is visible.
 */
class LibraryPagingController(
    private val scope: CoroutineScope,
    private val getLibraryMangaPaged: GetLibraryMangaPaged,
) {
    private val active = MutableStateFlow(false)

    val invalidations: SharedFlow<Unit> = getLibraryMangaPaged.changes()
        .coalesceInvalidations(active, INVALIDATION_WINDOW)
        .shareIn(scope, SharingStarted.Eagerly)

    private class Entry(val plan: LibraryPagedPlan, val scope: CoroutineScope, val flow: Flow<PagingData<LibraryManga>>)

    private val entries = LinkedHashMap<Long, Entry>(MAX_PAGERS + 1, 0.75f, true)

    init {
        scope.launch(Dispatchers.IO) { getLibraryMangaPaged.ensureIndexes() }
    }

    fun setActive(isActive: Boolean) {
        active.value = isActive
    }

    fun flowFor(plan: LibraryPagedPlan): Flow<PagingData<LibraryManga>> = synchronized(entries) {
        entries[plan.categoryId]?.takeIf { it.plan == plan }?.let { return it.flow }
        entries.remove(plan.categoryId)?.scope?.cancel()
        val entryScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
        val entry = Entry(plan, entryScope, build(plan).cachedIn(entryScope))
        entries[plan.categoryId] = entry
        while (entries.size > MAX_PAGERS) {
            val eldest = entries.entries.first()
            entries.remove(eldest.key)
            eldest.value.scope.cancel()
        }
        entry.flow
    }

    private fun build(plan: LibraryPagedPlan): Flow<PagingData<LibraryManga>> {
        if (plan.inMemory) {
            // Each reload reads the whole category, so it follows changes more slowly.
            val slower = invalidations.coalesceInvalidations(active, IN_MEMORY_INVALIDATION_WINDOW)
            return getLibraryMangaPaged.subscribeLoaded(slower, plan.loadInMemory)
        }
        val paged = getLibraryMangaPaged.subscribe(plan.categoryId, plan.isNovel, plan.spec, invalidations)
        val filter = plan.filter ?: return paged
        return paged.map { data -> data.filter { filter(it) } }
    }

    companion object {
        // The pager composes the current category and its two neighbours; one spare keeps a page that
        // is still on screen during a swipe from being evicted.
        private const val MAX_PAGERS = 4
        private val INVALIDATION_WINDOW = 750.milliseconds
        private val IN_MEMORY_INVALIDATION_WINDOW = 5.seconds
    }
}
