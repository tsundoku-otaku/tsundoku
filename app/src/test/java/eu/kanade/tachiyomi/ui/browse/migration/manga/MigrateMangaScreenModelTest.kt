package eu.kanade.tachiyomi.ui.browse.migration.manga

import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.Source
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class MigrateMangaScreenModelTest {

    private val slashSource: Source = mockk<JsSource>(relaxed = true)
    private val plainSource: Source = mockk<Source>(relaxed = true)

    private fun manga(id: Long, url: String) = Manga.create().copy(id = id, url = url)

    @Test
    fun `normalize adds a single leading slash for a source that uses the convention`() {
        assertEquals("/series/a", normalizeQuickMigrateUrl("series/a", slashSource))
        assertEquals("/series/a", normalizeQuickMigrateUrl("/series/a", slashSource))
    }

    @Test
    fun `normalize leaves the url untouched for a plain source`() {
        assertEquals("series/a", normalizeQuickMigrateUrl("series/a", plainSource))
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

        val targets = quickMigrateTargets(selected, existing, slashSource)

        assertEquals(listOf(1L, 3L), targets.map { it.first.id })
        assertEquals(listOf("/series/a", "/series/c"), targets.map { it.second })
    }

    @Test
    fun `duplicate detection matches regardless of leading slash on the selected url`() {
        val selected = listOf(manga(1, "series/a"))
        val existing = setOf("/series/a")

        assertEquals(emptyList<Pair<Manga, String>>(), quickMigrateTargets(selected, existing, slashSource))
    }

    @Test
    fun `empty favorites keeps every selected entry`() {
        val selected = listOf(manga(1, "a"), manga(2, "b"))

        assertEquals(2, quickMigrateTargets(selected, emptySet(), slashSource).size)
        assertEquals(emptyList<Manga>(), quickMigrateSkipped(selected, emptySet(), slashSource))
    }

    @Test
    fun `skipped is exactly what targets leaves out`() {
        val selected = listOf(
            manga(1, "series/a"),
            manga(2, "/series/b"),
            manga(3, "series/c"),
        )
        val existing = setOf("/series/b", "/series/c")

        val targets = quickMigrateTargets(selected, existing, slashSource)
        val skipped = quickMigrateSkipped(selected, existing, slashSource)

        assertEquals(listOf(1L), targets.map { it.first.id })
        assertEquals(listOf(2L, 3L), skipped.map { it.id })
        assertEquals(selected.size, targets.size + skipped.size)
    }
}
