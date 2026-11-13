package eu.kanade.tachiyomi.data.backup.restore.restorers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category

class CategoryRestoreMatchTest {

    private fun category(id: Long, contentType: Int) =
        Category(id = id, name = "Reading", order = id, flags = 0, contentType = contentType)

    @Test
    fun `prefers exact content type`() {
        val categories = listOf(
            category(1, Category.CONTENT_TYPE_ALL),
            category(2, Category.CONTENT_TYPE_NOVEL),
        )

        assertEquals(2L, categories.restoreMatch(Category.CONTENT_TYPE_NOVEL)?.id)
    }

    @Test
    fun `falls back to all type when no exact match`() {
        val categories = listOf(
            category(1, Category.CONTENT_TYPE_MANGA),
            category(2, Category.CONTENT_TYPE_ALL),
        )

        assertEquals(2L, categories.restoreMatch(Category.CONTENT_TYPE_NOVEL)?.id)
    }

    @Test
    fun `typed backup category matches existing all category`() {
        val categories = listOf(category(1, Category.CONTENT_TYPE_ALL))

        assertEquals(1L, categories.restoreMatch(Category.CONTENT_TYPE_NOVEL)?.id)
    }

    @Test
    fun `no match when only a conflicting type exists`() {
        val categories = listOf(category(1, Category.CONTENT_TYPE_NOVEL))

        assertNull(categories.restoreMatch(Category.CONTENT_TYPE_MANGA))
        assertNull(categories.restoreMatch(Category.CONTENT_TYPE_ALL))
    }

    @Test
    fun `restore target never puts an entry in a category of the other type`() {
        val manga = listOf(category(1, Category.CONTENT_TYPE_MANGA))

        assertNull(manga.restoreTarget(Category.CONTENT_TYPE_ALL, isNovel = true))
        assertEquals(1L, manga.restoreTarget(Category.CONTENT_TYPE_ALL, isNovel = false)?.id)
    }

    @Test
    fun `restore target falls back to a same-name category that accepts the entry`() {
        val categories = listOf(
            category(1, Category.CONTENT_TYPE_MANGA),
            category(2, Category.CONTENT_TYPE_NOVEL),
        )

        assertEquals(2L, categories.restoreTarget(Category.CONTENT_TYPE_MANGA, isNovel = true)?.id)
    }

    @Test
    fun `an entry not flagged as a novel keeps the category its backup names`() {
        val categories = listOf(category(1, Category.CONTENT_TYPE_NOVEL))

        assertEquals(1L, categories.restoreTarget(Category.CONTENT_TYPE_NOVEL, isNovel = false)?.id)
    }
}
