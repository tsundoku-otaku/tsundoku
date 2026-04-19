package eu.kanade.tachiyomi.util

/**
 * Utility for automatically splitting text after a certain number of words,
 * continuing until a sentence-ending punctuation mark is found.
 */
object TextSplitter {

    private val sentenceEndingPunctuation = setOf('.', '!', '?', '。', '！', '？', '…')

    /**
     * Splits text by inserting paragraph breaks after approximately [wordCount] words,
     * but always continuing until a sentence-ending punctuation mark is found.
     *
     * @param text The input text (can be HTML or plain text)
     * @param wordCount Target number of words before looking for punctuation
     * @return Text with additional paragraph breaks inserted
     */
    fun splitText(text: String, wordCount: Int): String {
        if (wordCount <= 0) return text
        val effectiveWordCount = wordCount.coerceAtLeast(20)

        // Check if this is HTML content
        val isHtml = Regex("<\\s*(p|div|br|body)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

        return if (isHtml) {
            splitHtmlText(text, effectiveWordCount)
        } else {
            splitPlainText(text, effectiveWordCount)
        }
    }

    private fun splitPlainText(text: String, targetWordCount: Int): String {
        val result = StringBuilder()
        val words = text.split(Regex("\\s+"))
        var wordsSincePunctuation = 0

        for (i in words.indices) {
            val word = words[i]
            if (word.isEmpty()) continue

            result.append(word)
            wordsSincePunctuation++

            val endsWithPunctuation = word.lastOrNull()?.let { it in sentenceEndingPunctuation } == true

            if (endsWithPunctuation) {
                // Check if we've passed the threshold since last punctuation
                if (wordsSincePunctuation >= targetWordCount) {
                    // Insert paragraph break after this punctuation
                    result.append("<br><br>")
                    wordsSincePunctuation = 0
                } else {
                    // Just add a space, continue counting
                    result.append(" ")
                }
            } else {
                // No punctuation yet, just add space
                result.append(" ")
            }
        }

        return result.toString().trim()
    }

    private fun splitHtmlText(html: String, targetWordCount: Int): String {
        // For HTML, we process the text content within tags
        // We'll extract text segments, split them, and rebuild
        val result = StringBuilder()
        var wordsSincePunctuation = 0

        // Process the HTML by finding text segments
        var i = 0
        while (i < html.length) {
            if (html[i] == '<') {
                // Found a tag - copy it as-is
                val tagEnd = html.indexOf('>', i)
                if (tagEnd == -1) {
                    result.append(html.substring(i))
                    break
                }
                val tag = html.substring(i, tagEnd + 1)
                result.append(tag)

                // Check if it's a paragraph or break tag - reset counter
                if (tag.lowercase().startsWith("<p>") ||
                    tag.lowercase().startsWith("<br") ||
                    tag.lowercase().startsWith("</p>") ||
                    tag.lowercase().startsWith("<div") ||
                    tag.lowercase().startsWith("</div") ||
                    tag.lowercase().startsWith("<body") ||
                    tag.lowercase().startsWith("</body")
                ) {
                    wordsSincePunctuation = 0
                }
                i = tagEnd + 1
            } else {
                // Text content - process word by word
                val nextTag = html.indexOf('<', i)
                val textEnd = if (nextTag == -1) html.length else nextTag
                val text = html.substring(i, textEnd)

                // Walk character-by-character to preserve original whitespace exactly
                var ti = 0
                while (ti < text.length) {
                    // Collect and emit whitespace as-is
                    val wsStart = ti
                    while (ti < text.length && text[ti].isWhitespace()) ti++
                    if (ti > wsStart) result.append(text, wsStart, ti)
                    if (ti >= text.length) break

                    // Collect a word (non-whitespace run)
                    val wordStart = ti
                    while (ti < text.length && !text[ti].isWhitespace()) ti++
                    val word = text.substring(wordStart, ti)

                    result.append(word)
                    wordsSincePunctuation++

                    val endsWithPunctuation = word.lastOrNull()?.let { it in sentenceEndingPunctuation } == true
                    if (endsWithPunctuation && wordsSincePunctuation >= targetWordCount) {
                        // Use line breaks instead of forcing paragraph tags.
                        // This keeps splitting valid for <div>-based chapters and body-level plain HTML.
                        result.append("<br><br>")
                        wordsSincePunctuation = 0
                    }
                }
                i = textEnd
            }
        }

        return result.toString()
    }
}
