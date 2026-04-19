package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.TextSplitter
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.injectLazy

/**
 * Loader used to load a chapter from the downloaded chapters.
 */
internal class DownloadPageLoader(
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: Source,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
) : PageLoader() {

    private val context: Application by injectLazy()
    private val readerPreferences: ReaderPreferences by injectLazy()

    private var archivePageLoader: ArchivePageLoader? = null

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val dbChapter = chapter.chapter
        logcat { "DownloadPageLoader.getPages: chapter=${dbChapter.name}, url=${dbChapter.url}" }

        val chapterPath = downloadProvider.findChapterDir(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            source,
        )
        logcat {
            "DownloadPageLoader.getPages: chapterPath=$chapterPath, exists=${chapterPath?.exists()}, isFile=${chapterPath?.isFile}"
        }

        return if (chapterPath?.isFile == true) {
            logcat { "DownloadPageLoader.getPages: Loading from archive" }
            getPagesFromArchive(chapterPath)
        } else {
            logcat { "DownloadPageLoader.getPages: Loading from directory" }
            getPagesFromDirectory()
        }
    }

    override fun recycle() {
        super.recycle()
        archivePageLoader?.recycle()
    }

    private suspend fun getPagesFromArchive(file: UniFile): List<ReaderPage> {
        val loader = ArchivePageLoader(file.archiveReader(context)).also { archivePageLoader = it }
        return loader.getPages()
    }

    private fun getPagesFromDirectory(): List<ReaderPage> {
        logcat { "DownloadPageLoader.getPagesFromDirectory: Starting" }
        val pages = downloadManager.buildPageList(source, manga, chapter.chapter.toDomainChapter()!!)
        logcat { "DownloadPageLoader.getPagesFromDirectory: Got ${pages.size} pages from buildPageList" }

        return pages.map { page ->
            val uriString = page.uri?.toString() ?: ""
            logcat { "DownloadPageLoader: Processing page ${page.index}, uri=$uriString" }

            var textContent = if (uriString.isHtmlContentPath()) {
                logcat { "DownloadPageLoader: Reading HTML content from $uriString" }
                context.contentResolver.openInputStream(page.uri!!)?.use {
                    it.bufferedReader().readText()
                }
            } else {
                null
            }
            // Apply auto-split if enabled
            if (textContent != null && readerPreferences.novelAutoSplitText.get()) {
                val wordCount = readerPreferences.novelAutoSplitWordCount.get().coerceAtLeast(20)
                if (wordCount > 0) {
                    textContent = TextSplitter.splitText(textContent, wordCount)
                }
            }

            logcat { "DownloadPageLoader: Page ${page.index} has ${textContent?.length ?: 0} chars of text" }

            ReaderPage(page.index, page.url, page.imageUrl, textContent) {
                context.contentResolver.openInputStream(page.uri ?: Uri.EMPTY)!!
            }.apply {
                status = Page.State.Ready
            }
        }
    }

    override suspend fun getPageDataStream(url: String): java.io.InputStream? {
        // 1. Try archive (for normal downloaded CBZ)
        archivePageLoader?.getPageDataStream(url)?.let { return it }

        // 2. Try directory (for normal downloaded directories)
        val dbChapter = chapter.chapter
        val chapterPath = downloadProvider.findChapterDir(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            source,
        )
        if (chapterPath?.isDirectory == true) {
            val file = chapterPath.findFile(url)
            if (file != null && file.exists()) {
                context.contentResolver.openInputStream(file.uri)?.let { return it }
            }
        }

        // 3. Fallback to source (critical for edited EPUB chapters where the custom `.cbz` only contains 001.html)
        return (source as? tachiyomi.source.local.LocalNovelSource)?.getChapterImage(dbChapter, url)
    }

    override suspend fun loadPage(page: ReaderPage) {
        archivePageLoader?.loadPage(page)
    }

    private fun String.isHtmlContentPath(): Boolean {
        val normalized = lowercase()
        return normalized.endsWith(".html") || normalized.endsWith(".htm") || normalized.endsWith(".xhtml")
    }
}
