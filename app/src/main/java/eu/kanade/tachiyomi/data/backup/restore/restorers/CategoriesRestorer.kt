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
                    // Names are unique in the UI and the user's category keeps the type they set, so a
                    // backup category it can't hold restores next to it under a typed name.
                    val name = if (sameName.isEmpty()) it.name else typedRestoreName(it.name, it.contentType)
                    if (name != it.name) {
                        dbCategoriesByName[name]?.restoreMatch(it.contentType)
                            ?.let { dbCategory -> return@map dbCategory }
                    }
                    val order = nextOrder++
                    database.categoriesQueries.insert(name, order, it.flags, it.contentType.toLong())
                    database.categoriesQueries.selectLastInsertedRowId()
                        .awaitAsOne()
                        .let { id -> it.toCategory(id).copy(name = name, order = order) }
                }

            libraryPreferences.categorizedDisplaySettings.set(
                (dbCategories + categories)
                    .distinctBy { it.flags }
                    .size > 1,
            )
        }
    }
}

/** Name a backup category restores under when a same-name category of an incompatible type exists. */
internal fun typedRestoreName(name: String, contentType: Int): String = when (contentType) {
    Category.CONTENT_TYPE_NOVEL -> "$name (Novel)"
    Category.CONTENT_TYPE_MANGA -> "$name (Manga)"
    else -> "$name (All)"
}

// Backups made before entries carried is_novel restore novels as false, so only a novel flag can veto
// the category the backup names.
internal fun List<Category>.restoreTarget(contentType: Int, isNovel: Boolean): Category? {
    restoreMatch(contentType)?.takeIf { !isNovel || it.accepts(true) }?.let { return it }
    return firstOrNull { it.accepts(isNovel) }
}

private fun Category.accepts(isNovel: Boolean): Boolean = when (contentType) {
    Category.CONTENT_TYPE_NOVEL -> isNovel
    Category.CONTENT_TYPE_MANGA -> !isNovel
    else -> true
}

internal fun List<Category>.restoreMatch(contentType: Int): Category? =
    firstOrNull { it.contentType == contentType } ?: firstOrNull { it.contentType == Category.CONTENT_TYPE_ALL }
