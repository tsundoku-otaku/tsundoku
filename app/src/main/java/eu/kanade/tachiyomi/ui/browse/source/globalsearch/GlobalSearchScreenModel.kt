package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isNovelSource

class GlobalSearchScreenModel(
    initialQuery: String = "",
    initialExtensionFilter: String? = null,
) : SearchScreenModel(State(searchQuery = initialQuery)) {

    init {
        extensionFilter = initialExtensionFilter
        if (initialQuery.isNotBlank() || !initialExtensionFilter.isNullOrBlank()) {
            if (extensionFilter != null) {
                // we're going to use custom extension filter instead
                setSourceFilter(SourceFilter.All)
            }
            search()
        }
    }

    override fun getEnabledSources(): List<Source> {
        val filter = state.value.sourceFilter
        return super.getEnabledSources()
            .filterNot { it.isNovelSource() } // Exclude novel sources from manga global search
            .filter {
                when (filter) {
                    SourceFilter.All -> true
                    SourceFilter.PinnedOnly -> "${it.id}" in pinnedSources
                    is SourceFilter.Group -> "${it.id}" in sourceGroups.getOrElse(filter.name) { emptySet() }
                }
            }
    }
}
