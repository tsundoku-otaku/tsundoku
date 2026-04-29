package tachiyomi.source.local

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

class EpubChapterValidationTest {
    
    data class TocEntry(
        val title: String,
        val href: String,
    )
    
    private fun resolveZipPath(basePath: String, relativePath: String): String {
        if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) {
            return relativePath
        }

        val normalizedBase = basePath.trim('/').trim('\\')
        val normalizedRelative = relativePath.trim()
        if (normalizedRelative.isBlank()) return normalizedRelative
        if (normalizedRelative.startsWith("/") || normalizedRelative.startsWith("\\")) {
            return normalizedRelative.trimStart('/', '\\')
        }

        val parts = buildList {
            if (normalizedBase.isNotEmpty()) addAll(normalizedBase.split('/'))
            addAll(normalizedRelative.split('/'))
        }

        val resolved = mutableListOf<String>()
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
                else -> resolved.add(part)
            }
        }

        return resolved.joinToString("/")
    }

    private fun getRootFilePath(zipFile: ZipFile): String? {
        val containerEntry = zipFile.getEntry("META-INF/container.xml") ?: return null
        return zipFile.getInputStream(containerEntry).use { stream ->
            val doc = Jsoup.parse(stream, "UTF-8", "", Parser.xmlParser())
            doc.selectFirst("rootfile")?.attr("full-path")?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun parseTocEntriesFromZip(zipFile: ZipFile): List<TocEntry> {
        val rootfilePath = getRootFilePath(zipFile) ?: return emptyList()
        val rootDir = rootfilePath.substringBeforeLast('/', "")
        val opfEntry = zipFile.getEntry(rootfilePath) ?: return emptyList()
        val opfDoc = zipFile.getInputStream(opfEntry).use { stream ->
            Jsoup.parse(stream, "UTF-8", "", Parser.xmlParser())
        }

        val navHref = opfDoc.select("manifest > item[properties*=nav]").attr("href").trim()
        if (navHref.isNotEmpty()) {
            val navPath = resolveZipPath(rootDir, navHref)
            val navEntry = zipFile.getEntry(navPath)
            if (navEntry != null) {
                val navEntries = zipFile.getInputStream(navEntry).use { stream ->
                    val doc = Jsoup.parse(stream, "UTF-8", "")
                    doc.select("nav[epub:type=toc] li > a").mapNotNull { link ->
                        val title = link.text().trim()
                        val href = link.attr("href").trim()
                        if (title.isNotEmpty() && href.isNotEmpty()) {
                            TocEntry(title, resolveZipPath(rootDir, href))
                        } else {
                            null
                        }
                    }
                }
                if (navEntries.isNotEmpty()) return navEntries
            }
        }

        val ncxHref = opfDoc.select("manifest > item[media-type='application/x-dtbncx+xml']").attr("href").trim()
        if (ncxHref.isNotEmpty()) {
            val ncxPath = resolveZipPath(rootDir, ncxHref)
            val ncxEntry = zipFile.getEntry(ncxPath)
            if (ncxEntry != null) {
                val ncxEntries = zipFile.getInputStream(ncxEntry).use { stream ->
                    val doc = Jsoup.parse(stream, null, "", Parser.xmlParser())
                    doc.select("navPoint").mapNotNull { navPoint ->
                        val title = navPoint.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
                        val href = navPoint.selectFirst("content")?.attr("src")?.trim().orEmpty()
                        if (title.isNotEmpty() && href.isNotEmpty()) {
                            TocEntry(title, resolveZipPath(rootDir, href))
                        } else {
                            null
                        }
                    }
                }
                if (ncxEntries.isNotEmpty()) return ncxEntries
            }
        }

        return emptyList()
    }
    
    @Test
    fun validateEpubChapterExtraction() {
        val output = StringBuilder()
        
        output.append("\n" + "=".repeat(80) + "\n")
        output.append("EPUB CHAPTER EXTRACTION VALIDATION - REAL EPUB FILES\n")
        output.append("=".repeat(80) + "\n\n")

        val epubFiles = listOf(
            "Il trono di vetro (Vol. 1).epub",
            "Il Trono di Vetro (Vol. 2).epub"
        )

        epubFiles.forEach { epubName ->
            val epubPath = "C:\\Users\\oussama\\StudioProjects\\tsundoku\\$epubName"
            val file = File(epubPath)
            
            if (!file.exists()) {
                output.append("❌ File not found: $epubPath\n")
                return@forEach
            }

            output.append("\n" + "-".repeat(80) + "\n")
            output.append("📖 PROCESSING: $epubName (${file.length() / 1024 / 1024}MB)\n")
            output.append("-".repeat(80) + "\n")

            try {
                val zipFile = ZipFile(file)
                
                // Parse TOC the same way the real EPUB reader does:
                // container.xml -> content.opf -> nav/ncx paths.
                val rootfilePath = getRootFilePath(zipFile)
                output.append("\n📄 ROOTFILE PATH: ${rootfilePath ?: "NONE"}\n")
                val tocChapters = parseTocEntriesFromZip(zipFile)

                output.append("\n📋 RAW TOC ENTRIES (${tocChapters.size} total):\n")
                tocChapters.forEachIndexed { i, entry ->
                    val marker = when {
                        entry.title.uppercase().contains("RINGRAZIAMENTI") -> "🚨"
                        entry.title.uppercase().contains("COPYRIGHT") -> "⚠️"
                        entry.title.uppercase().contains("FRONTESPIZIO") -> "⚠️"
                        entry.title.uppercase().contains("COPERTINA") -> "⚠️"
                        entry.title.uppercase().contains("IL LIBRO") -> "⚠️"
                        entry.title.uppercase().contains("INDICE") -> "⚠️"
                        entry.title.uppercase().contains("COLOPHON") -> "⚠️"
                        else -> "  "
                    }
                    output.append("  $marker [$i] '${entry.title}' -> ${entry.href}\n")
                }

                // Convert to EpubChapter format for buildEpubChaptersFromToc
                val epubChapters = tocChapters.mapIndexed { idx, entry ->
                    mihon.core.archive.EpubReader.EpubChapter(
                        title = entry.title,
                        href = entry.href,
                        order = idx
                    )
                }

                output.append("\n🔍 CALLING buildEpubChaptersFromToc()...\n")
                val chapters = buildEpubChaptersFromToc(
                    mangaUrl = "file://$epubPath",
                    chapterFileName = epubName,
                    chapterFileNameWithoutExtension = epubName.substringBeforeLast("."),
                    chapterLastModified = System.currentTimeMillis(),
                    tocChapters = epubChapters,
                    spinePageHrefs = emptyList(),
                    hasMultipleEpubFiles = false
                )

                output.append("\n✅ EXTRACTED CHAPTERS (${chapters.size} final):\n")
                chapters.forEachIndexed { i, chapter ->
                    val truncated = if (chapter.name.length > 70) {
                        chapter.name.take(67) + "..."
                    } else {
                        chapter.name
                    }
                    output.append("  [${String.format("%2d", i + 1)}] $truncated\n")
                }

                // Analysis
                output.append("\n📊 ANALYSIS:\n")
                output.append("  • TOC entries: ${tocChapters.size}\n")
                output.append("  • Extracted chapters: ${chapters.size}\n")
                output.append("  • Filtered/Skipped: ${tocChapters.size - chapters.size}\n")

                // Check for problematic entries
                val nonContentPages = tocChapters.filter { 
                    it.title.uppercase().let { upper ->
                        upper.contains("RINGRAZIAMENTI") ||
                        upper.contains("COPYRIGHT") ||
                        upper.contains("FRONTESPIZIO") ||
                        upper.contains("COPERTINA") ||
                        upper.contains("IL LIBRO") ||
                        upper.contains("INDICE") ||
                        upper.contains("COLOPHON") ||
                        upper.contains("TRANSLATOR") ||
                        upper.contains("TRADUTTORE")
                    }
                }

                if (nonContentPages.isNotEmpty()) {
                    output.append("\n⚠️  NON-CONTENT PAGES IN TOC (${nonContentPages.size}):\n")
                    nonContentPages.forEach { entry ->
                        val inChapters = chapters.any { it.name.contains(entry.title) }
                        val status = if (inChapters) "🚨 LEAKED" else "✅ FILTERED"
                        output.append("  $status '${entry.title}'\n")
                    }
                } else {
                    output.append("\n✅ No non-content pages in TOC\n")
                }

                // Final check
                val lastChapter = chapters.lastOrNull()
                output.append("\n🏁 LAST CHAPTER: '${lastChapter?.name ?: "NONE"}'\n")
                
                if (lastChapter?.name?.uppercase()?.contains("RINGRAZIAMENTI") == true) {
                    output.append("🚨 ERROR: Last chapter is RINGRAZIAMENTI (should be filtered!)\n")
                } else {
                    output.append("✅ Last chapter is content (not frontmatter/backmatter)\n")
                }

                zipFile.close()

            } catch (e: Exception) {
                output.append("\n❌ Error processing EPUB: ${e.message}\n")
                e.printStackTrace(System.out)
            }
        }

        output.append("\n" + "=".repeat(80) + "\n")
        output.append("VALIDATION COMPLETE\n")
        output.append("=".repeat(80) + "\n\n")
        
        // Write to file
        val outputFile = File("C:\\Users\\oussama\\StudioProjects\\tsundoku\\epub_validation_output.txt")
        outputFile.writeText(output.toString())
    }
}
