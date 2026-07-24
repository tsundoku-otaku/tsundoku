package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ProcessedContent
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewDocumentBuilderTest {

    private fun minimalInput(
        text: String = "<p>Hello</p>",
        isPlainText: Boolean = false,
        hideChapterTitle: Boolean = false,
        css: String = "body { color: black; }",
        infiniteScrollEnabled: Boolean = false,
        blockMedia: Boolean = false,
    ) = NovelWebViewDocumentBuilder.DocumentInput(
        processed = ProcessedContent(text = text, isPlainText = isPlainText, chapterUrl = null),
        chapter = null,
        style = NovelWebViewStyler.CustomStylePayload(
            css = css,
            hideChapterTitle = hideChapterTitle,
            backgroundColor = 0xFFFFFFFF.toInt(),
        ),
        themeTokens = ThemeUtils.ThemeTokens(cssVariables = ":root {}", jsObject = "{}"),
        tsundokuScript = "",
        infiniteScrollEnabled = infiniteScrollEnabled,
        blockMedia = blockMedia,
    )

    // ── escapeForStyleTag ──────────────────────────────────────────────────

    @Test
    fun `escapeForStyleTag replaces closing style tags case-insensitively`() {
        with(NovelWebViewDocumentBuilder) {
            val input = "a { color: red; } </style> b </Style> c </STYLE>"
            val escaped = input.escapeForStyleTag()
            assertFalse(escaped.contains("</style>"))
            assertFalse(escaped.contains("</Style>"))
            assertFalse(escaped.contains("</STYLE>"))
            assertTrue(escaped.contains("<\\/style>"))
            assertTrue(escaped.contains("<\\/Style>"))
            assertTrue(escaped.contains("<\\/STYLE>"))
        }
    }

    @Test
    fun `escapeForStyleTag leaves safe CSS unchanged`() {
        with(NovelWebViewDocumentBuilder) {
            val safe = "body { font-size: 16px; margin: 0; }"
            assertTrue(safe.escapeForStyleTag() == safe)
        }
    }

    // ── extractBodyOrFallback ─────────────────────────────────────────────

    @Test
    fun `extractBodyOrFallback returns body inner html from full document`() {
        val html = "<!DOCTYPE html><html><head><title>T</title></head><body><p>Content</p></body></html>"
        val result = NovelWebViewDocumentBuilder.extractBodyOrFallback(html)
        assertTrue(result.contains("<p>"))
        assertFalse(result.contains("<head>"))
        assertFalse(result.contains("<html>"))
    }

    @Test
    fun `extractBodyOrFallback returns input unchanged when html is a fragment`() {
        val fragment = "<p>Just a paragraph</p>"
        val result = NovelWebViewDocumentBuilder.extractBodyOrFallback(fragment)
        assertTrue(result.contains("paragraph"))
    }

    // ── assemble: structural checks ────────────────────────────────────────

    @Test
    fun `assemble produces valid html skeleton`() {
        val html = NovelWebViewDocumentBuilder.assemble(minimalInput())
        assertTrue(html.trimStart().startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<head>"))
        assertTrue(html.contains("</head>"))
        assertTrue(html.contains("<body>"))
        assertTrue(html.contains("</body>"))
        assertTrue(html.contains("</html>"))
    }

    @Test
    fun `assemble embeds custom css in style tag`() {
        val css = "body { font-size: 18px; }"
        val html = NovelWebViewDocumentBuilder.assemble(minimalInput(css = css))
        assertTrue(html.contains(css))
    }

    @Test
    fun `assemble escapes malicious closing style in user css`() {
        val evilCss = "body { } </style><script>alert(1)</script><style>"
        val html = NovelWebViewDocumentBuilder.assemble(minimalInput(css = evilCss))
        // The raw </style> must not appear in the output unescaped
        // (the style tag itself closes correctly; the embedded one is escaped)
        val styleTagContent = html.substringAfter("tsundoku-custom-style\">").substringBefore("</style>")
        assertFalse(styleTagContent.contains("</style>"))
    }

    @Test
    fun `assemble hides chapter headings with tsundoku-chapter scoped selector`() {
        val html = NovelWebViewDocumentBuilder.assemble(minimalInput(hideChapterTitle = true))
        // Selector must be scoped to tsundoku-chapter, not document-wide
        assertTrue(html.contains("tsundoku-chapter h1:first-of-type"))
        assertTrue(html.contains("display: none !important"))
        // Must NOT use the old document-scoped form
        val styleSection = html.substringAfter("<style>").substringBefore("</style>")
        assertFalse(styleSection.trimStart().startsWith("h1:first-of-type"))
    }

    @Test
    fun `assemble injects plain text via textContent assignment not innerHTML`() {
        val content = "Hello <world> & \"friends\""
        val html = NovelWebViewDocumentBuilder.assemble(
            minimalInput(text = content, isPlainText = true),
        )
        // textContent assignment must appear (not innerHTML)
        assertTrue(html.contains(".textContent ="))
        // The content must be JSON-quoted (not raw)
        assertTrue(html.contains("Hello"))
        // Raw unescaped angle brackets must NOT appear outside the script
        val bodySection = html.substringAfter("<body>")
        // The plain-text container is empty; paragraphs are appended via JS
        assertTrue(bodySection.contains("<div class=\"${NovelWebViewDocumentBuilder.PLAIN_TEXT_CLASS}\""))
    }

    @Test
    fun `assemble blocks media when blockMedia is true`() {
        val html = NovelWebViewDocumentBuilder.assemble(minimalInput(blockMedia = true))
        assertTrue(html.contains("display: none !important"))
        assertTrue(html.contains("img,") || html.contains("img ,"))
    }

    @Test
    fun `assemble does not produce chapter divider without infinite scroll`() {
        val html = NovelWebViewDocumentBuilder.assemble(
            minimalInput(infiniteScrollEnabled = false),
        )
        assertFalse(html.contains("tsundoku-chapter-divider"))
    }
}
