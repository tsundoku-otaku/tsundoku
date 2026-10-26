package tachiyomi.domain.category.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.category.model.Category

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

    suspend fun updateName(categoryId: Long, name: String)

    suspend fun getMangaIdsNotMatchingType(categoryId: Long, contentType: Int): List<Long>

    /** Sets the content type and removes entries that no longer match it. */
    suspend fun updateContentType(categoryId: Long, contentType: Int)

    suspend fun updateFlags(categoryId: Long, flags: Long)

    suspend fun updateAllFlags(flags: Long?)

    suspend fun updateAllOrders(orderedIds: List<Long>)

    suspend fun delete(categoryId: Long)
}
