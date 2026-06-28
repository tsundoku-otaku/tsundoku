package eu.kanade.tachiyomi.ui.browse.migration.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class MigrateMangaScreenModelTest {

    private fun manga(id: Long, url: String) = Manga.create().copy(id = id, url = url)

    @Test
    fun `normalize adds a single leading slash`() {
        assertEquals("/series/a", normalizeQuickMigrateUrl("series/a"))
        assertEquals("/series/a", normalizeQuickMigrateUrl("/series/a"))
    }

    @Test
    fun `targets drop entries already favorited on the target source`() {
        val selected = listOf(
            manga(1, "series/a"),
            manga(2, "/series/b"),
            manga(3, "series/c"),
        )
        // Target favorite urls are stored normalized (leading slash).
        val existing = setOf("/series/b")

        val targets = quickMigrateTargets(selected, existing)

        assertEquals(listOf(1L, 3L), targets.map { it.first.id })
        assertEquals(listOf("/series/a", "/series/c"), targets.map { it.second })
    }

    @Test
    fun `duplicate detection matches regardless of leading slash on the selected url`() {
        val selected = listOf(manga(1, "series/a"))
        val existing = setOf("/series/a")

        assertEquals(emptyList<Pair<Manga, String>>(), quickMigrateTargets(selected, existing))
    }

    @Test
    fun `empty favorites keeps every selected entry`() {
        val selected = listOf(manga(1, "a"), manga(2, "b"))

        assertEquals(2, quickMigrateTargets(selected, emptySet()).size)
    }
}
