package eu.kanade.tachiyomi.util.chapter

import org.jsoup.parser.Parser

/**
 * Single-pass walk over chapter HTML or plain text that avoids building a DOM.
 * Comments, scripts, styles and the head are skipped, entities are decoded and soft hyphens dropped.
 */
object HtmlTextScanner {

    private val SKIPPED_ELEMENTS = setOf("script", "style", "title", "head")
    private val INLINE_ELEMENTS = setOf(
        "a", "abbr", "b", "bdi", "bdo", "big", "cite", "code", "data", "del", "dfn", "em", "font", "i", "img",
        "ins", "kbd", "label", "mark", "nobr", "q", "rb", "rp", "rt", "rtc", "ruby", "s", "samp", "small", "span",
        "strike", "strong", "sub", "sup", "time", "tt", "u", "var", "wbr",
    )
    private const val MAX_ENTITY_LENGTH = 32

    @PublishedApi
    internal const val SOFT_HYPHEN = '­'

    /** Calls [onChar] for each text character and [onBreak] where a block-level tag or comment splits the text. */
    inline fun scan(content: String, onChar: (Char) -> Unit, onBreak: () -> Unit) {
        val length = content.length
        val unclosed = HashSet<String>(2)
        var i = 0
        while (i < length) {
            val char = content[i]
            if (char == '<' && i + 1 < length && isTagStart(content[i + 1])) {
                val next = indexAfterTag(content, i, unclosed)
                if (next < 0) {
                    onChar(char)
                    i++
                    continue
                }
                if (isBlockBoundary(content, i)) onBreak()
                i = next
            } else if (char == '&') {
                val semi = entityEnd(content, i)
                val decoded = if (semi < 0) null else decodeEntity(content.substring(i, semi + 1))
                if (decoded != null) {
                    for (c in decoded) if (c != SOFT_HYPHEN) onChar(c)
                    i = semi + 1
                } else {
                    onChar(char)
                    i++
                }
            } else {
                if (char != SOFT_HYPHEN) onChar(char)
                i++
            }
        }
    }

    @PublishedApi
    internal fun isTagStart(char: Char) = char.isLetter() || char == '/' || char == '!' || char == '?'

    @PublishedApi
    internal fun isBlockBoundary(content: String, start: Int): Boolean {
        if (content.startsWith("<!--", start)) return true
        return tagName(content, start) !in INLINE_ELEMENTS
    }

    /**
     * Index just past the tag at [start], including the body of a skipped element, or -1 when the
     * '<' is plain text. An unclosed comment runs to the end; an unclosed skipped element (HTML5
     * lets </head> be omitted) only skips its own tag.
     */
    @PublishedApi
    internal fun indexAfterTag(content: String, start: Int, unclosed: MutableSet<String> = HashSet()): Int {
        val length = content.length
        if (content.startsWith("<!--", start)) {
            val end = content.indexOf("-->", start + 4)
            return if (end < 0) length else end + 3
        }
        val end = tagEnd(content, start)
        if (end < 0) return -1
        val closing = content[start + 1] == '/'
        val name = tagName(content, start)
        if (closing || name !in SKIPPED_ELEMENTS || content[end - 1] == '/') return end + 1
        // Once a closing tag is missing from one point on, it is missing from every later point too, so
        // repeated unclosed tags don't each rescan the rest of the chapter.
        val close = if (name in unclosed) -1 else closingTagIndex(content, name, end + 1)
        if (close < 0) unclosed += name
        if (close < 0) return end + 1
        val closeEnd = content.indexOf('>', close)
        return if (closeEnd < 0) length else closeEnd + 1
    }

    // "</head" must not match "</header".
    private fun closingTagIndex(content: String, name: String, from: Int): Int {
        if (name == "head") return headCloseIndex(content, from)
        var at = from
        while (true) {
            val close = content.indexOf("</$name", at, ignoreCase = true)
            if (close < 0) return -1
            if (isNameEnd(content, close + 2 + name.length)) return close
            at = close + 2
        }
    }

    // One forward walk, so each comment, script or style in the head is passed once and a "</head>"
    // written inside one of them doesn't end the head.
    private fun headCloseIndex(content: String, from: Int): Int {
        var at = from
        while (true) {
            val lt = content.indexOf('<', at)
            if (lt < 0) return -1
            when {
                content.startsWith("<!--", lt) -> {
                    val end = content.indexOf("-->", lt + 4)
                    if (end < 0) return -1
                    at = end + 3
                }
                content.startsWith("</head", lt, ignoreCase = true) && isNameEnd(content, lt + 6) -> return lt
                else -> {
                    val raw = HEAD_RAW_TEXT.firstOrNull {
                        content.startsWith("<$it", lt, ignoreCase = true) && isNameEnd(content, lt + 1 + it.length)
                    }
                    if (raw == null) {
                        at = lt + 1
                    } else {
                        // The close is looked for after the whole opening tag, whose attributes may quote "</script>".
                        val open = tagEnd(content, lt)
                        if (open < 0) return -1
                        val close = closingTagIndex(content, raw, open + 1)
                        if (close < 0) return -1
                        at = close + 2
                    }
                }
            }
        }
    }

    private fun isNameEnd(content: String, at: Int): Boolean {
        val after = content.getOrNull(at) ?: return true
        return !(after.isLetterOrDigit() || after == '-')
    }

    private val HEAD_RAW_TEXT = listOf("script", "style")

    @PublishedApi
    internal fun entityEnd(content: String, start: Int): Int {
        val limit = minOf(content.length - 1, start + MAX_ENTITY_LENGTH)
        for (j in start + 2..limit) {
            when (content[j]) {
                ';' -> return j
                '&', '<', ' ' -> return -1
            }
        }
        return -1
    }

    @PublishedApi
    internal fun decodeEntity(entity: String): String? =
        Parser.unescapeEntities(entity, false).takeIf { it != entity }

    // Quoted attribute values may contain '>'. A '<' before the closing '>' means the first one was
    // plain text; an unclosed quote falls back to the first '>'. Only a quote right after '=' opens a
    // value, so an apostrophe inside an unquoted one (title=don't) is plain text.
    private fun tagEnd(content: String, start: Int): Int {
        var quote = 0.toChar()
        var afterEquals = false
        for (i in start + 1 until content.length) {
            val char = content[i]
            when {
                quote != 0.toChar() -> if (char == quote) quote = 0.toChar()
                (char == '"' || char == '\'') && afterEquals -> quote = char
                char == '>' -> return i
                char == '<' -> return -1
            }
            if (!char.isWhitespace()) afterEquals = quote == 0.toChar() && char == '='
        }
        return content.indexOf('>', start)
    }

    private fun tagName(content: String, start: Int): String {
        var nameStart = start + 1
        if (content[nameStart] == '/') nameStart++
        var nameEnd = nameStart
        while (nameEnd < content.length && content[nameEnd].isLetterOrDigit()) nameEnd++
        return content.substring(nameStart, nameEnd).lowercase()
    }
}
