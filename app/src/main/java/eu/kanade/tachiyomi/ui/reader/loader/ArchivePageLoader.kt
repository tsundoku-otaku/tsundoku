package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.archive.ArchiveReader
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Loader used to load a chapter from an archive file.
 */
internal class ArchivePageLoader(private val reader: ArchiveReader) : PageLoader() {
    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> = reader.useEntries { entries ->
        entries
            .filter { entry ->
                entry.isFile && (
                    entry.name.isHtmlContentFileName() ||
                        ImageUtil.isImage(entry.name) { reader.getInputStream(entry.name)!! }
                    )
            }
            .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
            .mapIndexed { i, entry ->
                val isHtml = entry.name.isHtmlContentFileName()
                val textContent = if (isHtml) {
                    reader.getInputStream(entry.name)?.use { it.bufferedReader().readText() }
                } else {
                    null
                }

                ReaderPage(i).apply {
                    text = textContent
                    stream = { reader.getInputStream(entry.name)!! }
                    status = Page.State.Ready
                }
            }
            .toList()
    }

    override suspend fun getPageDataStream(url: String): java.io.InputStream? {
        return reader.getInputStream(url)?.readBytes()?.let { java.io.ByteArrayInputStream(it) }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        reader.close()
    }

    private fun String.isHtmlContentFileName(): Boolean {
        val normalized = lowercase()
        return normalized.endsWith(".html") || normalized.endsWith(".htm") || normalized.endsWith(".xhtml")
    }
}
