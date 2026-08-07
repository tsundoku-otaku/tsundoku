package mihon.core.archive

/** Shared HTML resource-URL discovery and rewriting for the embedder and local-novel resolver. */
object HtmlAssetRewriter {

    private val RESOURCE_TAG_REGEX = Regex(
        "<(?:img|source|video|audio|track|embed|object|image|link|script)\\b(?:\"[^\"]*\"|'[^']*'|[^>])*>",
        RegexOption.IGNORE_CASE,
    )
    private val URL_ATTR_REGEX = Regex(
        "(?<![\\w:-])(src|href|poster|data|srcset|xlink:href)(\\s*=\\s*)(?:([\"'])(.*?)\\3|([^\\s\"'>]+))",
        RegexOption.IGNORE_CASE,
    )
    private val CSS_URL_REGEX = Regex(
        "url\\(\\s*([\"']?)([^\"')]+)\\1\\s*\\)",
        RegexOption.IGNORE_CASE,
    )
    private val STYLE_BLOCK_REGEX = Regex(
        "(<style\\b[^>]*>)(.*?)(</style>)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val STYLE_ATTR_REGEX = Regex(
        "(style\\s*=\\s*)([\"'])(.*?)\\2",
        RegexOption.IGNORE_CASE,
    )

    private val IMAGE_TAG_REGEX = Regex(
        "<img\\b(?:\"[^\"]*\"|'[^']*'|[^>])*>",
        RegexOption.IGNORE_CASE,
    )
    private val IMAGE_ATTR_REGEX = Regex(
        "(?<![\\w:-])(src|srcset)(\\s*=\\s*)(?:([\"'])(.*?)\\3|([^\\s\"'>]+))",
        RegexOption.IGNORE_CASE,
    )
    private val BG_IMAGE_CSS_REGEX = Regex(
        "background-image\\s*:\\s*url\\s*\\(\\s*([\"']?)([^\"')]+)\\1\\s*\\)",
        RegexOption.IGNORE_CASE,
    )

    fun rewriteHtml(content: String, toScheme: (String) -> String?): String {
        val withTags = RESOURCE_TAG_REGEX.replace(content) { tagMatch ->
            URL_ATTR_REGEX.replace(tagMatch.value) { attr ->
                val name = attr.groupValues[1]
                val eq = attr.groupValues[2]
                val quote = attr.groupValues[3]
                val value = if (quote.isNotEmpty()) attr.groupValues[4] else attr.groupValues[5]
                val newValue = if (name.equals("srcset", ignoreCase = true)) {
                    rewriteSrcset(value, toScheme)
                } else {
                    toScheme(value) ?: value
                }
                "$name$eq$quote$newValue$quote"
            }
        }
        val withStyleBlocks = STYLE_BLOCK_REGEX.replace(withTags) { m ->
            "${m.groupValues[1]}${rewriteCssUrls(m.groupValues[2], toScheme)}${m.groupValues[3]}"
        }
        return STYLE_ATTR_REGEX.replace(withStyleBlocks) { m ->
            val eqAndQuote = m.groupValues[1]
            val quote = m.groupValues[2]
            "$eqAndQuote$quote${rewriteCssUrls(m.groupValues[3], toScheme)}$quote"
        }
    }

    fun rewriteCssUrls(css: String, toScheme: (String) -> String?): String {
        return CSS_URL_REGEX.replace(css) { m ->
            val quote = m.groupValues[1]
            val url = m.groupValues[2]
            "url($quote${toScheme(url) ?: url}$quote)"
        }
    }

    fun rewriteSrcset(srcset: String, toScheme: (String) -> String?): String {
        return srcset.split(',').joinToString(", ") { candidate ->
            val trimmed = candidate.trim()
            if (trimmed.isEmpty()) return@joinToString candidate
            val spaceIdx = trimmed.indexOf(' ')
            val url = if (spaceIdx >= 0) trimmed.substring(0, spaceIdx) else trimmed
            val descriptor = if (spaceIdx >= 0) trimmed.substring(spaceIdx) else ""
            "${toScheme(url) ?: url}$descriptor"
        }
    }

