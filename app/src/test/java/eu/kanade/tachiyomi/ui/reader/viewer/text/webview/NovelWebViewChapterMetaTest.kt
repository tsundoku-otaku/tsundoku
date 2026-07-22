package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.htmlAttributeEscape
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.jsEscape
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.quoteForJson
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.resolveWebViewBaseUrl
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.toAbsoluteChapterUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewChapterMetaTest {

    // â”€â”€ jsEscape â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `jsEscape escapes backslashes`() {
        assertEquals("foo\\\\bar", "foo\\bar".jsEscape())
    }

    @Test
    fun `jsEscape escapes double quotes`() {
        assertEquals("\\\"foo\\\"", "\"foo\"".jsEscape())
    }

    @Test
    fun `jsEscape escapes newlines and carriage returns`() {
        assertEquals("a\\nb\\rc", "a\nb\rc".jsEscape())
    }

    @Test
    fun `jsEscape neutralizes script close tags`() {
        assertEquals("<\\/script>", "</script>".jsEscape())
        assertEquals("<\\/Script>", "</Script>".jsEscape())
        assertEquals("<\\/SCRIPT>", "</SCRIPT>".jsEscape())
    }

    @Test
    fun `jsEscape leaves safe text unchanged`() {
        assertEquals("hello world", "hello world".jsEscape())
    }

    // â”€â”€ htmlAttributeEscape â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `htmlAttributeEscape escapes ampersand first to avoid double-escape`() {
        assertEquals("&amp;quot;", "&quot;".htmlAttributeEscape())
    }

    @Test
    fun `htmlAttributeEscape escapes quotes apostrophes and angle brackets`() {
        assertEquals("&quot;&#39;&lt;&gt;", "\"'<>".htmlAttributeEscape())
    }

    // â”€â”€ quoteForJson â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `quoteForJson wraps in double quotes`() {
        val quoted = quoteForJson("hello")
        assertTrue(quoted.startsWith("\""))
        assertTrue(quoted.endsWith("\""))
    }

    @Test
    fun `quoteForJson escapes inner quotes`() {
        val quoted = quoteForJson("he\"llo")
        assertTrue(quoted.contains("he\\\"llo"))
    }

    @Test
    fun `quoteForJson escapes backslash`() {
        assertTrue(quoteForJson("a\\b").contains("a\\\\b"))
    }

    @Test
    fun `quoteForJson escapes newline tab carriage return`() {
        val quoted = quoteForJson("a\nb\tc\r")
        assertTrue(quoted.contains("\\n"))
        assertTrue(quoted.contains("\\t"))
        assertTrue(quoted.contains("\\r"))
    }

    @Test
    fun `quoteForJson escapes forward slash for script tag safety`() {
        // </script> inside JSON must not terminate the enclosing script tag
        val quoted = quoteForJson("</script>")
        assertFalse(quoted.contains("</script>"), "raw </script> must not appear")
        assertTrue(quoted.contains("<\\/script>"))
    }

    @Test
    fun `quoteForJson safe string passes through unchanged except quotes`() {
        val result = quoteForJson("Hello, world!")
        assertEquals("\"Hello, world!\"", result)
    }

    // â”€â”€ toAbsoluteChapterUrl â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `toAbsoluteChapterUrl passes absolute http urls through`() {
        assertEquals(
            "https://example.com/ch1",
            toAbsoluteChapterUrl("https://example.com/ch1", "https://novel.com/n"),
        )
    }

    @Test
    fun `toAbsoluteChapterUrl resolves relative path against novel url`() {
        assertEquals(
            "https://novel.com/ch1",
            toAbsoluteChapterUrl("/ch1", "https://novel.com/n"),
        )
    }

    @Test
    fun `toAbsoluteChapterUrl returns blank for blank chapterPath`() {
        assertEquals("", toAbsoluteChapterUrl("", "https://novel.com"))
        assertEquals("", toAbsoluteChapterUrl(null, "https://novel.com"))
    }

    @Test
    fun `toAbsoluteChapterUrl preserves relative when novelUrl is not http`() {
        assertEquals("/local/ch1", toAbsoluteChapterUrl("/local/ch1", null))
        assertEquals("/local/ch1", toAbsoluteChapterUrl("/local/ch1", "file:///app/data"))
    }

    @Test
    fun `toAbsoluteChapterUrl repairs missing colon in scheme`() {
        // HtmlUtils.normalizeUrl converts "https//foo.com/x" to "https://foo.com/x"
        assertEquals(
            "https://foo.com/x",
            toAbsoluteChapterUrl("https//foo.com/x", "https://novel.com"),
        )
    }

    // â”€â”€ resolveWebViewBaseUrl â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `resolveWebViewBaseUrl prefers absolute chapter url`() {
        assertEquals(
            "https://example.com/ch1",
            resolveWebViewBaseUrl("https://example.com/ch1", "https://novel.com"),
        )
    }

    @Test
    fun `resolveWebViewBaseUrl falls back to novel url for relative chapter`() {
        assertEquals(
            "https://novel.com",
            resolveWebViewBaseUrl("/relative", "https://novel.com"),
        )
    }

    @Test
    fun `resolveWebViewBaseUrl returns null when neither is absolute http`() {
        assertEquals(null, resolveWebViewBaseUrl("/foo", null))
        assertEquals(null, resolveWebViewBaseUrl(null, null))
    }

    // â”€â”€ buildChapterJson â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `buildChapterJson returns null sentinels for null chapter`() {
        val json = NovelWebViewChapterMeta.buildChapterJson(null, "https://n.com")
        assertTrue(json.contains("\"id\": -1"))
        assertTrue(json.contains("\"title\": \"\""))
        assertTrue(json.contains("\"number\": -1"))
        assertTrue(json.contains("\"path\": \"\""))
    }

    // â”€â”€ buildChaptersJson â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `buildChaptersJson empty list returns empty array literal`() {
        assertEquals("[]", NovelWebViewChapterMeta.buildChaptersJson(emptyList(), "https://n.com"))
    }

    // â”€â”€ buildTsundokuScript: assembled script contains all keys â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `buildTsundokuScript includes the required Tsundoku keys`() {
        val script = NovelWebViewChapterMeta.buildTsundokuScript(
            NovelWebViewChapterMeta.TsundokuScriptContext(
                novelUrl = "https://n.com",
                currentChapter = null,
                chaptersInOrder = emptyList(),
                isEditingMode = false,
                isInfiniteScroll = true,
                textSelectionBlocked = false,
                forcedLowercase = false,
            ),
        )
        assertTrue(script.contains("window.Tsundoku = window.Tsundoku || {}"))
        assertTrue(script.contains(".novelUrl"))
        assertTrue(script.contains(".currentChapter"))
        assertTrue(script.contains(".chapters"))
        assertTrue(script.contains(".runtime"))
        assertTrue(script.contains(".isEditMode"))
        assertTrue(script.contains(".isInfScroll"))
        assertTrue(script.contains(".textSelectionBlocked"))
        assertTrue(script.contains(".forcedLowercase"))
        assertTrue(script.contains(".menuVisible"))
        assertTrue(script.contains(".immersive"))
        assertTrue(script.contains(".ttsState"))
        assertTrue(script.contains(".loadingChapter"))
    }

    @Test
    fun `buildTsundokuScript seeds the new runtime state`() {
        val script = NovelWebViewChapterMeta.buildTsundokuScript(
            NovelWebViewChapterMeta.TsundokuScriptContext(
                novelUrl = null,
                currentChapter = null,
                chaptersInOrder = emptyList(),
                isEditingMode = false,
                isInfiniteScroll = false,
                textSelectionBlocked = false,
                forcedLowercase = false,
                menuVisible = true,
                immersive = false,
                ttsState = "playing",
                loadingChapter = true,
            ),
        )
        assertTrue(script.contains(".menuVisible = true"))
        assertTrue(script.contains(".immersive = false"))
        assertTrue(script.contains(".ttsState = \"playing\""))
        assertTrue(script.contains(".loadingChapter = true"))
    }

    @Test
    fun `buildTsundokuScript runtime flags reflect context booleans`() {
        val script = NovelWebViewChapterMeta.buildTsundokuScript(
            NovelWebViewChapterMeta.TsundokuScriptContext(
                novelUrl = null,
                currentChapter = null,
                chaptersInOrder = emptyList(),
                isEditingMode = true,
                isInfiniteScroll = false,
                textSelectionBlocked = true,
                forcedLowercase = true,
            ),
        )
        // Each flag appears once in `= true|false;` form.
        assertTrue(script.contains(".isEditMode = true"))
        assertTrue(script.contains(".isInfScroll = false"))
        assertTrue(script.contains(".textSelectionBlocked = true"))
        assertTrue(script.contains(".forcedLowercase = true"))
        assertFalse(script.contains(".isEditMode = false"))
    }
}
