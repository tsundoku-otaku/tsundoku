package eu.kanade.tachiyomi.util.epub

import eu.kanade.tachiyomi.source.model.SManga

object EpubExportNaming {

    fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(200)
    }

    fun formatChapterNumber(number: Double): String {
        return if (number == number.toLong().toDouble()) {
            number.toLong().toString()
        } else {
            number.toString()
        }
    }

    fun appendChapterCount(
        filenameBuilder: StringBuilder,
        chapterCount: Int,
        includeChapterCount: Boolean,
    ) {
        if (!includeChapterCount) return
        filenameBuilder.append(" [${chapterCount}ch]")
    }

    fun appendChapterRange(
        filenameBuilder: StringBuilder,
        chapterNumbers: List<Double>,
        includeChapterRange: Boolean,
    ) {
        if (!includeChapterRange || chapterNumbers.isEmpty()) return

        val sortedNumbers = chapterNumbers.filter { it != 0.0 }
        if (sortedNumbers.isEmpty()) return

        val firstLabel = formatChapterNumber(sortedNumbers.minOrNull() ?: 0.0)
        val lastLabel = formatChapterNumber(sortedNumbers.maxOrNull() ?: 0.0)

        if (firstLabel == lastLabel) {
            filenameBuilder.append(" [ch$firstLabel]")
        } else {
            filenameBuilder.append(" [ch$firstLabel-$lastLabel]")
        }
    }

    fun appendStatusLabel(
        filenameBuilder: StringBuilder,
        statusLabel: String?,
        includeStatus: Boolean,
    ) {
        if (!includeStatus) return
        statusLabel?.takeIf { it.isNotBlank() }?.let { filenameBuilder.append(" [$it]") }
    }

    fun mangaStatusLabel(status: Long): String? {
        return when (status) {
            SManga.ONGOING.toLong() -> "Ongoing"
            SManga.COMPLETED.toLong() -> "Completed"
            SManga.LICENSED.toLong() -> "Licensed"
            SManga.PUBLISHING_FINISHED.toLong() -> "Finished"
            SManga.CANCELLED.toLong() -> "Cancelled"
            SManga.ON_HIATUS.toLong() -> "Hiatus"
            else -> null
        }
    }
}
