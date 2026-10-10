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

    private fun item(mangaId: Long, chapterId: Long, dateFetch: Long) = UpdatesItem(
        update = UpdatesWithRelations(
            mangaId = mangaId,
            mangaTitle = "Novel $mangaId",
            chapterId = chapterId,
            chapterName = "Chapter $chapterId",
            scanlator = null,
            chapterUrl = "/c/$chapterId",
            read = false,
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
    fun `chapter count only covers the latest update day`() {
        val state = UpdatesViewModel.State(
            items = listOf(
                item(1, 1, base + 3 * day),
                item(1, 2, base + 3 * day),
                item(1, 3, base + day),
                item(1, 4, base + day),
                item(1, 5, base),
            ),
        )

        val group = state.getNovelGroups().single()

        assertEquals(2, group.chapterCount)
        assertEquals(5, group.chapters.size)
    }

    @Test
    fun `groups are counted independently per novel`() {
        val state = UpdatesViewModel.State(
            items = listOf(
                item(1, 1, base + 2 * day),
                item(2, 2, base + day),
                item(2, 3, base + day),
                item(2, 4, base),
            ),
        )

        val groups = state.getNovelGroups().associateBy { it.mangaId }

        assertEquals(1, groups.getValue(1).chapterCount)
        assertEquals(2, groups.getValue(2).chapterCount)
    }
}
