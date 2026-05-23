package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

/** Renderers must NOT re-run any preprocessing step on [text]. */
data class ProcessedContent(
    val text: String,
    val isPlainText: Boolean,
    val chapterUrl: String?,
)
