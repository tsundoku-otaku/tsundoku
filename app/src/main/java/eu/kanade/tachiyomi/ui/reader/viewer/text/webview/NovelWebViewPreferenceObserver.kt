package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

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
