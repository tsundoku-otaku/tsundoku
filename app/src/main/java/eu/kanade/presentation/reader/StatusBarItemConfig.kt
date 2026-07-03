package eu.kanade.presentation.reader

enum class StatusBarItem(val id: String) {
    TIME("time"),
    CHAPTER("chapter"),
    PROGRESS("progress"),
    BATTERY("battery"),
}

val DefaultStatusBarOrder = listOf(
    StatusBarItem.TIME,
    StatusBarItem.CHAPTER,
    StatusBarItem.PROGRESS,
    StatusBarItem.BATTERY,
)

fun String.deserializeStatusBarOrder(): List<StatusBarItem> {
    val savedIds = split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val saved = savedIds.mapNotNull { id -> StatusBarItem.entries.find { it.id == id } }
    val missing = DefaultStatusBarOrder.filter { it !in saved }
    return (saved + missing).distinct()
}

fun List<StatusBarItem>.serializeStatusBarOrder(): String = joinToString(",") { it.id }
