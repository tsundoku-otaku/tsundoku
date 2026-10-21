package tachiyomi.domain.library.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.library.model.LibraryManga

/**
 * Offset-keyed source over one library query generation. Keys are row offsets: an append or refresh
 * key is where the page starts, a prepend key is where the already loaded rows start.
 *
 * No count query: the end is reached when a page comes back short. A row whose sort key changed
 * between two page loads can come back at a second offset; only its first position is kept, since
 * lazy lists crash on duplicate keys. The next invalidation reloads a consistent snapshot.
 */
class LibraryPagingSource(
    private val fetch: suspend (limit: Int, offset: Int) -> List<LibraryManga>,
) : PagingSource<Int, LibraryManga>() {

    // id -> offset it was first loaded at
    private val firstSeenAt = HashMap<Long, Int>()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LibraryManga> {
        val key = params.key ?: 0
        val (offset, limit) = when (params) {
            is LoadParams.Prepend -> (key - params.loadSize).coerceAtLeast(0).let { it to key - it }
            else -> key to params.loadSize
        }
        if (limit <= 0) return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)

        val rows = try {
            fetch(limit, offset)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return LoadResult.Error(e)
        }

        val data = synchronized(firstSeenAt) {
            rows.filterIndexed { index, row ->
                val at = offset + index
                val seen = firstSeenAt.getOrPut(row.id) { at }
                // A dropped page loaded again keeps its rows.
                seen in offset until offset + limit
            }
        }

        return LoadResult.Page(
            data = data,
            prevKey = offset.takeIf { it > 0 },
            nextKey = if (rows.size < limit) null else offset + rows.size,
        )
    }

    override fun getRefreshKey(state: PagingState<Int, LibraryManga>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        // Start a bit above the anchor so the reloaded window still covers what was on screen.
        val pageStart = page.prevKey ?: 0
        return (pageStart - state.config.pageSize / 2).coerceAtLeast(0)
    }
}
