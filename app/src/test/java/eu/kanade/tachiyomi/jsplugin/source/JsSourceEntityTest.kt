package eu.kanade.tachiyomi.jsplugin.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsSourceEntityTest {

    // ── looksLikeHtml ──────────────────────────────────────────────────────

    @Test
    fun `looksLikeHtml returns false for plain text`() {
        assertFalse(JsSource.looksLikeHtml("Amanda defeats &lt;D&gt; rank villain"))
    }

    @Test
    fun `looksLikeHtml returns false for angle-bracket rank markers without known tags`() {
        // <D> is not in the known-tag list, so should be treated as plain text
        assertFalse(JsSource.looksLikeHtml("Amanda defeats <D> rank villain"))
    }

    @Test
    fun `looksLikeHtml returns true for content with p tags`() {
        assertTrue(JsSource.looksLikeHtml("<p>Amanda defeats &lt;D&gt; rank villain</p>"))
    }

    @Test
    fun `looksLikeHtml returns true for content with div tags`() {
        assertTrue(JsSource.looksLikeHtml("<div class=\"txt\"><p>Chapter text</p></div>"))
    }

    @Test
    fun `looksLikeHtml returns true for heading tags`() {
        assertTrue(JsSource.looksLikeHtml("<h4>Chapter 1</h4><p>Content</p>"))
    }

    // ── fixDoubleEncodedEntities ───────────────────────────────────────────

    @Test
    fun `fixDoubleEncodedEntities fixes amp-lt and amp-gt`() {
        val input = "<p>defeats &amp;lt;D&amp;gt; rank villain</p>"
        val expected = "<p>defeats &lt;D&gt; rank villain</p>"
        assertEquals(expected, JsSource.fixDoubleEncodedEntities(input))
    }

    @Test
    fun `fixDoubleEncodedEntities fixes amp-nbsp`() {
        val input = "<p>Hello&amp;nbsp;World</p>"
        assertEquals("<p>Hello&nbsp;World</p>", JsSource.fixDoubleEncodedEntities(input))
    }

    @Test
    fun `fixDoubleEncodedEntities fixes amp-mdash and amp-rsquo`() {
        val input = "<p>he&amp;rsquo;s a hero&amp;mdash;confirmed</p>"
        assertEquals("<p>he&rsquo;s a hero&mdash;confirmed</p>", JsSource.fixDoubleEncodedEntities(input))
    }

    @Test
    fun `fixDoubleEncodedEntities fixes numeric decimal references`() {
        val input = "<p>star&amp;#9733;bullet</p>"
        assertEquals("<p>star&#9733;bullet</p>", JsSource.fixDoubleEncodedEntities(input))
    }

    @Test
    fun `fixDoubleEncodedEntities fixes numeric hex references`() {
        val input = "<p>star&amp;#x2605;bullet</p>"
        assertEquals("<p>star&#x2605;bullet</p>", JsSource.fixDoubleEncodedEntities(input))
    }

    @Test
    fun `fixDoubleEncodedEntities leaves correct single-encoded entities unchanged`() {
        val input = "<p>defeats &lt;D&gt; rank villain</p>"
        assertEquals(input, JsSource.fixDoubleEncodedEntities(input))
    }

    @Test
    fun `fixDoubleEncodedEntities leaves literal amp unchanged`() {
        // &amp; with no following entity name should stay as-is (it's a literal &)
        val input = "<p>Tom &amp; Jerry</p>"
        assertEquals(input, JsSource.fixDoubleEncodedEntities(input))
    }

    @Test
    fun `fixDoubleEncodedEntities returns early when no amp present`() {
        val input = "<p>No entities here</p>"
        assertEquals(input, JsSource.fixDoubleEncodedEntities(input))
    }

    // ── normalizePluginContent ────────────────────────────────────────────

    @Test
    fun `normalizePluginContent plain text decodes lt-D-gt to angle bracket D`() {
        val input = "Amanda defeats &lt;D&gt; rank villain"
        assertEquals("Amanda defeats <D> rank villain", JsSource.normalizePluginContent(input))
    }

    @Test
    fun `normalizePluginContent plain text decodes all named entities`() {
        val input = "he&rsquo;s a hero&mdash;well&nbsp;known"
        assertEquals("he’s a hero—well known", JsSource.normalizePluginContent(input))
    }

    @Test
    fun `normalizePluginContent plain text without entities is unchanged`() {
        val input = "Amanda defeats D rank villain"
        assertEquals(input, JsSource.normalizePluginContent(input))
    }

    @Test
    fun `normalizePluginContent HTML with double-encoded entities fixes them`() {
        val input = "<p>Amanda defeats &amp;lt;D&amp;gt; rank villain</p>"
        val expected = "<p>Amanda defeats &lt;D&gt; rank villain</p>"
        assertEquals(expected, JsSource.normalizePluginContent(input))
    }

    @Test
    fun `normalizePluginContent HTML with correct entities is unchanged`() {
        val input = "<p>Amanda defeats &lt;D&gt; rank villain</p>"
        assertEquals(input, JsSource.normalizePluginContent(input))
    }

    @Test
    fun `normalizePluginContent HTML literal amp Tom and Jerry is unchanged`() {
        val input = "<p>Tom &amp; Jerry</p>"
        assertEquals(input, JsSource.normalizePluginContent(input))
    }

    @Test
    fun `normalizePluginContent HTML with double-encoded nbsp fixed`() {
        val input = "<div><p>Chapter&amp;nbsp;1</p></div>"
        assertEquals("<div><p>Chapter&nbsp;1</p></div>", JsSource.normalizePluginContent(input))
    }
}
