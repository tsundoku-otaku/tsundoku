package eu.kanade.tachiyomi.ui.reader.viewer.text.textview

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

internal class NovelTextViewPreferenceObserver(
    private val preferences: ReaderPreferences,
    private val scope: CoroutineScope,
    private val onStylePrefChanged: () -> Unit,
    private val onContentReloadRequested: () -> Unit,
    private val onTtsSettingsChanged: () -> Unit,
    private val onInfiniteScrollChanged: (Boolean) -> Unit,
) {

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
        // drop counts = number of merged flows, suppresses initial-emit spurious refresh
        private const val STYLE_PREF_COUNT = 11
        private const val CONTENT_PREF_COUNT = 7
        private const val LOWERCASE_AND_TITLE_PREF_COUNT = 2
        private const val TTS_PREF_COUNT = 3
    }
}
