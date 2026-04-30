# Multi-Book EPUB Parsing Issue Analysis

## Problem Summary

When parsing EPUBs containing multiple books/volumes in a single file (like your "Throne of Glass" volumes), the chapter list becomes distorted and confusing. Chapters from different books have identical names, making it impossible for readers to distinguish between them.

## Root Cause

The current `buildEpubChaptersFromToc()` function in [EpubChapterListBuilder.kt](source-local/src/androidMain/kotlin/tachiyomi/source/local/EpubChapterListBuilder.kt) has this behavior:

1. **Linear numbering without context**: It sequentially numbers all chapters (1, 2, 3, ...) without preserving section/book context
2. **No section prefix**: When `hasMultipleEpubFiles=false`, it doesn't add book/volume labels to chapter names
3. **Duplicate chapter titles**: Both Volume 1 and Volume 2 have "CAPITOLO 1", "CAPITOLO 2", etc., creating confusion

## Concrete Example from Your Files

### Volume 1 Structure (content.opf):
```
IL TRONO DI VETRO
  ├─ CAPITOLO 1  → p011_capitolo-01.xhtml
  ├─ CAPITOLO 2  → p012_capitolo-02.xhtml
  ├─ CAPITOLO 3  → p013_capitolo-03.xhtml
  └─ ... CAPITOLO 55 → p065_capitolo-55.xhtml

LA CORONA DI MEZZANOTTE
  ├─ PARTE PRIMA. La Campionessa del Re
  │  ├─ CAPITOLO 1  → p071_capitolo-56.xhtml  ← WRONG: labeled "1" but file is "56"!
  │  ├─ CAPITOLO 2  → p072_capitolo-57.xhtml  ← WRONG: labeled "2" but file is "57"!
  │  ├─ CAPITOLO 3  → p073_capitolo-58.xhtml  ← WRONG: labeled "3" but file is "58"!
  │  └─ CAPITOLO 29 → p099_capitolo-84.xhtml  ← File is "84" but TOC says "29"
```

### What Readers See (Current Bug):
```
Chapter 1:  "CAPITOLO 1"          [p011_capitolo-01.xhtml]
Chapter 2:  "CAPITOLO 2"          [p012_capitolo-02.xhtml]
...
Chapter 55: "CAPITOLO 55"         [p065_capitolo-55.xhtml]
Chapter 56: "CAPITOLO 1"          [p071_capitolo-56.xhtml] ← CONFUSING! Same name as Chapter 1!
Chapter 57: "CAPITOLO 2"          [p072_capitolo-57.xhtml] ← CONFUSING! Same name as Chapter 2!
Chapter 58: "CAPITOLO 3"          [p073_capitolo-58.xhtml] ← CONFUSING! Same name as Chapter 3!
...
```

### What Readers SHOULD See:
```
Chapter 1:  "IL TRONO DI VETRO - CAPITOLO 1"                    [p011_capitolo-01.xhtml]
Chapter 2:  "IL TRONO DI VETRO - CAPITOLO 2"                    [p012_capitolo-02.xhtml]
...
Chapter 55: "IL TRONO DI VETRO - CAPITOLO 55"                   [p065_capitolo-55.xhtml]
Chapter 56: "LA CORONA DI MEZZANOTTE - PARTE PRIMA - CAPITOLO 1" [p071_capitolo-56.xhtml]
Chapter 57: "LA CORONA DI MEZZANOTTE - PARTE PRIMA - CAPITOLO 2" [p072_capitolo-57.xhtml]
Chapter 58: "LA CORONA DI MEZZANOTTE - PARTE PRIMA - CAPITOLO 3" [p073_capitolo-58.xhtml]
...
```

## Why This Happens

The TOC hierarchy is being flattened without preserving context:

### From toc.xhtml (XML structure):
```xml
<li><a href="p009_half-title-01.xhtml">IL TRONO DI VETRO</a>
  <ol>
    <li><a href="p011_capitolo-01.xhtml">CAPITOLO 1</a></li>
    <li><a href="p012_capitolo-02.xhtml">CAPITOLO 2</a></li>
    <!-- ... 55 chapters ... -->
  </ol>
</li>

<li><a href="p068_half-title-02.xhtml">LA CORONA DI MEZZANOTTE</a>
  <ol>
    <li><a href="p070_parte-01.xhtml">PARTE PRIMA. La Campionessa del Re</a>
      <ol>
        <li><a href="p071_capitolo-56.xhtml">CAPITOLO 1</a></li>
        <li><a href="p072_capitolo-57.xhtml">CAPITOLO 2</a></li>
        <!-- ... 29 chapters ... -->
      </ol>
    </li>
    <!-- ... PARTE SECONDA, PARTE TERZA, etc. ... -->
  </ol>
</li>
```

The parser reads these as a flat list and loses the hierarchy information.

## Current Code Behavior

In `buildEpubChaptersFromToc()`:

```kotlin
fun addChapter(chapterHref: String, title: String?) {
    val href = chapterHref.trim()
    if (href.isBlank() || !emittedUrls.add(href)) return

    chapterNumber += 1
    val resolvedTitle = title?.trim().orEmpty().ifBlank { "Chapter $chapterNumber" }
    
    // ISSUE: When hasMultipleEpubFiles=false, no context is added!
    val chapterDisplayName = if (hasMultipleEpubFiles) {
        "${chapterFileNameWithoutExtension.orEmpty()} - $resolvedTitle"
    } else {
        resolvedTitle  // ← Just uses the title as-is!
    }
    
    chapters += SChapter.create().apply {
        url = "$mangaUrl/${chapterFileName.orEmpty()}#$href"
        name = chapterDisplayName
        chapter_number = chapterNumber.toFloat()
    }
}
```

## Solution Requirements

The parser should:

1. **Detect book-level structure** by identifying "half-title" pages or section headers
2. **Track hierarchical context** (book name, part name, chapter name)
3. **Build qualified chapter names** like: "Book - Part - Chapter"
4. **Test with multi-book EPUBs** to ensure chapters are clearly distinguished

## Files Affected

- **Core Parser**: `source-local/src/androidMain/kotlin/tachiyomi/source/local/EpubChapterListBuilder.kt`
- **Tests**: `source-local/src/androidUnitTest/kotlin/tachiyomi/source/local/EpubChapterListBuilderTest.kt`
- **EPUB Reader**: `core/archive/src/main/kotlin/mihon/core/archive/EpubReader.kt` (parses TOC structure)

## Test Coverage

Added test: `buildEpubChaptersFromToc handles multi-book EPUBs with repeating chapter titles()`
- Verifies chapter numbering
- Checks for proper context preservation in multi-book scenarios
- Currently **PASSES** (but only validates current buggy behavior)

## Next Steps

1. Modify `EpubChapterListBuilder.kt` to detect and preserve hierarchical context
2. Update chapter name generation to include parent section titles
3. Add logic to identify "structural" pages (half-titles, part dividers) vs content pages
4. Write comprehensive tests with real multi-book EPUB structures
