package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.DuplicateGroup
import tachiyomi.domain.manga.repository.DuplicatePair
import tachiyomi.domain.manga.repository.MangaRepository

enum class DuplicateMatchMode {
    EXACT, // Exact title match (case-insensitive, trimmed)
    CONTAINS, // One title contains another
    URL, // Same URL within the same extension/source
}

/**
 * Interactor to find duplicate novels in the library.
 * Uses database queries for efficient duplicate detection without blocking UI thread.
 */
class FindDuplicateNovels(
    private val mangaRepository: MangaRepository,
) {
    /**
     * Find duplicate groups using exact matching (case-insensitive, trimmed).
     * Returns groups of manga IDs that share the same normalized title.
     */
    suspend fun findExact(): List<DuplicateGroup> {
        return mangaRepository.findDuplicatesExact()
    }

    /**
     * Find duplicate pairs using contains matching.
     * Returns pairs where one title contains another.
     */
    suspend fun findContains(): List<DuplicatePair> {
        val favorites = mangaRepository.getFavoriteIdAndTitle()
        if (favorites.size < 2) return emptyList()

        val normalized = favorites.map { (id, title) ->
            Triple(id, title, title.lowercase().trim())
        }.filter { it.third.isNotEmpty() }
            .sortedBy { it.third.length } // Sort by length so shorter titles are checked first

        val pairs = mutableListOf<DuplicatePair>()
        for (i in normalized.indices) {
            val (idA, titleA, normA) = normalized[i]
            for (j in i + 1 until normalized.size) {
                val (idB, titleB, normB) = normalized[j]
                // Since sorted by length, normB.length >= normA.length
                // "Contains" only makes sense when lengths differ significantly
                if (normA.length >= normB.length * 0.8) continue
                // Short title must be at least 3 chars to avoid false positives
                if (normA.length < 3) continue
                if (normB.contains(normA)) {
                    pairs.add(DuplicatePair(idA, titleA, idB, titleB))
                }
            }
        }
        return pairs
    }

    /**
     * Get manga with chapter counts for a list of IDs.
     * Used to get full manga info after finding duplicates.
     */
    suspend fun getMangaWithCounts(ids: List<Long>): List<MangaWithChapterCount> {
        return mangaRepository.getMangaWithCounts(ids)
    }

    /**
     * Lightweight version that skips genre/description to avoid OOM on large libraries.
     */
    private suspend fun getMangaWithCountsLight(ids: List<Long>): List<MangaWithChapterCount> {
        return mangaRepository.getMangaWithCountsLight(ids)
    }

    /**
     * Find potential similar novels for a specific manga (excluding itself).
     * Returns novels in library that match or contain the title.
     */
    suspend fun findSimilarTo(mangaId: Long, title: String): List<MangaWithChapterCount> {
        val exactMatches = mangaRepository.findDuplicatesExact()
            .find { group -> group.ids.contains(mangaId) }
            ?.ids?.filter { it != mangaId }
            ?: emptyList()

        val containsMatches = mangaRepository.findDuplicatesContains()
            .filter { it.idA == mangaId || it.idB == mangaId }
            .flatMap { listOf(it.idA, it.idB) }
            .filter { it != mangaId }
            .distinct()

        val allMatchIds = (exactMatches + containsMatches).distinct()

        return getMangaWithCounts(allMatchIds).sortedByDescending { it.chapterCount }
    }

    /**
     * Find duplicates by URL within the same extension.
     * Returns groups where multiple manga have the same URL from the same source.
     */
    suspend fun findUrlDuplicates(): List<DuplicateGroup> {
        return mangaRepository.findDuplicatesByUrl()
    }

    /**
     * Find duplicates and return full manga info with chapter counts.
     * Groups results by normalized title.
     */
    suspend fun findDuplicatesGrouped(mode: DuplicateMatchMode): Map<String, List<MangaWithChapterCount>> {
        return when (mode) {
            DuplicateMatchMode.EXACT -> {
                val groups = findExact()
                val allIds = groups.flatMap { it.ids }
                val mangaMap = getMangaWithCountsLight(allIds).associateBy { it.manga.id }

                groups.mapNotNull { group ->
                    val mangaList = group.ids.mapNotNull { mangaMap[it] }
                    if (mangaList.size > 1) {
                        group.normalizedTitle to mangaList.sortedByDescending { it.chapterCount }
                    } else {
                        null
                    }
                }.toMap()
            }
            DuplicateMatchMode.CONTAINS -> {
                val pairs = findContains()
                // Group pairs by the shorter title (the one that's contained)
                val allIds = pairs.flatMap { listOf(it.idA, it.idB) }.distinct()
                val mangaMap = getMangaWithCountsLight(allIds).associateBy { it.manga.id }

                val groups = mutableMapOf<String, MutableSet<Long>>()
                pairs.forEach { pair ->
                    val keyA = pair.titleA.lowercase().trim()
                    val keyB = pair.titleB.lowercase().trim()
                    val key = if (keyA.length <= keyB.length) keyA else keyB

                    groups.getOrPut(key) { mutableSetOf() }.apply {
                        add(pair.idA)
                        add(pair.idB)
                    }
                }

                groups.mapNotNull { (title, ids) ->
                    val mangaList = ids.mapNotNull { mangaMap[it] }
                    if (mangaList.size > 1) {
                        title to mangaList.sortedByDescending { it.chapterCount }
                    } else {
                        null
                    }
                }.toMap()
            }
            DuplicateMatchMode.URL -> {
                val groups = findUrlDuplicates()
                val allIds = groups.flatMap { it.ids }
                val mangaMap = getMangaWithCountsLight(allIds).associateBy { it.manga.id }

                groups.mapNotNull { group ->
                    val mangaList = group.ids.mapNotNull { mangaMap[it] }
                    if (mangaList.size > 1) {
                        group.normalizedTitle to mangaList.sortedByDescending { it.chapterCount }
                    } else {
                        null
                    }
                }.toMap()
            }
        }
    }
}
