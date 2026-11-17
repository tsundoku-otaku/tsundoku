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

    @Test
    fun `readableTextOn picks light text on dark backgrounds`() {
        assertEquals(0xFFE0E0E0.toInt(), ThemeUtils.readableTextOn(0xFF000000.toInt()))
        assertEquals(0xFFE0E0E0.toInt(), ThemeUtils.readableTextOn(0xFF292832.toInt()))
    }

    @Test
    fun `readableTextOn picks dark text on light backgrounds`() {
        assertEquals(0xFF000000.toInt(), ThemeUtils.readableTextOn(0xFFFFFFFF.toInt()))
        assertEquals(0xFF000000.toInt(), ThemeUtils.readableTextOn(0xFFF4ECD8.toInt()))
    }

    @Test
    fun `updateJs re-applies css variables and theme object`() {
        val tokens = ThemeUtils.ThemeTokens(
            cssVariables = ":root { --tsundoku-reader-background: #112233; }",
            jsObject = "{}",
        )
        val js = tokens.updateJs
        assertTrue(js.contains("setProperty('--tsundoku-reader-background','#112233')"), js)
        assertTrue(js.contains("window.TsundokuTheme={}"), js)
    }

    @Test
    fun `contrastRatio is 21 for black on white and 1 for identical colors`() {
        assertEquals(21.0, ThemeUtils.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, ThemeUtils.contrastRatio(0xFF336699.toInt(), 0xFF336699.toInt()), 0.001)
    }

    @Test
    fun `isLight matches background brightness`() {
        assertTrue(ThemeUtils.isLight(0xFFF4ECD8.toInt()))
        assertTrue(!ThemeUtils.isLight(0xFF121212.toInt()))
    }

    @Test
    fun `readableTextOn picks the higher contrast text on mid tones`() {
        assertEquals(0xFF000000.toInt(), ThemeUtils.readableTextOn(0xFF808080.toInt()))
        assertEquals(0xFFE0E0E0.toInt(), ThemeUtils.readableTextOn(0xFF4A4A4A.toInt()))
    }

    @Test
    fun `colorToCss keeps hex for opaque colors and uses rgba otherwise`() {
        assertEquals("#123456", ThemeUtils.colorToCss(0xFF123456.toInt()))
        assertEquals("rgba(18,52,86,0.502)", ThemeUtils.colorToCss(0x80123456.toInt()))
    }
}
