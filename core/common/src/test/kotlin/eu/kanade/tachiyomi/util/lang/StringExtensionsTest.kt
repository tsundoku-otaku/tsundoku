package eu.kanade.tachiyomi.util.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StringExtensionsTest {

    @Test
    fun `normalizeHtmlDescription decodes entities and strips tags`() {
        val input = "<p>Hello &amp; welcome</p><div>to <b>Tsundoku</b></div>"

        val actual = normalizeHtmlDescription(input)

        assertEquals("Hello & welcome\n\nto Tsundoku", actual)
    }

    @Test
    fun `normalizeHtmlDescription preserves line breaks and collapses spacing`() {
        val input = "<div>Line 1<br>   Line 2</div><p>   Line 3   </p>"

        val actual = normalizeHtmlDescription(input)

        assertEquals("Line 1\nLine 2\n\nLine 3", actual)
    }

    @Test
    fun `normalizeHtmlDescription returns null for blank`() {
        assertNull(normalizeHtmlDescription("   "))
    }
}
