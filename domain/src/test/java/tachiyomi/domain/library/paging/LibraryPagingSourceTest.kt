package tachiyomi.domain.library.paging

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class LibraryPagingSourceTest {

    private fun row(id: Long) = LibraryManga(
        manga = Manga.create().copy(id = id),
        categories = listOf(0L),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    private fun sourceOver(rows: () -> List<Long>) = LibraryPagingSource { limit, offset ->
        LibraryPagingSource.Chunk.of(rows().drop(offset).take(limit).map(::row), limit, offset)
    }

    private suspend fun PagingSource<Int, LibraryManga>.page(params: PagingSource.LoadParams<Int>) =
        load(params).shouldBeInstanceOf<PagingSource.LoadResult.Page<Int, LibraryManga>>()

    private fun append(key: Int, size: Int) = PagingSource.LoadParams.Append(key, size, false)

    @Test
    fun `appends by offset and stops on a short page`() = runTest {
        val source = sourceOver { (1L..5L).toList() }

        val first = source.page(PagingSource.LoadParams.Refresh(null, 2, false))
        first.data.map { it.id } shouldContainExactly listOf(1L, 2L)
        first.prevKey shouldBe null
        first.nextKey shouldBe 2

        val last = source.page(append(4, 2))
        last.data.map { it.id } shouldContainExactly listOf(5L)
        last.nextKey shouldBe null
        last.prevKey shouldBe 4
    }

    @Test
    fun `prepend loads the rows before the key`() = runTest {
        val source = sourceOver { (1L..10L).toList() }

        val page = source.page(PagingSource.LoadParams.Prepend(3, 5, false))

        page.data.map { it.id } shouldContainExactly listOf(1L, 2L, 3L)
        page.prevKey shouldBe null
    }

    @Test
    fun `a row that moved to a later page is shown once`() = runTest {
        var rows = listOf(1L, 2L, 3L, 4L)
        val source = sourceOver { rows }

        source.page(PagingSource.LoadParams.Refresh(null, 2, false)).data.map { it.id } shouldContainExactly
            listOf(1L, 2L)
        // Entry 1 got a new sort key and now sorts after 3, without an invalidation yet.
        rows = listOf(2L, 3L, 1L, 4L)

        source.page(append(2, 2)).data.map { it.id } shouldContainExactly listOf(4L)
    }

    @Test
    fun `a dropped page loaded again keeps its rows`() = runTest {
        val source = sourceOver { (1L..6L).toList() }

        source.page(PagingSource.LoadParams.Refresh(null, 2, false))
        source.page(append(2, 2))

        source.page(PagingSource.LoadParams.Prepend(2, 2, false)).data.map { it.id } shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `the next key comes from the fetched chunk`() = runTest {
        val source = LibraryPagingSource { _, offset ->
            LibraryPagingSource.Chunk(listOf(row(offset + 1L)), nextOffset = offset + 3)
        }

        val page = source.page(PagingSource.LoadParams.Refresh(null, 3, false))

        page.data.map { it.id } shouldContainExactly listOf(1L)
        page.nextKey shouldBe 3
    }

    @Test
    fun `fetch errors become load errors`() = runTest {
        val source = LibraryPagingSource { _, _ -> error("boom") }

        source.load(PagingSource.LoadParams.Refresh(null, 2, false))
            .shouldBeInstanceOf<PagingSource.LoadResult.Error<Int, LibraryManga>>()
    }

    @Test
    fun `refresh key centers the initial load on the anchored row`() {
        val source = sourceOver { emptyList() }
        val pages = listOf(
            PagingSource.LoadResult.Page(data = (1L..20L).map(::row), prevKey = null, nextKey = 20),
            PagingSource.LoadResult.Page(data = (21L..40L).map(::row), prevKey = 20, nextKey = 40),
        )
        val config = PagingConfig(pageSize = 20, initialLoadSize = 20)
        val state = PagingState(pages, anchorPosition = 30, config = config, leadingPlaceholderCount = 0)

        source.getRefreshKey(state) shouldBe 20
    }

    @Test
    fun `refreshing from the refresh key keeps the same anchor`() = runTest {
        val source = sourceOver { (1L..200L).toList() }
        val config = PagingConfig(pageSize = 20, initialLoadSize = 40)
        val first = source.page(PagingSource.LoadParams.Refresh(100, 40, false))
        val state = PagingState(listOf(first), anchorPosition = 20, config = config, leadingPlaceholderCount = 0)

        val key = source.getRefreshKey(state)!!
        val second = source.page(PagingSource.LoadParams.Refresh(key, 40, false))

        key shouldBe 100
        second.data.map { it.id } shouldContainExactly first.data.map { it.id }
    }

    @Test
    fun `a refresh past the new end restarts from the top`() = runTest {
        val source = sourceOver { (1L..5L).toList() }

        val page = source.page(PagingSource.LoadParams.Refresh(50, 4, false))

        page.data.map { it.id } shouldContainExactly listOf(1L, 2L, 3L, 4L)
        page.prevKey shouldBe null
    }

    @Test
    fun `a far away row is tracked again once the window moved on`() = runTest {
        val source = sourceOver { (1L..200_000L).toList() }

        source.page(PagingSource.LoadParams.Refresh(null, 60_000, false))
        val far = source.page(append(150_000, 10))
        val back = source.page(PagingSource.LoadParams.Refresh(0, 10, false))

        far.data.map { it.id }.first() shouldBe 150_001L
        back.data.map { it.id } shouldContainExactly (1L..10L).toList()
    }
}
