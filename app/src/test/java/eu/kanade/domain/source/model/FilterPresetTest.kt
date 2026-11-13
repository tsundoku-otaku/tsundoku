package eu.kanade.domain.source.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `preset json leaves searchQuery out so older builds still decode it`() {
        val list = FilterPresetList(listOf(FilterPreset(1, 2, "a", "{}", searchQuery = "query")))

        val encoded = Json.encodeToString(FilterPresetList.serializer(), list)

        assertFalse("searchQuery" in encoded)
        assertNull(Json.decodeFromString<FilterPresetList>(encoded).presets.single().searchQuery)
    }
}
