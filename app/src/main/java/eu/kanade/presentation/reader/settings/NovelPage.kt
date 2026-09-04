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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.automirrored.outlined.FormatAlignRight
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.FormatAlignJustify
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.font.FontManager
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.CheckboxItem
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
    // JS only. Default off so one-shot snippets don't re-run on every infinite-scroll append.
    val runOnAppend: Boolean = false,
    val id: String = "legacy-${java.util.Objects.hash(title, code)}",
)

private val UNSAFE_TITLE_CHARS = Regex("[^A-Za-z0-9._-]")

fun safeTitleOf(title: String): String = title.replace(UNSAFE_TITLE_CHARS, "-")

fun CodeSnippet.safeTitle(): String = safeTitleOf(title)

@Serializable
data class RegexReplacement(
    val title: String,
    val pattern: String,
    val replacement: String,
    val enabled: Boolean = true,
    val isRegex: Boolean = true,
    val matchWholeWord: Boolean = false,
    val caseSensitive: Boolean = false,
    val id: String = java.util.UUID.randomUUID().toString(),
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
    Icons.AutoMirrored.Outlined.FormatAlignLeft to "left",
    Icons.Outlined.FormatAlignCenter to "center",
    Icons.AutoMirrored.Outlined.FormatAlignRight to "right",
    Icons.Outlined.FormatAlignJustify to "justify",
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
internal fun ColumnScope.NovelReadingTab(viewModel: ReaderSettingsViewModel, renderingMode: String) {
    val context = LocalContext.current
    val fontFamily by viewModel.preferences.novelFontFamily.collectAsState()
    val textAlign by viewModel.preferences.novelTextAlign.collectAsState()
    val autoSplitEnabled by viewModel.preferences.novelAutoSplitText.collectAsState()
    val autoSplitWordCount by viewModel.preferences.novelAutoSplitWordCount.collectAsState()

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
        renderingModes.forEach { (labelRes, value) ->
            FilterChip(
                selected = renderingMode == value,
                onClick = { viewModel.preferences.novelRenderingMode.set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    // Font Family
    RadioSelectItem(
        label = stringResource(TDMR.strings.pref_font_family),
        options = allFonts,
        selected = fontFamily,
        onSelect = { viewModel.preferences.novelFontFamily.set(it) },
        defaultValue = viewModel.preferences.novelFontFamily.defaultValue(),
    )

    // Use Original Fonts (WebView mode only)
    if (renderingMode == "webview") {
        CheckboxItem(
            label = stringResource(TDMR.strings.pref_novel_use_original_fonts),
            pref = viewModel.preferences.novelUseOriginalFonts,
        )
    }

    // Text Alignment
    InlineSettingsChipRow(TDMR.strings.pref_novel_text_align) {
        textAlignments.forEach { (icon, value) ->
            IconToggleButton(
                checked = textAlign == value,
                onCheckedChange = { viewModel.preferences.novelTextAlign.set(value) },
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(imageVector = icon, contentDescription = value)
            }
        }
    }

    // Font Size
    StepperItem(
        label = stringResource(TDMR.strings.pref_font_size),
        pref = viewModel.preferences.novelFontSize,
        valueRange = 10..40,
    )

    // Line Height — 1..50 / 10 = 0.1x..5.0x. Below ~0.5x both TextView and
    // WebView collapse lines so they overlap (text becomes unreadable but
    // not crashy). Negative not exposed (pref is Int-backed via multiplier).
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_line_height),
        pref = viewModel.preferences.novelLineHeight,
        valueRange = 1..50,
        multiplier = 10,
    )

    // Paragraph Indentation
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_paragraph_indent),
        pref = viewModel.preferences.novelParagraphIndent,
        valueRange = 0..100,
        multiplier = 10,
    )

    // Paragraph Spacing
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_paragraph_spacing),
        pref = viewModel.preferences.novelParagraphSpacing,
        valueRange = 0..30,
        multiplier = 10,
    )

    // Margins
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_margin_left),
        pref = viewModel.preferences.novelMarginLeft,
        valueRange = 0..100,
    )
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_margin_right),
        pref = viewModel.preferences.novelMarginRight,
        valueRange = 0..100,
    )
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_margin_top),
        pref = viewModel.preferences.novelMarginTop,
        valueRange = 0..300,
    )
    StepperItem(
        label = stringResource(TDMR.strings.pref_novel_margin_bottom),
        pref = viewModel.preferences.novelMarginBottom,
        valueRange = 0..300,
    )

    HorizontalDivider()

    // Auto-split paragraphs
    CheckboxItem(
        label = stringResource(TDMR.strings.novel_auto_split),
        pref = viewModel.preferences.novelAutoSplitText,
    )

    // Word count threshold (only shown when enabled)
    if (autoSplitEnabled) {
        SliderItem(
            label = stringResource(TDMR.strings.novel_split_word_count),
            value = autoSplitWordCount.coerceAtLeast(20),
            valueRange = 20..2000,
            onChange = { viewModel.preferences.novelAutoSplitWordCount.set(it.coerceAtLeast(20)) },
        )
    }
}

