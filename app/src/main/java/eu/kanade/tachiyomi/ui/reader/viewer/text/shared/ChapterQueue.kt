package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

/**
 * Not thread-safe. Both viewers mutate this from the main thread only.
 */
class ChapterQueue<T>(private val idOf: (T) -> Long?) {

    private val items = mutableListOf<T>()
    private val ids = mutableSetOf<Long>()

    var currentIndex: Int = 0
    var isLoadingNext: Boolean = false

    val size: Int get() = items.size
    fun isEmpty(): Boolean = items.isEmpty()
    fun isNotEmpty(): Boolean = items.isNotEmpty()

    val all: List<T> get() = items
    val loadedIds: Set<Long> get() = ids

    fun contains(chapterId: Long): Boolean = chapterId in ids

    fun append(item: T): Boolean {
        val id = idOf(item) ?: return false
        if (id in ids) return false
        items.add(item)
        ids.add(id)
        return true
    }

    fun prepend(item: T): Boolean {
        val id = idOf(item) ?: return false
        if (id in ids) return false
        items.add(0, item)
        ids.add(id)
        currentIndex += 1
        return true
    }

    fun current(): T? = items.getOrNull(currentIndex)
    fun getOrNull(index: Int): T? = items.getOrNull(index)
    fun firstOrNull(): T? = items.firstOrNull()
    fun lastOrNull(): T? = items.lastOrNull()

    fun indexOf(chapterId: Long): Int = items.indexOfFirst { idOf(it) == chapterId }

    fun removeFirst(): T? {
        if (items.isEmpty()) return null
        val removed = items.removeAt(0)
        idOf(removed)?.let { ids.remove(it) }
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        return removed
    }

    fun removeFirstN(count: Int) {
        var actualRemoved = 0
        repeat(count.coerceAtLeast(0)) {
            if (items.isEmpty()) return@repeat
            val removed = items.removeAt(0)
            idOf(removed)?.let { ids.remove(it) }
            actualRemoved++
        }
        currentIndex = (currentIndex - actualRemoved).coerceAtLeast(0)
    }

    fun reset(item: T) {
        clear()
        append(item)
    }

    fun clear() {
        items.clear()
        ids.clear()
        currentIndex = 0
    }
}
