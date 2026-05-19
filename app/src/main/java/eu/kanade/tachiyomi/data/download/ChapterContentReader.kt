package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Shared utility for reading text content (HTML / TXT) from downloaded chapters.
 *
 * Both [eu.kanade.tachiyomi.data.translation.TranslationService] and
 * [eu.kanade.tachiyomi.data.epub.EpubExportJob] previously had their own
 * copy of this logic.  This class is the single source of truth.
 *
 * It supports:
 * - Plain directories containing `.html` / `.txt` files
 * - CBZ archives (read via libarchive's [ArchiveReader])
 * - Fallback CBZ lookup in the manga directory when the chapter directory
 *   itself is not found
 */
class ChapterContentReader(
    private val context: Context,
    private val downloadProvider: DownloadProvider,
) {

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Read the text content for [chapter] from its downloaded files.
     *
     * @return the concatenated content, or `null` if nothing was found.
     */
    fun readDownloadedContent(
        manga: Manga,
        chapter: Chapter,
        source: eu.kanade.tachiyomi.source.Source,
    ): String? {
        return try {
            readFromChapterDir(manga, chapter, source)
                ?: readFromMangaDirCbz(manga, chapter, source)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read downloaded chapter: ${chapter.name}" }
            null
        }
    }

    // ── Internals ───────────────────────────────────────────────────

    private fun readFromChapterDir(
        manga: Manga,
        chapter: Chapter,
        source: eu.kanade.tachiyomi.source.Source,
    ): String? {
        val chapterDirOrCbz = downloadProvider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            source,
        ) ?: return null

        val isCbz = chapterDirOrCbz.name?.let { it.endsWith(".cbz") || it.endsWith(".zip") } == true

        return if (isCbz) {
            readContentFromCbz(chapterDirOrCbz)
        } else {
            readContentFromDirectory(chapterDirOrCbz)
        }
    }

    /**
     * When `findChapterDir` returns null, fall back to scanning the manga directory
     * for a CBZ whose base name matches the chapter.
     */
    private fun readFromMangaDirCbz(
        manga: Manga,
        chapter: Chapter,
        source: eu.kanade.tachiyomi.source.Source,
    ): String? {
        val mangaDir = downloadProvider.findMangaDir(manga.title, source) ?: return null
        val cbzFiles = mangaDir.listFiles()?.filter {
            it.isFile && (it.name?.endsWith(".cbz") == true || it.name?.endsWith(".zip") == true)
        } ?: return null

        val validNames = downloadProvider.getValidChapterDirNames(
            chapter.name,
            chapter.scanlator,
            chapter.url,
        )

        val matchingCbz = cbzFiles.find { cbz ->
            val base = cbz.name?.substringBeforeLast(".") ?: ""
            validNames.any { it == base }
        } ?: return null

        return readContentFromCbz(matchingCbz)
    }

    // ── File-type readers ───────────────────────────────────────────

    /**
     * Read `.html` / `.txt` files from a plain directory, sorted by name.
     */
    private fun readContentFromDirectory(dir: UniFile): String? {
        val allFiles = dir.listFiles() ?: return null
        val htmlFiles = allFiles.filter {
            it.isFile && it.name?.endsWith(".html") == true
        }.sortedBy { it.name }

        val txtFiles = allFiles.filter {
            it.isFile && it.name?.endsWith(".txt") == true
        }.sortedBy { it.name }

        val files = htmlFiles.ifEmpty { txtFiles }
        if (files.isEmpty()) return null

        val sb = StringBuilder()
        files.forEachIndexed { i, file ->
            val text = context.contentResolver.openInputStream(file.uri)?.use {
                it.bufferedReader().readText()
            } ?: ""
            sb.append(text)
            if (i < files.size - 1) sb.append("\n\n")
        }
        val content = sb.toString()
        return content.ifBlank { null }
    }

    /**
     * Read `.html` / `.htm` / `.xhtml` / `.txt` entries from a CBZ archive.
     *
     * Uses libarchive's [ArchiveReader] for compatibility with archives
     * created by the download system's ZipWriter.
     */
    fun readContentFromCbz(cbzFile: UniFile): String? {
        val uri = cbzFile.uri
        logcat(LogPriority.DEBUG) { "CBZ: reading from $uri" }
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use { descriptor ->
                ArchiveReader(descriptor).use { reader ->
                    // First pass: collect content file names
                    val contentFileNames = mutableListOf<String>()
                    reader.useEntries { seq ->
                        seq.forEach { entry ->
                            val name = entry.name.lowercase()
                            if (entry.isFile && (
                                    name.endsWith(".html") ||
                                        name.endsWith(".htm") ||
                                        name.endsWith(".xhtml") ||
                                        name.endsWith(".txt")
                                    )
                            ) {
                                contentFileNames.add(entry.name)
                            }
                        }
                    }

                    // Second pass: read each file
                    val entries = mutableListOf<Pair<String, String>>()
                    contentFileNames.forEach { fileName ->
                        try {
                            reader.getInputStream(fileName)?.use { stream ->
                                entries.add(fileName to stream.bufferedReader().readText())
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "CBZ: failed to read entry $fileName" }
                        }
                    }

                    // Sort by name and concatenate
                    entries.sortedBy { it.first }
                        .joinToString("\n\n") { it.second }
                        .ifEmpty { null }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "CBZ: failed to read archive $uri" }
            null
        }
    }
}
