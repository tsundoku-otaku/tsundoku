package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences

/**
 * Which surface a piece of chapter content is being prepared for.
 *
 * [TEXT_VIEW]  : Android TextView fed via Html.fromHtml. All scripts/styles are
 *                always stripped before rendering (TextView would render them as
 *                visible text).
 * [WEB_VIEW]   : Android WebView. Embedded CSS/JS can be preserved per user
 *                preferences ([ReaderPreferences.enableEpubStyles] /
 *                [ReaderPreferences.enableEpubJs]).
 */
enum class RenderTarget { TEXT_VIEW, WEB_VIEW }

/**
 * Configuration for a single [ContentPipeline.process] invocation.
 *
 * Build a config from [ReaderPreferences] via [from] for the common case, or
 * construct directly when you need to override a flag (e.g. translator-only
 * second pass on the same source text).
 */
data class ContentConfig(
    val chapterUrl: String?,
    val chapterName: String,
    val target: RenderTarget,
    val hideTitle: Boolean = false,
    val forceLowercase: Boolean = false,
    val blockMedia: Boolean = false,
    val keepEmbeddedCss: Boolean = true,
    val keepEmbeddedJs: Boolean = false,
) {
    companion object {
        /**
         * Build a config from the current [preferences]. Per-call overrides
         * (e.g. forcing `hideTitle = false` for a debug view) can be applied
         * with `.copy(...)`.
         */
        fun from(
            preferences: ReaderPreferences,
            target: RenderTarget,
            chapterUrl: String?,
            chapterName: String,
        ): ContentConfig = ContentConfig(
            chapterUrl = chapterUrl,
            chapterName = chapterName,
            target = target,
            hideTitle = preferences.novelHideChapterTitle.get(),
            forceLowercase = preferences.novelForceTextLowercase.get(),
            blockMedia = preferences.novelBlockMedia.get(),
            // Note: preferences are named "epub" for historical reasons but apply
            // to all HTML-format chapters, not only EPUB sources.
            keepEmbeddedCss = preferences.enableEpubStyles.get(),
            keepEmbeddedJs = preferences.enableEpubJs.get(),
        )
    }
}
