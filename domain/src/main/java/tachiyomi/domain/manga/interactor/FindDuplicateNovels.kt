package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.DuplicateGroup
import tachiyomi.domain.manga.repository.DuplicatePair
import tachiyomi.domain.manga.repository.MangaRepository

enum class DuplicateMatchMode {
    EXACT, // Exact title match (case-insensitive, trimmed)
    CONTAINS, // One title contains another
    URL, // Same URL within the same extension/source
}

enum class BlankTitleFilter {
    EXCLUDE, // Default; avoids one giant false-duplicate group of every blank title/URL
    INCLUDE,
}

/**
 * [displayGroups] is capped per-group and in total group count, so neither one pathological group
 * nor many small ones can materialize more than [MAX_TOTAL_MATERIALIZED_IDS] full manga rows.
 * [allGroupIds] has every id in every duplicate group, uncapped, including groups that didn't make
 * it into [displayGroups] at all: callers that only need ids (bulk select/delete/move) aren't
 * bound by the display cap. [truncated]/[totalGroups] let the caller show "N of M groups" instead
 * of silently dropping some.
 */
data class DuplicateScanResult(
    val displayGroups: Map<String, List<MangaWithChapterCount>>,
    val allGroupIds: Map<String, List<Long>>,
    val truncated: Boolean = false,
    val totalGroups: Int = 0,
)

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
    suspend fun findExact(includeBlank: Boolean = false): List<DuplicateGroup> {
        return mangaRepository.findDuplicatesExact(includeBlank)
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

    private suspend fun getMangaWithCountsLight(ids: List<Long>): List<MangaWithChapterCount> {
        return mangaRepository.getMangaWithCountsLightWithGenre(ids)
    }

    /**
     * Find potential similar novels for a specific manga (excluding itself).
     * Returns novels in library that match or contain the title.
     */
    suspend fun findSimilarTo(mangaId: Long, title: String): List<MangaWithChapterCount> {
        val exactMatches = mangaRepository.findDuplicatesExact(includeBlank = true)
            .find { group -> group.ids.contains(mangaId) }
            ?.ids?.filter { it != mangaId }
            ?: emptyList()

        val containsMatches = mangaRepository.findDuplicatesContains()
            .filter { it.idA == mangaId || it.idB == mangaId }
            .flatMap { listOf(it.idA, it.idB) }
            .filter { it != mangaId }
            .distinct()

        val allMatchIds = (exactMatches + containsMatches).distinct()
        val cappedMatchIds = if (allMatchIds.size <= MAX_GROUP_MEMBERS) {
            allMatchIds
        } else {
            val counts = mangaRepository.getTotalCountsForIds(allMatchIds).toMap()
            allMatchIds.sortedByDescending { counts[it] ?: 0L }.take(MAX_GROUP_MEMBERS)
        }

        return getMangaWithCounts(cappedMatchIds).sortedByDescending { it.chapterCount }
    }

    /**
     * Find duplicates by URL within the same extension.
     * Returns groups where multiple manga have the same URL from the same source.
     */
    suspend fun findUrlDuplicates(includeBlank: Boolean = false): List<DuplicateGroup> {
        return mangaRepository.findDuplicatesByUrl(includeBlank)
    }

    /**
     * Materialize the given manga ids and group them by normalized title, keeping single entries.
     * The caller resolves the id set (e.g. category-filtered) so only the needed rows are loaded.
     */
    suspend fun findGroupedByIds(ids: List<Long>): Map<String, List<MangaWithChapterCount>> {
        if (ids.isEmpty()) return emptyMap()

        val items = getMangaWithCountsLight(ids)
        if (items.isEmpty()) return emptyMap()

        // A manga's match keys are its normalized title plus each normalized alternative title.
        // Two manga that share ANY key are duplicates, so union them (title-A == alt-title-B counts).
        val keyToItems = HashMap<String, MutableList<Int>>()
        items.forEachIndexed { idx, item ->
            val keys = buildSet {
                item.manga.title.trim().lowercase().takeIf { it.isNotBlank() }?.let { add(it) }
                item.manga.alternativeTitles.forEach { alt ->
                    alt.trim().lowercase().takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
            keys.forEach { keyToItems.getOrPut(it) { mutableListOf() }.add(idx) }
        }

        val parent = IntArray(items.size) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != cur) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }
        keyToItems.values.forEach { shared ->
            for (k in 1 until shared.size) {
                val ra = find(shared[0])
                val rb = find(shared[k])
                if (ra != rb) parent[ra] = rb
            }
        }

        val groupsByRoot = LinkedHashMap<Int, MutableList<MangaWithChapterCount>>()
        items.indices.forEach { i -> groupsByRoot.getOrPut(find(i)) { mutableListOf() }.add(items[i]) }

        val result = LinkedHashMap<String, List<MangaWithChapterCount>>()
        groupsByRoot.values.forEach { members ->
            val representativeId = members.first().manga.id
            val baseKey = members.first().manga.title.trim().lowercase().ifBlank { representativeId.toString() }
            // Two distinct groups can share a base key (e.g. one group's blank-title fallback
            // equals another group's literal title); disambiguate rather than silently
            // overwriting and losing a whole duplicate group from the result.
            val key = if (result.containsKey(baseKey)) "$baseKey#$representativeId" else baseKey
            result[key] = members.sortedByDescending { it.chapterCount }
        }
        return result
    }

    /**
     * Turns [DuplicateGroup]s into a key->ids map. URL-mode groups are keyed by URL alone (the
     * SQL groups by url+source), so two different-source groups can share a normalizedTitle;
     * disambiguate rather than letting `.associate` silently drop one group's ids.
     */
    private fun groupsToMap(groups: List<DuplicateGroup>): Map<String, List<Long>> {
        val result = LinkedHashMap<String, List<Long>>()
        groups.forEach { group ->
            val baseKey = group.normalizedTitle
            val key = if (result.containsKey(baseKey)) "$baseKey#${group.ids.first()}" else baseKey
            result[key] = group.ids
        }
        return result
    }

    private fun groupContainsPairs(pairs: List<DuplicatePair>): Map<String, List<Long>> {
        val groups = mutableMapOf<String, MutableSet<Long>>()
        pairs.forEach { pair ->
            val keyA = pair.titleA.lowercase().trim()
            val keyB = pair.titleB.lowercase().trim()
            // Group pairs by the shorter title (the one that's contained)
            val key = if (keyA.length <= keyB.length) keyA else keyB
            groups.getOrPut(key) { mutableSetOf() }.apply {
                add(pair.idA)
                add(pair.idB)
            }
        }
        return groups.mapValues { it.value.toList() }
    }

    /**
     * Find duplicates and return full manga info with chapter counts, grouped by key.
     * See [DuplicateScanResult] for how a pathologically large group is handled.
     */
    suspend fun findDuplicatesGrouped(
        mode: DuplicateMatchMode,
        blankTitleFilter: BlankTitleFilter = BlankTitleFilter.EXCLUDE,
    ): DuplicateScanResult {
        val includeBlank = blankTitleFilter != BlankTitleFilter.EXCLUDE

        val rawGroups = when (mode) {
            DuplicateMatchMode.EXACT -> groupsToMap(findExact(includeBlank))
            DuplicateMatchMode.URL -> groupsToMap(findUrlDuplicates(includeBlank))
            DuplicateMatchMode.CONTAINS -> groupContainsPairs(findContains())
        }.filterValues { it.size > 1 }

        // A mass-imported library can surface thousands of small real duplicate groups; their
        // combined member count is just as heap-dangerous as one giant group, so cap the total
        // across groups too, not just per-group. Largest groups first: the biggest offenders are
        // what the user is here to fix, and they'd otherwise be crowded out by many tiny ones.
        val orderedGroups = rawGroups.entries.sortedByDescending { it.value.size }
        var runningTotal = 0
        val keptGroups = LinkedHashMap<String, List<Long>>()
        for (entry in orderedGroups) {
            val size = entry.value.size.coerceAtMost(MAX_GROUP_MEMBERS)
            if (keptGroups.isNotEmpty() && runningTotal + size > MAX_TOTAL_MATERIALIZED_IDS) break
            keptGroups[entry.key] = entry.value
            runningTotal += size
        }
        val truncated = keptGroups.size < rawGroups.size

        // Rank by chapter count BEFORE truncating so a group larger than MAX_GROUP_MEMBERS keeps
        // its highest-chapter-count entries instead of an arbitrary DB-order slice; total_count is
        // a cached column, so this is a cheap id-scoped lookup, not a full manga row fetch.
        val cappedGroups = coroutineScope {
            keptGroups.mapValues { (_, ids) ->
                async {
                    if (ids.size <= MAX_GROUP_MEMBERS) {
                        ids
                    } else {
                        val counts = mangaRepository.getTotalCountsForIds(ids).toMap()
                        ids.sortedByDescending { counts[it] ?: 0L }.take(MAX_GROUP_MEMBERS)
                    }
                }
            }.mapValues { (_, deferred) -> deferred.await() }
        }
        val mangaMap = getMangaWithCountsLight(cappedGroups.values.flatten()).associateBy { it.manga.id }

        val displayGroups = cappedGroups.mapNotNull { (key, ids) ->
            val mangaList = ids.mapNotNull { mangaMap[it] }
            if (mangaList.size > 1) key to mangaList.sortedByDescending { it.chapterCount } else null
        }.toMap()

        return DuplicateScanResult(displayGroups, rawGroups, truncated, rawGroups.size)
    }

    companion object {
        private const val MAX_GROUP_MEMBERS = 2000
        private const val MAX_TOTAL_MATERIALIZED_IDS = 20_000
    }
}
