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
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibraryPageSpec
import tachiyomi.domain.library.paging.coalesceInvalidations
import tachiyomi.domain.manga.interactor.GetLibraryMangaPaged
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Equality is by key only, so a plan rebuilt from unchanged inputs keeps its pager.
class LibraryPagedPlan(
    private val key: Any,
    val categoryId: Long,
    val isNovel: Boolean?,
    val spec: LibraryPageSpec,
    val inMemory: Boolean,
    val filter: ((LibraryManga) -> Boolean)?,
    val reloadOnDownloads: Boolean,
    val loadInMemory: suspend () -> List<LibraryManga>,
) {
    val hasExactCount: Boolean get() = !inMemory && filter == null

    override fun equals(other: Any?): Boolean = other is LibraryPagedPlan && other.key == key

    override fun hashCode(): Int = key.hashCode()
}

class LibraryPagingController(
    private val scope: CoroutineScope,
    private val getLibraryMangaPaged: GetLibraryMangaPaged,
    downloadChanges: Flow<Unit>,
) {
    private val active = MutableStateFlow(false)

    val invalidations: SharedFlow<Unit> = getLibraryMangaPaged.changes()
        .coalesceInvalidations(active, INVALIDATION_WINDOW)
        .shareIn(scope, SharingStarted.Eagerly)

    private val downloadInvalidations: SharedFlow<Unit> = downloadChanges
        .coalesceInvalidations(active, INVALIDATION_WINDOW)
        .shareIn(scope, SharingStarted.Eagerly)

    private class Entry(val plan: LibraryPagedPlan, val scope: CoroutineScope, val flow: Flow<PagingData<LibraryManga>>)

    private val entries = LinkedHashMap<Long, Entry>(MAX_PAGERS + 1, 0.75f, true)

    init {
        scope.launch(Dispatchers.IO) { getLibraryMangaPaged.ensureIndexes() }
    }

    fun slowInvalidations(window: Duration, source: Flow<Unit> = invalidations): Flow<Unit> =
        source.coalesceInvalidations(active, window)

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
        val changes = if (plan.reloadOnDownloads) merge(invalidations, downloadInvalidations) else invalidations
        if (plan.inMemory) {
            return getLibraryMangaPaged.subscribeLoaded(
                slowInvalidations(IN_MEMORY_INVALIDATION_WINDOW, changes),
                plan.loadInMemory,
            )
        }
        val paged = getLibraryMangaPaged.subscribe(plan.categoryId, plan.isNovel, plan.spec, changes)
        val filter = plan.filter ?: return paged
        return paged.map { data -> data.filter { filter(it) } }
    }

    companion object {
        // Current page, its two neighbours, and one spare for a page still visible mid-swipe.
        private const val MAX_PAGERS = 4
        private val INVALIDATION_WINDOW = 750.milliseconds
        private val IN_MEMORY_INVALIDATION_WINDOW = 5.seconds
    }
}
