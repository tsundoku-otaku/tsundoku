package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import androidx.annotation.WorkerThread
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences

/**
 * Deterministic preprocessing of chapter source text into renderer-ready output.
 *
 * The pipeline owns the full transform sequence so callers cannot accidentally
 * skip a step or reorder them. Both [NovelViewer] and [NovelWebViewViewer] route
 * every content render through a single [process] call; the renderer never
 * re-applies any of these transforms.
 *
 * Order (kept stable — changing it changes user-visible output for some chapters):
 *
 *   1. **stripChapterTitle** — runs on raw source so it can match HTML heading
 *      tags or the first plain-text line.
 *   2. **normalize** — plain-text chapters get HTML-escaped and wrapped in
 *      `<pre>`; markdown gets rendered to HTML; HTML passes through.
 *   3. **regex replacements** — user-defined Find/Replace rules. Runs *after*
 *      normalize so HTML chapters and plain-text chapters see consistent input
 *      (the user-visible representation).
 *   4. **forceLowercase** — naive `String.lowercase()`. Affects tags too; users
 *      enabling this accept that.
 *   5. **translate** — async, only when [translator] is supplied. Receives the
 *      already-sanitized content but runs *before* the target sanitize so any
 *      translator-injected markup is also cleaned up.
 *   6. **sanitizeForRender** — strips scripts/styles/comments/media based on
 *      [ContentConfig.target] and the keep-embedded flags. No-op for plain-text
 *      chapters (their content is already escaped — there are no live tags).
 *
 * Translator-aware callers (TextView reader's untranslated-first-then-translated
 * paint pattern) should call [preTranslate] once and [finalize] twice instead of
 * [process] twice — that skips the expensive normalize + regex on the second
 * pass.
 */
class ContentPipeline(private val preferences: ReaderPreferences) {

    /**
     * Run the full pipeline on [raw] using [config].
     *
     * @param translator Optional async hook for translating the content mid-pipeline.
     *                   Pass `null` to skip translation entirely. The caller is
     *                   responsible for guarding the call with `isTranslationEnabled()`
     *                   and `!showRawHtml` semantics if applicable.
     */
    suspend fun process(
        raw: String,
        config: ContentConfig,
        translator: (suspend (String) -> String)? = null,
    ): ProcessedContent = finalize(preTranslate(raw, config), config, translator)

    /**
     * Runs the translator-independent prefix of the pipeline:
     * stripChapterTitle → normalize → regex → forceLowercase.
     *
     * The result is **not** ready to render — sanitize has not run. Pass it to
     * [finalize] (optionally with a translator) to get a [ProcessedContent].
     *
     * Use this when you need to render the same source text twice (e.g. show
     * untranslated content immediately, then replace with translated). Caching
     * the [PreTranslated] result and calling [finalize] twice avoids re-running
     * normalize and regex on the second pass.
     */
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

    /**
     * Completes the pipeline on [pre] using [config]: optionally translates,
     * then applies the target-aware sanitize step.
     */
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

    /**
     * Intermediate value from [preTranslate]. Holds the post-normalize+regex+lowercase
     * content along with the plain-text flag derived from the chapter URL.
     */
    data class PreTranslated(
        val text: String,
        val isPlainText: Boolean,
    )
}
