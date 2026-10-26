package eu.kanade.tachiyomi.ui.manga.search

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.util.chapter.HtmlTextScanner
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.coroutines.cancellation.CancellationException

@Immutable
data class ChapterSearchOptions(
    val isRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
)

/**
 * A short excerpt around one match. [matchStart]/[matchEnd] index into [text].
 * [position] is where the match sits in the chapter, from 0 to 1.
 */
@Immutable
data class ChapterSearchSnippet(
    val text: String,
    val matchStart: Int,
    val matchEnd: Int,
    val position: Float,
)

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

    const val COLLAPSED_SNIPPETS = 3
    const val MAX_SNIPPETS_PER_CHAPTER = 10
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
    fun toPlainText(content: String): String {
        val out = StringBuilder(content.length)
        var pendingSpace = false
        HtmlTextScanner.scan(
            content,
            onChar = { char ->
                if (char.isWhitespace() || Character.isSpaceChar(char)) {
                    pendingSpace = out.isNotEmpty()
                } else {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.append(char)
                }
            },
            onBreak = { pendingSpace = out.isNotEmpty() },
        )
        return out.toString()
    }

    /** Plain query equivalent of [findMatches] without the regex engine. */
    fun findLiteralMatches(
        text: String,
        query: String,
        ignoreCase: Boolean,
        maxSnippets: Int = MAX_SNIPPETS_PER_CHAPTER,
    ): ChapterTextMatches {
        if (text.isEmpty() || query.isEmpty()) return ChapterTextMatches(0, emptyList())
        var count = 0
        val snippets = mutableListOf<ChapterSearchSnippet>()
        var from = text.indexOf(query, 0, ignoreCase)
        while (from >= 0) {
            count++
            if (snippets.size < maxSnippets) snippets += snippetFor(text, from, from + query.length)
            from = text.indexOf(query, from + query.length, ignoreCase)
        }
        return ChapterTextMatches(count, snippets)
    }

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
            count++
            if (snippets.size < maxSnippets) {
                snippets += snippetFor(text, match.range.first, match.range.last + 1)
            }
        }
        return ChapterTextMatches(count, snippets)
    }

    private fun snippetFor(text: String, start: Int, end: Int): ChapterSearchSnippet {
        val shownEnd = minOf(end, start + MAX_SNIPPET_MATCH_LENGTH)
        val from = (start - SNIPPET_CONTEXT).coerceAtLeast(0)
        val to = (shownEnd + SNIPPET_CONTEXT).coerceAtMost(text.length)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < text.length) "…" else ""
        return ChapterSearchSnippet(
            text = prefix + text.substring(from, to) + suffix,
            matchStart = prefix.length + start - from,
            matchEnd = prefix.length + shownEnd - from,
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
