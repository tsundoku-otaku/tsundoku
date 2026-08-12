package mihon.domain.migration

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.source.normalizeSourcePath
import tachiyomi.domain.manga.model.Manga

/** Leading-slash normalization matching how source urls are stored, for [targetSource]'s convention. */
fun normalizeQuickMigrateUrl(url: String, targetSource: Source): String =
    normalizeSourcePath(targetSource, url)

/**
 * Pairs each selectable manga with its normalized target url, dropping the ones already favorited on
 * the target source. [existingFavoriteUrls] is the one-shot set of target-source favorite urls, so
 * duplicate detection is in-memory instead of one query per manga.
 */
fun quickMigrateTargets(
    selected: List<Manga>,
    existingFavoriteUrls: Set<String>,
    targetSource: Source,
): List<Pair<Manga, String>> =
    selected.mapNotNull { manga ->
        val newUrl = normalizeQuickMigrateUrl(manga.url, targetSource)
        if (newUrl in existingFavoriteUrls) null else manga to newUrl
    }

/** The other half of [quickMigrateTargets]: the entries the target source already has. */
fun quickMigrateSkipped(
    selected: List<Manga>,
    existingFavoriteUrls: Set<String>,
    targetSource: Source,
): List<Manga> = selected.filter { normalizeQuickMigrateUrl(it.url, targetSource) in existingFavoriteUrls }
