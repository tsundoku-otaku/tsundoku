package eu.kanade.presentation.reader.settings

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.font.FontManager
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.InlineSettingsChipRow
import tachiyomi.presentation.core.components.RadioSelectItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.StepperItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Serializable
data class CodeSnippet(
    val title: String,
    val code: String,
    val enabled: Boolean = true,
)

@Serializable
data class RegexReplacement(
    val title: String,
    val pattern: String,
    val replacement: String,
    val enabled: Boolean = true,
    val isRegex: Boolean = true,
)

private val novelThemes = listOf(
    TDMR.strings.novel_theme_app to "app",
    TDMR.strings.novel_theme_light to "light",
    TDMR.strings.novel_theme_dark to "dark",
    TDMR.strings.novel_theme_sepia to "sepia",
    TDMR.strings.novel_theme_black to "black",
    TDMR.strings.novel_theme_grey to "grey",
    TDMR.strings.novel_theme_custom to "custom",
)

// System fonts - always available
private val systemFonts = listOf(
    TDMR.strings.novel_font_sans_serif to "sans-serif",
    TDMR.strings.novel_font_serif to "serif",
    TDMR.strings.novel_font_monospace to "monospace",
    TDMR.strings.novel_font_georgia to "Georgia, serif",
    TDMR.strings.novel_font_times to "Times New Roman, serif",
    TDMR.strings.novel_font_arial to "Arial, sans-serif",
)

private val textAlignments = listOf(
    TDMR.strings.novel_align_left to "left",
    TDMR.strings.novel_align_center to "center",
    TDMR.strings.novel_align_right to "right",
    TDMR.strings.novel_align_justify to "justify",
)

private val renderingModes = listOf(
    TDMR.strings.novel_render_default to "default",
    TDMR.strings.novel_render_webview to "webview",
)

// Predefined font colors (ARGB int format, 0 = theme default, Int.MIN_VALUE = custom)
private val fontColors = listOf(
    TDMR.strings.novel_color_default to 0,
    TDMR.strings.novel_color_black to 0xFF000000.toInt(),
    TDMR.strings.novel_color_white to 0xFFFFFFFF.toInt(),
    TDMR.strings.novel_color_gray to 0xFF808080.toInt(),
    TDMR.strings.novel_color_dark_gray to 0xFF404040.toInt(),
    TDMR.strings.novel_color_light_gray to 0xFFC0C0C0.toInt(),
    TDMR.strings.novel_color_off_white to 0xFFCCCCCC.toInt(),
    TDMR.strings.novel_color_sepia to 0xFF5C4033.toInt(),
    TDMR.strings.novel_color_custom to Int.MIN_VALUE,
)

// Predefined background colors (ARGB int format, 0 = theme default, Int.MIN_VALUE = custom)
private val backgroundColors = listOf(
    TDMR.strings.novel_color_default to 0,
    TDMR.strings.novel_color_white to 0xFFFFFFFF.toInt(),
    TDMR.strings.novel_color_black to 0xFF000000.toInt(),
    TDMR.strings.novel_color_light_gray to 0xFFF5F5F5.toInt(),
    TDMR.strings.novel_color_dark_gray to 0xFF1A1A1A.toInt(),
    TDMR.strings.novel_color_sepia to 0xFFF4ECD8.toInt(),
    TDMR.strings.novel_color_cream to 0xFFFFFDD0.toInt(),
    TDMR.strings.novel_color_charcoal to 0xFF292832.toInt(),
    TDMR.strings.novel_color_custom to Int.MIN_VALUE,
)

