package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

/**
 * Output of [ContentPipeline.process]. [text] is ready to hand directly to the
 * renderer — all preprocessing (title strip, normalize, regex, lowercase,
 * sanitize) has already been applied. Renderers must NOT re-run any
 * preprocessing step on this value.
 *
 * @property text         The fully-prepared content (HTML for non-plaintext
 *                        chapters, pre-wrapped escaped text for plaintext).
 * @property isPlainText  True when the source chapter was detected as plain
 *                        text (`.txt` / `.text` URL). Affects how the renderer
 *                        injects content into the DOM (textContent vs innerHTML).
 * @property chapterUrl   The chapter URL the content was prepared for. Carried
 *                        through unchanged for downstream identity checks.
 */
data class ProcessedContent(
    val text: String,
    val isPlainText: Boolean,
    val chapterUrl: String?,
)
