package tachiyomi.source.local

import mihon.core.archive.HtmlAssetRewriter
import mihon.core.archive.NOVEL_IMAGE_SCHEME
import mihon.core.archive.isResolvableAssetRef
import mihon.core.archive.relativeAssetScheme

internal object NovelAssetRewriter {

    const val SCHEME = NOVEL_IMAGE_SCHEME

    private val MD_IMAGE_REGEX = Regex("""(!\[[^\]]*]\()([^)\s]+)""")

    fun rewrite(content: String, ext: String, toScheme: (String) -> String?): String {
        return when (ext.lowercase()) {
            "html", "htm", "xhtml" -> HtmlAssetRewriter.rewriteHtml(content, toScheme)
            "md", "markdown" ->
                HtmlAssetRewriter.rewriteHtml(rewriteMarkdownImages(content, toScheme), toScheme)
            else -> content
        }
    }

    private fun rewriteMarkdownImages(content: String, toScheme: (String) -> String?): String {
        return MD_IMAGE_REGEX.replace(content) { m ->
            "${m.groupValues[1]}${toScheme(m.groupValues[2]) ?: m.groupValues[2]}"
        }
    }

    // Root-absolute refs in a saved site point at the site root, which for a local novel is the
    // chapter's own base directory, so they resolve like relative refs once the leading slash is dropped.
    fun isResolvableRef(ref: String): Boolean = isResolvableAssetRef(ref)

    fun relativeScheme(ref: String): String? = relativeAssetScheme(ref)

    fun archiveScheme(baseDir: String, ref: String): String? {
        val v = ref.trim()
        if (!isResolvableRef(v)) return null
        val decoded = decodePath(v.substringBefore('?').substringBefore('#'))
        val effectiveBase = if (decoded.startsWith("/")) "" else baseDir
        val path = resolveArchivePath(effectiveBase, decoded) ?: return null
        if (path.isBlank()) return null
        return "$SCHEME${java.net.URLEncoder.encode(path, "UTF-8")}"
    }

    // Saved web pages write pre-encoded refs; decode before re-encoding so "%20" doesn't become "%2520".
    // URLDecoder is form-decoding and maps "+" to space, but "+" is a literal in a URL path, so
    // shield it as "%2B" first to keep filenames like "a+b.png" intact.
    private fun decodePath(path: String): String =
        runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)

    // Returns null when a ".." escapes the archive root, matching LocalNovelSource.resolveRelativeFile
    // (both refuse to resolve an out-of-bounds ref rather than silently clamping to a wrong file).
    fun resolveArchivePath(baseDir: String, ref: String): String? {
        val stack = ArrayDeque<String>()
        baseDir.split('/').filter { it.isNotEmpty() }.forEach { stack.addLast(it) }
        for (segment in ref.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return null else stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return stack.joinToString("/")
    }
}
