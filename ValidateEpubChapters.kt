import mihon.core.archive.EpubReader
import mihon.core.archive.ArchiveReader
import tachiyomi.source.local.buildEpubChaptersFromToc
import java.io.File

fun main() {
    println("\n" + "=".repeat(80))
    println("EPUB CHAPTER EXTRACTION VALIDATION")
    println("=".repeat(80) + "\n")

    val epubFiles = listOf(
        "Il trono di vetro (Vol. 1).epub",
        "Il Trono di Vetro (Vol. 2).epub"
    )

    epubFiles.forEach { epubName ->
        val epubPath = "C:\\Users\\oussama\\StudioProjects\\tsundoku\\$epubName"
        val file = File(epubPath)
        
        if (!file.exists()) {
            println("❌ File not found: $epubPath\n")
            return@forEach
        }

        println("\n" + "-".repeat(80))
        println("TESTING: $epubName")
        println("-".repeat(80))

        try {
            val reader = ArchiveReader(file)
            val epubReader = EpubReader(reader)

            // Get TOC
            val tocChapters = epubReader.getTableOfContents()
            val spinePageHrefs = epubReader.getSpinePageHrefs()

            println("\n📋 RAW TOC ENTRIES (${tocChapters.size} total):")
            tocChapters.forEachIndexed { i, chapter ->
                println("  [$i] Title: '${chapter.title}' | Href: '${chapter.href}' | Order: ${chapter.order}")
            }

            println("\n🔗 SPINE PAGES (${spinePageHrefs.size} total):")
            spinePageHrefs.take(10).forEachIndexed { i, href ->
                println("  [$i] $href")
            }
            if (spinePageHrefs.size > 10) {
                println("  ... and ${spinePageHrefs.size - 10} more")
            }

            // Test with buildEpubChaptersFromToc
            println("\n🔍 CALLING buildEpubChaptersFromToc():")
            val chapters = buildEpubChaptersFromToc(
                mangaUrl = "file://$epubPath",
                chapterFileName = epubName,
                chapterFileNameWithoutExtension = epubName.substringBeforeLast("."),
                chapterLastModified = System.currentTimeMillis(),
                tocChapters = tocChapters,
                spinePageHrefs = spinePageHrefs,
                hasMultipleEpubFiles = false
            )

            println("\n✅ EXTRACTED CHAPTERS (${chapters.size} total):")
            chapters.forEachIndexed { i, chapter ->
                println("  [${String.format("%2d", i + 1)}] '${chapter.name}'")
                println("        URL: ${chapter.url}")
            }

            // Analysis
            println("\n📊 ANALYSIS:")
            println("  - TOC entries: ${tocChapters.size}")
            println("  - Extracted chapters: ${chapters.size}")
            println("  - Filtered out: ${tocChapters.size - chapters.size}")

            val nonContentPages = tocChapters.filter { 
                it.title.uppercase().let { upper ->
                    upper.contains("RINGRAZIAMENTI") ||
                    upper.contains("COPYRIGHT") ||
                    upper.contains("FRONTESPIZIO") ||
                    upper.contains("COPERTINA") ||
                    upper.contains("IL LIBRO") ||
                    upper.contains("INDICE") ||
                    upper.contains("COLOPHON")
                }
            }

            if (nonContentPages.isNotEmpty()) {
                println("\n⚠️  NON-CONTENT PAGES DETECTED:")
                nonContentPages.forEach { toc ->
                    println("  - ${toc.title}")
                }
            } else {
                println("\n✅ No non-content pages detected (good!)")
            }

            val chapterNames = chapters.map { it.name }
            if (chapterNames.any { it.uppercase().contains("RINGRAZIAMENTI") }) {
                println("\n🚨 ERROR: RINGRAZIAMENTI found in chapter list!")
            } else {
                println("\n✅ RINGRAZIAMENTI correctly filtered")
            }

            epubReader.close()

        } catch (e: Exception) {
            println("\n❌ Error processing EPUB: ${e.message}")
            e.printStackTrace()
        }
    }

    println("\n" + "=".repeat(80))
    println("VALIDATION COMPLETE")
    println("=".repeat(80) + "\n")
}
