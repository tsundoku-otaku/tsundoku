package tachiyomi.source.local

internal object NovelAssetRewriter {

    const val SCHEME = "tsundoku-novel-image://"

    private val RESOURCE_TAG_REGEX = Regex(
        "<(?:img|source|video|audio|track|embed|object|image|link|script)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val URL_ATTR_REGEX = Regex(
        "(?<![\\w:-])(src|href|poster|data|srcset|xlink:href)(\\s*=\\s*)([\"'])(.*?)\\3",
        RegexOption.IGNORE_CASE,
    )
    private val CSS_URL_REGEX = Regex(
        "url\\(\\s*([\"']?)([^\"')]+)\\1\\s*\\)",
        RegexOption.IGNORE_CASE,
    )
    private val MD_IMAGE_REGEX = Regex("""(!\[[^\]]*]\()([^)\s]+)""")
    private val ABSOLUTE_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:|^//")

    fun rewrite(content: String, ext: String, toScheme: (String) -> String?): String {
        return when (ext.lowercase()) {
            "html", "htm", "xhtml" -> rewriteHtml(content, toScheme)
            "md", "markdown" -> rewriteHtml(rewriteMarkdownImages(content, toScheme), toScheme)
            else -> content
        }
    }

    private fun rewriteHtml(content: String, toScheme: (String) -> String?): String {
        val withTags = RESOURCE_TAG_REGEX.replace(content) { tagMatch ->
            URL_ATTR_REGEX.replace(tagMatch.value) { attr ->
                val name = attr.groupValues[1]
                val eq = attr.groupValues[2]
                val quote = attr.groupValues[3]
                val value = attr.groupValues[4]
                val newValue = if (name.equals("srcset", ignoreCase = true)) {
                    rewriteSrcset(value, toScheme)
                } else {
                    toScheme(value) ?: value
                }
                "$name$eq$quote$newValue$quote"
            }
        }
        return CSS_URL_REGEX.replace(withTags) { m ->
            val quote = m.groupValues[1]
            val url = m.groupValues[2]
            "url($quote${toScheme(url) ?: url}$quote)"
        }
    }

    private fun rewriteMarkdownImages(content: String, toScheme: (String) -> String?): String {
        return MD_IMAGE_REGEX.replace(content) { m ->
            "${m.groupValues[1]}${toScheme(m.groupValues[2]) ?: m.groupValues[2]}"
        }
    }

    private fun rewriteSrcset(srcset: String, toScheme: (String) -> String?): String {
        return srcset.split(',').joinToString(", ") { candidate ->
            val trimmed = candidate.trim()
            if (trimmed.isEmpty()) return@joinToString candidate
            val spaceIdx = trimmed.indexOf(' ')
            val url = if (spaceIdx >= 0) trimmed.substring(0, spaceIdx) else trimmed
            val descriptor = if (spaceIdx >= 0) trimmed.substring(spaceIdx) else ""
            "${toScheme(url) ?: url}$descriptor"
        }
    }

    fun isRelativeRef(ref: String): Boolean {
        val v = ref.trim()
        if (v.isEmpty()) return false
        if (v.startsWith("#") || v.startsWith("/")) return false
        return !ABSOLUTE_SCHEME_REGEX.containsMatchIn(v)
    }

    // Resolvable = document-relative (images/x.png, ../x.png) OR root-absolute (/forks/x.png).
    // Root-absolute refs in a saved site point at the site root, which for a local novel is the
    // chapter's own base directory, so they resolve the same way once the leading slash is dropped.
    // Protocol-relative (//cdn), absolute schemes, and pure fragments stay untouched.
    fun isResolvableRef(ref: String): Boolean {
        val v = ref.trim()
        if (v.isEmpty()) return false
        if (v.startsWith("#") || v.startsWith("//")) return false
        return !ABSOLUTE_SCHEME_REGEX.containsMatchIn(v)
    }

    fun relativeScheme(ref: String): String? {
        val v = ref.trim()
        if (!isResolvableRef(v)) return null
        val path = decodePath(v.substringBefore('#')).removePrefix("./").removePrefix("/")
        if (path.isBlank()) return null
        return "$SCHEME${java.net.URLEncoder.encode(path, "UTF-8")}"
    }

    fun archiveScheme(baseDir: String, ref: String): String? {
        val v = ref.trim()
        if (!isResolvableRef(v)) return null
        val decoded = decodePath(v.substringBefore('#'))
        // A root-absolute ref resolves from the archive root, not the entry's directory.
        val effectiveBase = if (decoded.startsWith("/")) "" else baseDir
        val path = resolveArchivePath(effectiveBase, decoded)
        if (path.isBlank()) return null
        return "$SCHEME${java.net.URLEncoder.encode(path, "UTF-8")}"
    }

    // Saved web pages write pre-encoded refs; decode before re-encoding so "%20" doesn't become "%2520".
    private fun decodePath(path: String): String =
        runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)

    fun resolveArchivePath(baseDir: String, ref: String): String {
        val stack = ArrayDeque<String>()
        baseDir.split('/').filter { it.isNotEmpty() }.forEach { stack.addLast(it) }
        ref.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return stack.joinToString("/")
    }
}
