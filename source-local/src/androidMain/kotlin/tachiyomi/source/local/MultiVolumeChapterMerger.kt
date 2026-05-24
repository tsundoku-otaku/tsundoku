package tachiyomi.source.local

import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder


fun <T> groupChaptersByVolume(
    chapters: List<T>,
    volumeKeyOf: (T) -> String?,
    orderOf: (T) -> Int,
    withOrder: (T, Int) -> T,
): List<T> {
    if (chapters.isEmpty()) return chapters

    val (volumeChapters, looseChapters) = chapters.partition { volumeKeyOf(it) != null }
    if (volumeChapters.isEmpty()) return chapters

    val groupedByVolume = volumeChapters
        .groupBy { volumeKeyOf(it)!! }
        .mapValues { (_, items) -> items.sortedBy(orderOf) }

    val sortedVolumeKeys = groupedByVolume.keys.sortedWith { a, b ->
        a.compareToCaseInsensitiveNaturalOrder(b)
    }

    val ordered = ArrayList<T>(chapters.size)
    var nextOrder = 0
    sortedVolumeKeys.forEach { key ->
        groupedByVolume[key].orEmpty().forEach { chapter ->
            ordered.add(withOrder(chapter, nextOrder++))
        }
    }
    looseChapters.sortedBy(orderOf).forEach { chapter ->
        ordered.add(withOrder(chapter, nextOrder++))
    }
    return ordered
}
