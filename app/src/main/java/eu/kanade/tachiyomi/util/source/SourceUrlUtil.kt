package eu.kanade.tachiyomi.util.source

import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource

fun resolveRelativeUrl(baseUrl: String, pathOrUrl: String): String {
    if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
        return pathOrUrl
    }
    val normalizedBase = baseUrl.trimEnd('/')
    return if (pathOrUrl.startsWith('/')) {
        normalizedBase + pathOrUrl
    } else {
        "$normalizedBase/$pathOrUrl"
    }
}

fun Source.getMangaUrlOrNull(manga: SManga): String? {
    return when (this) {
        is HttpSource -> getMangaUrl(manga)
        is JsSource -> resolveRelativeUrl(baseUrl, manga.url)
        else -> manga.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
}

fun Source.getChapterUrlOrNull(chapter: SChapter): String? {
    return when (this) {
        is HttpSource -> getChapterUrl(chapter)
        is JsSource -> resolveRelativeUrl(baseUrl, chapter.url)
        else -> chapter.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
}
