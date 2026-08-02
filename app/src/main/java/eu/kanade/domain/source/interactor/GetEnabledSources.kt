package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.isNovelSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Pins
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal

class GetEnabledSources(
    private val repository: SourceRepository,
    private val preferences: SourcePreferences,
    private val sourceManager: SourceManager,
) {

    fun subscribe(): Flow<List<Source>> {
        val pinStateFlow = combine(
            preferences.pinnedSources.changes(),
            preferences.groupPinnedSources.changes(),
        ) { pinnedSourceIds, groupPinnedSources ->
            pinnedSourceIds to groupPinnedSources
        }

        return combine(
            pinStateFlow,
            preferences.enabledLanguages.changes(),
            preferences.disabledSources.changes(),
            preferences.lastUsedSource.changes(),
            repository.getSources(),
        ) { (pinnedSourceIds, groupPinnedSources), enabledLanguages, disabledSources, lastUsedSource, sources ->
            sources
                .filter { it.lang in enabledLanguages || it.isLocal() }
                .filterNot { it.id.toString() in disabledSources }
                .filterNot { sourceManager.get(it.id)?.isNovelSource() == true }
                .filter { it.isLocal() || sourceManager.get(it.id) is CatalogueSource }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .flatMap {
                    val flag = if ("${it.id}" in pinnedSourceIds) Pins.pinned else Pins.unpinned
                    val sourceGroups = groupPinnedSources
                        .filter { entry -> entry.endsWith("|${it.id}") }
                        .map { entry -> entry.substringBeforeLast("|") }
                        .toSet()
                    val source = it.copy(pin = flag, pinnedGroups = sourceGroups)
                    val toFlatten = mutableListOf(source)
                    if (source.id == lastUsedSource) {
                        toFlatten.add(source.copy(isUsedLast = true, pin = source.pin - Pin.Actual))
                    }
                    toFlatten
                }
        }
            .distinctUntilChanged()
    }
}
