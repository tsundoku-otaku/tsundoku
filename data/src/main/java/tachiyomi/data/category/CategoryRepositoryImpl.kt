package tachiyomi.data.category

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

class CategoryRepositoryImpl(
    private val database: Database,
) : CategoryRepository {

    override suspend fun get(id: Long): Category? {
        return database.categoriesQueries.getCategory(id, ::mapCategory).awaitAsOneOrNull()
    }

    override suspend fun getAll(): List<Category> {
        return database.categoriesQueries.getCategories(::mapCategory).awaitAsList()
    }

    override fun getAllAsFlow(): Flow<List<Category>> {
        return database.categoriesQueries.getCategories(::mapCategory).subscribeToList()
    }

    override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
        return database.categoriesQueries.getCategoriesByMangaId(mangaId, ::mapCategory).awaitAsList()
    }

    override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
        return database.categoriesQueries.getCategoriesByMangaId(mangaId, ::mapCategory).subscribeToList()
    }

    override suspend fun getCategoriesByMangaIds(mangaIds: List<Long>): Map<Long, List<Category>> {
        if (mangaIds.isEmpty()) return emptyMap()

        val resultMap = mutableMapOf<Long, MutableList<Category>>()
        mangaIds.chunked(500).forEach { chunk ->
            database.categoriesQueries.getCategoriesByMangaIds(chunk) { mangaId, id, name, order, flags, contentType ->
                resultMap.getOrPut(mangaId) { mutableListOf() }
                    .add(mapCategory(id, name, order, flags, contentType))
            }.awaitAsList()
        }
        return resultMap
    }

    override suspend fun getAllMangaCategoryPairs(): List<Pair<Long, Long>> {
        return database.mangas_categoriesQueries.getAllMangaCategoryPairs { mangaId, categoryId ->
            mangaId to categoryId
        }.awaitAsList()
    }

    override suspend fun getCategoriesByContentType(contentType: Int): List<Category> {
        return database.categoriesQueries.getCategoriesByContentType(contentType.toLong(), ::mapCategory).awaitAsList()
    }

    override fun getCategoriesByContentTypeAsFlow(contentType: Int): Flow<List<Category>> {
        return database.categoriesQueries.getCategoriesByContentType(
            contentType.toLong(),
            ::mapCategory,
        ).subscribeToList()
    }

    override suspend fun insert(category: Category) {
        database.categoriesQueries.insert(
            name = category.name,
            order = category.order,
            flags = category.flags,
            contentType = category.contentType.toLong(),
        )
    }

    override suspend fun updateName(categoryId: Long, name: String) {
        database.categoriesQueries.updateName(name = name, categoryId = categoryId)
    }

    override suspend fun getMangaIdsNotMatchingType(categoryId: Long, contentType: Int): List<Long> {
        val isNovel = contentType.isNovelTypeOrNull() ?: return emptyList()
        return database.categoriesQueries.getMangaIdsNotMatchingType(categoryId, isNovel).awaitAsList()
    }

    override suspend fun updateContentType(categoryId: Long, contentType: Int) {
        database.transaction {
            contentType.isNovelTypeOrNull()?.let {
                database.categoriesQueries.deleteMangaNotMatchingType(categoryId, it)
            }
            database.categoriesQueries.updateContentType(contentType.toLong(), categoryId)
        }
    }

    private fun Int.isNovelTypeOrNull(): Boolean? = when (this) {
        Category.CONTENT_TYPE_MANGA -> false
        Category.CONTENT_TYPE_NOVEL -> true
        else -> null
    }

    override suspend fun updateFlags(categoryId: Long, flags: Long) {
        database.categoriesQueries.updateFlags(flags = flags, categoryId = categoryId)
    }

    override suspend fun updateAllFlags(flags: Long?) {
        database.categoriesQueries.updateAllFlags(flags = flags)
    }

    override suspend fun updateAllOrders(orderedIds: List<Long>) {
        database.transaction {
            orderedIds.forEachIndexed { index, categoryId ->
                database.categoriesQueries.updateOrder(order = index.toLong(), categoryId = categoryId)
            }
        }
    }

    override suspend fun delete(categoryId: Long) {
        database.categoriesQueries.delete(
            categoryId = categoryId,
        )
    }

    private fun mapCategory(
        id: Long,
        name: String,
        order: Long,
        flags: Long,
        contentType: Long,
    ): Category {
        return Category(
            id = id,
            name = name,
            order = order,
            flags = flags,
            contentType = contentType.toInt(),
        )
    }
}
