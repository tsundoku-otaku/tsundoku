package tachiyomi.domain.library.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.library.model.LibraryManga

/**
 * Offset-keyed source over one library query generation. A row whose sort key changed between two
 * loads can come back at a second offset; only its first position is kept, since lazy lists crash
 * on duplicate keys.
 */
class LibraryPagingSource(
    private val fetch: suspend (limit: Int, offset: Int) -> Chunk,
) : PagingSource<Int, LibraryManga>() {

    class Chunk(val rows: List<LibraryManga>, val nextOffset: Int?) {
        companion object {
            fun of(rows: List<LibraryManga>, limit: Int, offset: Int) =
                Chunk(rows, if (rows.size < limit) null else offset + rows.size)
        }
    }

    private val firstSeenAt = HashMap<Long, Int>()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LibraryManga> {
        val key = params.key ?: 0
        var (offset, limit) = when (params) {
            is LoadParams.Prepend -> (key - params.loadSize).coerceAtLeast(0).let { it to key - it }
            else -> key to params.loadSize
        }
        if (limit <= 0) return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)

        val chunk = try {
            fetch(limit, offset).takeUnless { params is LoadParams.Refresh && it.rows.isEmpty() && offset > 0 }
                // Rows were deleted past the saved position, so restart from the top instead of showing nothing.
                ?: fetch(limit, 0).also {
                    offset = 0
                    synchronized(firstSeenAt) { firstSeenAt.clear() }
                }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return LoadResult.Error(e)
        }

        val data = synchronized(firstSeenAt) {
            if (firstSeenAt.size > MAX_TRACKED_ROWS) {
                firstSeenAt.values.removeAll {
                    it < offset - MAX_TRACKED_ROWS / 2 || it > offset + MAX_TRACKED_ROWS / 2
                }
            }
            chunk.rows.filterIndexed { index, row ->
                val at = offset + index
                val seen = firstSeenAt.getOrPut(row.id) { at }
                // A dropped page loaded again keeps its rows.
                seen in offset until offset + limit
            }
        }

        return LoadResult.Page(
            data = data,
            prevKey = offset.takeIf { it > 0 },
            nextKey = chunk.nextOffset,
        )
    }

    private companion object {
        const val MAX_TRACKED_ROWS = 50_000
    }

    override fun getRefreshKey(state: PagingState<Int, LibraryManga>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        val loadedBefore = state.pages.takeWhile { it !== page }.sumOf { it.data.size }
        val anchorOffset = (page.prevKey ?: 0) + (anchor - loadedBefore)
        return (anchorOffset - state.config.initialLoadSize / 2).coerceAtLeast(0)
    }
}
