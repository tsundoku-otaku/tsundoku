package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

/**
 * Ordered queue of chapters loaded into a reader viewer with O(1) duplicate
 * detection by chapter id, a cursor pointing at the visible chapter, and an
 * `isLoadingNext` flag for the infinite-scroll trigger.
 *
 * Both novel viewers used to track this state with parallel
 * `loadedChapters: MutableList<...>` + `loadedChapterIds: MutableList<Long>`
 * + `currentChapterIndex: Int` + `isLoadingNext: Boolean` fields kept in sync
 * by convention. Mistakes were easy: WebView appended to `loadedChapters` but
 * forgot to update `loadedChapterIds` in one branch, TextView prepended
 * without bumping `currentChapterIndex`, etc. The queue centralizes these
 * invariants.
 *
 * Generic on the item type [T] because the two viewers store different
 * payloads (TextView wraps the chapter with view bindings; WebView stores the
 * `ReaderChapter` directly). The caller supplies the id extractor via
 * [idOf]; items without an id are rejected.
 *
 * Not thread-safe. Both viewers mutate this from the main thread only.
 */
class ChapterQueue<T>(private val idOf: (T) -> Long?) {

    private val items = mutableListOf<T>()
    private val ids = mutableSetOf<Long>()

    /** Cursor pointing at the visible item. Read by scroll detection. */
    var currentIndex: Int = 0

    /**
     * Mutex flag for the infinite-scroll "load next chapter" trigger. Set
     * `true` while a fetch is in flight, reset to `false` in the `finally`
     * block of the fetch coroutine.
     */
    var isLoadingNext: Boolean = false

    val size: Int get() = items.size
    fun isEmpty(): Boolean = items.isEmpty()
    fun isNotEmpty(): Boolean = items.isNotEmpty()

    /** Read-only snapshot of the items in declaration order. */
    val all: List<T> get() = items

    /** Read-only snapshot of the loaded chapter ids. */
    val loadedIds: Set<Long> get() = ids

    fun contains(chapterId: Long): Boolean = chapterId in ids

    /**
     * Append [item] to the end of the queue. Returns `false` (no-op) when the
     * chapter is already loaded or carries no id.
     */
    fun append(item: T): Boolean {
        val id = idOf(item) ?: return false
        if (id in ids) return false
        items.add(item)
        ids.add(id)
        return true
    }

    /**
     * Prepend [item] to the head of the queue. Increments [currentIndex] so
     * the user's currently-visible chapter stays in place. Returns `false`
     * (no-op) when the chapter is already loaded or carries no id.
     */
    fun prepend(item: T): Boolean {
        val id = idOf(item) ?: return false
        if (id in ids) return false
        items.add(0, item)
        ids.add(id)
        currentIndex += 1
        return true
    }

    /** The item the cursor is pointing at, or `null` if the queue is empty. */
    fun current(): T? = items.getOrNull(currentIndex)
    fun getOrNull(index: Int): T? = items.getOrNull(index)
    fun firstOrNull(): T? = items.firstOrNull()
    fun lastOrNull(): T? = items.lastOrNull()

    /**
     * Index of the item whose id matches [chapterId], or -1 if not loaded.
     */
    fun indexOf(chapterId: Long): Int = items.indexOfFirst { idOf(it) == chapterId }

    /**
     * Drop the head item, shifting the cursor left when it no longer points
     * inside the queue. Used by both viewers for the "unload distant chapters"
     * memory clean-up.
     */
    fun removeFirst(): T? {
        if (items.isEmpty()) return null
        val removed = items.removeAt(0)
        idOf(removed)?.let { ids.remove(it) }
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        return removed
    }

    /** Drop the first [count] items. */
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

    /**
     * Wipe the queue and seed it with [item] (or leave empty if [item] has no
     * id). Resets the cursor to 0.
     */
    fun reset(item: T) {
        clear()
        append(item)
    }

    /** Wipe the queue entirely. */
    fun clear() {
        items.clear()
        ids.clear()
        currentIndex = 0
    }
}
