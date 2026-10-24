package eu.kanade.domain.source.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FilterPresetTest {

    @Test
    fun `preset json without searchQuery decodes with null query`() {
        val legacy = """{"presets":[{"id":1,"sourceId":2,"name":"a","filterState":"{}","isDefault":true}]}"""

        val preset = Json.decodeFromString<FilterPresetList>(legacy).presets.single()

        assertNull(preset.searchQuery)
        assertEquals("a", preset.name)
    }

    @Test
    fun `preset json round trips searchQuery`() {
        val list = FilterPresetList(listOf(FilterPreset(1, 2, "a", "{}", searchQuery = "query")))

        val decoded = Json.decodeFromString<FilterPresetList>(Json.encodeToString(FilterPresetList.serializer(), list))

        assertEquals("query", decoded.presets.single().searchQuery)
    }
}
