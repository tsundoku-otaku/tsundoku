package eu.kanade.tachiyomi.ui.updates

import eu.kanade.tachiyomi.data.download.model.Download
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import java.util.concurrent.TimeUnit

class UpdatesNovelGroupsTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val base = 1_700_000_000_000L

    private fun item(mangaId: Long, chapterId: Long, dateFetch: Long, read: Boolean = false) = UpdatesItem(
        update = UpdatesWithRelations(
            mangaId = mangaId,
            mangaTitle = "Novel $mangaId",
            chapterId = chapterId,
            chapterName = "Chapter $chapterId",
            scanlator = null,
            chapterUrl = "/c/$chapterId",
            read = read,
            bookmark = false,
            lastPageRead = 0,
            sourceId = 1L,
            dateFetch = dateFetch,
            coverData = MangaCover(mangaId, 1L, true, null, 0L),
        ),
        downloadStateProvider = { Download.State.NOT_DOWNLOADED },
        downloadProgressProvider = { 0 },
    )

    @Test
    fun `chapter count only covers the latest update batch`() {
        val state = UpdatesViewModel.State(
            items = listOf(
                item(1, 1, base + 3 * day + 2),
                item(1, 2, base + 3 * day + 1),
                item(1, 3, base + day + 2),
                item(1, 4, base + day + 1),
                item(1, 5, base),
            ),
        )

        val group = state.getNovelGroups().single()

        assertEquals(2, group.chapterCount)
        assertEquals(5, group.chapters.size)
    }

    @Test
    fun `batch crossing midnight is counted once`() {
        val midnight = base - base % day + day
        val state = UpdatesViewModel.State(
            items = listOf(
                item(1, 1, midnight + 1),
                item(1, 2, midnight),
                item(1, 3, midnight - 1),
                item(1, 4, midnight - day),
            ),
        )

        assertEquals(3, state.getNovelGroups().single().chapterCount)
    }

    @Test
    fun `read chapters of the latest batch are still counted`() {
        val state = UpdatesViewModel.State(
            items = listOf(
                item(1, 1, base + 3, read = true),
                item(1, 2, base + 2),
                item(1, 3, base + 1),
            ),
        )

        assertEquals(3, state.getNovelGroups().single().chapterCount)
    }

    @Test
    fun `refreshes seconds apart are separate batches`() {
        val state = UpdatesViewModel.State(
            items = listOf(
                item(1, 1, base + 10_001),
                item(1, 2, base + 10_000),
                item(1, 3, base + 1),
                item(1, 4, base),
            ),
        )

        assertEquals(2, state.getNovelGroups().single().chapterCount)
    }

    @Test
    fun `chapters out of fetch order still count the latest batch`() {
        val group = UpdatesNovelGroup(
            mangaId = 1,
            mangaTitle = "",
            coverUrl = null,
            sourceId = 1L,
            latestChapterDate = base + day + 1,
            chapters = listOf(
                item(1, 1, base),
                item(1, 2, base + day + 1),
                item(1, 3, base + day),
            ),
        )

        assertEquals(2, group.chapterCount)
    }

    @Test
    fun `groups are counted independently per novel`() {
        val state = UpdatesViewModel.State(
            items = listOf(
                item(1, 1, base + 2 * day),
                item(2, 2, base + day + 1),
                item(2, 3, base + day),
                item(2, 4, base),
            ),
        )

        val groups = state.getNovelGroups().associateBy { it.mangaId }

        assertEquals(1, groups.getValue(1).chapterCount)
        assertEquals(2, groups.getValue(2).chapterCount)
    }
}
