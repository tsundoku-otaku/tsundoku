package tachiyomi.source.local

import eu.kanade.tachiyomi.source.model.SChapter
import mihon.core.archive.EpubReader

internal fun buildEpubChaptersFromToc(
    mangaUrl: String,
    chapterFileName: String?,
    chapterFileNameWithoutExtension: String?,
    chapterLastModified: Long,
    tocChapters: List<EpubReader.EpubChapter>,
    spinePageHrefs: List<String> = emptyList(),
    hasMultipleEpubFiles: Boolean,
): List<SChapter> {
    if (tocChapters.isEmpty() && spinePageHrefs.isEmpty()) return emptyList()

    val emittedUrls = linkedSetOf<String>()
    var chapterNumber = 0
    val chapters = mutableListOf<SChapter>()
    val contextStack = mutableListOf<String>() // Track hierarchy: [Book, Part, Section]

    fun addChapter(chapterHref: String, title: String?, isStructuralHeader: Boolean = false) {
        val href = chapterHref.trim()
        if (href.isBlank() || !emittedUrls.add(href)) return

        if (isStructuralHeader) {
            // Don't number structural headers, just use as context
            return
        }

        chapterNumber += 1
        val resolvedTitle = title?.trim().orEmpty().ifBlank { "Chapter $chapterNumber" }
        
        // Build hierarchical name by prepending context stack (book/part names)
        val contextPrefix = if (contextStack.isNotEmpty()) {
            contextStack.joinToString(" - ")
        } else {
            ""
        }
        
        val chapterDisplayName = buildString {
            if (hasMultipleEpubFiles) {
                append(chapterFileNameWithoutExtension.orEmpty())
                append(" - ")
            }
            if (contextPrefix.isNotEmpty()) {
                append(contextPrefix)
                append(" - ")
            }
            append(resolvedTitle)
        }

        chapters += SChapter.create().apply {
            url = "$mangaUrl/${chapterFileName.orEmpty()}#$href"
            name = chapterDisplayName
            date_upload = chapterLastModified
            chapter_number = chapterNumber.toFloat()
        }
    }

    if (spinePageHrefs.isEmpty()) {
        // Build context by tracking structural pages (half-titles, part headers, etc.)
        tocChapters.forEach { tocEntry ->
            // Skip non-content pages entirely
            if (isNonContentPage(tocEntry.title)) {
                return@forEach
            }
            
            if (isStructuralHeader(tocEntry.title)) {
                if (isBookLevelHeader(tocEntry.title)) {
                    // Book-level headers: add to context only, don't add as chapter
                    contextStack.clear()
                    contextStack.add(tocEntry.title.trim())
                } else if (isContextualHeader(tocEntry.title)) {
                    // Part-level with description (e.g., "PARTE PRIMA. ..."):
                    // add to context only, don't add as chapter
                    contextStack.add(tocEntry.title.trim())
                } else {
                    // Simple part headers (e.g., "PART ONE"): add as chapter only, not to context
                    addChapter(tocEntry.href, tocEntry.title)
                }
            } else {
                addChapter(tocEntry.href, tocEntry.title)
            }
        }
        return chapters
    }

    val tocByPath = linkedMapOf<String, MutableList<EpubReader.EpubChapter>>()
    tocChapters.forEach { tocEntry ->
        val href = tocEntry.href.trim()
        if (href.isBlank()) return@forEach
        val path = href.substringBefore('#').trim()
        if (path.isBlank()) return@forEach
        tocByPath.getOrPut(path) { mutableListOf() }.add(tocEntry)
    }

    spinePageHrefs.forEach { spineHref ->
        val spinePath = spineHref.substringBefore('#').trim()
        if (spinePath.isBlank()) return@forEach

        val pathTocEntries = tocByPath.remove(spinePath).orEmpty()
        if (pathTocEntries.isNotEmpty()) {
            pathTocEntries.forEach { tocEntry ->
                // Skip non-content pages entirely
                if (isNonContentPage(tocEntry.title)) {
                    return@forEach
                }
                
                if (isStructuralHeader(tocEntry.title)) {
                    if (isBookLevelHeader(tocEntry.title)) {
                        // Book-level headers: add to context only, don't add as chapter
                        contextStack.clear()
                        contextStack.add(tocEntry.title.trim())
                    } else if (isContextualHeader(tocEntry.title)) {
                        // Part-level with description: add to context only, don't add as chapter
                        contextStack.add(tocEntry.title.trim())
                    } else {
                        // Simple part headers: add as chapter only, not to context
                        addChapter(tocEntry.href, tocEntry.title)
                    }
                } else {
                    addChapter(tocEntry.href, tocEntry.title)
                }
            }
            return@forEach
        }

        if (isLikelyNavigationDocument(spinePath)) return@forEach

        val fallbackTitle = spinePath.substringAfterLast('/')
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()

        addChapter(spinePath, fallbackTitle)
    }

    // Keep any TOC items that are not listed in OPF spine (rare but valid in malformed EPUBs).
    tocByPath.values.flatten().forEach { tocEntry ->
        // Skip non-content pages entirely
        if (isNonContentPage(tocEntry.title)) {
            return@forEach
        }
        
        if (isStructuralHeader(tocEntry.title)) {
            if (isBookLevelHeader(tocEntry.title)) {
                contextStack.clear()
                contextStack.add(tocEntry.title.trim())
            } else if (isContextualHeader(tocEntry.title)) {
                contextStack.add(tocEntry.title.trim())
            } else {
                addChapter(tocEntry.href, tocEntry.title)
            }
        } else {
            addChapter(tocEntry.href, tocEntry.title)
        }
    }

    return chapters
}

