package eu.kanade.tachiyomi.ui.browse.extension

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.jsplugin.model.JsPluginRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelExtensionReposScreenModel(
    private val jsPluginManager: JsPluginManager = Injekt.get(),
    private val getExtensionRepo: GetExtensionRepo = Injekt.get(),
    private val createExtensionRepo: CreateExtensionRepo = Injekt.get(),
    private val deleteExtensionRepo: DeleteExtensionRepo = Injekt.get(),
    private val replaceExtensionRepo: ReplaceExtensionRepo = Injekt.get(),
    private val updateExtensionRepo: UpdateExtensionRepo = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<NovelRepoScreenState>(NovelRepoScreenState.Loading) {

    private val _events: Channel<NovelRepoEvent> = Channel(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            combine(
                jsPluginManager.repositories,
                getExtensionRepo.subscribeAll(),
                sourcePreferences.disabledExtensionRepos.changes(),
            ) { jsRepos, kotlinRepos, disabledKotlinRepos ->
                NovelRepoScreenState.Success(
                    jsRepos = jsRepos.toImmutableList(),
                    kotlinRepos = kotlinRepos.toImmutableSet(),
                    disabledKotlinRepos = disabledKotlinRepos.toImmutableSet(),
                )
            }.collectLatest { state ->
                mutableState.update {
                    state.copy(dialog = (it as? NovelRepoScreenState.Success)?.dialog)
                }
            }
        }
    }

    /**
     * Create a JS plugin repository
     */
    fun createJsRepo(name: String, url: String) {
        screenModelScope.launchIO {
            jsPluginManager.addRepository(name, url)
            dismissDialog()
        }
    }

    /**
     * Create a Kotlin extension repository
     */
    fun createKotlinRepo(baseUrl: String) {
        screenModelScope.launchIO {
            when (val result = createExtensionRepo.await(baseUrl)) {
                CreateExtensionRepo.Result.Success -> {
                    extensionManager.findAvailableExtensions()
                    dismissDialog()
                }
                CreateExtensionRepo.Result.InvalidUrl -> _events.send(NovelRepoEvent.InvalidUrl)
                CreateExtensionRepo.Result.RepoAlreadyExists -> _events.send(NovelRepoEvent.RepoAlreadyExists)
                is CreateExtensionRepo.Result.DuplicateFingerprint -> {
                    showDialog(NovelRepoDialog.KotlinConflict(result.oldRepo, result.newRepo))
                }
                else -> {}
            }
        }
    }

    fun replaceKotlinRepo(newRepo: ExtensionRepo) {
        screenModelScope.launchIO {
            replaceExtensionRepo.await(newRepo)
            dismissDialog()
        }
    }

    fun deleteJsRepo(url: String) {
        screenModelScope.launchIO {
            jsPluginManager.removeRepository(url)
            dismissDialog()
        }
    }

    fun deleteKotlinRepo(baseUrl: String) {
        screenModelScope.launchIO {
            deleteExtensionRepo.await(baseUrl)
            sourcePreferences.disabledExtensionRepos.set(
                sourcePreferences.disabledExtensionRepos.get() - baseUrl,
            )
            extensionManager.findAvailableExtensions()
            dismissDialog()
        }
    }

    fun setJsRepoEnabled(url: String, enabled: Boolean) {
        jsPluginManager.setRepositoryEnabled(url, enabled)
    }

    fun setKotlinRepoEnabled(baseUrl: String, enabled: Boolean) {
        sourcePreferences.disabledExtensionRepos.set(
            sourcePreferences.disabledExtensionRepos.get().let { disabledRepos ->
                if (enabled) disabledRepos - baseUrl else disabledRepos + baseUrl
            },
        )
    }

    fun refreshRepos() {
        screenModelScope.launchIO {
            jsPluginManager.refreshAvailablePlugins(forceRefresh = true)
            val disabledRepos = sourcePreferences.disabledExtensionRepos.get()
            val enabledRepos = getExtensionRepo.getAll().filterNot { it.baseUrl in disabledRepos }
            updateExtensionRepo.awaitAll(enabledRepos)
        }
    }

    fun showDialog(dialog: NovelRepoDialog) {
        mutableState.update {
            when (it) {
                NovelRepoScreenState.Loading -> it
                is NovelRepoScreenState.Success -> it.copy(dialog = dialog)
            }
        }
    }

    fun dismissDialog() {
        mutableState.update {
            when (it) {
                NovelRepoScreenState.Loading -> it
                is NovelRepoScreenState.Success -> it.copy(dialog = null)
            }
        }
    }
}

sealed class NovelRepoEvent {
    sealed class LocalizedMessage(val stringRes: dev.icerock.moko.resources.StringResource) : NovelRepoEvent()
    data object InvalidUrl : LocalizedMessage(MR.strings.invalid_repo_name)
    data object RepoAlreadyExists : LocalizedMessage(MR.strings.error_repo_exists)
}

sealed class NovelRepoDialog {
    data object ChooseType : NovelRepoDialog()
    data object CreateJs : NovelRepoDialog()
    data object CreateKotlin : NovelRepoDialog()
    data class DeleteJs(val repo: JsPluginRepository) : NovelRepoDialog()
    data class DeleteKotlin(val baseUrl: String) : NovelRepoDialog()
    data class KotlinConflict(val oldRepo: ExtensionRepo, val newRepo: ExtensionRepo) : NovelRepoDialog()
}

sealed class NovelRepoScreenState {
    @Immutable
    data object Loading : NovelRepoScreenState()

    @Immutable
    data class Success(
        val jsRepos: ImmutableList<JsPluginRepository> = kotlinx.collections.immutable.persistentListOf(),
        val kotlinRepos: ImmutableSet<ExtensionRepo> = kotlinx.collections.immutable.persistentSetOf(),
        val disabledKotlinRepos: ImmutableSet<String> = kotlinx.collections.immutable.persistentSetOf(),
        val dialog: NovelRepoDialog? = null,
    ) : NovelRepoScreenState() {
        val isEmpty: Boolean
            get() = jsRepos.isEmpty() && kotlinRepos.isEmpty()
    }
}
