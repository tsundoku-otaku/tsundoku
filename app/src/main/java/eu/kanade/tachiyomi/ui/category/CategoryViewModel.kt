package eu.kanade.tachiyomi.ui.category

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.core.viewmodel.StateViewModel
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.SetCategoryContentType
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CategoryViewModel(
    private val getCategories: GetCategories = Injekt.get(),
    private val createCategoryWithName: CreateCategoryWithName = Injekt.get(),
    private val deleteCategory: DeleteCategory = Injekt.get(),
    private val reorderCategory: ReorderCategory = Injekt.get(),
    private val renameCategory: RenameCategory = Injekt.get(),
    private val setCategoryContentType: SetCategoryContentType = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val categoryRepository: CategoryRepository = Injekt.get(),
) : StateViewModel<CategoryScreenState>(CategoryScreenState.Loading) {

    private val _events: Channel<CategoryEvent> = Channel()
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            getCategories.subscribe()
                .collectLatest { categories ->
                    mutableState.update {
                        CategoryScreenState.Success(
                            categories = categories
                                .filterNot(Category::isSystemCategory),
                        )
                    }
                }
        }
    }

    fun createCategory(name: String, contentType: Int = Category.CONTENT_TYPE_ALL) {
        viewModelScope.launch {
            when (createCategoryWithName.await(name, contentType)) {
                is CreateCategoryWithName.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    fun deleteCategory(categoryId: Long) {
        viewModelScope.launch {
            // Get all manga-category pairs to find manga IDs for this category
            val allPairs = categoryRepository.getAllMangaCategoryPairs()
            val mangaIds = allPairs
                .filter { it.second == categoryId }
                .map { it.first }
                .distinct()

            when (deleteCategory.await(categoryId = categoryId)) {
                is DeleteCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {
                    // Refresh the manga that were in this category to update their category lists
                    if (mangaIds.isNotEmpty()) {
                        getLibraryManga.applyCategoryUpdates(
                            mangaIds = mangaIds,
                            addCategories = emptyList(),
                            removeCategories = listOf(categoryId),
                        )
                    }
                }
            }
        }
    }

    fun changeOrder(category: Category, newIndex: Int) {
        viewModelScope.launch {
            when (reorderCategory.await(category, newIndex)) {
                is ReorderCategory.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                else -> {}
            }
        }
    }

    fun editCategory(category: Category, name: String, contentType: Int) {
        viewModelScope.launch {
            if (name != category.name) {
                if (renameCategory.await(category, name) is RenameCategory.Result.InternalError) {
                    _events.send(CategoryEvent.InternalError)
                    return@launch
                }
            }
            if (contentType == category.contentType) return@launch

            val mismatched = setCategoryContentType.getMismatchedMangaIds(category.id, contentType)
            if (mismatched.isEmpty()) {
                changeContentType(category, contentType)
            } else {
                showDialog(CategoryDialog.ConfirmContentType(category.copy(name = name), contentType, mismatched.size))
            }
        }
    }

    fun changeContentType(category: Category, contentType: Int) {
        viewModelScope.launch {
            when (val result = setCategoryContentType.await(category.id, contentType)) {
                is SetCategoryContentType.Result.InternalError -> _events.send(CategoryEvent.InternalError)
                is SetCategoryContentType.Result.Success -> getLibraryManga.applyCategoryUpdates(
                    mangaIds = result.removedMangaIds,
                    addCategories = emptyList(),
                    removeCategories = listOf(category.id),
                )
            }
        }
    }

    fun showDialog(dialog: CategoryDialog) {
        mutableState.update {
            when (it) {
                CategoryScreenState.Loading -> it
                is CategoryScreenState.Success -> it.copy(dialog = dialog)
            }
        }
    }

    fun dismissDialog() {
        mutableState.update {
            when (it) {
                CategoryScreenState.Loading -> it
                is CategoryScreenState.Success -> it.copy(dialog = null)
            }
        }
    }
}

sealed interface CategoryDialog {
    data object Create : CategoryDialog
    data class Edit(val category: Category) : CategoryDialog
    data class ConfirmContentType(val category: Category, val contentType: Int, val mismatchedCount: Int) :
        CategoryDialog
    data class Delete(val category: Category) : CategoryDialog
}

sealed interface CategoryEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : CategoryEvent
    data object InternalError : LocalizedMessage(MR.strings.internal_error)
}

sealed interface CategoryScreenState {

    @Immutable
    data object Loading : CategoryScreenState

    @Immutable
    data class Success(
        val categories: List<Category>,
        val dialog: CategoryDialog? = null,
    ) : CategoryScreenState {

        val isEmpty: Boolean
            get() = categories.isEmpty()
    }
}
