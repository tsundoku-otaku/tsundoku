package eu.kanade.tachiyomi.util.lang

import androidx.core.text.parseAsHtml
import net.greypanther.natsort.CaseInsensitiveSimpleNaturalComparator
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.nio.charset.StandardCharsets
import kotlin.math.floor

/**
 * Replaces the given string to have at most [count] characters using [replacement] at its end.
 * If [replacement] is longer than [count] an exception will be thrown when `length > count`.
 */
fun String.chop(count: Int, replacement: String = "…"): String {
    return if (length > count) {
        take(count - replacement.length) + replacement
    } else {
        this
    }
}

/**
 * Replaces the given string to have at most [count] characters using [replacement] near the center.
 * If [replacement] is longer than [count] an exception will be thrown when `length > count`.
 */
fun String.truncateCenter(count: Int, replacement: String = "..."): String {
    if (length <= count) {
        return this
    }

    val pieceLength: Int = floor((count - replacement.length).div(2.0)).toInt()

    return "${take(pieceLength)}$replacement${takeLast(pieceLength)}"
}

/**
 * Case-insensitive natural comparator for strings.
 */
fun String.compareToCaseInsensitiveNaturalOrder(other: String): Int {
    val comparator = CaseInsensitiveSimpleNaturalComparator.getInstance<String>()
    return comparator.compare(this, other)
}

/**
 * Returns the size of the string as the number of bytes.
 */
fun String.byteSize(): Int {
    return toByteArray(StandardCharsets.UTF_8).size
}

/**
 * Returns a string containing the first [n] bytes from this string, or the entire string if this
 * string is shorter.
 */
fun String.takeBytes(n: Int): String {
    val bytes = toByteArray(StandardCharsets.UTF_8)
    return if (bytes.size <= n) {
        this
    } else {
        bytes.decodeToString(endIndex = n).replace("\uFFFD", "")
    }
}

/**
 * HTML-decode the string
 */
fun String.htmlDecode(): String {
    return this.parseAsHtml().toString()
}

/**
 * Normalize HTML-ish description content into clean plain text.
 *
 * Intended for metadata fields (EPUB/XML/JS plugin summaries) that may contain tags,
 * entities, and inconsistent whitespace.
 */
fun normalizeHtmlDescription(rawDescription: String?): String? {
    if (rawDescription.isNullOrBlank()) return null

    val doc = Jsoup.parse(rawDescription)
    doc.outputSettings().prettyPrint(false)

    // Preserve explicit <br> breaks as single newlines.
    doc.select("br").forEach { it.replaceWith(TextNode("\n")) }

    // Treat block boundaries as paragraph separators.
    doc.select("p, div, li, blockquote, h1, h2, h3, h4, h5, h6").forEach { it.appendText("\n\n") }

    val normalized = doc.body().wholeText()
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .lines()
        .map { it.trim() }
        .joinToString("\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    return normalized.ifBlank { null }
}