/**
 * Detects if a page should be skipped entirely (frontmatter, backmatter, etc).
 * These are not chapters and should not be added to the chapter list.
 */
private fun isNonContentPage(title: String): Boolean {
    if (title.isBlank()) return false
    val trimmed = title.trim().uppercase()
    
    return when {
        trimmed.contains("FRONTESPIZIO") -> true  // Frontispiece
        trimmed.contains("COPERTINA") -> true      // Cover
        trimmed.contains("COPYRIGHT") -> true      // Copyright
        trimmed.contains("RINGRAZIAMENTI") -> true // Acknowledgments
        trimmed.contains("TRANSLATOR") -> true     // Translator notes
        trimmed.contains("NOTE") && trimmed.contains("TRADUTTORE|TRANSLATOR") -> true
        trimmed.contains("IL LIBRO") -> true       // Book info pages
        trimmed.contains("INDICE") -> true         // Table of contents
        trimmed.contains("COLOPHON") -> true       // Colophon
        else -> false
    }
}

/**
 * Detects if a title is a structural header (section/part/book header) vs a content chapter.
 * Structural headers are book titles, part titles, etc. that group chapters but don't have content.
 * They're identified by patterns like:
 * - All uppercase: "IL TRONO DI VETRO", "LA CORONA DI MEZZANOTTE"
 * - Part/chapter headers: "PARTE PRIMA", "LIBRO I", "VOLUME 2"
 * - Single word titles in ALL CAPS: "INTRODUCTION", "PROLOGUE" (but not "CHAPTER 1")
 */
private fun isStructuralHeader(title: String): Boolean {
    if (title.isBlank()) return false
    val trimmed = title.trim()
    
    // Skip non-content pages first
    if (isNonContentPage(trimmed)) return false
    
    // Check if it's all uppercase (strong signal of section header)
    if (trimmed != trimmed.lowercase() && trimmed == trimmed.uppercase()) {
        // But exclude patterns like "CHAPTER 1", "CAPITOLO 1" (chapter numbers)
        val isChapterLike = Regex(
            "^(CHAPTER|CAPITOLO|CHAPITRE|CAPIT|CH|C)\\.?\\s*\\d+",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(trimmed)
        if (!isChapterLike) {
            return true // Likely a book/section title
        }
    }
    
    // Check for explicit part/section keywords
    val isPartHeader = Regex(
        "^(PARTE|PART|BOOK|TOME|VOLUME|LIVRO|BUCH|LIBRO)\\s+(PRIMA|PRIMA\\s|SECOND|TERZA|I\\b|II\\b|III\\b|1|2|3|ONE|TWO|THREE)",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(trimmed)
    if (isPartHeader) return true
    
    return false
}

/**
 * Detects if a structural header is a book-level header (clears context) vs a sub-section header.
 * Book-level: "IL TRONO DI VETRO", "LA CORONA DI MEZZANOTTE" (all-caps non-part titles)
 * Sub-section: "PARTE PRIMA", "VOLUME 2", "CHAPTER 1" (part/volume/chapter headers)
 */
private fun isBookLevelHeader(title: String): Boolean {
    if (title.isBlank()) return false
    val trimmed = title.trim()
    
    // Check if it's all uppercase AND not a part/volume/chapter header
    if (trimmed != trimmed.lowercase() && trimmed == trimmed.uppercase()) {
        val isPartOrVolumeHeader = Regex(
            "^(PARTE|PART|BOOK|TOME|VOLUME|LIVRO|BUCH|LIBRO|CHAPTER|CAPITOLO|CHAPITRE)\\s+",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(trimmed)
        if (!isPartOrVolumeHeader) {
            return true // It's an all-caps book title
        }
    }
    
    return false
}

/**
 * Detects if a part-level header should be added to context or just treated as a chapter.
 * Context headers: "PARTE PRIMA. La Campionessa del Re" (has descriptive content after period)
 * Standalone titles: "INTRODUZIONE", "PROLOGO" (no part numbers)
 * Content headers: "PART ONE", "PART TWO" (simple level markers without descriptions)
 */
private fun isContextualHeader(title: String): Boolean {
    if (title.isBlank()) return false
    val trimmed = title.trim()
    
    // Standalone section titles
    if (Regex("^(INTRODUCTION|INTRODUZIONE|PROLOGO|PROLOGUE|PREFAZIONE|PREFACE)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(trimmed)
    ) {
        return true
    }
    
    // Part/Volume headers with descriptive text after a period or additional content
    // "PARTE PRIMA. La Campionessa del Re" → matches (has ". La Campionessa del Re")
    // "PARTE PRIMA" → does NOT match (no descriptive content)
    // "PART ONE" → does NOT match
    val hasDescription = Regex(
        "^(PARTE|PART|VOLUME|LIBRO|BOOK|TOME)\\s+\\w+\\.\\s+\\w+",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(trimmed)
    
    return hasDescription
}

private fun isLikelyNavigationDocument(path: String): Boolean {
    val lower = path.lowercase()
    return lower.endsWith("/nav.xhtml") ||
        lower.endsWith("/nav.html") ||
        lower.endsWith("/toc.xhtml") ||
        lower.endsWith("/toc.html") ||
        lower == "nav.xhtml" ||
        lower == "nav.html" ||
        lower == "toc.xhtml" ||
        lower == "toc.html"
}
