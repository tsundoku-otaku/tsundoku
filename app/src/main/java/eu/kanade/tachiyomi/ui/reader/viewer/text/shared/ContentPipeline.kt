package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import androidx.annotation.WorkerThread
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences

/**
 * Order (kept stable — changing it changes user-visible output for some chapters):
 * stripChapterTitle → normalize → regex replacements → forceLowercase → translate → sanitizeForRender.
 */
class ContentPipeline(private val preferences: ReaderPreferences) {

    suspend fun process(
        raw: String,
        config: ContentConfig,
        translator: (suspend (String) -> String)? = null,
    ): ProcessedContent = finalize(preTranslate(raw, config), config, translator)

    @WorkerThread
    fun preTranslate(raw: String, config: ContentConfig): PreTranslated {
        var content = raw
        val plainTextMode = HtmlUtils.isPlainTextChapter(config.chapterUrl)

        if (config.hideTitle) {
            content = HtmlUtils.stripChapterTitle(content, config.chapterName)
        }

        content = if (plainTextMode) {
            HtmlUtils.normalizePlainTextContent(content)
        } else {
            HtmlUtils.normalizeContentForHtml(content, config.chapterUrl)
        }

        content = RegexReplacementsProcessor.apply(content, preferences)

        if (config.forceLowercase) content = content.lowercase()

        return PreTranslated(content, plainTextMode)
    }

    suspend fun finalize(
        pre: PreTranslated,
        config: ContentConfig,
        translator: (suspend (String) -> String)? = null,
    ): ProcessedContent {
        var content = pre.text
        if (translator != null) content = translator(content)

        if (!pre.isPlainText) {
            content = HtmlUtils.sanitizeForRender(
                content,
                target = config.target,
                keepEmbeddedCss = config.keepEmbeddedCss,
                keepEmbeddedJs = config.keepEmbeddedJs,
                blockMedia = config.blockMedia,
            )
        }

        return ProcessedContent(
            text = content,
            isPlainText = pre.isPlainText,
            chapterUrl = config.chapterUrl,
        )
    }

    data class PreTranslated(
        val text: String,
        val isPlainText: Boolean,
    )
}
