package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsOne
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import tachiyomi.data.Database
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CategoriesRestorer(
    private val database: Database = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) {

    suspend operator fun invoke(backupCategories: List<BackupCategory>) {
        if (backupCategories.isNotEmpty()) {
            val dbCategories = getCategories.await()
            val dbCategoriesByName = dbCategories.groupBy { it.name }
            var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0

            val categories = backupCategories
                .sortedBy { it.order }
                .map {
                    val sameName = dbCategoriesByName[it.name].orEmpty()
                    sameName.restoreMatch(it.contentType)?.let { dbCategory -> return@map dbCategory }
                    // Names are unique in the UI, so widen a conflicting type instead of adding a duplicate
                    sameName.firstOrNull()?.let { dbCategory ->
                        database.categoriesQueries.updateContentType(Category.CONTENT_TYPE_ALL.toLong(), dbCategory.id)
                        return@map dbCategory.copy(contentType = Category.CONTENT_TYPE_ALL)
                    }
                    val order = nextOrder++
                    database.categoriesQueries.insert(it.name, order, it.flags, it.contentType.toLong())
                    database.categoriesQueries.selectLastInsertedRowId()
                        .awaitAsOne()
                        .let { id -> it.toCategory(id).copy(order = order) }
                }

            libraryPreferences.categorizedDisplaySettings.set(
                (dbCategories + categories)
                    .distinctBy { it.flags }
                    .size > 1,
            )
        }
    }
}

internal fun List<Category>.restoreMatch(contentType: Int): Category? =
    firstOrNull { it.contentType == contentType } ?: firstOrNull { it.contentType == Category.CONTENT_TYPE_ALL }
