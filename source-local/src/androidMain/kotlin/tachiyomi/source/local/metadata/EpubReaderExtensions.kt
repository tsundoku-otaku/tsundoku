package tachiyomi.source.local.metadata

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import mihon.core.archive.EpubReader
import org.jsoup.Jsoup
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Fills manga and chapter metadata using this epub file's metadata.
 */
fun EpubReader.fillMetadata(manga: SManga, chapter: SChapter) {
    val ref = getPackageHref()
    val doc = getPackageDocument(ref)
    var title = doc.getElementsByTag("dc:title").firstOrNull()?.text()
    if (title.isNullOrBlank()) {
        title = doc.select("docTitle").firstOrNull()?.text()
    }

    if (title.isNullOrBlank()) {
        title = doc.select("meta[name=title]").firstOrNull()?.attr("content")
    }
    val publisher = doc.getElementsByTag("dc:publisher").firstOrNull()
    val creator = doc.getElementsByTag("dc:creator").firstOrNull()
    var description = doc.getElementsByTag("dc:description").firstOrNull()?.text()
    if (description.isNullOrBlank()) {
        description = doc.select("dc\\:description").firstOrNull()?.text()
    }

    val subjects = doc.getElementsByTag("dc:subject").map { it.text() }
    val mappedSubjects = if (subjects.isEmpty()) {
        doc.select("dc\\:subject").map { it.text() }
    } else {
        subjects
    }

    val collection = doc.select("meta[property=belongs-to-collection]").firstOrNull()?.text()

    val currentTitle = runCatching { manga.title }.getOrNull()
    if (!collection.isNullOrBlank() && currentTitle.isNullOrBlank()) {
        manga.title = collection
    } else if (!title.isNullOrBlank() && currentTitle.isNullOrBlank()) {
        manga.title = title
    }

    var date = doc.getElementsByTag("dc:date").firstOrNull()
    if (date == null) {
        date = doc.select("meta[property=dcterms:modified]").firstOrNull()
    }

    creator?.text()?.let { manga.author = it }
    description?.let { if (it.isNotBlank()) manga.description = it }

    if (mappedSubjects.isNotEmpty()) {
        val currentGenres = manga.genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val allGenres = (currentGenres + mappedSubjects).distinct()
        manga.genre = allGenres.joinToString(", ")
    }

    title?.let { if (it.isNotBlank()) chapter.name = it }

    if (publisher != null) {
        chapter.scanlator = publisher.text()
    } else if (creator != null) {
        chapter.scanlator = creator.text()
    }

    if (date != null) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
        try {
            val parsedDate = dateFormat.parse(date.text())
            if (parsedDate != null) {
                chapter.date_upload = parsedDate.time
            }
        } catch (e: ParseException) {
        }
    }

    extractCoverUrl(manga, doc, ref)
}

/**
 * Extracts the cover image from the EPUB and sets it as thumbnail.
 * Skips extraction if thumbnail_url is already set to a valid external URI
 * (e.g., by LocalCoverManager/LocalNovelCoverManager).
 */
private fun EpubReader.extractCoverUrl(manga: SManga, doc: org.jsoup.nodes.Document, packageRef: String) {
    val existing = manga.thumbnail_url
    if (!existing.isNullOrBlank() && (existing.startsWith("content://") || existing.startsWith("file://"))) {
        return
    }

    try {
        val coverPath = getCoverImage()
        if (!coverPath.isNullOrBlank()) {
            manga.thumbnail_url = coverPath
            return
        }

        var coverId = doc.select("meta[name=cover]").firstOrNull()?.attr("content")

        if (coverId.isNullOrBlank()) {
            coverId = doc.select("manifest > item[properties*=cover-image]").firstOrNull()?.attr("id")
        }

        if (!coverId.isNullOrBlank()) {
            val coverHref = doc.select("manifest > item#$coverId").firstOrNull()?.attr("href")
            if (!coverHref.isNullOrBlank()) {
                manga.thumbnail_url = coverHref
                return
            }
        }

        val pages = getPagesFromDocument(doc)
        if (pages.isNotEmpty()) {
            val coverImages = getImagesFromPages()
            if (coverImages.isNotEmpty()) {
                manga.thumbnail_url = coverImages.first()
            }
        }
    } catch (e: Exception) {
    }
}
