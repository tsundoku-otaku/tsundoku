package mihon.core.archive

import java.net.URLDecoder
import java.net.URLEncoder

private val ABSOLUTE_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:|^//")

/** True when [ref] is a same-origin-relative reference resolvable to a local file. */
fun isResolvableAssetRef(ref: String): Boolean {
    val v = ref.trim()
    if (v.isEmpty()) return false
    if (v.startsWith("#") || v.startsWith("//")) return false
    return !ABSOLUTE_SCHEME_REGEX.containsMatchIn(v)
}

/** Maps a flat/relative asset reference to a [NOVEL_IMAGE_SCHEME] URI, or null if already absolute. */
fun relativeAssetScheme(ref: String): String? {
    val v = ref.trim()
    if (!isResolvableAssetRef(v)) return null
    val decoded = decodeAssetPath(v.substringBefore('?').substringBefore('#'))
        .removePrefix("./").removePrefix("/")
    if (decoded.isBlank()) return null
    return NOVEL_IMAGE_SCHEME + URLEncoder.encode(decoded, "UTF-8")
}

private fun decodeAssetPath(path: String): String =
    runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
