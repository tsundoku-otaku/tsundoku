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
}
