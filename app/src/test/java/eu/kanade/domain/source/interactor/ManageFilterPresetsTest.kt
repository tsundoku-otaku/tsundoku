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

    private class Choice(name: String, values: Array<String>, state: Int = 0) : Filter.Select<String>(name, values, state)

    private class Order(values: Array<String>, state: Selection? = null) : Filter.Sort("Order", values, state)

    private class Tags(items: List<Flag>) : Filter.Group<Flag>("Tags", items)

    private fun restore(saved: FilterList, target: FilterList) {
        ManageFilterPresets.applyPresetState(target, ManageFilterPresets.serializeFilters(saved))
    }

    @Test
    fun `restores by name when filters were reordered`() {
        val target = FilterList(Flag("b"), Flag("a"))

        restore(FilterList(Flag("a", true), Flag("b", false)), target)

        assertTrue((target[1] as Flag).state)
        assertFalse((target[0] as Flag).state)
    }

    @Test
    fun `skips a saved filter the source no longer has`() {
        val target = FilterList(Flag("b"))

        restore(FilterList(Flag("a", true), Flag("b", true)), target)

        assertTrue((target[0] as Flag).state)
    }

    @Test
    fun `select restores its option by name`() {
        val target = FilterList(Choice("Status", arrayOf("Hiatus", "Ongoing", "Done")))

        restore(FilterList(Choice("Status", arrayOf("All", "Ongoing", "Done"), 1)), target)

        assertEquals(1, (target[0] as Choice).state)
    }

    @Test
    fun `select ignores an option that no longer exists`() {
        val target = FilterList(Choice("Status", arrayOf("All", "Done")))

        restore(FilterList(Choice("Status", arrayOf("All", "Done", "Dropped"), 2)), target)

        assertEquals(0, (target[0] as Choice).state)
    }

    @Test
    fun `legacy select index is clamped to the option range`() {
        val legacy = """[{"index":0,"name":"Status","state":7}]"""
        val target = FilterList(Choice("Status", arrayOf("All", "Done")))

        ManageFilterPresets.applyPresetState(target, legacy)

        assertEquals(0, (target[0] as Choice).state)
    }

    @Test
    fun `sort index outside the options is ignored`() {
        val target = FilterList(Order(arrayOf("Name")))

        restore(FilterList(Order(arrayOf("Name", "Date", "Rank"), Filter.Sort.Selection(2, false))), target)

        assertNull((target[0] as Order).state)
    }

    @Test
    fun `unset sort stays unset`() {
        val target = FilterList(Order(arrayOf("Name", "Date"), Filter.Sort.Selection(1, false)))

        restore(FilterList(Order(arrayOf("Name", "Date"))), target)

        assertNull((target[0] as Order).state)
    }

    @Test
    fun `one mismatched entry does not stop later filters`() {
        val legacy = """[{"index":0,"name":"Tags","state":null},{"index":1,"name":"a","state":true}]"""
        val target = FilterList(Tags(listOf(Flag("x"))), Flag("a"))

        ManageFilterPresets.applyPresetState(target, legacy)

        assertTrue((target[1] as Flag).state)
    }

    @Test
    fun `select with duplicate option names keeps the saved option`() {
        val target = FilterList(Choice("Status", arrayOf("Any", "Done", "Done")))

        restore(FilterList(Choice("Status", arrayOf("Any", "Done", "Done"), 2)), target)

        assertEquals(2, (target[0] as Choice).state)
    }

    @Test
    fun `select options without their own names fall back to the saved index`() {
        val saved = """[{"index":0,"name":"Genre","state":1,"value":"com.example.Genre@1a2b3c"}]"""
        val target = FilterList(Choice("Genre", arrayOf("a", "b")))

        ManageFilterPresets.applyPresetState(target, saved)

        assertEquals(1, (target[0] as Choice).state)
    }

    @Test
    fun `a header does not take the slot of a same-named filter`() {
        val target = FilterList(Filter.Header("Tags"), Tags(listOf(Flag("x"))))

        restore(FilterList(Filter.Header("Tags"), Tags(listOf(Flag("x", true)))), target)

        assertTrue((target[1] as Tags).state.single().state)
    }

    @Test
    fun `group items restore by name`() {
        val target = FilterList(Tags(listOf(Flag("y"), Flag("x"))))

        restore(FilterList(Tags(listOf(Flag("x", true), Flag("y")))), target)

        val items = (target[0] as Tags).state
        assertTrue(items[1].state)
        assertFalse(items[0].state)
    }
}
