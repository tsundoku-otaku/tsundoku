package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
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
    @Suppress("unused") private val sourceManager: SourceManager,
) {

    fun subscribe(): Flow<List<Source>> {
        return combine(
            preferences.pinnedSources().changes(),
            preferences.enabledLanguages().changes(),
            preferences.disabledSources().changes(),
            preferences.lastUsedSource().changes(),
            repository.getSources(),
        ) { pinnedSourceIds, enabledLanguages, disabledSources, lastUsedSource, sources ->
            logcat(LogPriority.DEBUG) {
                "GetEnabledNovelSources: ${sources.size} total sources, enabledLangs=$enabledLanguages"
            }
            sources
                .filter { it.lang in enabledLanguages || it.isLocalNovel() }
                .filterNot { it.id.toString() in disabledSources }
                .filter { it.isNovelSource || it.isLocalNovel() }
                .sortedWith(
                    // Local novel source always appears first
                    compareBy<Source> { if (it.isLocalNovel()) 0 else 1 }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                )
                .flatMap {
                    val flag = if ("${it.id}" in pinnedSourceIds) Pins.pinned else Pins.unpinned
                    val source = it.copy(pin = flag)
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
