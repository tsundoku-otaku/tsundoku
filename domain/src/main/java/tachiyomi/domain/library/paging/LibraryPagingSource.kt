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
        val (offset, limit) = when (params) {
            is LoadParams.Prepend -> (key - params.loadSize).coerceAtLeast(0).let { it to key - it }
            else -> key to params.loadSize
        }
        if (limit <= 0) return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)

        val chunk = try {
            fetch(limit, offset)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return LoadResult.Error(e)
        }

        val data = synchronized(firstSeenAt) {
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

    override fun getRefreshKey(state: PagingState<Int, LibraryManga>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        val pageStart = page.prevKey ?: 0
        return (pageStart - state.config.pageSize / 2).coerceAtLeast(0)
    }
}
