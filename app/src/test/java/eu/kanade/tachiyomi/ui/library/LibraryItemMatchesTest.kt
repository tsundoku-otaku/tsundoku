package eu.kanade.tachiyomi.ui.library

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/**
 * Covers field-prefixed matching only. The DEFAULT and SOURCE paths resolve the source name via
 * an Injekt-backed SourceManager and are exercised in instrumented/integration tests instead.
 */
class LibraryItemMatchesTest {

    private fun item(
        id: Long = 1L,
        title: String = "Naruto",
        author: String? = null,
        artist: String? = null,
        genre: List<String>? = null,
        url: String = "",
    ): LibraryItem {
        val manga = Manga.create().copy(
            id = id,
            title = title,
            author = author,
            artist = artist,
            genre = genre,
            url = url,
        )
        return LibraryItem(
            libraryManga = LibraryManga(
                manga = manga,
                categories = emptyList(),
                totalChapters = 0,
                readCount = 0,
                bookmarkCount = 0,
                latestUpload = 0,
                chapterFetchedAt = 0,
                lastRead = 0,
            ),
        )
    }

    private fun spec(query: String, useRegex: Boolean = false, searchByUrl: Boolean = false) =
        LibrarySearchSpec.parse(query, useRegex, searchByUrl)

    private fun metaIds(
        author: Set<Long> = emptySet(),
        artist: Set<Long> = emptySet(),
        description: Set<Long> = emptySet(),
    ) = LibraryScreenModel.MetadataMatchIds(author = author, artist = artist, description = description)

    @Test
    fun `title prefix matches substring case-insensitively`() {
        assertTrue(item(title = "One Piece").matches(spec("title:piece")))
        assertFalse(item(title = "One Piece").matches(spec("title:bleach")))
    }

    @Test
    fun `title prefix supports regex`() {
        assertTrue(item(title = "Naruto").matches(spec("title:nar.*to", useRegex = true)))
    }

    @Test
    fun `author prefix is matched via metadataMatchIds not the in-memory field`() {
        val it = item(id = 3L)
        assertTrue(it.matches(spec("author:oda"), metadataMatchIds = metaIds(author = setOf(3L))))
        assertFalse(it.matches(spec("author:oda"), metadataMatchIds = metaIds(author = setOf(4L))))
    }

    @Test
    fun `artist prefix is matched via metadataMatchIds`() {
        val it = item(id = 9L)
        assertTrue(it.matches(spec("artist:x"), metadataMatchIds = metaIds(artist = setOf(9L))))
        assertFalse(it.matches(spec("artist:x"), metadataMatchIds = metaIds(artist = emptySet())))
    }

    @Test
    fun `description is matched via metadataMatchIds not the in-memory field`() {
        val it = item(id = 7L)
        assertTrue(it.matches(spec("desc:pirate"), metadataMatchIds = metaIds(description = setOf(7L))))
        assertFalse(it.matches(spec("desc:pirate"), metadataMatchIds = metaIds(description = setOf(8L))))
    }

    @Test
    fun `chapter prefix is matched via chapterMatchIds`() {
        val it = item(id = 5L)
        assertTrue(it.matches(spec("chapter:vol"), chapterMatchIds = setOf(5L)))
        assertFalse(it.matches(spec("chapter:vol"), chapterMatchIds = emptySet()))
    }

    @Test
    fun `tag prefix matches any genre`() {
        assertTrue(item(genre = listOf("Action", "Comedy")).matches(spec("tag:comedy")))
        assertFalse(item(genre = listOf("Action")).matches(spec("genre:horror")))
    }

    @Test
    fun `url prefix matches url`() {
        assertTrue(item(url = "/manga/123").matches(spec("url:/manga/")))
    }

    @Test
    fun `id prefix matches numeric id`() {
        assertTrue(item(id = 99L).matches(spec("id:99")))
        assertFalse(item(id = 99L).matches(spec("id:100")))
    }
}
