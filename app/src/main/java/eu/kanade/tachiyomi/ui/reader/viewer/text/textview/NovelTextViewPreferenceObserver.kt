package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Wires reader-preference flows to [NovelViewer] actions.
 *
 * The viewer used to launch six `scope.launch { … merge(…).drop(N).collect … }`
 * blocks inline. They are noisy, easy to misorder, and easy to get wrong on
 * the drop-count side (drop the wrong number → spurious refresh on subscribe).
 * This class groups the prefs by reaction and lets the viewer supply each
 * reaction as a lambda.
 *
 * Call [observe] once during viewer construction. The block runs forever on
 * the supplied [scope] and is cancelled when the scope is.
 */
internal class NovelTextViewPreferenceObserver(
    private val preferences: ReaderPreferences,
    private val scope: CoroutineScope,
    private val onStylePrefChanged: () -> Unit,
    private val onContentReloadRequested: () -> Unit,
    private val onTtsSettingsChanged: () -> Unit,
    private val onInfiniteScrollChanged: (Boolean) -> Unit,
) {

    fun observe() {
        // Style-affecting preferences refresh in-place; no chapter reload.
        scope.launch {
            merge(
                preferences.novelFontSize.changes(),
                preferences.novelFontFamily.changes(),
                preferences.novelTheme.changes(),
                preferences.novelLineHeight.changes(),
                preferences.novelTextAlign.changes(),
                preferences.novelMarginLeft.changes(),
                preferences.novelMarginRight.changes(),
                preferences.novelMarginTop.changes(),
                preferences.novelMarginBottom.changes(),
                preferences.novelFontColor.changes(),
                preferences.novelBackgroundColor.changes(),
            )
                .drop(STYLE_PREF_COUNT)
                .collect { onStylePrefChanged() }
        }

        // Content-affecting preferences require re-running the pipeline.
        scope.launch {
            merge(
                preferences.novelParagraphIndent.changes(),
                preferences.novelParagraphSpacing.changes(),
                preferences.novelShowRawHtml.changes(),
                preferences.novelRegexReplacements.changes(),
                preferences.novelAutoSplitText.changes(),
                preferences.novelAutoSplitWordCount.changes(),
                preferences.novelBlockMedia.changes(),
            )
                .drop(CONTENT_PREF_COUNT)
                .collect { onContentReloadRequested() }
        }

        // forceLowercase / hideChapterTitle also trigger a content reload, but
        // they live on a separate flow so the drop count stays small.
        scope.launch {
            merge(
                preferences.novelForceTextLowercase.changes(),
                preferences.novelHideChapterTitle.changes(),
            )
                .drop(LOWERCASE_AND_TITLE_PREF_COUNT)
                .collectLatest { onContentReloadRequested() }
        }

        scope.launch {
            merge(
                preferences.novelTtsVoice.changes(),
                preferences.novelTtsSpeed.changes(),
                preferences.novelTtsPitch.changes(),
            )
                .drop(TTS_PREF_COUNT)
                .collectLatest { onTtsSettingsChanged() }
        }

        scope.launch {
            preferences.novelInfiniteScroll.changes()
                .drop(1)
                .collectLatest { infiniteEnabled -> onInfiniteScrollChanged(infiniteEnabled) }
        }

        scope.launch {
            preferences.novelTextSelectable.changes()
                .drop(1)
                .collectLatest { onContentReloadRequested() }
        }
    }

    companion object {
        // Drop counts match the number of merged flows so the initial
        // emissions don't trigger a spurious refresh on subscribe.
        private const val STYLE_PREF_COUNT = 11
        private const val CONTENT_PREF_COUNT = 7
        private const val LOWERCASE_AND_TITLE_PREF_COUNT = 2
        private const val TTS_PREF_COUNT = 3
    }
}
