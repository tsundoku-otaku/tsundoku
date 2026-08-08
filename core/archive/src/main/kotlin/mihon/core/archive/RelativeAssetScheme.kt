package mihon.core.archive

import java.net.URLDecoder

private val ABSOLUTE_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:|^//")

/** True when [ref] is a same-origin-relative reference resolvable to a local file. */
fun isResolvableAssetRef(ref: String): Boolean {
    val v = ref.trim()
    if (v.isEmpty()) return false
    if (v.startsWith("#") || v.startsWith("//")) return false
    return !ABSOLUTE_SCHEME_REGEX.containsMatchIn(v)
}

fun relativeAssetPath(ref: String): String? {
    val v = ref.trim()
    if (!isResolvableAssetRef(v)) return null
    val decoded = decodeAssetPath(v.substringBefore('?').substringBefore('#'))
        .removePrefix("./").removePrefix("/")
    return decoded.ifBlank { null }
}

private fun decodeAssetPath(path: String): String =
    runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)

/** Maps a flat/relative asset reference to a [NOVEL_IMAGE_SCHEME] URI, or null if already absolute. */
fun relativeAssetScheme(ref: String): String? = relativeAssetPath(ref)?.let(::novelImageUrl)

/** Rewrites resolvable asset refs to [NOVEL_IMAGE_SCHEME] URIs, only when [fileExists] confirms the file. */
fun rewriteResolvedAssetRefs(text: String, fileExists: (String) -> Boolean): String =
    HtmlAssetRewriter.rewriteHtml(text) { ref -> relativeAssetPath(ref)?.takeIf(fileExists)?.let(::novelImageUrl) }