    fun extractUrls(content: String): Set<String> {
        val urls = mutableSetOf<String>()
        fun addIfFetchable(url: String) {
            if (url.isNotBlank() && !url.startsWith("data:", ignoreCase = true)) urls.add(url)
        }

        RESOURCE_TAG_REGEX.findAll(content).forEach { tagMatch ->
            URL_ATTR_REGEX.findAll(tagMatch.value).forEach { attr ->
                val name = attr.groupValues[1]
                val quote = attr.groupValues[3]
                val value = if (quote.isNotEmpty()) attr.groupValues[4] else attr.groupValues[5]
                if (name.equals("srcset", ignoreCase = true)) {
                    value.split(',').forEach { candidate ->
                        val trimmed = candidate.trim()
                        if (trimmed.isNotEmpty()) {
                            val spaceIdx = trimmed.indexOf(' ')
                            addIfFetchable(if (spaceIdx >= 0) trimmed.substring(0, spaceIdx) else trimmed)
                        }
                    }
                } else {
                    addIfFetchable(value)
                }
            }
        }

        STYLE_BLOCK_REGEX.findAll(content).forEach { m ->
            CSS_URL_REGEX.findAll(m.groupValues[2]).forEach { addIfFetchable(it.groupValues[2]) }
        }
        STYLE_ATTR_REGEX.findAll(content).forEach { m ->
            CSS_URL_REGEX.findAll(m.groupValues[3]).forEach { addIfFetchable(it.groupValues[2]) }
        }

        return urls
    }

    fun extractImageUrls(content: String): Set<String> {
        val urls = mutableSetOf<String>()
        fun addIfFetchable(url: String) {
            if (url.isNotBlank() && !url.startsWith("data:", ignoreCase = true)) urls.add(url)
        }

        IMAGE_TAG_REGEX.findAll(content).forEach { tagMatch ->
            IMAGE_ATTR_REGEX.findAll(tagMatch.value).forEach { attr ->
                val name = attr.groupValues[1]
                val quote = attr.groupValues[3]
                val value = if (quote.isNotEmpty()) attr.groupValues[4] else attr.groupValues[5]
                if (name.equals("srcset", ignoreCase = true)) {
                    value.split(',').forEach { candidate ->
                        val trimmed = candidate.trim()
                        if (trimmed.isNotEmpty()) {
                            val spaceIdx = trimmed.indexOf(' ')
                            addIfFetchable(if (spaceIdx >= 0) trimmed.substring(0, spaceIdx) else trimmed)
                        }
                    }
                } else {
                    addIfFetchable(value)
                }
            }
        }

        BG_IMAGE_CSS_REGEX.findAll(content).forEach { addIfFetchable(it.groupValues[2]) }

        return urls
    }

    fun rewriteImageUrls(content: String, toScheme: (String) -> String?): String {
        val withImgTags = IMAGE_TAG_REGEX.replace(content) { tagMatch ->
            IMAGE_ATTR_REGEX.replace(tagMatch.value) { attr ->
                val name = attr.groupValues[1]
                val eq = attr.groupValues[2]
                val quote = attr.groupValues[3]
                val value = if (quote.isNotEmpty()) attr.groupValues[4] else attr.groupValues[5]
                val newValue = if (name.equals("srcset", ignoreCase = true)) {
                    rewriteSrcset(value, toScheme)
                } else {
                    toScheme(value) ?: value
                }
                "$name$eq$quote$newValue$quote"
            }
        }
        return BG_IMAGE_CSS_REGEX.replace(withImgTags) { m ->
            val quote = m.groupValues[1]
            val url = m.groupValues[2]
            "background-image:url($quote${toScheme(url) ?: url}$quote)"
        }
    }
}
