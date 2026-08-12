package mihon.domain.extension.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository

class GetExtensionStores(
    private val repository: ExtensionStoreRepository,
) {
    suspend fun get(): List<ExtensionStore> = repository.getAll()

    fun subscribe(): Flow<List<ExtensionStore>> = repository.getAllAsFlow()

    /**
     * Stores filtered by which screen registered them, for tidier repo-management lists.
     * Novel only shows stores explicitly tagged isNovel=true; manga shows everything else,
     * so an untagged/legacy store (isNovel defaults false) still shows up somewhere.
     */
    fun subscribe(isNovel: Boolean): Flow<List<ExtensionStore>> =
        repository.getAllAsFlow().map { stores -> stores.filter { it.isNovel == isNovel } }
}
