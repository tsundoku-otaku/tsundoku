package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Wires reader-preference flows to [NovelWebViewViewer] actions.
 *
 * Groups the prefs by reaction:
 * - **Styles** — refresh inline `<style>` via [onStyleChanged].
 * - **Scripts** — re-inject Tsundoku JS via [onScriptChanged].
 * - **Chapter reload** — embedded CSS/JS toggles, force-lowercase, regex
 *   rules — trigger a full chapter reload via [onChapterReloadRequested]
 *   because the pipeline re-sanitizes / re-transforms.
 * - **Media block** — set WebView's `blockNetworkImage` / reload via
 *   [onBlockMediaChanged] (the only reaction that touches WebView settings).
 * - **TTS** — apply engine settings via [onTtsSettingsChanged].
 *
 * Call [observe] once during viewer construction. The block runs forever on
 * the supplied [scope] and is cancelled when the scope is.
 */
internal class NovelWebViewPreferenceObserver(
    private val preferences: ReaderPreferences,
    private val scope: CoroutineScope,
    private val onStyleChanged: () -> Unit,
    private val onScriptChanged: () -> Unit,
    private val onChapterReloadRequested: () -> Unit,
    private val onBlockMediaChanged: (Boolean) -> Unit,
    private val onTtsSettingsChanged: () -> Unit,
) {

    fun observe() {
        // Style-affecting preferences re-inject the inline <style>; no chapter reload.
        scope.launch {
            merge(
                preferences.novelFontSize.changes().drop(1),
                preferences.novelFontFamily.changes().drop(1),
                preferences.novelTheme.changes().drop(1),
                preferences.novelLineHeight.changes().drop(1),
                preferences.novelTextAlign.changes().drop(1),
                preferences.novelMarginLeft.changes().drop(1),
                preferences.novelMarginRight.changes().drop(1),
                preferences.novelMarginTop.changes().drop(1),
                preferences.novelMarginBottom.changes().drop(1),
                preferences.novelFontColor.changes().drop(1),
                preferences.novelBackgroundColor.changes().drop(1),
                preferences.novelParagraphIndent.changes().drop(1),
                preferences.novelParagraphSpacing.changes().drop(1),
                preferences.novelCustomCss.changes().drop(1),
                preferences.novelCustomCssSnippets.changes().drop(1),
                preferences.novelHideChapterTitle.changes().drop(1),
                preferences.novelTextSelectable.changes().drop(1),
            ).collect { onStyleChanged() }
        }

        scope.launch {
            merge(
                preferences.novelCustomJs.changes().drop(1),
                preferences.novelCustomJsSnippets.changes().drop(1),
            ).collect { onScriptChanged() }
        }

        // Reload-requiring preferences:
        //   - Embedded CSS/JS toggles → pipeline re-sanitizes
        //   - Force-lowercase → pipeline re-transforms
        //   - Regex rules → pipeline re-applies
        //   - Source CSS priority → toggles !important on the inline reader
        //     CSS, but embedded source CSS only takes effect once the
        //     document is reloaded so the new specificity layers settle
        //   - Use-original-fonts → the @font-face declaration is baked into
        //     the document head at assemble time; only a reload picks up
        //     the new font choice (cf. NovelWebViewDocumentBuilder)
        scope.launch {
            merge(
                preferences.enableEpubStyles.changes().drop(1),
                preferences.enableEpubJs.changes().drop(1),
                preferences.novelForceTextLowercase.changes().drop(1),
                preferences.novelRegexReplacements.changes().drop(1),
                preferences.novelSourceCssPriority.changes().drop(1),
                preferences.novelUseOriginalFonts.changes().drop(1),
            ).collect { onChapterReloadRequested() }
        }

        scope.launch {
            preferences.novelBlockMedia.changes()
                .drop(1)
                .collect { blockMedia -> onBlockMediaChanged(blockMedia) }
        }

        scope.launch {
            merge(
                preferences.novelTtsVoice.changes(),
                preferences.novelTtsSpeed.changes(),
                preferences.novelTtsPitch.changes(),
            )
                .drop(TTS_PREF_COUNT)
                .collect { onTtsSettingsChanged() }
        }
    }

    companion object {
        private const val TTS_PREF_COUNT = 3
    }
}
