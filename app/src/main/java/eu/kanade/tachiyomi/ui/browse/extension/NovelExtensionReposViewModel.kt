package eu.kanade.tachiyomi.ui.browse.extension

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.jsplugin.model.JsPluginRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import mihon.core.viewmodel.StateViewModel
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.interactor.RemoveExtensionStore
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelExtensionReposViewModel(
    private val jsPluginManager: JsPluginManager = Injekt.get(),
    private val getExtensionStores: GetExtensionStores = Injekt.get(),
    private val addExtensionStore: AddExtensionStore = Injekt.get(),
    private val removeExtensionStore: RemoveExtensionStore = Injekt.get(),
    private val updateExtensionStores: UpdateExtensionStores = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) : StateViewModel<NovelRepoScreenState>(NovelRepoScreenState.Loading) {

    private val _events: Channel<NovelRepoEvent> = Channel(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launchIO {
            combine(
                jsPluginManager.repositories,
                getExtensionStores.subscribe(),
                sourcePreferences.disabledExtensionRepos.changes(),
            ) { jsRepos, kotlinRepos, disabledKotlinRepos ->
                NovelRepoScreenState.Success(
                    jsRepos = jsRepos.toList(),
                    kotlinRepos = kotlinRepos.toSet(),
                    disabledKotlinRepos = disabledKotlinRepos.toSet(),
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
        viewModelScope.launchIO {
            jsPluginManager.addRepository(name, url)
            dismissDialog()
        }
    }

    /**
     * Add a Kotlin extension store (mihon extension index).
     */
    fun createKotlinRepo(indexUrl: String) {
        viewModelScope.launchIO {
            addExtensionStore(indexUrl, isNovel = true).fold(
                onSuccess = {
                    extensionManager.findAvailableExtensions()
                    dismissDialog()
                },
                onFailure = { _events.send(NovelRepoEvent.InvalidUrl) },
            )
        }
    }

    fun deleteJsRepo(url: String) {
        viewModelScope.launchIO {
            jsPluginManager.removeRepository(url)
            dismissDialog()
        }
    }

    fun deleteKotlinRepo(indexUrl: String) {
        viewModelScope.launchIO {
            removeExtensionStore(indexUrl)
            sourcePreferences.disabledExtensionRepos.set(
                sourcePreferences.disabledExtensionRepos.get() - indexUrl,
            )
            extensionManager.findAvailableExtensions()
            dismissDialog()
        }
    }

    fun setJsRepoEnabled(url: String, enabled: Boolean) {
        jsPluginManager.setRepositoryEnabled(url, enabled)
        viewModelScope.launchIO { jsPluginManager.refreshAvailablePlugins() }
    }

    fun setKotlinRepoEnabled(indexUrl: String, enabled: Boolean) {
        sourcePreferences.disabledExtensionRepos.set(
            sourcePreferences.disabledExtensionRepos.get().let { disabledRepos ->
                if (enabled) disabledRepos - indexUrl else disabledRepos + indexUrl
            },
        )
        viewModelScope.launchIO { extensionManager.findAvailableExtensions() }
    }

    fun refreshRepos() {
        viewModelScope.launchIO {
            jsPluginManager.refreshAvailablePlugins(forceRefresh = true)
            updateExtensionStores()
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
    data class DeleteKotlin(val indexUrl: String) : NovelRepoDialog()
}

sealed class NovelRepoScreenState {
    @Immutable
    data object Loading : NovelRepoScreenState()

    @Immutable
    data class Success(
        val jsRepos: List<JsPluginRepository> = listOf(),
        val kotlinRepos: Set<ExtensionStore> = setOf(),
        val disabledKotlinRepos: Set<String> = setOf(),
        val dialog: NovelRepoDialog? = null,
    ) : NovelRepoScreenState() {
        val isEmpty: Boolean
            get() = jsRepos.isEmpty() && kotlinRepos.isEmpty()
    }
}
