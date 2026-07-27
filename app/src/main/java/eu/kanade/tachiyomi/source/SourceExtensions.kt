package eu.kanade.tachiyomi.source

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.custom.CustomNovelSource
import tachiyomi.domain.source.model.StubSource
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

fun Source.getNameForMangaInfo(): String {
    val preferences = Injekt.get<SourcePreferences>()
    val enabledLanguages = preferences.enabledLanguages.get()
        .filterNot { it in listOf("all", "other") }
    val hasOneActiveLanguages = enabledLanguages.size == 1
    val isInEnabledLanguages = lang in enabledLanguages
    return when {
        // For edge cases where user disables a source they got manga of in their library.
        hasOneActiveLanguages && !isInEnabledLanguages -> toString()
        // Hide the language tag when only one language is used, but keep the type tag.
        hasOneActiveLanguages && isInEnabledLanguages -> nameWithTypeTag()
        else -> toString()
    }
}

fun Source.isLocalOrStub(): Boolean = isLocal() || this is StubSource

enum class SourceTypeTag(val label: String) {
    JS("JS"),
    CUSTOM("Custom"),
}

// A stub carries the marker it was registered with; deriving it from isNovelSource is wrong
// because novel sources also ship as Kotlin extensions.
fun Source.typeTag(): SourceTypeTag? = when {
    this is CustomNovelSource -> SourceTypeTag.CUSTOM
    this is JsSource -> SourceTypeTag.JS
    this is StubSource && isJsSource -> SourceTypeTag.JS
    else -> null
}

fun Source.nameWithTypeTag(): String = typeTag()?.let { "$name (${it.label})" } ?: name

// Custom and local sources have no language toggle of their own (local reports "other"), so they
// are never filtered out.
fun <T : Source> List<T>.filterUserEnabled(preferences: SourcePreferences = Injekt.get()): List<T> {
    val enabledLanguages = preferences.enabledLanguages.get()
    val disabledSources = preferences.disabledSources.get()
    return filter { source ->
        source is CustomNovelSource || source.isLocal() ||
            (source.lang in enabledLanguages && "${source.id}" !in disabledSources)
    }
}
