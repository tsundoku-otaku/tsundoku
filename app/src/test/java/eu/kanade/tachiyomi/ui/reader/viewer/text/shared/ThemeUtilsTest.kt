package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the pure, Android-free helpers in ThemeUtils.
 *
 * getThemeColors / getThemeTokens require an Activity (Material theme attribute
 * resolution) and are not testable in JVM unit tests without Robolectric.
 * colorToHex is the pure extracted piece that warrants unit test coverage.
 */
class ThemeUtilsTest {

    // ── colorToHex ────────────────────────────────────────────────────────────

    @Test
    fun `colorToHex formats white correctly`() {
        assertEquals("#FFFFFF", ThemeUtils.colorToHex(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `colorToHex formats black correctly`() {
        assertEquals("#000000", ThemeUtils.colorToHex(0xFF000000.toInt()))
    }

    @Test
    fun `colorToHex strips alpha channel`() {
        // Two colors with different alpha but same RGB must produce same hex
        val opaque = 0xFF123456.toInt()
        val transparent = 0x00123456
        assertEquals(ThemeUtils.colorToHex(opaque), ThemeUtils.colorToHex(transparent))
        assertEquals("#123456", ThemeUtils.colorToHex(opaque))
    }

    @Test
    fun `colorToHex uses uppercase hex digits`() {
        val result = ThemeUtils.colorToHex(0xFFABCDEF.toInt())
        assertTrue(result == result.uppercase(), "expected uppercase: $result")
    }

    @Test
    fun `colorToHex pads short values to 6 digits`() {
        // Color #00000F must be zero-padded, not "#F"
        assertEquals("#00000F", ThemeUtils.colorToHex(0xFF00000F.toInt()))
    }

    @Test
    fun `colorToHex always starts with hash`() {
        val result = ThemeUtils.colorToHex(0xFF006A6A.toInt())
        assertTrue(result.startsWith("#"), "expected # prefix: $result")
        assertEquals(7, result.length)
    }

    // ── ThemeTokens data class ────────────────────────────────────────────────

    @Test
    fun `ThemeTokens equality holds for identical values`() {
        val a = ThemeUtils.ThemeTokens(cssVariables = ":root {}", jsObject = "{}")
        val b = ThemeUtils.ThemeTokens(cssVariables = ":root {}", jsObject = "{}")
        assertEquals(a, b)
    }

    @Test
    fun `ThemeTokens stores css and js independently`() {
        val tokens = ThemeUtils.ThemeTokens(cssVariables = "css", jsObject = "js")
        assertEquals("css", tokens.cssVariables)
        assertEquals("js", tokens.jsObject)
    }
}
