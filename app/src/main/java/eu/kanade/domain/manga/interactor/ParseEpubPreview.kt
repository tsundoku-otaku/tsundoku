package eu.kanade.domain.manga.interactor

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import mihon.core.archive.EpubReader
import mihon.core.archive.epubReader
import org.jsoup.nodes.Element
import tachiyomi.source.local.metadata.fillMetadata
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ParseEpubPreview {

    data class PreviewFile(
        val uri: Uri,
        val fileName: String,
        val title: String,
        val author: String?,
        val description: String?,
        val coverUri: Uri?,
        val collection: String?,
        val collectionPosition: Int?,
        val genres: String?,
        val tableOfContents: List<String>,
    )

    private data class CollectionMetadata(
        val collection: String?,
        val position: Int?,
    )

    data class TitleCandidate(
        val fileName: String,
        val title: String,
        val collection: String?,
    )

    data class Result(
        val files: List<PreviewFile>,
        val errors: List<String>,
    )

    suspend fun parseSelected(context: Context, uris: List<Uri>): Result {
        val errors = mutableListOf<String>()
        val files = uris.mapNotNull { uri ->
            runCatching {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@mapNotNull null
                val fileName = getFileNameFromUri(context, uri) ?: "unknown.epub"

                if (!fileName.endsWith(".epub", ignoreCase = true)) {
                    errors += "Skipped non-EPUB file: $fileName"
                    inputStream.close()
                    return@mapNotNull null
                }

                val tempFile = File.createTempFile("epub_import_", ".epub", context.cacheDir)
                tempFile.outputStream().use { out -> inputStream.copyTo(out) }
                inputStream.close()

                val uniFile = UniFile.fromFile(tempFile)
                val epubReader = uniFile!!.epubReader(context)

                val manga = SManga.create()
                val chapter = SChapter.create()
                epubReader.fillMetadata(manga, chapter)

                val metadataTitle = manga.title.takeIf { it.isNotBlank() }
                val title = metadataTitle
                    ?: chapter.name.takeIf { it.isNotBlank() }
                    ?: fileName.removeSuffix(".epub")

                val collectionMetadata = extractCollectionMetadata(epubReader)

                val coverUri = extractCoverUri(context, epubReader, manga)
                val tableOfContents = runCatching {
                    epubReader.getNormalizedTableOfContents()
                        .map { it.title.trim() }
                        .filter { it.isNotEmpty() }
                }.getOrDefault(emptyList())

                epubReader.close()
                tempFile.delete()

                PreviewFile(
                    uri = uri,
                    fileName = fileName,
                    title = title,
                    author = manga.author,
                    description = manga.description,
                    coverUri = coverUri,
                    collection = collectionMetadata.collection ?: metadataTitle,
                    collectionPosition = collectionMetadata.position,
                    genres = manga.genre,
                    tableOfContents = tableOfContents,
                )
            }.onFailure {
                errors += "Failed to parse EPUB: ${it.message}"
            }.getOrNull()
        }

        return Result(files = files, errors = errors)
    }

    fun defaultCustomTitle(files: List<PreviewFile>): String {
        return defaultCustomTitleFromCandidates(
            files.map {
                TitleCandidate(
                    fileName = it.fileName,
                    title = it.title,
                    collection = it.collection,
                )
            },
        )
    }

    private fun extractCollectionMetadata(epubReader: EpubReader): CollectionMetadata {
        return runCatching {
            val packageHref = epubReader.getPackageHref()
            val packageDoc = epubReader.getPackageDocument(packageHref)
            val metadata = packageDoc.selectFirst("metadata") ?: return CollectionMetadata(null, null)

            val collectionMeta = metadata.selectFirst("meta[property=belongs-to-collection], meta[name=calibre:series]")

            val collection = collectionMeta
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: collectionMeta
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

            val refinedPosition = collectionMeta
                ?.attr("id")
                ?.takeIf { it.isNotBlank() }
                ?.let { collectionId ->
                    metadata.selectFirst(
                        "meta[refines='#$collectionId'][property=group-position], " +
                            "meta[refines='#$collectionId'][name=calibre:series_index]",
                    )
                }

            val fallbackPosition = metadata.selectFirst("meta[property=group-position], meta[name=calibre:series_index]")
            val position = parseCollectionPosition(refinedPosition) ?: parseCollectionPosition(fallbackPosition)

            CollectionMetadata(collection, position)
        }.getOrElse { CollectionMetadata(null, null) }
    }

    private fun parseCollectionPosition(element: Element?): Int? {
        if (element == null) return null

        val raw = element.attr("content").takeIf { it.isNotBlank() }
            ?: element.text().trim()

        return raw.toDoubleOrNull()?.toInt()
    }

    fun defaultCustomTitleFromCandidates(candidates: List<TitleCandidate>): String {
        if (candidates.isEmpty()) return ""
        if (candidates.size == 1) return candidates.first().title

        val commonCollection = candidates.mapNotNull { it.collection }.distinct()
        if (commonCollection.size == 1) return commonCollection.first()

        val first = candidates.first()
        return first.fileName.substringBeforeLast('.', first.fileName)
    }

    private fun extractCoverUri(context: Context, epubReader: EpubReader, manga: SManga): Uri? {
        return runCatching {
            val coverHref = manga.thumbnail_url
            val coverExt = coverHref
                ?.substringBefore('?')
                ?.substringAfterLast('.', "png")
                ?.takeIf { it.length <= 4 }
                ?: "png"

            val coverStream = if (!coverHref.isNullOrBlank()) {
                if (coverHref.startsWith("http://") || coverHref.startsWith("https://")) {
                    runCatching {
                        val connection = URL(coverHref).openConnection() as HttpURLConnection
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 10_000
                        val bytes = connection.inputStream.use { it.readBytes() }
                        ByteArrayInputStream(bytes)
                    }.getOrNull()
                } else {
                    epubReader.getInputStream(coverHref)
                }
            } else {
                val commonNames = listOf(
                    "cover.jpg", "cover.jpeg", "cover.png",
                    "OEBPS/cover.jpg", "OEBPS/cover.jpeg", "OEBPS/cover.png",
                    "Images/cover.jpg", "Images/cover.jpeg", "Images/cover.png",
                )
                commonNames.firstNotNullOfOrNull { name -> epubReader.getInputStream(name) }
            }

            coverStream?.use { stream ->
                val coverFile = File.createTempFile("epub_cover_", ".$coverExt", context.cacheDir)
                coverFile.outputStream().use { out -> stream.copyTo(out) }
                UniFile.fromFile(coverFile)?.uri
            }
        }.getOrNull()
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    }
}
