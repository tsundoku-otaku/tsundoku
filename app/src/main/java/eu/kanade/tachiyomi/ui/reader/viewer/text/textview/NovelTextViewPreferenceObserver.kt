package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

internal class NovelTextViewPreferenceObserver(
    private val preferences: ReaderPreferences,
    private val scope: CoroutineScope,
    private val onStylePrefChanged: () -> Unit,
    private val onContentReloadRequested: () -> Unit,
    private val onTtsSettingsChanged: () -> Unit,
) {

    @OptIn(FlowPreview::class)
    fun observe() {
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
                .debounce(STYLE_DEBOUNCE_MS)
                .collect { onStylePrefChanged() }
        }

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
            // Infinite scroll is a structural change (single flow vs. multi-chapter appends), so
            // reload the whole view for a consistent state instead of patching it live.
            preferences.novelInfiniteScroll.changes()
                .drop(1)
                .collectLatest { onContentReloadRequested() }
        }

        scope.launch {
            preferences.novelTextSelectable.changes()
                .drop(1)
                .collectLatest { onContentReloadRequested() }
        }
    }

    companion object {
        // drop counts = number of merged flows, suppresses initial-emit spurious refresh
        private const val STYLE_PREF_COUNT = 11
        private const val CONTENT_PREF_COUNT = 7
        private const val LOWERCASE_AND_TITLE_PREF_COUNT = 2
        private const val TTS_PREF_COUNT = 3
        private const val STYLE_DEBOUNCE_MS = 120L
    }
}
