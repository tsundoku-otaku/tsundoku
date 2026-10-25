package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import android.app.Activity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences

object ThemeUtils {

    data class ThemeTokens(
        val cssVariables: String,
        val jsObject: String,
    ) {
        // Re-applies the tokens to an already-loaded document, which only got them at build time.
        val updateJs: String
            get() {
                val vars = CSS_VAR_REGEX.findAll(cssVariables).joinToString("") {
                    "r.setProperty('${it.groupValues[1]}','${it.groupValues[2]}');"
                }
                return "(function(){var r=document.documentElement.style;$vars" +
                    "window.TsundokuTheme=$jsObject;})();"
            }
    }

    private val CSS_VAR_REGEX = Regex("""(--[\w-]+):\s*(#[0-9A-Fa-f]{6});""")

    internal fun isLight(color: Int): Boolean = readableTextOn(color) == 0xFF000000.toInt()

    private fun luminance(color: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    internal fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    // App accent for links, falling back to the text color when it would be hard to read on the reader bg.
    fun getLinkColor(activity: Activity, background: Int, text: Int): Int {
        val accent = resolveColorAttribute(
            activity.theme,
            android.util.TypedValue(),
            com.google.android.material.R.attr.colorPrimary,
            text.toLong(),
        )
        return if (contrastRatio(accent, background) >= 3.0) accent else text
    }

    internal fun readableTextOn(background: Int): Int {
        val r = (background shr 16) and 0xFF
        val g = (background shr 8) and 0xFF
        val b = background and 0xFF
        val luma = 0.299 * r + 0.587 * g + 0.114 * b
        return if (luma < 128) 0xFFE0E0E0.toInt() else 0xFF000000.toInt()
    }

    fun getThemeColors(activity: Activity, preferences: ReaderPreferences, theme: String): Pair<Int, Int> {
        val backgroundColor = preferences.novelBackgroundColor.get()
        val fontColor = preferences.novelFontColor.get()

        val (bg, themeText) = when (theme) {
            "app" -> appColors(activity)
            "dark" -> 0xFF121212.toInt() to 0xFFE0E0E0.toInt()
            "sepia" -> 0xFFF4ECD8.toInt() to 0xFF5B4636.toInt()
            "black" -> 0xFF000000.toInt() to 0xFFCCCCCC.toInt()
            "grey" -> 0xFF292832.toInt() to 0xFFCCCCCC.toInt()
            "custom" -> {
                val customBg = if (backgroundColor != 0) backgroundColor else 0xFFFFFFFF.toInt()
                customBg to readableTextOn(customBg)
            }
            else -> 0xFFFFFFFF.toInt() to 0xFF000000.toInt()
        }
        return bg to if (fontColor != 0) fontColor else themeText
    }

    private fun appColors(activity: Activity): Pair<Int, Int> {
        val typedValue = android.util.TypedValue()
        val actTheme = activity.theme
        val isNight = (
            activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            ) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bgColor = resolveColorAttribute(
            actTheme,
            typedValue,
            com.google.android.material.R.attr.colorSurface,
            if (isNight) 0xFF121212 else 0xFFFFFFFF,
        )
        val textColor = resolveColorAttribute(
            actTheme,
            typedValue,
            com.google.android.material.R.attr.colorOnSurface,
            if (isNight) 0xFFE0E0E0 else 0xFF000000,
        )
        return bgColor to textColor
    }

    fun getThemeTokens(activity: Activity, preferences: ReaderPreferences, theme: String): ThemeTokens {
        val (readerBgColor, readerTextColor) = getThemeColors(activity, preferences, theme)

        val readerBgHex = colorToHex(readerBgColor)
        val readerTextHex = colorToHex(readerTextColor)

        val typedValue = android.util.TypedValue()
        val appTheme = activity.theme

        val mdSysColorPrimary =
            resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorPrimary, 0xFF006A6A)
        val mdSysColorOnPrimary =
            resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF)
        val mdSysColorPrimaryContainer =
            resolveColorAttribute(
                appTheme,
                typedValue,
                com.google.android.material.R.attr.colorPrimaryContainer,
                0xFF6FF7F6,
            )
        val mdSysColorOnPrimaryContainer =
            resolveColorAttribute(
                appTheme,
                typedValue,
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                0xFF002020,
            )
        val mdSysColorSecondary =
            resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorSecondary, 0xFF006A6A)
        val mdSysColorOnSecondary =
            resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnSecondary, 0xFFFFFFFF)
        val mdSysColorSecondaryContainer =
            resolveColorAttribute(
                appTheme,
                typedValue,
                com.google.android.material.R.attr.colorSecondaryContainer,
                0xFF6FF7F6,
            )
        val mdSysColorOnSecondaryContainer =
            resolveColorAttribute(
                appTheme,
                typedValue,
                com.google.android.material.R.attr.colorOnSecondaryContainer,
                0xFF002020,
            )
        val mdSysColorTertiary =
            resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorTertiary, 0xFF006A6A)
        val mdSysColorOnTertiary =
            resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnTertiary, 0xFFFFFFFF)
        val mdSysColorTertiaryContainer =
            resolveColorAttribute(
                appTheme,
                typedValue,
                com.google.android.material.R.attr.colorTertiaryContainer,
                0xFF6FF7F6,
            )
        val mdSysColorOnTertiaryContainer =
            resolveColorAttribute(
                appTheme,
                typedValue,
                com.google.android.material.R.attr.colorOnTertiaryContainer,
                0xFF002020,
            )
        val mdSysColorError =
            resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorError, 0xFFB3261E)
        val mdSysColorOnError =
            resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOnError, 0xFFFFFFFF)
        val mdSysColorErrorContainer =
            resolveColorAttribute(
                appTheme,
                typedValue,
                com.google.android.material.R.attr.colorErrorContainer,
                0xFFF9DEDC,
            )
        val mdSysColorOnErrorContainer =
            resolveColorAttribute(
                appTheme,
                typedValue,
                com.google.android.material.R.attr.colorOnErrorContainer,
                0xFF410E0B,
            )
        val followApp = theme == "app"
        val mdSysColorBackground = if (followApp) {
            resolveColorAttribute(appTheme, typedValue, android.R.attr.colorBackground, readerBgColor.toLong())
        } else {
            readerBgColor
        }
        val mdSysColorOnBackground = if (followApp) {
            resolveColorAttribute(
                appTheme,
                typedValue,
                com.google.android.material.R.attr.colorOnBackground,
                readerTextColor.toLong(),
            )
        } else {
            readerTextColor
        }
        val mdSysColorSurface = if (followApp) {
            resolveColorAttribute(
                appTheme,
                typedValue,
                com.google.android.material.R.attr.colorSurface,
                readerBgColor.toLong(),
            )
        } else {
            readerBgColor
        }
        val mdSysColorOnSurface = if (followApp) {
            resolveColorAttribute(
                appTheme,
                typedValue,
                com.google.android.material.R.attr.colorOnSurface,
                readerTextColor.toLong(),
            )
        } else {
            readerTextColor
        }
        val mdSysColorSurfaceVariant =
            resolveColorAttribute(
                appTheme,
                typedValue,
                com.google.android.material.R.attr.colorSurfaceVariant,
                0xFFCCC7C0,
            )
        val mdSysColorOnSurfaceVariant =
            resolveColorAttribute(
                appTheme,
                typedValue,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                0xFF49454E,
            )
        val mdSysColorOutline =
            resolveColorAttribute(appTheme, typedValue, com.google.android.material.R.attr.colorOutline, 0xFF79747E)

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
