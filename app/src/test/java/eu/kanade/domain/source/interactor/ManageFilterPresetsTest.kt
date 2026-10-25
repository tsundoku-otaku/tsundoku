package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.model.FilterPresetList
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class ManageFilterPresetsTest {

    private class Flag(name: String, state: Boolean = false) : Filter.CheckBox(name, state)

    private var stored = FilterPresetList()
    private var storedQueries = emptyMap<Long, String>()

    private val manage = run {
        val pref = mockk<Preference<FilterPresetList>>(relaxed = true)
        every { pref.get() } answers { stored }
        val written = slot<FilterPresetList>()
        every { pref.set(capture(written)) } answers { stored = written.captured }
        val queries = mockk<Preference<Map<Long, String>>>(relaxed = true)
        every { queries.get() } answers { storedQueries }
        val writtenQueries = slot<Map<Long, String>>()
        every { queries.set(capture(writtenQueries)) } answers { storedQueries = writtenQueries.captured }
        val prefs = mockk<SourcePreferences>(relaxed = true)
        every { prefs.filterPresets(any()) } returns pref
        every { prefs.filterPresetQueries(any()) } returns queries
        ManageFilterPresets(prefs)
    }

    private fun filters(on: Boolean) = FilterList(Flag("a", on))

    @Test
    fun `saved preset keeps its search query`() {
        manage.savePreset(1L, "p", filters(true), searchQuery = "query")

        assertEquals("query", manage.getPresets(1L).presets.single().searchQuery)
    }

    @Test
    fun `saved preset restores filter state through getPreset`() {
        manage.savePreset(1L, "p", filters(true))
        val preset = manage.getPreset(1L, manage.getPresets(1L).presets.single().id)!!

        val target = filters(false)
        ManageFilterPresets.applyPresetState(target, preset.filterState)

        assertTrue((target[0] as Flag).state)
    }

    @Test
    fun `getPreset returns null for an unknown id`() {
        assertNull(manage.getPreset(1L, 42L))
    }

    @Test
    fun `toggling a preset as default keeps its search query`() {
        manage.savePreset(1L, "p", filters(true), searchQuery = "query")
        val id = manage.getPresets(1L).presets.single().id

        manage.setDefaultPreset(1L, id)
        assertTrue(manage.getPresets(1L).presets.single().isDefault)
        manage.setDefaultPreset(1L, null)

        assertEquals("query", manage.getPresets(1L).presets.single().searchQuery)
    }

    @Test
    fun `search queries are stored apart from the presets and dropped on delete`() {
        manage.savePreset(1L, "p", filters(true), searchQuery = "query")
        val id = manage.getPresets(1L).presets.single().id

        assertNull(stored.presets.single().searchQuery)
        assertEquals(mapOf(id to "query"), storedQueries)

        manage.deletePreset(1L, id)

        assertTrue(storedQueries.isEmpty())
    }

    @Test
    fun `saving a default preset unsets the previous default`() {
        manage.savePreset(1L, "a", filters(true), setAsDefault = true)
        manage.savePreset(1L, "b", filters(true), setAsDefault = true)

        val presets = manage.getPresets(1L).presets
        assertFalse(presets.first { it.name == "a" }.isDefault)
        assertTrue(presets.first { it.name == "b" }.isDefault)
    }
}
