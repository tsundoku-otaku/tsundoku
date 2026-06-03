package eu.kanade.tachiyomi.ui.library

/**
 * A library search query parsed once per search rather than once per item. Holds the
 * resolved field, the comma-split negatable sub-terms, and any compiled regex so that
 * matching 200k items does not recompile the pattern or re-parse the query each time.
 */
class LibrarySearchSpec private constructor(
    val field: Field,
    val term: String,
    val termRegex: Regex?,
    val subTerms: List<SubTerm>,
    val useRegex: Boolean,
    val searchByUrl: Boolean,
) {
    enum class Field { ID, TITLE, AUTHOR, ARTIST, DESCRIPTION, TAG, SOURCE, URL, CHAPTER, DEFAULT }

    class SubTerm(val text: String, val negate: Boolean, val regex: Regex?)

    companion object {
        private val PREFIXES: List<Pair<String, Field>> = listOf(
            "id:" to Field.ID,
            "title:" to Field.TITLE,
            "author:" to Field.AUTHOR,
            "artist:" to Field.ARTIST,
            "description:" to Field.DESCRIPTION,
            "desc:" to Field.DESCRIPTION,
            "tag:" to Field.TAG,
            "genre:" to Field.TAG,
            "source:" to Field.SOURCE,
            "url:" to Field.URL,
            "chapter:" to Field.CHAPTER,
        )

        private fun compile(query: String, useRegex: Boolean): Regex? =
            if (useRegex && query.isNotEmpty()) {
                try {
                    Regex(query, RegexOption.IGNORE_CASE)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

        fun parse(query: String, useRegex: Boolean, searchByUrl: Boolean): LibrarySearchSpec {
            for ((prefix, field) in PREFIXES) {
                if (query.startsWith(prefix, ignoreCase = true)) {
                    val term = query.substring(prefix.length).trim()
                    return LibrarySearchSpec(field, term, compile(term, useRegex), emptyList(), useRegex, searchByUrl)
                }
            }
            val subTerms = query.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { raw ->
                    val negate = raw.startsWith("-")
                    val text = if (negate) raw.substring(1).trimStart() else raw
                    SubTerm(text, negate, compile(text, useRegex))
                }
            return LibrarySearchSpec(Field.DEFAULT, query, compile(query, useRegex), subTerms, useRegex, searchByUrl)
        }
    }
}