@Composable
internal fun ColumnScope.NovelAppearanceTab(viewModel: ReaderSettingsViewModel, renderingMode: String) {
    val theme by viewModel.preferences.novelTheme.collectAsState()
    val fontColor by viewModel.preferences.novelFontColor.collectAsState()
    val backgroundColor by viewModel.preferences.novelBackgroundColor.collectAsState()
    var showFontColorPicker by remember { mutableStateOf(false) }
    var showBgColorPicker by remember { mutableStateOf(false) }

    // Color picker dialogs
    if (showFontColorPicker) {
        ColorPickerDialog(
            title = stringResource(TDMR.strings.pref_novel_font_color),
            initialColor = if (fontColor != 0) fontColor else 0xFF000000.toInt(),
            onDismiss = { showFontColorPicker = false },
            onConfirm = { color ->
                viewModel.preferences.novelFontColor.set(color)
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
                viewModel.preferences.novelBackgroundColor.set(color)
                viewModel.preferences.novelTheme.set("custom")
                showBgColorPicker = false
            },
        )
    }

    // Theme
    SettingsChipRow(TDMR.strings.pref_novel_theme) {
        novelThemes.forEach { (labelRes, value) ->
            FilterChip(
                selected = theme == value,
                onClick = { viewModel.preferences.novelTheme.set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    // Font Color
    SettingsChipRow(TDMR.strings.pref_novel_font_color) {
        fontColors.forEach { (labelRes, colorValue) ->
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
                        viewModel.preferences.novelFontColor.set(colorValue)
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
        backgroundColors.forEach { (labelRes, colorValue) ->
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
                        viewModel.preferences.novelBackgroundColor.set(colorValue)
                        if (colorValue != 0) {
                            viewModel.preferences.novelTheme.set("custom")
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

    // Hide Chapter Title in Content
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_hide_chapter_title),
        pref = viewModel.preferences.novelHideChapterTitle,
    )

    // Force Lowercase Text
    CheckboxItem(
        label = stringResource(TDMR.strings.novel_force_lowercase),
        pref = viewModel.preferences.novelForceTextLowercase,
    )

    // Chapter Title Display Format
    val chapterTitleDisplay by viewModel.preferences.novelChapterTitleDisplay.collectAsState()
    val titleDisplayOptions = listOf(
        stringResource(MR.strings.name) to 0,
        stringResource(TDMR.strings.novel_chapter_display_number) to 1,
        stringResource(TDMR.strings.novel_chapter_display_both) to 2,
    )
    InlineSettingsChipRow(TDMR.strings.pref_novel_chapter_title_display) {
        titleDisplayOptions.forEach { (label, value) ->
            FilterChip(
                selected = chapterTitleDisplay == value,
                onClick = { viewModel.preferences.novelChapterTitleDisplay.set(value) },
                label = { Text(label) },
            )
        }
    }

    // Custom Brightness
    val novelCustomBrightness by viewModel.preferences.novelCustomBrightness.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_brightness),
        pref = viewModel.preferences.novelCustomBrightness,
    )

    if (novelCustomBrightness) {
        val novelCustomBrightnessValue by viewModel.preferences.novelCustomBrightnessValue.collectAsState()
        SliderItem(
            value = novelCustomBrightnessValue,
            valueRange = -75..100,
            steps = 0,
            label = stringResource(MR.strings.pref_custom_brightness),
            onChange = { viewModel.preferences.novelCustomBrightnessValue.set(it) },
        )
    }

    // Keep Screen On
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_keep_screen_on),
        pref = viewModel.preferences.novelKeepScreenOn,
    )

    // Block Media (images, videos, audio)
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_block_media),
        pref = viewModel.preferences.novelBlockMedia,
    )

    // Show Raw HTML (TextView only) - for debugging
    if (renderingMode == "default") {
        CheckboxItem(
            label = stringResource(TDMR.strings.pref_novel_show_raw_html),
            pref = viewModel.preferences.novelShowRawHtml,
        )
    }
}