@Composable
internal fun ColumnScope.NovelReadingTab(screenModel: ReaderSettingsScreenModel, renderingMode: String) {
    val context = LocalContext.current
    val fontFamily by screenModel.preferences.novelFontFamily().collectAsState()
    val textAlign by screenModel.preferences.novelTextAlign().collectAsState()
    val autoSplitEnabled by screenModel.preferences.novelAutoSplitText().collectAsState()
    val autoSplitWordCount by screenModel.preferences.novelAutoSplitWordCount().collectAsState()

    // Load custom fonts from FontManager
    val fontManager = remember { FontManager(context) }
    val resolvedSystemFonts = systemFonts.map { (labelRes, value) -> stringResource(labelRes) to value }
    val allFonts by produceState(initialValue = resolvedSystemFonts) {
        val customFonts = fontManager.getInstalledFonts().map { font ->
            font.name to font.path
        }
        value = resolvedSystemFonts + customFonts
    }

    // Rendering Mode
    InlineSettingsChipRow(TDMR.strings.pref_novel_rendering_mode) {
        renderingModes.map { (labelRes, value) ->
            FilterChip(
                selected = renderingMode == value,
                onClick = { screenModel.preferences.novelRenderingMode().set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    // Font Size
    StepperItem(
        label = stringResource(TDMR.strings.pref_font_size),
        pref = screenModel.preferences.novelFontSize(),
        valueRange = 10..40,
    )

    // Line Height
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_line_height),
        pref = screenModel.preferences.novelLineHeight(),
        valueRange = 10..30,
        multiplier = 10,
    )

    // Paragraph Indentation
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_paragraph_indent),
        pref = screenModel.preferences.novelParagraphIndent(),
        valueRange = 0..100,
        multiplier = 10,
    )

    // Paragraph Spacing
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_paragraph_spacing),
        pref = screenModel.preferences.novelParagraphSpacing(),
        valueRange = 0..30,
        multiplier = 10,
    )

    // Margins
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_margin_left),
        pref = screenModel.preferences.novelMarginLeft(),
        valueRange = 0..100,
    )
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_margin_right),
        pref = screenModel.preferences.novelMarginRight(),
        valueRange = 0..100,
    )
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_margin_top),
        pref = screenModel.preferences.novelMarginTop(),
        valueRange = 0..300,
    )
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_margin_bottom),
        pref = screenModel.preferences.novelMarginBottom(),
        valueRange = 0..300,
    )

    // Font Family
    RadioSelectItem(
        label = stringResource(TDMR.strings.pref_font_family),
        options = allFonts,
        selected = fontFamily,
        onSelect = { screenModel.preferences.novelFontFamily().set(it) },
        defaultValue = screenModel.preferences.novelFontFamily().defaultValue(),
    )

    // Use Original Fonts (WebView mode only)
    if (renderingMode == "webview") {
        CheckboxItem(
            label = stringResource(TDMR.strings.pref_novel_use_original_fonts),
            pref = screenModel.preferences.novelUseOriginalFonts(),
        )
    }

    // Text Alignment
    SettingsChipRow(TDMR.strings.pref_novel_text_align) {
        textAlignments.map { (labelRes, value) ->
            FilterChip(
                selected = textAlign == value,
                onClick = { screenModel.preferences.novelTextAlign().set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    HorizontalDivider()

    // Auto-split paragraphs
    CheckboxItem(
        label = stringResource(TDMR.strings.novel_auto_split),
        pref = screenModel.preferences.novelAutoSplitText(),
    )

    // Word count threshold (only shown when enabled)
    if (autoSplitEnabled) {
        SliderItem(
            label = stringResource(TDMR.strings.novel_split_word_count),
            value = autoSplitWordCount / 50,
            valueRange = 1..40,
            onChange = { screenModel.preferences.novelAutoSplitWordCount().set(it * 50) },
        )
    }
}

@Composable
internal fun ColumnScope.NovelAppearanceTab(screenModel: ReaderSettingsScreenModel, renderingMode: String) {
    val theme by screenModel.preferences.novelTheme().collectAsState()
    val fontColor by screenModel.preferences.novelFontColor().collectAsState()
    val backgroundColor by screenModel.preferences.novelBackgroundColor().collectAsState()
    var showFontColorPicker by remember { mutableStateOf(false) }
    var showBgColorPicker by remember { mutableStateOf(false) }

    // Color picker dialogs
    if (showFontColorPicker) {
        ColorPickerDialog(
            title = stringResource(TDMR.strings.pref_novel_font_color),
            initialColor = if (fontColor != 0) fontColor else 0xFF000000.toInt(),
            onDismiss = { showFontColorPicker = false },
            onConfirm = { color ->
                screenModel.preferences.novelFontColor().set(color)
                showFontColorPicker = false
            },
        )
    }

    if (showBgColorPicker) {
        ColorPickerDialog(
            title = stringResource(TDMR.strings.pref_novel_background_color),
            initialColor = if (backgroundColor != 0) backgroundColor else 0xFFFFFFFF.toInt(),
            onDismiss = { showBgColorPicker = false },
            onConfirm = { color ->
                screenModel.preferences.novelBackgroundColor().set(color)
                screenModel.preferences.novelTheme().set("custom")
                showBgColorPicker = false
            },
        )
    }

    // Theme
    SettingsChipRow(TDMR.strings.pref_novel_theme) {
        novelThemes.map { (labelRes, value) ->
            FilterChip(
                selected = theme == value,
                onClick = { screenModel.preferences.novelTheme().set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    // Font Color
    SettingsChipRow(TDMR.strings.pref_novel_font_color) {
        fontColors.map { (labelRes, colorValue) ->
            val isCustom = colorValue == Int.MIN_VALUE
            val isSelected = if (isCustom) {
                fontColors.none { it.second == fontColor } && fontColor != 0
            } else {
                fontColor == colorValue
            }
            FilterChip(
                selected = isSelected,
                onClick = {
                    if (isCustom) {
                        showFontColorPicker = true
                    } else {
                        screenModel.preferences.novelFontColor().set(colorValue)
                    }
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val displayColor = when {
                            isCustom && isSelected && fontColor != 0 -> Color(fontColor)
                            !isCustom && colorValue != 0 -> Color(colorValue)
                            else -> null
                        }
                        if (displayColor != null) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(displayColor)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                            )
                        }
                        if (isCustom) {
                            Icon(
                                Icons.Outlined.Palette,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            stringResource(labelRes),
                            modifier = Modifier.padding(
                                start = if (displayColor != null ||
                                    isCustom
                                ) {
                                    4.dp
                                } else {
                                    0.dp
                                },
                            ),
                        )
                    }
                },
            )
        }
    }

    // Background Color
    SettingsChipRow(TDMR.strings.pref_novel_background_color) {
        backgroundColors.map { (labelRes, colorValue) ->
            val isCustom = colorValue == Int.MIN_VALUE
            val isSelected = if (isCustom) {
                backgroundColors.none { it.second == backgroundColor } && backgroundColor != 0
            } else {
                backgroundColor == colorValue
            }
            FilterChip(
                selected = isSelected,
                onClick = {
                    if (isCustom) {
                        showBgColorPicker = true
                    } else {
                        screenModel.preferences.novelBackgroundColor().set(colorValue)
                        if (colorValue != 0) {
                            screenModel.preferences.novelTheme().set("custom")
                        }
                    }
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val displayColor = when {
                            isCustom && isSelected && backgroundColor != 0 -> Color(backgroundColor)
                            !isCustom && colorValue != 0 -> Color(colorValue)
                            else -> null
                        }
                        if (displayColor != null) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(displayColor)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                            )
                        }
                        if (isCustom) {
                            Icon(
                                Icons.Outlined.Palette,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            stringResource(labelRes),
                            modifier = Modifier.padding(
                                start = if (displayColor != null ||
                                    isCustom
                                ) {
                                    4.dp
                                } else {
                                    0.dp
                                },
                            ),
                        )
                    }
                },
            )
        }
    }

    // Custom Brightness
    val novelCustomBrightness by screenModel.preferences.novelCustomBrightness().collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_brightness),
        pref = screenModel.preferences.novelCustomBrightness(),
    )

    if (novelCustomBrightness) {
        val novelCustomBrightnessValue by screenModel.preferences.novelCustomBrightnessValue().collectAsState()
        SliderItem(
            value = novelCustomBrightnessValue,
            valueRange = -75..100,
            steps = 0,
            label = stringResource(MR.strings.pref_custom_brightness),
            onChange = { screenModel.preferences.novelCustomBrightnessValue().set(it) },
        )
    }

    // Keep Screen On
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_keep_screen_on),
        pref = screenModel.preferences.novelKeepScreenOn(),
    )
}

