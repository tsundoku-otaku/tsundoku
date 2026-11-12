package tachiyomi.source.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalNovelEntryResolverTest {

    private data class Entry(val name: String?, val isDirectory: Boolean)

    private val folder = Entry("Folder Tale", isDirectory = true)
    private val epub = Entry("Lantern Harbor.epub", isDirectory = false)
    private val other = Entry("Moss and Ember.epub", isDirectory = false)
    private val entries = listOf(folder, epub, other)

    private fun resolve(url: String, list: List<Entry> = entries) =
        resolveLocalNovelEntry(url, list, { it.name }, { it.isDirectory })

    @Test
    fun `resolves a novel folder by its exact name`() {
        assertEquals(folder, resolve("Folder Tale"))
    }

    @Test
    fun `resolves a single file novel by its exact file name`() {
        assertEquals(epub, resolve("Lantern Harbor.epub"))
    }

    @Test
    fun `resolves a single file novel by name without extension ignoring case`() {
        assertEquals(epub, resolve("lantern harbor"))
    }

    @Test
    fun `does not match a folder by name without extension`() {
        val dotted = Entry("Vol. 1", isDirectory = true)
        assertNull(resolve("Vol", listOf(dotted)))
    }

    @Test
    fun `prefers exact name over extension-less match`() {
        val plain = Entry("Lantern Harbor", isDirectory = true)
        assertEquals(plain, resolve("Lantern Harbor", listOf(epub, plain)))
    }

    @Test
    fun `returns null when nothing matches`() {
        assertNull(resolve("Paper Kite Atlas.epub"))
        assertNull(resolve("Lantern"))
    }

    @Test
    fun `never resolves urls that point outside the base folder`() {
        val nested = listOf(Entry("..", true), Entry(".", true), Entry("", false), epub)
        listOf("..", ".", "", "../Lantern Harbor.epub", "Folder Tale/Chapter 01.epub", "a\\b", "/").forEach {
            assertNull(resolve(it, nested), "url '$it' must not resolve")
        }
    }

    @Test
    fun `ignores entries without a name`() {
        assertNull(resolve("Lantern Harbor.epub", listOf(Entry(null, false))))
    }

    @Test
    fun `delete reports successes and failures separately and keeps going`() {
        val result = deleteLocalNovelEntries(listOf("a", "b", "c", "d")) {
            when (it) {
                "a" -> true
                "b" -> false
                "c" -> error("permission denied")
                else -> true
            }
        }
        assertEquals(listOf("a", "d"), result.deleted)
        assertEquals(listOf("b", "c"), result.failed)
    }

    @Test
    fun `delete of nothing succeeds with empty result`() {
        val result = deleteLocalNovelEntries(emptyList<String>()) { true }
        assertEquals(0, result.deleted.size)
        assertEquals(0, result.failed.size)
    }
}
