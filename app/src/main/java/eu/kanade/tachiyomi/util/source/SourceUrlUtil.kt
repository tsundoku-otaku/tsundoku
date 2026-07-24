package eu.kanade.tachiyomi.util.source

import androidx.core.net.toUri
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.custom.CustomNovelSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource

fun resolveRelativeUrl(baseUrl: String, pathOrUrl: String): String {
    val value = pathOrUrl.trim()
    if (value.startsWith("http://") || value.startsWith("https://")) {
        return value
    }
    if (value.startsWith("https//")) {
        return "https://" + value.removePrefix("https//")
    }
    if (value.startsWith("http//")) {
        return "http://" + value.removePrefix("http//")
    }

    val embeddedAbsoluteStart = listOf("https://", "http://", "https//", "http//")
        .mapNotNull { scheme ->
            val idx = value.lastIndexOf(scheme)
            if (idx > 0) idx to scheme else null
        }
        .maxByOrNull { it.first }
    if (embeddedAbsoluteStart != null) {
        val embedded = value.substring(embeddedAbsoluteStart.first)
        return when {
            embedded.startsWith("https//") -> "https://" + embedded.removePrefix("https//")
            embedded.startsWith("http//") -> "http://" + embedded.removePrefix("http//")
            else -> embedded
        }
    }

    val normalizedBase = baseUrl.trimEnd('/')
    if (value.startsWith("//")) {
        val scheme = baseUrl.substringBefore("://", "https")
        return "$scheme:$value"
    }

    val baseHost = runCatching { baseUrl.toUri().host?.removePrefix("www.") }.getOrNull()
    if (!baseHost.isNullOrBlank() && value.contains(baseHost, ignoreCase = true)) {
        val scheme = baseUrl.substringBefore("://", "https")
        val hostStart = value.indexOf(baseHost, ignoreCase = true)
        val hostAndPath = value.substring(hostStart)
        return "$scheme://$hostAndPath"
    }

    return if (value.startsWith('/')) {
        normalizedBase + value
    } else {
        "$normalizedBase/$value"
    }
}

fun normalizeSourcePath(source: Source, pathOrUrl: String): String {
    val trimmed = pathOrUrl.trim()
    if (trimmed.isBlank()) {
        return trimmed
    }
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        return trimmed
    }
    return when (source) {
        is JsSource, is HttpSource -> if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        else -> trimmed
    }
}

fun Source.getMangaUrlOrNull(manga: SManga): String? {
    return try {
        when (this) {
            is CustomNovelSource -> resolveRelativeUrl(baseUrl, manga.url)
            is HttpSource -> getMangaUrl(manga)
            is JsSource -> resolveRelativeUrl(baseUrl, manga.url)
            else -> manga.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
    } catch (_: Exception) {
        null
    }
}

fun Source.getChapterUrlOrNull(chapter: SChapter): String? {
    return try {
        when (this) {
            is CustomNovelSource -> resolveRelativeUrl(baseUrl, chapter.url)
            is HttpSource -> getChapterUrl(chapter)
            is JsSource -> resolveRelativeUrl(baseUrl, chapter.url)
            else -> chapter.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
    } catch (_: Exception) {
        null
    }
}