@Composable
internal fun ColumnScope.NovelControlsTab(screenModel: ReaderSettingsScreenModel, renderingMode: String) {
    val autoScrollSpeed by screenModel.preferences.novelAutoScrollSpeed().collectAsState()
    val chapterSortOrder by screenModel.preferences.novelChapterSortOrder().collectAsState()

    // Auto Scroll Speed
    SliderItem(
        label = stringResource(TDMR.strings.pref_novel_auto_scroll_speed),
        value = autoScrollSpeed,
        valueRange = 1..10,
        onChange = { screenModel.preferences.novelAutoScrollSpeed().set(it) },
    )

    // Volume Keys to Scroll
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_volume_keys_scroll),
        pref = screenModel.preferences.novelVolumeKeysScroll(),
    )

    // Tap to Scroll
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_tap_to_scroll),
        pref = screenModel.preferences.novelTapToScroll(),
    )

    // Swipe Navigation
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_swipe_navigation),
        pref = screenModel.preferences.novelSwipeNavigation(),
    )

    // Text Selection (WebView reader only)
    if (renderingMode == "webview") {
        CheckboxItem(
            label = stringResource(TDMR.strings.pref_novel_text_selectable),
            pref = screenModel.preferences.novelTextSelectable(),
        )
    }

    // Hide Chapter Title in Content
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_hide_chapter_title),
        pref = screenModel.preferences.novelHideChapterTitle(),
    )

    // Force Lowercase Text
    CheckboxItem(
        label = stringResource(TDMR.strings.novel_force_lowercase),
        pref = screenModel.preferences.novelForceTextLowercase(),
    )

    // Chapter Title Display Format
    val chapterTitleDisplay by screenModel.preferences.novelChapterTitleDisplay().collectAsState()
    val titleDisplayOptions = listOf(
        stringResource(TDMR.strings.novel_chapter_display_name) to 0,
        stringResource(TDMR.strings.novel_chapter_display_number) to 1,
        stringResource(TDMR.strings.novel_chapter_display_both) to 2,
    )
    SettingsChipRow(TDMR.strings.pref_novel_chapter_title_display) {
        titleDisplayOptions.map { (label, value) ->
            FilterChip(
                selected = chapterTitleDisplay == value,
                onClick = { screenModel.preferences.novelChapterTitleDisplay().set(value) },
                label = { Text(label) },
            )
        }
    }

    // Progress Slider
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_progress_slider),
        pref = screenModel.preferences.novelShowProgressSlider(),
    )

    // Infinite Scroll
    val infiniteScrollEnabled by screenModel.preferences.novelInfiniteScroll().collectAsState()
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_infinite_scroll),
        checked = infiniteScrollEnabled,
        onClick = { screenModel.preferences.novelInfiniteScroll().set(!infiniteScrollEnabled) },
    )

    // Auto-load next chapter at percentage (only relevant when infinite scroll is enabled)
    val autoLoadAt by screenModel.preferences.novelAutoLoadNextChapterAt().collectAsState()
    LaunchedEffect(autoLoadAt) {
        // Older installs may have persisted 0; treat it as legacy/unset and normalize to default.
        if (autoLoadAt <= 0) {
            screenModel.preferences.novelAutoLoadNextChapterAt().set(95)
        }
    }
    if (infiniteScrollEnabled) {
        val effectiveAutoLoadAt = if (autoLoadAt > 0) autoLoadAt else 95
        SliderItem(
            label = stringResource(TDMR.strings.pref_novel_auto_load_next_at),
            value = effectiveAutoLoadAt,
            valueRange = 1..99,
            valueString = "$effectiveAutoLoadAt%",
            onChange = { screenModel.preferences.novelAutoLoadNextChapterAt().set(it) },
        )
    }

    // Block Media (images, videos, audio)
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_block_media),
        pref = screenModel.preferences.novelBlockMedia(),
    )

    // Show Raw HTML (TextView only) - for debugging
    if (renderingMode == "default") {
        CheckboxItem(
            label = stringResource(TDMR.strings.pref_novel_show_raw_html),
            pref = screenModel.preferences.novelShowRawHtml(),
        )
    }

    // Chapter Sort Order
    val sortOrderOptions = listOf(
        stringResource(TDMR.strings.novel_sort_source) to "source",
        stringResource(TDMR.strings.novel_sort_chapter) to "chapter_number",
    )
    SettingsChipRow(TDMR.strings.pref_novel_chapter_sort_order) {
        sortOrderOptions.map { (label, value) ->
            FilterChip(
                selected = chapterSortOrder == value,
                onClick = { screenModel.preferences.novelChapterSortOrder().set(value) },
                label = { Text(label) },
            )
        }
    }

    // TTS Settings Section
    TtsSettingsSection(screenModel)
}

