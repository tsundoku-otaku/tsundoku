package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import android.app.Activity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences

/**
 * Reader-theme resolution: background/text colors and Material 3 token export
 * for both TextView and WebView surfaces.
 */
object ThemeUtils {

    /**
     * Holds CSS variable and JS-object representations of the theme tokens.
     */
    data class ThemeTokens(
        val cssVariables: String,
        val jsObject: String,
    )

    /**
     * Resolves theme colors (background, text) for the requested [theme] key.
     */
    fun getThemeColors(activity: Activity, preferences: ReaderPreferences, theme: String): Pair<Int, Int> {
        val backgroundColor = preferences.novelBackgroundColor.get()
        val fontColor = preferences.novelFontColor.get()

        return when (theme) {
            "app" -> {
                val typedValue = android.util.TypedValue()
                val actTheme = activity.theme
                val bgColor = if (actTheme.resolveAttribute(
                        com.google.android.material.R.attr.colorSurface,
                        typedValue,
                        true,
                    )
                ) {
                    typedValue.data
                } else {
                    val nightMode = activity.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                        0xFF121212.toInt()
                    } else {
                        0xFFFFFFFF.toInt()
                    }
                }
                val textColor = if (actTheme.resolveAttribute(
                        com.google.android.material.R.attr.colorOnSurface,
                        typedValue,
                        true,
                    )
                ) {
                    typedValue.data
                } else {
                    val nightMode = activity.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                        0xFFE0E0E0.toInt()
                    } else {
                        0xFF000000.toInt()
                    }
                }
                bgColor to textColor
            }
            "dark" -> 0xFF121212.toInt() to 0xFFE0E0E0.toInt()
            "sepia" -> 0xFFF4ECD8.toInt() to 0xFF5B4636.toInt()
            "black" -> 0xFF000000.toInt() to 0xFFCCCCCC.toInt()
            "grey" -> 0xFF292832.toInt() to 0xFFCCCCCC.toInt()
            "custom" -> {
                val bg = if (backgroundColor != 0) backgroundColor else 0xFFFFFFFF.toInt()
                val text = if (fontColor != 0) fontColor else 0xFF000000.toInt()
                bg to text
            }
            else -> 0xFFFFFFFF.toInt() to 0xFF000000.toInt()
        }
    }

    /**
     * Generates Material Design and Tsundoku reader theme tokens for WebView use.
     */
    fun getThemeTokens(activity: Activity, preferences: ReaderPreferences, theme: String): ThemeTokens {
        val (readerBgColor, readerTextColor) = getThemeColors(activity, preferences, theme)

        val readerBgHex = colorToHex(readerBgColor)
        val readerTextHex = colorToHex(readerTextColor)

        val typedValue = android.util.TypedValue()
        val appTheme = activity.theme

        val mdSysColorPrimary = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorPrimary, 0xFF006A6A)
        val mdSysColorOnPrimary = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF)
        val mdSysColorPrimaryContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorPrimaryContainer, 0xFF6FF7F6)
        val mdSysColorOnPrimaryContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF002020)
        val mdSysColorSecondary = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorSecondary, 0xFF006A6A)
        val mdSysColorOnSecondary = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnSecondary, 0xFFFFFFFF)
        val mdSysColorSecondaryContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorSecondaryContainer, 0xFF6FF7F6)
        val mdSysColorOnSecondaryContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF002020)
        val mdSysColorTertiary = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorTertiary, 0xFF006A6A)
        val mdSysColorOnTertiary = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnTertiary, 0xFFFFFFFF)
        val mdSysColorTertiaryContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorTertiaryContainer, 0xFF6FF7F6)
        val mdSysColorOnTertiaryContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnTertiaryContainer, 0xFF002020)
        val mdSysColorError = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorError, 0xFFB3261E)
        val mdSysColorOnError = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnError, 0xFFFFFFFF)
        val mdSysColorErrorContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorErrorContainer, 0xFFF9DEDC)
        val mdSysColorOnErrorContainer = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnErrorContainer, 0xFF410E0B)
        val mdSysColorBackground = resolveColorAttribute(appTheme, typedValue, android.R.attr.colorBackground, readerBgColor.toLong())
        val mdSysColorOnBackground = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnBackground, readerTextColor.toLong())
        val mdSysColorSurface = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorSurface, readerBgColor.toLong())
        val mdSysColorOnSurface = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnSurface, readerTextColor.toLong())
        val mdSysColorSurfaceVariant = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFCCC7C0)
        val mdSysColorOnSurfaceVariant = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454E)
        val mdSysColorOutline = resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOutline, 0xFF79747E)

        val mdHexColorPrimary = colorToHex(mdSysColorPrimary)
        val mdHexColorOnPrimary = colorToHex(mdSysColorOnPrimary)
        val mdHexColorPrimaryContainer = colorToHex(mdSysColorPrimaryContainer)
        val mdHexColorOnPrimaryContainer = colorToHex(mdSysColorOnPrimaryContainer)
        val mdHexColorSecondary = colorToHex(mdSysColorSecondary)
        val mdHexColorOnSecondary = colorToHex(mdSysColorOnSecondary)
        val mdHexColorSecondaryContainer = colorToHex(mdSysColorSecondaryContainer)
        val mdHexColorOnSecondaryContainer = colorToHex(mdSysColorOnSecondaryContainer)
        val mdHexColorTertiary = colorToHex(mdSysColorTertiary)
        val mdHexColorOnTertiary = colorToHex(mdSysColorOnTertiary)
        val mdHexColorTertiaryContainer = colorToHex(mdSysColorTertiaryContainer)
        val mdHexColorOnTertiaryContainer = colorToHex(mdSysColorOnTertiaryContainer)
        val mdHexColorError = colorToHex(mdSysColorError)
        val mdHexColorOnError = colorToHex(mdSysColorOnError)
        val mdHexColorErrorContainer = colorToHex(mdSysColorErrorContainer)
        val mdHexColorOnErrorContainer = colorToHex(mdSysColorOnErrorContainer)
        val mdHexColorBackground = colorToHex(mdSysColorBackground)
        val mdHexColorOnBackground = colorToHex(mdSysColorOnBackground)
        val mdHexColorSurface = colorToHex(mdSysColorSurface)
        val mdHexColorOnSurface = colorToHex(mdSysColorOnSurface)
        val mdHexColorSurfaceVariant = colorToHex(mdSysColorSurfaceVariant)
        val mdHexColorOnSurfaceVariant = colorToHex(mdSysColorOnSurfaceVariant)
        val mdHexColorOutline = colorToHex(mdSysColorOutline)

        val cssVariables = """
            :root {
                --md-sys-color-primary: $mdHexColorPrimary;
                --md-sys-color-on-primary: $mdHexColorOnPrimary;
                --md-sys-color-primary-container: $mdHexColorPrimaryContainer;
                --md-sys-color-on-primary-container: $mdHexColorOnPrimaryContainer;
                --md-sys-color-secondary: $mdHexColorSecondary;
                --md-sys-color-on-secondary: $mdHexColorOnSecondary;
                --md-sys-color-secondary-container: $mdHexColorSecondaryContainer;
                --md-sys-color-on-secondary-container: $mdHexColorOnSecondaryContainer;
                --md-sys-color-tertiary: $mdHexColorTertiary;
                --md-sys-color-on-tertiary: $mdHexColorOnTertiary;
                --md-sys-color-tertiary-container: $mdHexColorTertiaryContainer;
                --md-sys-color-on-tertiary-container: $mdHexColorOnTertiaryContainer;
                --md-sys-color-error: $mdHexColorError;
                --md-sys-color-on-error: $mdHexColorOnError;
                --md-sys-color-error-container: $mdHexColorErrorContainer;
                --md-sys-color-on-error-container: $mdHexColorOnErrorContainer;
                --md-sys-color-background: $mdHexColorBackground;
                --md-sys-color-on-background: $mdHexColorOnBackground;
                --md-sys-color-surface: $mdHexColorSurface;
                --md-sys-color-on-surface: $mdHexColorOnSurface;
                --md-sys-color-surface-variant: $mdHexColorSurfaceVariant;
                --md-sys-color-on-surface-variant: $mdHexColorOnSurfaceVariant;
                --md-sys-color-outline: $mdHexColorOutline;
                --tsundoku-reader-background: $readerBgHex;
                --tsundoku-reader-text: $readerTextHex;
            }
        """.trimIndent()

        val jsObject = """
            {
                "mdSysColorPrimary": "$mdHexColorPrimary",
                "mdSysColorOnPrimary": "$mdHexColorOnPrimary",
                "mdSysColorPrimaryContainer": "$mdHexColorPrimaryContainer",
                "mdSysColorOnPrimaryContainer": "$mdHexColorOnPrimaryContainer",
                "mdSysColorSecondary": "$mdHexColorSecondary",
                "mdSysColorOnSecondary": "$mdHexColorOnSecondary",
                "mdSysColorSecondaryContainer": "$mdHexColorSecondaryContainer",
                "mdSysColorOnSecondaryContainer": "$mdHexColorOnSecondaryContainer",
                "mdSysColorTertiary": "$mdHexColorTertiary",
                "mdSysColorOnTertiary": "$mdHexColorOnTertiary",
                "mdSysColorTertiaryContainer": "$mdHexColorTertiaryContainer",
                "mdSysColorOnTertiaryContainer": "$mdHexColorOnTertiaryContainer",
                "mdSysColorError": "$mdHexColorError",
                "mdSysColorOnError": "$mdHexColorOnError",
                "mdSysColorErrorContainer": "$mdHexColorErrorContainer",
                "mdSysColorOnErrorContainer": "$mdHexColorOnErrorContainer",
                "mdSysColorBackground": "$mdHexColorBackground",
                "mdSysColorOnBackground": "$mdHexColorOnBackground",
                "mdSysColorSurface": "$mdHexColorSurface",
                "mdSysColorOnSurface": "$mdHexColorOnSurface",
                "mdSysColorSurfaceVariant": "$mdHexColorSurfaceVariant",
                "mdSysColorOnSurfaceVariant": "$mdHexColorOnSurfaceVariant",
                "mdSysColorOutline": "$mdHexColorOutline",
                "tsundokuReaderBackground": "$readerBgHex",
                "tsundokuReaderText": "$readerTextHex"
            }
        """.trimIndent()

        return ThemeTokens(cssVariables, jsObject)
    }

    /**
     * Converts an ARGB color int to a `#RRGGBB` hex string (alpha stripped).
     * Extracted as `internal` so JVM unit tests can verify the formatting logic
     * without an Android `Activity`. The rest of `ThemeUtils` requires Android
     * framework objects and is covered by instrumented tests only.
     */
    internal fun colorToHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    private fun resolveColorAttribute(
        theme: android.content.res.Resources.Theme,
        typedValue: android.util.TypedValue,
        attr: Int,
        fallback: Long,
    ): Int {
        return if (theme.resolveAttribute(attr, typedValue, true)) {
            typedValue.data
        } else {
            fallback.toInt()
        }
    }
}
