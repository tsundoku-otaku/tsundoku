package tachiyomi.domain.manga.model

/**
 * Lightweight per-entry data used to drive bulk selection over the whole matching set without
 * materializing full [Manga] rows.
 */
data class MangaSelectionMetric(
    val id: Long,
    val groupKey: String,
    val source: Long,
    val chapterCount: Int,
    val readCount: Int,
)
