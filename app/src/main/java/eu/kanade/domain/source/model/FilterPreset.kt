package eu.kanade.domain.source.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class FilterPreset(
    val id: Long,
    val sourceId: Long,
    val name: String,
    val filterState: String, // JSON-encoded filter state
    val isDefault: Boolean = false,
    // Stored under its own preference key, see SourcePreferences.filterPresetQueries.
    @Transient val searchQuery: String? = null,
)

@Serializable
data class FilterPresetList(
    val presets: List<FilterPreset> = emptyList(),
)
