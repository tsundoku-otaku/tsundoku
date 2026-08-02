package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Pins
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocalNovel

class GetEnabledNovelSources(
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
            logcat(LogPriority.DEBUG) {
                "GetEnabledNovelSources: ${sources.size} total sources, enabledLangs=$enabledLanguages"
            }
            sources
                .asSequence()
                .filter { it.lang in enabledLanguages || it.isLocalNovel() }
                .filterNot { it.id.toString() in disabledSources }
                .filter { it.isNovelSource || it.isLocalNovel() }
                .filter { it.isLocalNovel() || sourceManager.get(it.id) is CatalogueSource }
                .sortedWith(
                    // Local novel source always appears first
                    compareBy<Source> { if (it.isLocalNovel()) 0 else 1 }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                )
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
                .toList()
        }
            .distinctUntilChanged()
    }
}
