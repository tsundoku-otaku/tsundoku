package tachiyomi.source.local

/**
 * Finds the entry of the local novel base folder that a stored novel url refers to.
 *
 * A url is the exact name of a child of the base folder: either a novel folder or a single loose file
 * (e.g. one `.epub`). Urls that are not a plain child name (empty, `.`/`..`, or containing a path separator)
 * never resolve, so callers can never reach outside the base folder.
 */
fun <T> resolveLocalNovelEntry(
    url: String,
    entries: List<T>,
    nameOf: (T) -> String?,
    isDirectory: (T) -> Boolean,
): T? {
    if (url.isEmpty() || url == "." || url == ".." || url.any { it == '/' || it == '\\' }) return null

    return entries.firstOrNull { nameOf(it) == url }
        ?: entries.firstOrNull {
            !isDirectory(it) && nameOf(it)?.substringBeforeLast('.').orEmpty().equals(url, ignoreCase = true)
        }
}

class LocalNovelDeleteResult<T>(val deleted: List<T>, val failed: List<T>)

/**
 * Deletes every item with [delete]. An item whose delete returns false or throws is reported in
 * [LocalNovelDeleteResult.failed] and the remaining items are still processed.
 */
inline fun <T> deleteLocalNovelEntries(items: Collection<T>, delete: (T) -> Boolean): LocalNovelDeleteResult<T> {
    val deleted = mutableListOf<T>()
    val failed = mutableListOf<T>()
    items.forEach { item ->
        val success = try {
            delete(item)
        } catch (_: Exception) {
            false
        }
        if (success) deleted.add(item) else failed.add(item)
    }
    return LocalNovelDeleteResult(deleted, failed)
}
