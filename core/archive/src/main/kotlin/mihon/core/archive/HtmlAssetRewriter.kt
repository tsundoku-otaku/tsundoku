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
    private val DATA_SRC_ATTR_REGEX = Regex(
        "(?<![\\w:-])data-src(\\s*=\\s*)(?:([\"'])(.*?)\\2|([^\\s\"'>]+))",
        RegexOption.IGNORE_CASE,
    )
    private val BG_IMAGE_CSS_REGEX = Regex(
        "background-image\\s*:\\s*url\\s*\\(\\s*([\"']?)([^\"')]+)\\1\\s*\\)",
        RegexOption.IGNORE_CASE,
    )

    private val PICTURE_BLOCK_REGEX = Regex(
        "(<picture\\b[^>]*>)(.*?)(</picture>)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val PICTURE_SOURCE_TAG_REGEX = Regex(
        "<source\\b(?:\"[^\"]*\"|'[^']*'|[^>])*>",
        RegexOption.IGNORE_CASE,
    )

    private fun MatchResult.attrValue(quoteGroup: Int): String {
        val quotedGroup = quoteGroup + 1
        val bareGroup = quoteGroup + 2
        return if (groupValues[quoteGroup].isNotEmpty()) groupValues[quotedGroup] else groupValues[bareGroup]
    }

    private fun lazyLoadSrc(tag: String): String? =
        DATA_SRC_ATTR_REGEX.find(tag)?.attrValue(quoteGroup = 2)?.takeIf { it.isNotBlank() }

    fun rewriteHtml(content: String, toScheme: (String) -> String?): String {
        val withTags = RESOURCE_TAG_REGEX.replace(content) { tagMatch ->
            URL_ATTR_REGEX.replace(tagMatch.value) { attr ->
                val name = attr.groupValues[1]
                val eq = attr.groupValues[2]
                val quote = attr.groupValues[3]
                val value = attr.attrValue(quoteGroup = 3)
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

    fun extractImageUrls(content: String): Set<String> {
        val urls = mutableSetOf<String>()
        fun addIfFetchable(url: String) {
            if (url.isNotBlank() && !url.startsWith("data:", ignoreCase = true)) urls.add(url)
        }
        fun collectFromTag(tag: String) {
            val lazySrc = lazyLoadSrc(tag)
            IMAGE_ATTR_REGEX.findAll(tag).forEach { attr ->
                val name = attr.groupValues[1]
                val value = attr.attrValue(quoteGroup = 3)
                if (name.equals("srcset", ignoreCase = true)) {
                    value.split(',').forEach { candidate ->
                        val trimmed = candidate.trim()
                        if (trimmed.isNotEmpty()) {
                            val spaceIdx = trimmed.indexOf(' ')
                            addIfFetchable(if (spaceIdx >= 0) trimmed.substring(0, spaceIdx) else trimmed)
                        }
                    }
                } else if (lazySrc == null) {
                    addIfFetchable(value)
                }
            }
            if (lazySrc != null) addIfFetchable(lazySrc)
        }

        IMAGE_TAG_REGEX.findAll(content).forEach { collectFromTag(it.value) }
        PICTURE_BLOCK_REGEX.findAll(content).forEach { block ->
            PICTURE_SOURCE_TAG_REGEX.findAll(block.groupValues[2]).forEach { collectFromTag(it.value) }
        }
        BG_IMAGE_CSS_REGEX.findAll(content).forEach { addIfFetchable(it.groupValues[2]) }

        return urls
    }

    fun rewriteImageUrls(content: String, toScheme: (String) -> String?): String {
        fun rewriteTag(tag: String): String {
            val lazySrc = lazyLoadSrc(tag)
            return IMAGE_ATTR_REGEX.replace(tag) { attr ->
                val name = attr.groupValues[1]
                val eq = attr.groupValues[2]
                val quote = attr.groupValues[3]
                val value = attr.attrValue(quoteGroup = 3)
                val newValue = when {
                    name.equals("srcset", ignoreCase = true) -> rewriteSrcset(value, toScheme)
                    lazySrc != null -> toScheme(lazySrc) ?: value
                    else -> toScheme(value) ?: value
                }
                "$name$eq$quote$newValue$quote"
            }
        }

        val withImgTags = IMAGE_TAG_REGEX.replace(content) { rewriteTag(it.value) }
        val withSourceTags = PICTURE_BLOCK_REGEX.replace(withImgTags) { block ->
            val rewrittenInner = PICTURE_SOURCE_TAG_REGEX.replace(block.groupValues[2]) { rewriteTag(it.value) }
            "${block.groupValues[1]}$rewrittenInner${block.groupValues[3]}"
        }
        return BG_IMAGE_CSS_REGEX.replace(withSourceTags) { m ->
            val quote = m.groupValues[1]
            val url = m.groupValues[2]
            "background-image:url($quote${toScheme(url) ?: url}$quote)"
        }
    }
}
