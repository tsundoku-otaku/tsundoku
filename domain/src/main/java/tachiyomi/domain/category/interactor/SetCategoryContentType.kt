package tachiyomi.domain.category.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.repository.CategoryRepository

class SetCategoryContentType(
    private val categoryRepository: CategoryRepository,
) {

    suspend fun getMismatchedMangaIds(categoryId: Long, contentType: Int): List<Long> =
        categoryRepository.getMangaIdsNotMatchingType(categoryId, contentType)

    suspend fun await(categoryId: Long, contentType: Int): Result = withNonCancellableContext {
        try {
            val removed = categoryRepository.getMangaIdsNotMatchingType(categoryId, contentType)
            categoryRepository.updateContentType(categoryId, contentType)
            Result.Success(removed)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.InternalError(e)
        }
    }

    sealed interface Result {
        data class Success(val removedMangaIds: List<Long>) : Result
        data class InternalError(val error: Throwable) : Result
    }
}
