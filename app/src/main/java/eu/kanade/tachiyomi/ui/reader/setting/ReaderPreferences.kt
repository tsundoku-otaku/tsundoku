package eu.kanade.tachiyomi.ui.reader.setting

import android.os.Build
import androidx.compose.ui.graphics.BlendMode
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.reader.appbars.DefaultBottomBarItems
import eu.kanade.presentation.reader.appbars.serialize
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.i18n.MR

class ReaderPreferences(
    preferenceStore: PreferenceStore,
) {

    // region General

    val pageTransitions: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_transitions_key", true)

    val flashOnPageChange: Preference<Boolean> = preferenceStore.getBoolean("pref_reader_flash", false)

    val flashDurationMillis: Preference<Int> = preferenceStore.getInt("pref_reader_flash_duration", MILLI_CONVERSION)

    val flashPageInterval: Preference<Int> = preferenceStore.getInt("pref_reader_flash_interval", 1)

    val flashColor: Preference<FlashColor> = preferenceStore.getEnum("pref_reader_flash_mode", FlashColor.BLACK)

    val doubleTapAnimSpeed: Preference<Int> = preferenceStore.getInt("pref_double_tap_anim_speed", 500)

    val showPageNumber: Preference<Boolean> = preferenceStore.getBoolean("pref_show_page_number_key", true)

    val showReadingMode: Preference<Boolean> = preferenceStore.getBoolean("pref_show_reading_mode", true)

    val fullscreen: Preference<Boolean> = preferenceStore.getBoolean("fullscreen", true)

    val drawUnderCutout: Preference<Boolean> = preferenceStore.getBoolean("cutout_short", true)

    val keepScreenOn: Preference<Boolean> = preferenceStore.getBoolean("pref_keep_screen_on_key", false)

    val defaultReadingMode: Preference<Int> = preferenceStore.getInt(
        "pref_default_reading_mode_key",
        ReadingMode.RIGHT_TO_LEFT.flagValue,
    )

    val defaultOrientationType: Preference<Int> = preferenceStore.getInt(
        "pref_default_orientation_type_key",
        ReaderOrientation.FREE.flagValue,
    )

    val webtoonDoubleTapZoomEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_enable_double_tap_zoom_webtoon",
        true,
    )

    val imageScaleType: Preference<Int> = preferenceStore.getInt("pref_image_scale_type_key", 1)

    val zoomStart: Preference<Int> = preferenceStore.getInt("pref_zoom_start_key", 1)

    val readerTheme: Preference<Int> = preferenceStore.getInt("pref_reader_theme_key", 1)

    val alwaysShowChapterTransition: Preference<Boolean> = preferenceStore.getBoolean(
        "always_show_chapter_transition",
        true,
    )

    val cropBorders: Preference<Boolean> = preferenceStore.getBoolean("crop_borders", false)

    val navigateToPan: Preference<Boolean> = preferenceStore.getBoolean("navigate_pan", true)

    val landscapeZoom: Preference<Boolean> = preferenceStore.getBoolean("landscape_zoom", true)

    val cropBordersWebtoon: Preference<Boolean> = preferenceStore.getBoolean("crop_borders_webtoon", false)

    val webtoonSidePadding: Preference<Int> = preferenceStore.getInt("webtoon_side_padding", WEBTOON_PADDING_MIN)

    val readerHideThreshold: Preference<ReaderHideThreshold> = preferenceStore.getEnum(
        "reader_hide_threshold",
        ReaderHideThreshold.LOW,
    )

    val folderPerManga: Preference<Boolean> = preferenceStore.getBoolean("create_folder_per_manga", false)

    val skipRead: Preference<Boolean> = preferenceStore.getBoolean("skip_read", false)

    val skipFiltered: Preference<Boolean> = preferenceStore.getBoolean("skip_filtered", true)

    val skipDupe: Preference<Boolean> = preferenceStore.getBoolean("skip_dupe", false)

    val webtoonDisableZoomOut: Preference<Boolean> = preferenceStore.getBoolean("webtoon_disable_zoom_out", false)

    val autoTranslate: Preference<Boolean> = preferenceStore.getBoolean("pref_auto_translate", false)

    // endregion

    // region Split two-page spread

    val dualPageSplitPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split", false)

    val dualPageInvertPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert", false)

    val dualPageSplitWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split_webtoon", false)

    val dualPageInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert_webtoon", false)

    val dualPageRotateToFit: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_rotate", false)

    val dualPageRotateToFitInvert: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert",
        false,
    )

    val dualPageRotateToFitWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_webtoon",
        false,
    )

    val dualPageRotateToFitInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert_webtoon",
        false,
    )

    // endregion

    // region Color filter

    val customBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_custom_brightness_key", false)

    val customBrightnessValue: Preference<Int> = preferenceStore.getInt("custom_brightness_value", 0)

    val colorFilter: Preference<Boolean> = preferenceStore.getBoolean("pref_color_filter_key", false)

    val colorFilterValue: Preference<Int> = preferenceStore.getInt("color_filter_value", 0)

    val colorFilterMode: Preference<Int> = preferenceStore.getInt("color_filter_mode", 0)

    val grayscale: Preference<Boolean> = preferenceStore.getBoolean("pref_grayscale", false)

    val invertedColors: Preference<Boolean> = preferenceStore.getBoolean("pref_inverted_colors", false)

    // endregion

    // region Controls

    val readWithLongTap: Preference<Boolean> = preferenceStore.getBoolean("reader_long_tap", true)

    val readWithVolumeKeys: Preference<Boolean> = preferenceStore.getBoolean("reader_volume_keys", false)

    val readWithVolumeKeysInverted: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_volume_keys_inverted",
        false,
    )

    val navigationModePager: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_pager", 0)

    val navigationModeWebtoon: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_webtoon", 0)

    val pagerNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted",
        TappingInvertMode.NONE,
    )

    val webtoonNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted_webtoon",
        TappingInvertMode.NONE,
    )

    val showNavigationOverlayNewUser: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_new_user",
        true,
    )

    val showNavigationOverlayOnStart: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_on_start",
        false,
    )

    // endregion

    enum class FlashColor {
        BLACK,
        WHITE,
        WHITE_BLACK,
    }

    enum class TappingInvertMode(
        val titleRes: StringResource,
        val shouldInvertHorizontal: Boolean = false,
        val shouldInvertVertical: Boolean = false,
    ) {
        NONE(MR.strings.tapping_inverted_none),
        HORIZONTAL(MR.strings.tapping_inverted_horizontal, shouldInvertHorizontal = true),
        VERTICAL(MR.strings.tapping_inverted_vertical, shouldInvertVertical = true),
        BOTH(MR.strings.tapping_inverted_both, shouldInvertHorizontal = true, shouldInvertVertical = true),
    }

    enum class ReaderHideThreshold(val threshold: Int) {
        HIGHEST(5),
        HIGH(13),
        LOW(31),
        LOWEST(47),
    }

    // region Novel
    val novelFontSize: Preference<Int> = preferenceStore.getInt("pref_novel_font_size", 16)
    val novelFontFamily: Preference<String> = preferenceStore.getString("pref_novel_font_family", "sans-serif")
    val novelTheme: Preference<String> = preferenceStore.getString("pref_novel_theme", "app")
    val novelLineHeight: Preference<Float> = preferenceStore.getFloat("pref_novel_line_height", 1.6f)
    val novelTextAlign: Preference<String> = preferenceStore.getString("pref_novel_text_align", "left")
    val novelAutoScrollSpeed: Preference<Int> = preferenceStore.getInt("pref_novel_auto_scroll_speed", 30)
    val novelVolumeKeysScroll: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_volume_keys_scroll", false)
    val novelTapToScroll: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_tap_to_scroll", false)
    val novelTextSelectable: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_text_selectable", true)

    // Block media elements (images, videos) in WebView and TextView readers
    val novelBlockMedia: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_block_media", false)

    // Font color (stored as ARGB int, 0 means use theme default)
    // Note: 0xFFFFFFFF (white) = -1 as signed int, so 0 is used as the "unset" marker
    val novelFontColor: Preference<Int> = preferenceStore.getInt("pref_novel_font_color", 0)

    // Background color (stored as ARGB int, 0 means use theme default)
    val novelBackgroundColor: Preference<Int> = preferenceStore.getInt("pref_novel_background_color", 0)

    // Paragraph indentation in em units (0 = no indent, default 2em)
    val novelParagraphIndent: Preference<Float> = preferenceStore.getFloat("pref_novel_paragraph_indent", 0f)

    // Margin preferences (in dp)
    val novelMarginLeft: Preference<Int> = preferenceStore.getInt("pref_novel_margin_left", 16)
    val novelMarginRight: Preference<Int> = preferenceStore.getInt("pref_novel_margin_right", 16)
    val novelMarginTop: Preference<Int> = preferenceStore.getInt("pref_novel_margin_top", 50)
    val novelMarginBottom: Preference<Int> = preferenceStore.getInt("pref_novel_margin_bottom", 16)

    // Rendering mode: "default" = TextView, "webview" = WebView rendering
    val novelRenderingMode: Preference<String> = preferenceStore.getString("pref_novel_rendering_mode", "default")

    // EPUB specific toggles
    val enableEpubStyles: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_enable_epub_css", true)
    val enableEpubJs: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_enable_epub_js", false)

    // Custom CSS/JS stored as JSON array of {title, code} objects
    val novelCustomCss: Preference<String> = preferenceStore.getString("pref_novel_custom_css", "")
    val novelCustomJs: Preference<String> = preferenceStore.getString("pref_novel_custom_js", "")
    val novelCustomCssSnippets: Preference<String> = preferenceStore.getString("pref_novel_css_snippets", "[]")
    val novelCustomJsSnippets: Preference<String> = preferenceStore.getString("pref_novel_js_snippets", "[]")

    // Global CSS/JS presets stored as JSON array of {name, css, js} objects
    val novelGlobalPresets: Preference<String> = preferenceStore.getString("pref_novel_global_presets", "[]")

    // Currently active global preset name (empty = none)
    val novelActivePreset: Preference<String> = preferenceStore.getString("pref_novel_active_preset", "")

    // Regex find/replace rules stored as JSON array of {title, pattern, replacement, enabled, isRegex}
    // Applied to chapter HTML content before rendering in both WebView and TextView modes
    val novelRegexReplacements: Preference<String> = preferenceStore.getString("pref_novel_regex_replacements", "[]")

    // Infinite scroll - automatically load next/previous chapters
    val novelInfiniteScroll: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_infinite_scroll", false)

    // Keep chapters loaded in memory (0 = only current, 1 = current + prev, 2 = current + next, 3 = both)
    val novelKeepChaptersLoaded: Preference<Int> = preferenceStore.getInt("pref_novel_keep_chapters_loaded", 0)

    // Custom brightness for novel reader
    val novelCustomBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_custom_brightness", false)

    // Brightness value for novel reader (-75 to 100, 0 = system)
    val novelCustomBrightnessValue: Preference<Int> = preferenceStore.getInt("pref_novel_custom_brightness_value", 0)

    // Show progress slider in novel reader (allows scrolling to position in current chapter)
    val novelShowProgressSlider: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_novel_show_progress_slider",
        true,
    )

    // Hide chapter title in novel content
    val novelHideChapterTitle: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_hide_chapter_title", false)

    // Force lowercase for all chapter content
    val novelForceTextLowercase: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_force_lowercase", false)

    // Auto-split text after X words until punctuation mark (0 = disabled)
    val novelAutoSplitText: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_auto_split_text", false)
    val novelAutoSplitWordCount: Preference<Int> = preferenceStore.getInt("pref_novel_auto_split_word_count", 50)

    // Use source's original fonts (don't force a specific font family)
    val novelUseOriginalFonts: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_use_original_fonts", false)

    // Chapter sort order for novel reader: "source" = use source order, "chapter_number" = sort by chapter number
    // Default is "source" since many novel sources don't provide proper chapter numbers
    val novelChapterSortOrder: Preference<String> = preferenceStore.getString("pref_novel_chapter_sort_order", "source")

    // Keep screen on while reading
    val novelKeepScreenOn: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_keep_screen_on", false)

    // Paragraph spacing (additional space between paragraphs in em units)
    val novelParagraphSpacing: Preference<Float> = preferenceStore.getFloat("pref_novel_paragraph_spacing", 0.5f)

    // Swipe navigation - swipe left/right to change chapters
    val novelSwipeNavigation: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_swipe_navigation", false)

    // Chapter title display format: 0 = name only, 1 = number only, 2 = both (name + number)
    val novelChapterTitleDisplay: Preference<Int> = preferenceStore.getInt("pref_novel_chapter_title_display", 2)

    // Auto-load next chapter at percentage (legacy 0 may exist; treated as default)
    val novelAutoLoadNextChapterAt: Preference<Int> = preferenceStore.getInt("pref_novel_auto_load_next_at", 95)

    // Mark chapter as read when progress reaches this percentage
    val novelMarkAsReadThreshold: Preference<Int> = preferenceStore.getInt("pref_novel_mark_read_threshold", 95)

    // Show raw HTML (display HTML tags without parsing) - useful for debugging
    val novelShowRawHtml: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_show_raw_html", false)

    // TTS (Text-to-Speech) preferences
    val novelTtsSpeed: Preference<Float> = preferenceStore.getFloat("pref_novel_tts_speed", 1.0f)
    val novelTtsPitch: Preference<Float> = preferenceStore.getFloat("pref_novel_tts_pitch", 1.0f)
    val novelTtsVoice: Preference<String> = preferenceStore.getString("pref_novel_tts_voice", "")
    val novelTtsAutoNextChapter: Preference<Boolean> = preferenceStore.getBoolean("pref_novel_tts_auto_next", true)

    val novelBottomBarItems: Preference<String> = preferenceStore.getString(
        "novel_bottom_bar_items",
        DefaultBottomBarItems.serialize(),
    )
    // endregion

    companion object {
        const val WEBTOON_PADDING_MIN = 0
        const val WEBTOON_PADDING_MAX = 25

        const val MILLI_CONVERSION = 100

        val TapZones = listOf(
            MR.strings.label_default,
            MR.strings.l_nav,
            MR.strings.kindlish_nav,
            MR.strings.edge_nav,
            MR.strings.right_and_left_nav,
            MR.strings.disabled_nav,
        )

        val ImageScaleType = listOf(
            MR.strings.scale_type_fit_screen,
            MR.strings.scale_type_stretch,
            MR.strings.scale_type_fit_width,
            MR.strings.scale_type_fit_height,
            MR.strings.scale_type_original_size,
            MR.strings.scale_type_smart_fit,
        )

        val ZoomStart = listOf(
            MR.strings.zoom_start_automatic,
            MR.strings.zoom_start_left,
            MR.strings.zoom_start_right,
            MR.strings.zoom_start_center,
        )

        val ColorFilterMode = buildList {
            addAll(
                listOf(
                    MR.strings.label_default to BlendMode.SrcOver,
                    MR.strings.filter_mode_multiply to BlendMode.Modulate,
                    MR.strings.filter_mode_screen to BlendMode.Screen,
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAll(
                    listOf(
                        MR.strings.filter_mode_overlay to BlendMode.Overlay,
                        MR.strings.filter_mode_lighten to BlendMode.Lighten,
                        MR.strings.filter_mode_darken to BlendMode.Darken,
                    ),
                )
            }
        }
    }
}