@Composable
internal fun ColumnScope.NovelControlsTab(viewModel: ReaderSettingsViewModel, renderingMode: String) {
    val autoScrollSpeed by viewModel.preferences.novelAutoScrollSpeed.collectAsState()
    val pagedMode by viewModel.preferences.novelPagedMode.collectAsState()
    val isWebviewPaged = renderingMode == "webview" && pagedMode

    // Auto-scroll has no paged-mode equivalent (continuous scroll vs. discrete page turns), so
    // hide it rather than leave a control that does nothing while paged mode is on.
    if (!isWebviewPaged) {
        SliderItem(
            label = stringResource(TDMR.strings.pref_novel_auto_scroll_speed),
            value = autoScrollSpeed,
            valueRange = 2..20,
            valueString = "${autoScrollSpeed / 2f}",
            onChange = { viewModel.preferences.novelAutoScrollSpeed.set(it) },
        )
    }

    // Volume Keys to Scroll
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_volume_keys_scroll),
        pref = viewModel.preferences.novelVolumeKeysScroll,
    )

    // Tap-zone navigation settings for novel viewer
    val navigationModeNovel by viewModel.preferences.navigationModeNovel.collectAsState()
    val novelNavInverted by viewModel.preferences.novelNavInverted.collectAsState()
    val effectiveNavigationModeNovel = if (navigationModeNovel == ReaderPreferences.TAPZONE_DISABLED_INDEX) {
        0
    } else {
        navigationModeNovel
    }
    SettingsChipRow(MR.strings.pref_viewer_nav) {
        ReaderPreferences.TapZones.forEachIndexed { idx, res ->
            if (idx == 0) {
                FilterChip(
                    selected = effectiveNavigationModeNovel == 0,
                    onClick = {
                        viewModel.preferences.navigationModeNovel.set(ReaderPreferences.TAPZONE_DISABLED_INDEX)
                    },
                    label = { Text(stringResource(res)) },
                )
            } else if (idx != ReaderPreferences.TAPZONE_DISABLED_INDEX) {
                FilterChip(
                    selected = effectiveNavigationModeNovel == idx,
                    onClick = { viewModel.preferences.navigationModeNovel.set(idx) },
                    label = { Text(stringResource(res)) },
                )
            }
        }

        FilterChip(
            selected = navigationModeNovel == ReaderPreferences.TAPZONE_CENTER_INDEX,
            onClick = { viewModel.preferences.navigationModeNovel.set(ReaderPreferences.TAPZONE_CENTER_INDEX) },
            label = { Text(stringResource(TDMR.strings.novel_nav_center_only)) },
        )
        FilterChip(
            selected = navigationModeNovel == ReaderPreferences.TAPZONE_CENTER_LARGE_INDEX,
            onClick = { viewModel.preferences.navigationModeNovel.set(ReaderPreferences.TAPZONE_CENTER_LARGE_INDEX) },
            label = { Text(stringResource(TDMR.strings.novel_nav_center_large)) },
        )
        FilterChip(
            selected = navigationModeNovel == ReaderPreferences.TAPZONE_BOTTOM_INDEX,
            onClick = { viewModel.preferences.navigationModeNovel.set(ReaderPreferences.TAPZONE_BOTTOM_INDEX) },
            label = { Text(stringResource(TDMR.strings.novel_status_bar_position_bottom)) },
        )
    }

    val invertOptions = when {
        effectiveNavigationModeNovel == 0 -> emptyList()
        navigationModeNovel == ReaderPreferences.TAPZONE_CENTER_INDEX ||
            navigationModeNovel == ReaderPreferences.TAPZONE_CENTER_LARGE_INDEX -> emptyList()
        navigationModeNovel == ReaderPreferences.TAPZONE_BOTTOM_INDEX -> listOf(
            ReaderPreferences.TappingInvertMode.NONE to TDMR.strings.novel_status_bar_position_bottom,
            ReaderPreferences.TappingInvertMode.VERTICAL to TDMR.strings.novel_status_bar_position_top,
        )
        else -> ReaderPreferences.TappingInvertMode.entries.map { it to it.titleRes }
    }
    if (invertOptions.isNotEmpty()) {
        SettingsChipRow(MR.strings.pref_read_with_tapping_inverted) {
            invertOptions.forEach { (entry, label) ->
                FilterChip(
                    selected = entry == novelNavInverted,
                    onClick = { viewModel.preferences.novelNavInverted.set(entry) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }

    if (navigationModeNovel == ReaderPreferences.TAPZONE_BOTTOM_INDEX) {
        val bottomZoneHeight by viewModel.preferences.novelBottomZoneHeight.collectAsState()
        SliderItem(
            label = stringResource(TDMR.strings.novel_nav_zone_height),
            value = bottomZoneHeight,
            valueRange = 5..50,
            valueString = "$bottomZoneHeight%",
            onChange = { viewModel.preferences.novelBottomZoneHeight.set(it) },
        )
    }

    // Swipe Navigation - paged mode's swipe IS the page-turn gesture, so this has no meaning
    // while it's on. Gated on isWebviewPaged, not the raw pref: paged mode only ever applies to
    // webview, so this setting must stay visible for a textview-rendered novel either way.
    if (!isWebviewPaged) {
        CheckboxItem(
            label = stringResource(TDMR.strings.pref_novel_swipe_navigation),
            pref = viewModel.preferences.novelSwipeNavigation,
        )
    }

    // Text Selection
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_text_selectable),
        pref = viewModel.preferences.novelTextSelectable,
    )

    // Progress slider mode
    val showProgressSlider by viewModel.preferences.novelShowProgressSlider.collectAsState()
    val showVerticalScrollbar by viewModel.preferences.novelVerticalScrollbar.collectAsState()
    val verticalScrollbarPosition by viewModel.preferences.novelVerticalScrollbarPosition.collectAsState()
    val scrollbarMode = when {
        !showProgressSlider -> "none"
        showVerticalScrollbar && verticalScrollbarPosition == "left" -> "vertical_left"
        showVerticalScrollbar && verticalScrollbarPosition == "right" -> "vertical_right"
        else -> "horizontal"
    }
    val scrollbarModeOptions = listOf(
        stringResource(TDMR.strings.novel_scrollbar_none) to "none",
        stringResource(TDMR.strings.novel_scrollbar_horizontal) to "horizontal",
        stringResource(TDMR.strings.novel_vertical_scrollbar_left) to "vertical_left",
        stringResource(TDMR.strings.novel_vertical_scrollbar_right) to "vertical_right",
    )
    SettingsChipRow(TDMR.strings.pref_novel_scrollbar_mode) {
        scrollbarModeOptions.forEach { (label, value) ->
            FilterChip(
                selected = scrollbarMode == value,
                onClick = {
                    when (value) {
                        "none" -> {
                            viewModel.preferences.novelShowProgressSlider.set(false)
                            viewModel.preferences.novelVerticalScrollbar.set(false)
                        }
                        "horizontal" -> {
                            viewModel.preferences.novelShowProgressSlider.set(true)
                            viewModel.preferences.novelVerticalScrollbar.set(false)
                        }
                        "vertical_left" -> {
                            viewModel.preferences.novelShowProgressSlider.set(true)
                            viewModel.preferences.novelVerticalScrollbarPosition.set("left")
                            viewModel.preferences.novelVerticalScrollbar.set(true)
                        }
                        "vertical_right" -> {
                            viewModel.preferences.novelShowProgressSlider.set(true)
                            viewModel.preferences.novelVerticalScrollbarPosition.set("right")
                            viewModel.preferences.novelVerticalScrollbar.set(true)
                        }
                    }
                },
                label = { Text(label) },
            )
        }
    }

    val verticalProgressSliderSize by viewModel.preferences.novelVerticalProgressSliderSize.collectAsState()
    if (scrollbarMode == "vertical_left" || scrollbarMode == "vertical_right") {
        val verticalSizeOptions = listOf(
            stringResource(TDMR.strings.novel_vertical_progress_slider_half) to "half",
            stringResource(TDMR.strings.novel_vertical_progress_slider_full) to "full",
        )
        InlineSettingsChipRow(TDMR.strings.pref_novel_vertical_progress_slider_size) {
            verticalSizeOptions.forEach { (label, value) ->
                FilterChip(
                    selected = verticalProgressSliderSize == value,
                    onClick = { viewModel.preferences.novelVerticalProgressSliderSize.set(value) },
                    label = { Text(label) },
                )
            }
        }
    }

    // Infinite Scroll - gated on isWebviewPaged (see Swipe Navigation above): stays fully
    // functional, and visible, for a textview-rendered novel regardless of the paged-mode pref.
    val infiniteScrollEnabled by viewModel.preferences.novelInfiniteScroll.collectAsState()
    if (!isWebviewPaged) {
        CheckboxItem(
            label = stringResource(TDMR.strings.pref_novel_infinite_scroll),
            checked = infiniteScrollEnabled,
            onClick = { viewModel.preferences.novelInfiniteScroll.set(!infiniteScrollEnabled) },
        )
    }

    // Paged Mode (WebView only, experimental) - swipe/tap turns a page instead of scrolling.
    // The page-number display only means anything once this is on, so it's shown right here
    // rather than in the unrelated Display group.
    if (renderingMode == "webview") {
        CheckboxItem(
            label = "Paged mode (experimental)",
            checked = pagedMode,
            onClick = { viewModel.preferences.novelPagedMode.set(!pagedMode) },
        )
        if (pagedMode) {
            CheckboxItem(
                label = "Swipe to turn pages",
                pref = viewModel.preferences.novelPagedSwipeEnabled,
            )
        }
    }

    // Auto-load next chapter at percentage (only relevant when infinite scroll is enabled)
    val autoLoadAt by viewModel.preferences.novelAutoLoadNextChapterAt.collectAsState()
    LaunchedEffect(autoLoadAt) {
        // Older installs may have persisted 0; treat it as legacy/unset and normalize to default.
        if (autoLoadAt <= 0) {
            viewModel.preferences.novelAutoLoadNextChapterAt.set(95)
        }
    }
    if (infiniteScrollEnabled && !isWebviewPaged) {
        val effectiveAutoLoadAt = if (autoLoadAt > 0) autoLoadAt else 95
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(TDMR.strings.pref_novel_auto_load_next_at), style = MaterialTheme.typography.bodyMedium)
            SliderItem(
                label = "",
                value = effectiveAutoLoadAt,
                valueRange = 1..99,
                valueString = "$effectiveAutoLoadAt%",
                onChange = { viewModel.preferences.novelAutoLoadNextChapterAt.set(it) },
            )
        }
    }

    // Status Bar
    HorizontalDivider()
    val statusBarEnabled by viewModel.preferences.novelStatusBarEnabled.collectAsState()
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_status_bar),
        pref = viewModel.preferences.novelStatusBarEnabled,
    )
    if (statusBarEnabled) {
        val statusBarPosition by viewModel.preferences.novelStatusBarPosition.collectAsState()
        CheckboxItem(
            label = stringResource(TDMR.strings.pref_novel_status_bar_at_top),
            checked = statusBarPosition == "top",
            onClick = {
                viewModel.preferences.novelStatusBarPosition.set(
                    if (statusBarPosition == "top") "bottom" else "top",
                )
            },
        )
        CheckboxItem(
            label = stringResource(TDMR.strings.pref_novel_status_bar_show_time),
            pref = viewModel.preferences.novelStatusBarShowTime,
        )
        CheckboxItem(
            label = stringResource(TDMR.strings.pref_novel_status_bar_show_battery),
            pref = viewModel.preferences.novelStatusBarShowBattery,
        )
        CheckboxItem(
            label = stringResource(TDMR.strings.pref_novel_status_bar_show_chapter_number),
            pref = viewModel.preferences.novelStatusBarShowChapterNumber,
        )
        CheckboxItem(
            label = stringResource(TDMR.strings.pref_novel_status_bar_show_chapter_title),
            pref = viewModel.preferences.novelStatusBarShowChapterTitle,
        )
        CheckboxItem(
            label = stringResource(TDMR.strings.pref_novel_status_bar_show_progress),
            pref = viewModel.preferences.novelStatusBarShowProgress,
        )
        val statusBarSize by viewModel.preferences.novelStatusBarSize.collectAsState()
        val statusBarSizeOptions = listOf(
            stringResource(TDMR.strings.novel_status_bar_size_small) to "small",
            stringResource(TDMR.strings.novel_status_bar_size_medium) to "medium",
        )
        InlineSettingsChipRow(TDMR.strings.pref_novel_status_bar_size) {
            statusBarSizeOptions.forEach { (label, value) ->
                FilterChip(
                    selected = statusBarSize == value,
                    onClick = { viewModel.preferences.novelStatusBarSize.set(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
internal fun ColumnScope.NovelAdvancedTab(viewModel: ReaderSettingsViewModel, renderingMode: String) {
    RegexReplacementSection(viewModel)

    if (renderingMode != "webview") {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(TDMR.strings.novel_advanced_webview_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(TDMR.strings.novel_no_cssjs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        return
    }

    // Show embedded CSS/JS toggles even when not an EPUB source — these control embedded styles/scripts
    CheckboxItem(
        label = "Enable embedded CSS",
        pref = viewModel.preferences.enableEpubStyles,
    )

    CheckboxItem(
        label = "Enable embedded JS",
        pref = viewModel.preferences.enableEpubJs,
    )

    // Allow user to choose whether source CSS has priority over reader theme
    CheckboxItem(
        label = "Source CSS priority",
        pref = viewModel.preferences.novelSourceCssPriority,
    )

    val cssSnippetsJson by viewModel.preferences.novelCustomCssSnippets.collectAsState()
    val jsSnippetsJson by viewModel.preferences.novelCustomJsSnippets.collectAsState()

    var showCssDialog by remember { mutableStateOf(false) }
    var showJsDialog by remember { mutableStateOf(false) }
    var editingCssSnippet by remember { mutableStateOf<CodeSnippet?>(null) }
    var editingJsSnippet by remember { mutableStateOf<CodeSnippet?>(null) }

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
        onEditClick = { snippet -> editingCssSnippet = snippet },
        onDeleteClick = { id ->
            val updated = cssSnippets.filterNot { it.id == id }
            viewModel.preferences.novelCustomCssSnippets.set(Json.encodeToString(updated))
        },
        onToggleClick = { index ->
            val updated = cssSnippets.toMutableList().apply {
                this[index] = this[index].copy(enabled = !this[index].enabled)
            }
            viewModel.preferences.novelCustomCssSnippets.set(Json.encodeToString(updated))
        },
        onMove = { from, to ->
            val updated = cssSnippets.toMutableList().apply { add(to, removeAt(from)) }
            viewModel.preferences.novelCustomCssSnippets.set(Json.encodeToString(updated))
        },
        onSortEnabledFirst = {
            val updated = cssSnippets.sortedByDescending { it.enabled }
            viewModel.preferences.novelCustomCssSnippets.set(Json.encodeToString(updated))
        },
    )

    // JS Snippets Section
    SnippetSection(
        title = stringResource(TDMR.strings.pref_novel_js_snippets),
        snippets = jsSnippets,
        onAddClick = { showJsDialog = true },
        onEditClick = { snippet -> editingJsSnippet = snippet },
        onDeleteClick = { id ->
            val updated = jsSnippets.filterNot { it.id == id }
            viewModel.preferences.novelCustomJsSnippets.set(Json.encodeToString(updated))
        },
        onToggleClick = { index ->
            val updated = jsSnippets.toMutableList().apply {
                this[index] = this[index].copy(enabled = !this[index].enabled)
            }
            viewModel.preferences.novelCustomJsSnippets.set(Json.encodeToString(updated))
        },
        onMove = { from, to ->
            val updated = jsSnippets.toMutableList().apply { add(to, removeAt(from)) }
            viewModel.preferences.novelCustomJsSnippets.set(Json.encodeToString(updated))
        },
        onSortEnabledFirst = {
            val updated = jsSnippets.sortedByDescending { it.enabled }
            viewModel.preferences.novelCustomJsSnippets.set(Json.encodeToString(updated))
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
            initialSnippet = editingCssSnippet,
            focusCodeFieldByDefault = editingCssSnippet != null,
            showRunOnAppend = false,
            existingSafeTitles = cssSnippets
                .filterNot { it.id == editingCssSnippet?.id }
                .mapTo(mutableSetOf()) { it.safeTitle() },
            onDismiss = {
                showCssDialog = false
                editingCssSnippet = null
            },
            onConfirm = { snippet ->
                val editingId = editingCssSnippet?.id
                val updated = if (editingId != null) {
                    cssSnippets.map { if (it.id == editingId) snippet else it }
                } else {
                    cssSnippets + snippet
                }
                viewModel.preferences.novelCustomCssSnippets.set(Json.encodeToString(updated))
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
            initialSnippet = editingJsSnippet,
            focusCodeFieldByDefault = editingJsSnippet != null,
            showRunOnAppend = true,
            existingSafeTitles = jsSnippets
                .filterNot { it.id == editingJsSnippet?.id }
                .mapTo(mutableSetOf()) { it.safeTitle() },
            onDismiss = {
                showJsDialog = false
                editingJsSnippet = null
            },
            onConfirm = { snippet ->
                val editingId = editingJsSnippet?.id
                val updated = if (editingId != null) {
                    jsSnippets.map { if (it.id == editingId) snippet else it }
                } else {
                    jsSnippets + snippet
                }
                viewModel.preferences.novelCustomJsSnippets.set(Json.encodeToString(updated))
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
    onEditClick: (CodeSnippet) -> Unit,
    onDeleteClick: (String) -> Unit,
    onToggleClick: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSortEnabledFirst: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (snippets.size > 1) {
                    IconButton(onClick = onSortEnabledFirst) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Sort,
                            contentDescription = stringResource(TDMR.strings.novel_snippets_enabled_first),
                        )
                    }
                }
                IconButton(onClick = onAddClick) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(TDMR.strings.novel_add_snippet))
                }
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
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = snippet.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        color = if (snippet.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        },
                    )
                    Row {
                        IconButton(
                            onClick = { onMove(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Outlined.ArrowUpward,
                                contentDescription = stringResource(TDMR.strings.novel_snippet_move_up),
                            )
                        }
                        IconButton(
                            onClick = { onMove(index, index + 1) },
                            enabled = index < snippets.lastIndex,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Outlined.ArrowDownward,
                                contentDescription = stringResource(TDMR.strings.novel_snippet_move_down),
                            )
                        }
                        IconButton(onClick = { onEditClick(snippet) }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(TDMR.strings.novel_edit_snippet),
                            )
                        }
                        IconButton(onClick = { pendingDelete = snippet.id }) {
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

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(TDMR.strings.novel_delete_snippet)) },
            text = { Text(stringResource(TDMR.strings.novel_delete_snippet_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteClick(id)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(MR.strings.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SnippetEditDialog(
    title: String,
    initialSnippet: CodeSnippet?,
    focusCodeFieldByDefault: Boolean,
    showRunOnAppend: Boolean,
    existingSafeTitles: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (CodeSnippet) -> Unit,
) {
    var snippetTitle by remember { mutableStateOf(initialSnippet?.title ?: "") }
    var snippetCode by remember { mutableStateOf(initialSnippet?.code ?: "") }
    var runOnAppend by remember { mutableStateOf(initialSnippet?.runOnAppend ?: false) }
    val codeFocusRequester = remember { FocusRequester() }
    val trimmedSafeTitle = safeTitleOf(snippetTitle.trim())
    val isDuplicateTitle = existingSafeTitles.contains(trimmedSafeTitle)

    LaunchedEffect(focusCodeFieldByDefault) {
        if (focusCodeFieldByDefault) {
            codeFocusRequester.requestFocus()
        }
    }

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
                    isError = isDuplicateTitle,
                    supportingText = if (isDuplicateTitle) {
                        { Text(stringResource(TDMR.strings.novel_snippet_duplicate_title, trimmedSafeTitle)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = snippetCode,
                    onValueChange = { snippetCode = it },
                    label = { Text(stringResource(TDMR.strings.novel_snippet_code)) },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .focusRequester(codeFocusRequester),
                )
                if (showRunOnAppend) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { runOnAppend = !runOnAppend }
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = runOnAppend,
                            onCheckedChange = { runOnAppend = it },
                        )
                        Text(
                            text = stringResource(TDMR.strings.novel_snippet_run_on_append),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = snippetTitle.isNotBlank() && snippetCode.isNotBlank() && !isDuplicateTitle,
                onClick = {
                    onConfirm(
                        CodeSnippet(
                            title = snippetTitle.trim(),
                            code = snippetCode,
                            enabled = initialSnippet?.enabled ?: true,
                            runOnAppend = runOnAppend,
                            id = initialSnippet?.id ?: java.util.UUID.randomUUID().toString(),
                        ),
                    )
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
private fun ColumnScope.RegexReplacementSection(viewModel: ReaderSettingsViewModel) {
    val regexJson by viewModel.preferences.novelRegexReplacements.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<RegexReplacement?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

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
                        viewModel.preferences.novelRegexReplacements.set(Json.encodeToString(updated))
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
                                if (!rule.isRegex) {
                                    if (rule.matchWholeWord) append(" • whole-word")
                                    if (rule.caseSensitive) append(" • case-sensitive")
                                }
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
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Row {
                        IconButton(onClick = { editingRule = rule }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(MR.strings.action_edit))
                        }
                        IconButton(onClick = { pendingDelete = rule.id }) {
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
            initialRule = editingRule,
            onDismiss = {
                showAddDialog = false
                editingRule = null
            },
            onConfirm = { rule ->
                val editingId = editingRule?.id
                val updated = if (editingId != null) {
                    rules.map { if (it.id == editingId) rule else it }
                } else {
                    rules + rule
                }
                viewModel.preferences.novelRegexReplacements.set(Json.encodeToString(updated))
                showAddDialog = false
                editingRule = null
            },
        )
    }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(MR.strings.action_delete)) },
            text = { Text(stringResource(TDMR.strings.novel_delete_rule_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updated = rules.filterNot { it.id == id }
                        viewModel.preferences.novelRegexReplacements.set(Json.encodeToString(updated))
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(MR.strings.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
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
    var matchWholeWord by remember { mutableStateOf(initialRule?.matchWholeWord ?: false) }
    var caseSensitive by remember { mutableStateOf(initialRule?.caseSensitive ?: false) }
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
            Text(
                if (initialRule !=
                    null
                ) {
                    stringResource(TDMR.strings.novel_edit_rule)
                } else {
                    stringResource(TDMR.strings.novel_add_rule_title)
                },
            )
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
                    label = {
                        Text(
                            if (isRegex) {
                                stringResource(
                                    TDMR.strings.novel_regex_pattern,
                                )
                            } else {
                                stringResource(TDMR.strings.novel_find_text)
                            },
                        )
                    },
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
                            // Reset whole-word/case-sensitive when switching regex mode
                            if (it) {
                                matchWholeWord = false
                                caseSensitive = false
                            }
                        },
                    )
                    Text(stringResource(TDMR.strings.novel_use_regex), modifier = Modifier.padding(start = 4.dp))
                }

                // Hint text for current mode
                Text(
                    text = stringResource(
                        if (isRegex) TDMR.strings.novel_regex_pattern else TDMR.strings.novel_find_text,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )

                if (!isRegex) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = matchWholeWord,
                            onCheckedChange = {
                                matchWholeWord = it
                                testOutput = null
                                testError = null
                            },
                        )
                        Text(
                            stringResource(TDMR.strings.novel_match_whole_word),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = caseSensitive,
                            onCheckedChange = {
                                caseSensitive = it
                                testOutput = null
                                testError = null
                            },
                        )
                        Text(
                            stringResource(TDMR.strings.novel_case_sensitive_matching),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
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
                                val escapedPattern = Regex.escape(pattern)
                                val boundedPattern = if (matchWholeWord) {
                                    "(?<![\\p{L}\\p{N}_])(?:$escapedPattern)(?![\\p{L}\\p{N}_])"
                                } else {
                                    escapedPattern
                                }
                                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                                Regex(boundedPattern, options).replace(testInput) { replacement }
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
                            RegexReplacement(
                                title = title.trim(),
                                pattern = pattern,
                                replacement = replacement,
                                enabled = initialRule?.enabled ?: true,
                                isRegex = isRegex,
                                matchWholeWord = if (isRegex) false else matchWholeWord,
                                caseSensitive = if (isRegex) false else caseSensitive,
                                id = initialRule?.id ?: java.util.UUID.randomUUID().toString(),
                            ),
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
internal fun ColumnScope.NovelTtsTab(viewModel: ReaderSettingsViewModel) {
    val context = LocalContext.current
    val ttsSpeed by viewModel.preferences.novelTtsSpeed.collectAsState()
    val ttsPitch by viewModel.preferences.novelTtsPitch.collectAsState()
    val ttsVoice by viewModel.preferences.novelTtsVoice.collectAsState()
    val ttsEnableHighlight by viewModel.preferences.novelTtsEnableHighlight.collectAsState()
    val ttsHighlightStyle by viewModel.preferences.novelTtsHighlightStyle.collectAsState()
    val ttsHighlightColor by viewModel.preferences.novelTtsHighlightColor.collectAsState()
    val ttsHighlightTextColor by viewModel.preferences.novelTtsHighlightTextColor.collectAsState()
    var showHighlightBgColorPicker by remember { mutableStateOf(false) }
    var showHighlightTextColorPicker by remember { mutableStateOf(false) }

    if (showHighlightBgColorPicker) {
        ColorPickerDialog(
            title = "TTS Highlight Background",
            initialColor = ttsHighlightColor,
            onDismiss = { showHighlightBgColorPicker = false },
            onConfirm = { color ->
                viewModel.preferences.novelTtsHighlightColor.set(color)
                showHighlightBgColorPicker = false
            },
        )
    }

    if (showHighlightTextColorPicker) {
        ColorPickerDialog(
            title = "TTS Highlight Text",
            initialColor = ttsHighlightTextColor,
            onDismiss = { showHighlightTextColorPicker = false },
            onConfirm = { color ->
                viewModel.preferences.novelTtsHighlightTextColor.set(color)
                showHighlightTextColorPicker = false
            },
        )
    }

    // Load available voices using TTS
    val availableVoices = remember { mutableStateListOf<Pair<String, String>>() }

    DisposableEffect(Unit) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
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
            tts.shutdown()
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
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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
                                viewModel.preferences.novelTtsVoice.set(voiceName)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }

    // Speech Speed Slider (0.5x to 6.0x)
    SliderItem(
        label = stringResource(TDMR.strings.pref_novel_tts_speed),
        value = (ttsSpeed * 10).toInt(),
        valueRange = 5..60,
        onChange = { viewModel.preferences.novelTtsSpeed.set(it / 10f) },
        valueString = String.format("%.1fx", ttsSpeed),
    )

    // Speech Pitch Slider (0.5x to 6.0x)
    SliderItem(
        label = stringResource(TDMR.strings.pref_novel_tts_pitch),
        value = (ttsPitch * 10).toInt(),
        valueRange = 5..60,
        onChange = { viewModel.preferences.novelTtsPitch.set(it / 10f) },
        valueString = String.format("%.1fx", ttsPitch),
    )

    // Auto-play next chapter
    CheckboxItem(
        label = stringResource(TDMR.strings.pref_novel_tts_auto_next),
        pref = viewModel.preferences.novelTtsAutoNextChapter,
    )

    // Enable TTS highlighting
    CheckboxItem(
        label = "Enable paragraph highlighting during TTS",
        pref = viewModel.preferences.novelTtsEnableHighlight,
    )

    // Highlight style selector (pill chips)
    if (ttsEnableHighlight) {
        SettingsChipRow("Highlight style") {
            listOf(
                "background" to "Background",
                "underline" to "Underline",
                "outline" to "Outline",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = ttsHighlightStyle == value,
                    onClick = { viewModel.preferences.novelTtsHighlightStyle.set(value) },
                    label = { Text(label) },
                )
            }
        }

        SettingsChipRow(TDMR.strings.pref_novel_background_color) {
            listOf(
                0xFFFFD54F.toInt() to "Amber",
                0xFF90CAF9.toInt() to "Blue",
                0xFFA5D6A7.toInt() to "Green",
                0xFFF48FB1.toInt() to "Pink",
                Int.MIN_VALUE to "Custom",
            ).forEach { (colorValue, label) ->
                val selected = if (colorValue == Int.MIN_VALUE) {
                    listOf(0xFFFFD54F.toInt(), 0xFF90CAF9.toInt(), 0xFFA5D6A7.toInt(), 0xFFF48FB1.toInt()).none {
                        it ==
                            ttsHighlightColor
                    }
                } else {
                    ttsHighlightColor == colorValue
                }
                FilterChip(
                    selected = selected,
                    onClick = {
                        if (colorValue == Int.MIN_VALUE) {
                            showHighlightBgColorPicker = true
                        } else {
                            viewModel.preferences.novelTtsHighlightColor.set(colorValue)
                        }
                    },
                    label = { Text(label) },
                )
            }
        }

        SettingsChipRow(TDMR.strings.pref_novel_font_color) {
            listOf(
                0xFF111111.toInt() to "Dark",
                0xFFFFFFFF.toInt() to "White",
                0xFF1E3A8A.toInt() to "Navy",
                0xFF7F1D1D.toInt() to "Maroon",
                Int.MIN_VALUE to "Custom",
            ).forEach { (colorValue, label) ->
                val selected = if (colorValue == Int.MIN_VALUE) {
                    listOf(0xFF111111.toInt(), 0xFFFFFFFF.toInt(), 0xFF1E3A8A.toInt(), 0xFF7F1D1D.toInt()).none {
                        it ==
                            ttsHighlightTextColor
                    }
                } else {
                    ttsHighlightTextColor == colorValue
                }
                FilterChip(
                    selected = selected,
                    onClick = {
                        if (colorValue == Int.MIN_VALUE) {
                            showHighlightTextColorPicker = true
                        } else {
                            viewModel.preferences.novelTtsHighlightTextColor.set(colorValue)
                        }
                    },
                    label = { Text(label) },
                )
            }
        }

        CheckboxItem(
            label = "Keep highlighted paragraph in view",
            pref = viewModel.preferences.novelTtsKeepHighlightInView,
        )
    }

    CheckboxItem(
        label = "Auto-start TTS when opening controls panel",
        pref = viewModel.preferences.novelTtsAutoStartOnPanelOpen,
    )

    CheckboxItem(
        label = "Keep TTS running in background",
        pref = viewModel.preferences.novelTtsBackgroundPlayback,
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
                        val newRed = it.toInt()
                        red = newRed
                        hexInput =
                            String.format(
                                "%06X",
                                ((0xFF shl 24) or (newRed shl 16) or (green shl 8) or blue) and 0xFFFFFF,
                            )
                    },
                    valueRange = 0f..255f,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Green slider
                Text("Green: $green", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = green.toFloat(),
                    onValueChange = {
                        val newGreen = it.toInt()
                        green = newGreen
                        hexInput =
                            String.format(
                                "%06X",
                                ((0xFF shl 24) or (red shl 16) or (newGreen shl 8) or blue) and 0xFFFFFF,
                            )
                    },
                    valueRange = 0f..255f,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Blue slider
                Text("Blue: $blue", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = blue.toFloat(),
                    onValueChange = {
                        val newBlue = it.toInt()
                        blue = newBlue
                        hexInput =
                            String.format(
                                "%06X",
                                ((0xFF shl 24) or (red shl 16) or (green shl 8) or newBlue) and 0xFFFFFF,
                            )
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
