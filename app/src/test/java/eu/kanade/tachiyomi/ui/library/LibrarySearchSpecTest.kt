package eu.kanade.tachiyomi.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibrarySearchSpecTest {

    private fun parse(query: String, useRegex: Boolean = false, searchByUrl: Boolean = false) =
        LibrarySearchSpec.parse(query, useRegex, searchByUrl)

    @Test
    fun `detects field prefixes case-insensitively and strips them`() {
        assertEquals(LibrarySearchSpec.Field.TITLE, parse("title:naruto").field)
        assertEquals("naruto", parse("Title:  naruto ").term)
        assertEquals(LibrarySearchSpec.Field.AUTHOR, parse("author:oda").field)
        assertEquals(LibrarySearchSpec.Field.ARTIST, parse("artist:x").field)
        assertEquals(LibrarySearchSpec.Field.DESCRIPTION, parse("desc:pirate").field)
        assertEquals(LibrarySearchSpec.Field.DESCRIPTION, parse("description:pirate").field)
        assertEquals(LibrarySearchSpec.Field.TAG, parse("tag:action").field)
        assertEquals(LibrarySearchSpec.Field.TAG, parse("genre:action").field)
        assertEquals(LibrarySearchSpec.Field.SOURCE, parse("source:mangadex").field)
        assertEquals(LibrarySearchSpec.Field.URL, parse("url:/manga/1").field)
        assertEquals(LibrarySearchSpec.Field.CHAPTER, parse("chapter:vol").field)
        assertEquals(LibrarySearchSpec.Field.ID, parse("id:42").field)
    }

    @Test
    fun `plain query is DEFAULT field`() {
        val spec = parse("one piece")
        assertEquals(LibrarySearchSpec.Field.DEFAULT, spec.field)
        assertEquals("one piece", spec.term)
    }

    @Test
    fun `does not compile regex when useRegex is false`() {
        assertNull(parse("naruto", useRegex = false).termRegex)
    }

    @Test
    fun `compiles regex once when useRegex is true`() {
        val spec = parse("nar.*to", useRegex = true)
        assertNotNull(spec.termRegex)
        assertTrue(spec.termRegex!!.containsMatchIn("naruto"))
    }

    @Test
    fun `invalid regex compiles to null`() {
        assertNull(parse("[unclosed", useRegex = true).termRegex)
    }

    @Test
    fun `default query splits comma into sub-terms and parses negation`() {
        val spec = parse("naruto, -filler")
        assertEquals(2, spec.subTerms.size)
        assertEquals("naruto", spec.subTerms[0].text)
        assertFalse(spec.subTerms[0].negate)
        assertEquals("filler", spec.subTerms[1].text)
        assertTrue(spec.subTerms[1].negate)
    }

    @Test
    fun `blank sub-terms are dropped`() {
        val spec = parse("a, , b")
        assertEquals(listOf("a", "b"), spec.subTerms.map { it.text })
    }

    @Test
    fun `searchByUrl flag is carried on the spec`() {
        assertTrue(parse("x", searchByUrl = true).searchByUrl)
        assertFalse(parse("x", searchByUrl = false).searchByUrl)
    }
}
