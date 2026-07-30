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

    @Test
    fun `legacy json missing id gets a deterministic id, same content yields same id`() {
        val legacy = """[{"title":"a","code":"x","enabled":true}]"""
        val first = Json.decodeFromString<List<CodeSnippet>>(legacy)[0].id
        val second = Json.decodeFromString<List<CodeSnippet>>(legacy)[0].id
        assertEquals(first, second)
    }

    @Test
    fun `legacy entries with different content get different ids`() {
        val a = Json.decodeFromString<List<CodeSnippet>>("""[{"title":"a","code":"x"}]""")[0].id
        val b = Json.decodeFromString<List<CodeSnippet>>("""[{"title":"b","code":"y"}]""")[0].id
        assertTrue(a != b)
    }

    @Test
    fun `id present in json is preserved as-is`() {
        val json = """[{"title":"a","code":"x","id":"custom-id"}]"""
        val snippet = Json.decodeFromString<List<CodeSnippet>>(json)[0]
        assertEquals("custom-id", snippet.id)
    }

    @Test
    fun `safeTitle replaces disallowed characters with a dash`() {
        assertEquals("Fix-Chapter-Title-", safeTitleOf("Fix Chapter Title!"))
        assertEquals("already_safe-1.2", safeTitleOf("already_safe-1.2"))
    }

    @Test
    fun `distinct titles can sanitize to the same safe title`() {
        assertEquals(safeTitleOf("Fix!"), safeTitleOf("Fix?"))
    }
}