@Composable
internal fun ColumnScope.NovelAdvancedTab(screenModel: ReaderSettingsScreenModel, renderingMode: String) {
    RegexReplacementSection(screenModel)

    if (renderingMode != "webview") {
        return
    }

    val cssSnippetsJson by screenModel.preferences.novelCustomCssSnippets().collectAsState()
    val jsSnippetsJson by screenModel.preferences.novelCustomJsSnippets().collectAsState()

    var showCssDialog by remember { mutableStateOf(false) }
    var showJsDialog by remember { mutableStateOf(false) }
    var editingCssSnippet by remember { mutableStateOf<Pair<Int, CodeSnippet>?>(null) }
    var editingJsSnippet by remember { mutableStateOf<Pair<Int, CodeSnippet>?>(null) }

    val cssSnippets = remember(cssSnippetsJson) {
        try {
            Json.decodeFromString<List<CodeSnippet>>(cssSnippetsJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    val jsSnippets = remember(jsSnippetsJson) {
        try {
            Json.decodeFromString<List<CodeSnippet>>(jsSnippetsJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // CSS Snippets Section
    SnippetSection(
        title = stringResource(TDMR.strings.pref_novel_css_snippets),
        snippets = cssSnippets,
        onAddClick = { showCssDialog = true },
        onEditClick = { index, snippet -> editingCssSnippet = index to snippet },
        onDeleteClick = { index ->
            val updated = cssSnippets.toMutableList().apply { removeAt(index) }
            screenModel.preferences.novelCustomCssSnippets().set(Json.encodeToString(updated))
        },
        onToggleClick = { index ->
            val updated = cssSnippets.toMutableList().apply {
                this[index] = this[index].copy(enabled = !this[index].enabled)
            }
            screenModel.preferences.novelCustomCssSnippets().set(Json.encodeToString(updated))
        },
    )

    // JS Snippets Section
    SnippetSection(
        title = stringResource(TDMR.strings.pref_novel_js_snippets),
        snippets = jsSnippets,
        onAddClick = { showJsDialog = true },
        onEditClick = { index, snippet -> editingJsSnippet = index to snippet },
        onDeleteClick = { index ->
            val updated = jsSnippets.toMutableList().apply { removeAt(index) }
            screenModel.preferences.novelCustomJsSnippets().set(Json.encodeToString(updated))
        },
        onToggleClick = { index ->
            val updated = jsSnippets.toMutableList().apply {
                this[index] = this[index].copy(enabled = !this[index].enabled)
            }
            screenModel.preferences.novelCustomJsSnippets().set(Json.encodeToString(updated))
        },
    )

    // CSS Add/Edit Dialog
    if (showCssDialog || editingCssSnippet != null) {
        SnippetEditDialog(
            title = if (editingCssSnippet != null) {
                stringResource(TDMR.strings.novel_edit_css_snippet)
            } else {
                stringResource(TDMR.strings.novel_add_css_snippet)
            },
            initialSnippet = editingCssSnippet?.second,
            onDismiss = {
                showCssDialog = false
                editingCssSnippet = null
            },
            onConfirm = { snippet ->
                val updated = cssSnippets.toMutableList()
                if (editingCssSnippet != null) {
                    updated[editingCssSnippet!!.first] = snippet
                } else {
                    updated.add(snippet)
                }
                screenModel.preferences.novelCustomCssSnippets().set(Json.encodeToString(updated))
                showCssDialog = false
                editingCssSnippet = null
            },
        )
    }

    // JS Add/Edit Dialog
    if (showJsDialog || editingJsSnippet != null) {
        SnippetEditDialog(
            title = if (editingJsSnippet != null) {
                stringResource(TDMR.strings.novel_edit_js_snippet)
            } else {
                stringResource(TDMR.strings.novel_add_js_snippet)
            },
            initialSnippet = editingJsSnippet?.second,
            onDismiss = {
                showJsDialog = false
                editingJsSnippet = null
            },
            onConfirm = { snippet ->
                val updated = jsSnippets.toMutableList()
                if (editingJsSnippet != null) {
                    updated[editingJsSnippet!!.first] = snippet
                } else {
                    updated.add(snippet)
                }
                screenModel.preferences.novelCustomJsSnippets().set(Json.encodeToString(updated))
                showJsDialog = false
                editingJsSnippet = null
            },
        )
    }
}

@Composable
private fun SnippetSection(
    title: String,
    snippets: List<CodeSnippet>,
    onAddClick: () -> Unit,
    onEditClick: (Int, CodeSnippet) -> Unit,
    onDeleteClick: (Int) -> Unit,
    onToggleClick: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Code,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            IconButton(onClick = onAddClick) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(TDMR.strings.novel_add_snippet))
            }
        }

        snippets.forEachIndexed { index, snippet ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onToggleClick(index) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = snippet.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (snippet.enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            },
                        )
                        Text(
                            text = if (snippet.enabled) stringResource(TDMR.strings.novel_enabled) else stringResource(TDMR.strings.novel_disabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (snippet.enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            },
                        )
                    }
                    Row {
                        IconButton(onClick = { onEditClick(index, snippet) }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(TDMR.strings.novel_edit_snippet),
                            )
                        }
                        IconButton(onClick = { onDeleteClick(index) }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(TDMR.strings.novel_delete_snippet),
                            )
                        }
                    }
                }
            }
        }

        if (snippets.isEmpty()) {
            Text(
                text = stringResource(TDMR.strings.novel_no_snippets),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SnippetEditDialog(
    title: String,
    initialSnippet: CodeSnippet?,
    onDismiss: () -> Unit,
    onConfirm: (CodeSnippet) -> Unit,
) {
    var snippetTitle by remember { mutableStateOf(initialSnippet?.title ?: "") }
    var snippetCode by remember { mutableStateOf(initialSnippet?.code ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = snippetTitle,
                    onValueChange = { snippetTitle = it },
                    label = { Text(stringResource(TDMR.strings.novel_snippet_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = snippetCode,
                    onValueChange = { snippetCode = it },
                    label = { Text(stringResource(TDMR.strings.novel_snippet_code)) },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (snippetTitle.isNotBlank() && snippetCode.isNotBlank()) {
                        onConfirm(CodeSnippet(snippetTitle.trim(), snippetCode, initialSnippet?.enabled ?: true))
                    }
                },
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

/**
 * Regex find/replace section — available for both WebView and TextView modes.
 * Rules are applied to chapter HTML content before rendering.
 */
@Composable
private fun ColumnScope.RegexReplacementSection(screenModel: ReaderSettingsScreenModel) {
    val regexJson by screenModel.preferences.novelRegexReplacements().collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<Pair<Int, RegexReplacement>?>(null) }

    val rules = remember(regexJson) {
        try {
            Json.decodeFromString<List<RegexReplacement>>(regexJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.FindReplace,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = stringResource(TDMR.strings.novel_regex_find_replace),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(TDMR.strings.novel_add_rule))
            }
        }

        rules.forEachIndexed { index, rule ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        val updated = rules.toMutableList().apply {
                            this[index] = this[index].copy(enabled = !this[index].enabled)
                        }
                        screenModel.preferences.novelRegexReplacements().set(Json.encodeToString(updated))
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rule.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (rule.enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            },
                        )
                        Text(
                            text = buildString {
                                append(if (rule.isRegex) "regex" else "text")
                                append(" • ")
                                append(if (rule.enabled) "Enabled" else "Disabled")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (rule.enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            },
                        )
                        Text(
                            text = "/${rule.pattern}/ → ${rule.replacement.ifEmpty { "(remove)" }}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Row {
                        IconButton(onClick = { editingRule = index to rule }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(MR.strings.action_edit))
                        }
                        IconButton(onClick = {
                            val updated = rules.toMutableList().apply { removeAt(index) }
                            screenModel.preferences.novelRegexReplacements().set(Json.encodeToString(updated))
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.action_delete))
                        }
                    }
                }
            }
        }

        if (rules.isEmpty()) {
            Text(
                text = stringResource(TDMR.strings.novel_no_rules),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }

    if (showAddDialog || editingRule != null) {
        RegexEditDialog(
            initialRule = editingRule?.second,
            onDismiss = {
                showAddDialog = false
                editingRule = null
            },
            onConfirm = { rule ->
                val updated = rules.toMutableList()
                if (editingRule != null) {
                    updated[editingRule!!.first] = rule
                } else {
                    updated.add(rule)
                }
                screenModel.preferences.novelRegexReplacements().set(Json.encodeToString(updated))
                showAddDialog = false
                editingRule = null
            },
        )
    }
}

@Composable
private fun RegexEditDialog(
    initialRule: RegexReplacement?,
    onDismiss: () -> Unit,
    onConfirm: (RegexReplacement) -> Unit,
) {
    var title by remember { mutableStateOf(initialRule?.title ?: "") }
    var pattern by remember { mutableStateOf(initialRule?.pattern ?: "") }
    var replacement by remember { mutableStateOf(initialRule?.replacement ?: "") }
    var isRegex by remember { mutableStateOf(initialRule?.isRegex ?: true) }
    var testInput by remember { mutableStateOf("") }
    var testOutput by remember { mutableStateOf<String?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }

    // Pre-compute strings for non-composable onClick callbacks
    val patternEmptyText = stringResource(TDMR.strings.novel_pattern_empty)
    val invalidRegexText = stringResource(TDMR.strings.novel_invalid_regex)
    val invalidRegexFormatText = stringResource(TDMR.strings.novel_invalid_regex_format, "%s")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialRule != null) stringResource(TDMR.strings.novel_edit_rule) else stringResource(TDMR.strings.novel_add_rule_title))
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(TDMR.strings.novel_rule_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = {
                        pattern = it
                        testOutput = null
                        testError = null
                    },
                    label = { Text(if (isRegex) stringResource(TDMR.strings.novel_regex_pattern) else stringResource(TDMR.strings.novel_find_text)) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = {
                        replacement = it
                        testOutput = null
                    },
                    label = { Text(stringResource(TDMR.strings.novel_replace_with)) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = isRegex,
                        onCheckedChange = {
                            isRegex = it
                            testOutput = null
                            testError = null
                        },
                    )
                    Text(stringResource(TDMR.strings.novel_use_regex), modifier = Modifier.padding(start = 4.dp))
                }

                // Test section
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(TDMR.strings.novel_test),
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = testInput,
                    onValueChange = {
                        testInput = it
                        testOutput = null
                        testError = null
                    },
                    label = { Text(stringResource(TDMR.strings.novel_sample_input)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                TextButton(
                    onClick = {
                        if (pattern.isBlank()) {
                            testError = patternEmptyText
                            return@TextButton
                        }
                        try {
                            testOutput = if (isRegex) {
                                val regex = Regex(pattern)
                                regex.replace(testInput, replacement)
                            } else {
                                testInput.replace(pattern, replacement)
                            }
                            testError = null
                        } catch (e: Exception) {
                            testError = e.message ?: invalidRegexText
                            testOutput = null
                        }
                    },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(TDMR.strings.novel_run_test))
                }
                testOutput?.let {
                    Text(
                        text = stringResource(TDMR.strings.novel_output_format, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                testError?.let {
                    Text(
                        text = stringResource(TDMR.strings.novel_error_format, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank() && pattern.isNotBlank()) {
                        if (isRegex) {
                            try {
                                Regex(pattern)
                            } catch (e: Exception) {
                                testError = invalidRegexFormatText.replace("%s", e.message ?: "")
                                return@TextButton
                            }
                        }
                        onConfirm(
                            RegexReplacement(title.trim(), pattern, replacement, initialRule?.enabled ?: true, isRegex),
                        )
                    }
                },
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.TtsSettingsSection(screenModel: ReaderSettingsScreenModel) {
    val context = LocalContext.current
    val ttsSpeed by screenModel.preferences.novelTtsSpeed().collectAsState()
    val ttsPitch by screenModel.preferences.novelTtsPitch().collectAsState()
    val ttsVoice by screenModel.preferences.novelTtsVoice().collectAsState()

    // Load available voices using TTS
    val availableVoices = remember { mutableStateListOf<Pair<String, String>>() }
    var ttsReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val voices = tts?.voices ?: emptySet()
                availableVoices.clear()
                availableVoices.add("" to context.stringResource(TDMR.strings.novel_tts_default_voice))
                voices.filter { !it.isNetworkConnectionRequired }
                    .sortedBy { "${it.locale.displayLanguage} (${it.name})" }
                    .forEach { voice ->
                        val displayName = "${voice.locale.displayLanguage} (${voice.name})"
                        availableVoices.add(voice.name to displayName)
                    }
            }
        }
        onDispose {
            tts?.shutdown()
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Section Header
    Text(
        text = stringResource(TDMR.strings.pref_novel_tts_section),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )

    // Voice Selection Dropdown
    if (availableVoices.isNotEmpty()) {
        var expanded by remember { mutableStateOf(false) }
        val selectedVoiceDisplay = availableVoices.find { it.first == ttsVoice }?.second
            ?: "Default (System)"

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(TDMR.strings.pref_novel_tts_voice),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
            ) {
                OutlinedTextField(
                    value = selectedVoiceDisplay,
                    onValueChange = { },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    availableVoices.forEach { (voiceName, displayName) ->
                        DropdownMenuItem(
                            text = { Text(displayName) },
                            onClick = {
                                screenModel.preferences.novelTtsVoice().set(voiceName)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }

    // Speech Speed Slider (0.5x to 2.0x)
    SliderItem(
        label = stringResource(TDMR.strings.pref_novel_tts_speed),
        value = (ttsSpeed * 10).toInt(),
        valueRange = 5..20,
        onChange = { screenModel.preferences.novelTtsSpeed().set(it / 10f) },
        valueString = String.format("%.1fx", ttsSpeed),
    )

    // Speech Pitch Slider (0.5x to 2.0x)
    SliderItem(
        label = stringResource(TDMR.strings.pref_novel_tts_pitch),
        value = (ttsPitch * 10).toInt(),
        valueRange = 5..20,
        onChange = { screenModel.preferences.novelTtsPitch().set(it / 10f) },
        valueString = String.format("%.1fx", ttsPitch),
    )

    // Auto-play next chapter
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_tts_auto_next),
        pref = screenModel.preferences.novelTtsAutoNextChapter(),
    )
}

/**
 * A simple RGB color picker dialog with sliders.
 */
@Composable
private fun ColorPickerDialog(
    title: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var red by remember { mutableIntStateOf((initialColor shr 16) and 0xFF) }
    var green by remember { mutableIntStateOf((initialColor shr 8) and 0xFF) }
    var blue by remember { mutableIntStateOf(initialColor and 0xFF) }
    var hexInput by remember { mutableStateOf(String.format("%06X", initialColor and 0xFFFFFF)) }

    val currentColor = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // Color preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(currentColor))
                        .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Hex input
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        val sanitized = input.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                        hexInput = sanitized
                        if (sanitized.length == 6) {
                            try {
                                val parsed = sanitized.toLong(16).toInt()
                                red = (parsed shr 16) and 0xFF
                                green = (parsed shr 8) and 0xFF
                                blue = parsed and 0xFF
                            } catch (_: Exception) {}
                        }
                    },
                    label = { Text("Hex Color") },
                    prefix = { Text("#") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Red slider
                Text("Red: $red", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = red.toFloat(),
                    onValueChange = {
                        red = it.toInt()
                        hexInput = String.format("%06X", currentColor and 0xFFFFFF)
                    },
                    valueRange = 0f..255f,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Green slider
                Text("Green: $green", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = green.toFloat(),
                    onValueChange = {
                        green = it.toInt()
                        hexInput = String.format("%06X", currentColor and 0xFFFFFF)
                    },
                    valueRange = 0f..255f,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Blue slider
                Text("Blue: $blue", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = blue.toFloat(),
                    onValueChange = {
                        blue = it.toInt()
                        hexInput = String.format("%06X", currentColor and 0xFFFFFF)
                    },
                    valueRange = 0f..255f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentColor) }) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
