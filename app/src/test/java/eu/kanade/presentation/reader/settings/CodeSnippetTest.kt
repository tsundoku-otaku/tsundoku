package eu.kanade.presentation.reader.settings

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeSnippetTest {

    @Test
    fun `decodes legacy json without runOnAppend to default false`() {
        val legacy = """[{"title":"a","code":"x","enabled":true}]"""
        val snippets = Json.decodeFromString<List<CodeSnippet>>(legacy)
        assertEquals(1, snippets.size)
        assertFalse(snippets[0].runOnAppend)
        assertTrue(snippets[0].enabled)
    }

    @Test
    fun `runOnAppend survives an encode-decode roundtrip`() {
        val original = listOf(
            CodeSnippet(title = "a", code = "x", enabled = true, runOnAppend = true),
            CodeSnippet(title = "b", code = "y", enabled = false, runOnAppend = false),
        )
        val roundTripped = Json.decodeFromString<List<CodeSnippet>>(Json.encodeToString(original))
        assertEquals(original, roundTripped)
    }

    @Test
    fun `new snippet defaults runOnAppend to false`() {
        assertFalse(CodeSnippet(title = "a", code = "x").runOnAppend)
    }
}
