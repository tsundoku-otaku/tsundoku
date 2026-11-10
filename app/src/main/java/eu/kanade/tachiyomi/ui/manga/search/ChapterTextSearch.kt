package eu.kanade.tachiyomi.ui.manga.search

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

@Immutable
@Serializable
data class ChapterSearchOptions(
    val isRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
)

/**
 * A short excerpt around one match. [matchStart]/[matchEnd] index into [text].
 * [matchIndex] is which match of the chapter this is, counting from 0.
 * [position] is where the match sits in the chapter, from 0 to 1.
 */
@Immutable
data class ChapterSearchSnippet(
    val text: String,
    val matchStart: Int,
    val matchEnd: Int,
    val matchIndex: Int,
    val position: Float,
)

/**
 * One match the reader should open at: the search that found it plus where it was found, so the
 * reader can find the same match again in the text it renders.
 */
@Serializable
data class ChapterSearchTarget(
    val query: String,
    val options: ChapterSearchOptions,
    val matchIndex: Int,
    val matchCount: Int,
    val position: Float,
) {
    fun buildRegex(): Regex = ChapterTextSearch.buildRegex(query, options)

    fun encode(): String = Json.encodeToString(this)

    companion object {
        fun decode(encoded: String): ChapterSearchTarget? =
            runCatching { Json.decodeFromString<ChapterSearchTarget>(encoded) }.getOrNull()
    }
}

@Immutable
data class ChapterTextMatches(
    val count: Int,
    val snippets: List<ChapterSearchSnippet>,
)

/**
 * Linear text search over chapter content, used by the entry-level chapter search.
 */
object ChapterTextSearch {

    const val SNIPPET_CONTEXT = 60
    const val MAX_SNIPPETS_PER_CHAPTER = 3

    /** Excerpts shown for one chapter once its result is expanded. */
    const val MAX_EXPANDED_SNIPPETS = 500
    private const val MAX_SNIPPET_MATCH_LENGTH = 200
    private const val WORD_CHAR = "[\\p{L}\\p{N}_]"

    /**
     * Builds the regex for [query]. Plain queries are matched literally.
     *
     * @throws PatternSyntaxException if [ChapterSearchOptions.isRegex] is set and [query] is not a valid pattern.
     */
    fun buildRegex(query: String, options: ChapterSearchOptions): Regex {
        val pattern = if (options.isRegex) query else Pattern.quote(query)
        val bounded = if (options.wholeWord) "(?<!$WORD_CHAR)(?:$pattern)(?!$WORD_CHAR)" else pattern
        val flags = if (options.caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return Pattern.compile(bounded, flags).toRegex()
    }

    /**
     * Reduces chapter HTML (or plain text) to whitespace-normalized visible text.
     */
    fun toPlainText(content: String): String = Jsoup.parse(content).text()

    /**
     * Counts every non-empty match of [regex] in [text] and keeps excerpts for the first few.
     *
     * When [isActive] turns false the scan aborts with a [CancellationException], even in the
     * middle of a slow regex evaluation.
     */
    fun findMatches(
        text: String,
        regex: Regex,
        maxSnippets: Int = MAX_SNIPPETS_PER_CHAPTER,
        isActive: () -> Boolean = { true },
    ): ChapterTextMatches {
        if (text.isEmpty()) return ChapterTextMatches(0, emptyList())
        val input = CancellableCharSequence(text, isActive)
        var count = 0
        val snippets = mutableListOf<ChapterSearchSnippet>()
        for (match in regex.findAll(input)) {
            if (match.range.isEmpty()) continue
            if (snippets.size < maxSnippets) {
                snippets += snippetFor(text, match.range.first, match.range.last + 1, matchIndex = count)
            }
            count++
        }
        return ChapterTextMatches(count, snippets)
    }

    /**
     * Finds, in the text a reader renders, the match a search result pointed at.
     *
     * The reader text can differ from the searched text in whitespace (paragraph breaks) and,
     * with translations or replacements, in content. Whitespace runs are compared as one space.
     * When the reader text holds as many matches as the search found, the match with the same
     * index is returned; otherwise the match nearest to [position].
     *
     * @return the match's range in [text], or null when the text holds no match.
     */
    fun locateMatch(
        text: CharSequence,
        regex: Regex,
        matchIndex: Int,
        matchCount: Int,
        position: Float,
    ): IntRange? {
        val normalized = WhitespaceCollapsed(text)
        val matches = regex.findAll(normalized.text)
            .map { it.range }
            .filterNot { it.isEmpty() }
            .toList()
        if (matches.isEmpty()) return null
        val length = normalized.text.length.toFloat()
        val match = if (matches.size == matchCount) {
            matches[matchIndex.coerceIn(matches.indices)]
        } else {
            matches.minBy { abs(it.first / length - position) }
        }
        return normalized.originalIndex(match.first) until normalized.originalIndex(match.last) + 1
    }

    /** [text] with every whitespace run replaced by one space, mapped back to [text] offsets. */
    private class WhitespaceCollapsed(source: CharSequence) {
        private val offsets = IntArray(source.length)
        val text: String

        init {
            val sb = StringBuilder(source.length)
            var inWhitespace = false
            for (i in source.indices) {
                val c = source[i]
                if (c.isWhitespace()) {
                    if (inWhitespace) continue
                    inWhitespace = true
                    offsets[sb.length] = i
                    sb.append(' ')
                } else {
                    inWhitespace = false
                    offsets[sb.length] = i
                    sb.append(c)
                }
            }
            text = sb.toString()
        }

        fun originalIndex(index: Int): Int = offsets[index]
    }

    private fun snippetFor(text: String, start: Int, end: Int, matchIndex: Int): ChapterSearchSnippet {
        val shownEnd = minOf(end, start + MAX_SNIPPET_MATCH_LENGTH)
        val from = (start - SNIPPET_CONTEXT).coerceAtLeast(0)
        val to = (shownEnd + SNIPPET_CONTEXT).coerceAtMost(text.length)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < text.length) "…" else ""
        return ChapterSearchSnippet(
            text = prefix + text.substring(from, to) + suffix,
            matchStart = prefix.length + start - from,
            matchEnd = prefix.length + shownEnd - from,
            matchIndex = matchIndex,
            position = start.toFloat() / text.length,
        )
    }

    /**
     * Lets a cancelled search escape a long-running regex match: the regex engine reads the
     * input through [get], so checking [isActive] there makes catastrophic patterns cancellable.
     */
    private class CancellableCharSequence(
        private val delegate: CharSequence,
        private val isActive: () -> Boolean,
    ) : CharSequence {
        private var reads = 0

        override val length: Int get() = delegate.length

        override fun get(index: Int): Char {
            if ((++reads and 0xFFF) == 0 && !isActive()) throw CancellationException("Chapter search cancelled")
            return delegate[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            CancellableCharSequence(delegate.subSequence(startIndex, endIndex), isActive)

        override fun toString(): String = delegate.toString()
    }
}
