package eu.kanade.tachiyomi.ui.deeplink

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.domain.manga.interactor.MassImport
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DeepLinkViewModel(
    private val query: String,
    private val extensionPackage: String? = null,
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getChapterByUrlAndMangaId: GetChapterByUrlAndMangaId = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
    private val massImport: MassImport = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
) : StateViewModel<DeepLinkViewModel.State>(State.Loading) {

    companion object {
        val QUERY_KEY = CreationExtras.Key<String>()
        val PACKAGE_KEY = CreationExtras.Key<String>()

        val Factory = viewModelFactory {
            initializer {
                DeepLinkViewModel(
                    query = get(QUERY_KEY)!!,
                    extensionPackage = get(PACKAGE_KEY),
                )
            }
        }
    }

    init {
        viewModelScope.launchIO {
            val resolvable = sourceManager.getAll()
                .filterIsInstance<ResolvableSource>()
                .firstOrNull { it.getUriType(query) != UriType.Unknown }

            val resolvableManga = resolvable?.getManga(query)?.let {
                networkToLocalManga(it.toDomainManga(resolvable.id, resolvable.isNovelSource()))
            }

            // No ResolvableSource handled the URL (novel extensions don't implement that
            // interface). Resolve it the way the URL mass-import flow does - fetch details for
            // the entry's path - so a shared "open in app" link lands on the manga page.
            // Prefer the source named by the sender's extension package; fall back to a host match.
            val fallback = if (resolvableManga == null) {
                resolveViaPackage() ?: resolveViaHost()
            } else {
                null
            }

            val manga = resolvableManga ?: fallback?.first

            val chapter = if (resolvable?.getUriType(query) == UriType.Chapter && manga != null) {
                resolvable.getChapter(query)?.let { getChapterFromSChapter(it, manga, resolvable) }
            } else {
                null
            }

            mutableState.update {
                when {
                    manga != null && chapter != null -> State.Result(manga, chapter.id)
                    manga != null -> State.Result(manga)
                    // Route an unresolved URL to the matching library's global search: a
                    // novel-source host goes to the novel search, everything else to manga.
                    else -> {
                        val isNovel = fallback?.second?.isNovelSource()
                            ?: packageSources().firstOrNull()?.isNovelSource()
                            ?: massImport.findMatchingSource(query)?.isNovelSource()
                            ?: false
                        State.NoResults(isNovel = isNovel)
                    }
                }
            }
        }
    }

    /** [CatalogueSource]s belonging to the extension package that sent the intent, if any. */
    private fun packageSources(): List<CatalogueSource> {
        val pkg = extensionPackage ?: return emptyList()
        return extensionManager.installedExtensionsFlow.value
            .firstOrNull { it.pkgName == pkg }
            ?.sources
            ?.filterIsInstance<CatalogueSource>()
            .orEmpty()
    }

    private suspend fun resolveViaPackage(): Pair<Manga, CatalogueSource>? {
        for (source in packageSources()) {
            val manga = runCatching { massImport.resolveUrlToManga(query, source) }
                .onFailure { logcat(LogPriority.WARN, it) { "DeepLink: pkg resolve failed on ${source.name}" } }
                .getOrNull()
            if (manga != null) return manga to source
        }
        return null
    }

    private suspend fun resolveViaHost(): Pair<Manga, CatalogueSource>? {
        return runCatching { massImport.resolveUrlToManga(query) }
            .onFailure { logcat(LogPriority.WARN, it) { "DeepLink: host resolve failed for $query" } }
            .getOrNull()
    }

    private suspend fun getChapterFromSChapter(sChapter: SChapter, manga: Manga, source: Source): Chapter? {
        val localChapter = getChapterByUrlAndMangaId.await(sChapter.url, manga.id)

        return localChapter
            ?: updateMangaFromRemote(manga, fetchChapters = true)
                .getOrElse { return null }
                .newChapters
                .find { it.url == sChapter.url }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class NoResults(val isNovel: Boolean = false) : State

        @Immutable
        data class Result(val manga: Manga, val chapterId: Long? = null) : State
    }
}
