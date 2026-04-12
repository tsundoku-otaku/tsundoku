package tachiyomi.domain.category.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate

interface CategoryRepository {

    suspend fun get(id: Long): Category?

    suspend fun getAll(): List<Category>

    fun getAllAsFlow(): Flow<List<Category>>

    suspend fun getCategoriesByMangaId(mangaId: Long): List<Category>

    fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>>

    /**
     * Batch fetch categories for multiple manga IDs. Returns a map of mangaId -> List<Category>.
     * Optimized for duplicate detection and bulk operations.
     */
    suspend fun getCategoriesByMangaIds(mangaIds: List<Long>): Map<Long, List<Category>>

    /**
     * Lightweight bulk query: returns all (manga_id, category_id) pairs.
     */
    suspend fun getAllMangaCategoryPairs(): List<Pair<Long, Long>>

    suspend fun getCategoriesByContentType(contentType: Int): List<Category>

    fun getCategoriesByContentTypeAsFlow(contentType: Int): Flow<List<Category>>

    suspend fun insert(category: Category)

    suspend fun updatePartial(update: CategoryUpdate)

    suspend fun updatePartial(updates: List<CategoryUpdate>)

    suspend fun updateAllFlags(flags: Long?)

    suspend fun delete(categoryId: Long)
}
