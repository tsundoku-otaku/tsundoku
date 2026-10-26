package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.model.FilterPreset
import eu.kanade.domain.source.model.FilterPresetList
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import logcat.logcat

class ManageFilterPresets(
    private val preferences: SourcePreferences,
) {
    fun getPresets(sourceId: Long): FilterPresetList {
        return withQueries(preferences.filterPresets(sourceId).get(), preferences.filterPresetQueries(sourceId).get())
    }

    fun getPresetsFlow(sourceId: Long): Flow<List<FilterPreset>> {
        return combine(
            preferences.filterPresets(sourceId).changes(),
            preferences.filterPresetQueries(sourceId).changes(),
            ::withQueries,
        )
            .map { it.presets }
    }

    private fun withQueries(list: FilterPresetList, queries: Map<Long, String>): FilterPresetList {
        if (queries.isEmpty()) return list
        return FilterPresetList(list.presets.map { it.copy(searchQuery = queries[it.id]) })
    }

    fun savePreset(
        sourceId: Long,
        name: String,
        filters: FilterList,
        setAsDefault: Boolean = false,
        searchQuery: String? = null,
    ): Result<Unit> {
        return runCatching {
            val filterState = serializeFilters(filters)
            val currentPresets = preferences.filterPresets(sourceId).get()

            val newPreset = FilterPreset(
                id = System.currentTimeMillis(),
                sourceId = sourceId,
                name = name,
                filterState = filterState,
                isDefault = setAsDefault,
            )

            val updatedPresets = if (setAsDefault) {
                currentPresets.presets.map { it.copy(isDefault = false) } + newPreset
            } else {
                currentPresets.presets + newPreset
            }

            preferences.filterPresets(sourceId).set(
                FilterPresetList(updatedPresets),
            )
            if (searchQuery != null) {
                val queries = preferences.filterPresetQueries(sourceId)
                queries.set(queries.get() + (newPreset.id to searchQuery))
            }
            logcat(LogPriority.INFO) { "FilterPresets: Saved preset '$name' for sourceId=$sourceId" }
        }.onFailure { error ->
            logcat(LogPriority.ERROR) { "FilterPresets: Failed to save preset: ${error.message}" }
        }
    }

    fun getPreset(sourceId: Long, presetId: Long): FilterPreset? {
        return getPresets(sourceId).presets.find { it.id == presetId }
    }

    fun deletePreset(sourceId: Long, presetId: Long) {
        val currentPresets = preferences.filterPresets(sourceId).get()
        val presetToDelete = currentPresets.presets.find { it.id == presetId }
        val updatedPresets = currentPresets.presets.filter { it.id != presetId }
        preferences.filterPresets(sourceId).set(
            FilterPresetList(updatedPresets),
        )
        val queries = preferences.filterPresetQueries(sourceId)
        if (presetId in queries.get()) queries.set(queries.get() - presetId)
        logcat(LogPriority.INFO) { "FilterPresets: Deleted preset ${presetToDelete?.name ?: presetId}" }
    }

    fun setDefaultPreset(sourceId: Long, presetId: Long?) {
        val currentPresets = preferences.filterPresets(sourceId).get()
        val updatedPresets = currentPresets.presets.map { preset ->
            preset.copy(isDefault = preset.id == presetId)
        }
        preferences.filterPresets(sourceId).set(
            FilterPresetList(updatedPresets),
        )
        val defaultName = updatedPresets.find { it.isDefault }?.name
        logcat(LogPriority.INFO) { "FilterPresets: Set default preset to: $defaultName" }
    }

    fun getDefaultPresetState(sourceId: Long): String? {
        val preset = getPresets(sourceId).presets.find { it.isDefault }
        return preset?.filterState
    }

    fun getAutoApplyEnabled(): Boolean {
        return preferences.autoApplyFilterPresets.get()
    }

    fun setAutoApplyEnabled(enabled: Boolean) {
        preferences.autoApplyFilterPresets.set(enabled)
        logcat(LogPriority.INFO) { "FilterPresets: Auto-apply presets set to $enabled" }
    }

    companion object {
        fun serializeFilters(filters: FilterList): String {
            val filterStates = filters.mapIndexed { index, filter ->
                val stateJson = when (filter) {
                    is Filter.CheckBox -> JsonPrimitive(filter.state)
                    is Filter.TriState -> JsonPrimitive(filter.state)
                    is Filter.Text -> JsonPrimitive(filter.state)
                    is Filter.Select<*> -> JsonPrimitive(filter.state)
                    is Filter.Sort -> JsonObject(
                        filter.state?.let {
                            mapOf("index" to JsonPrimitive(it.index), "ascending" to JsonPrimitive(it.ascending))
                        }.orEmpty(),
                    )
                    is Filter.Group<*> -> JsonArray(
                        filter.state.mapIndexed { i, groupFilter ->
                            val name = if (groupFilter is Filter<*>) {
                                groupFilter.name
                            } else {
                                ""
                            }
                            val groupState = when (groupFilter) {
                                is Filter.CheckBox -> JsonPrimitive(groupFilter.state)
                                is Filter.TriState -> JsonPrimitive(groupFilter.state)
                                else -> JsonNull
                            }
                            JsonObject(
                                mapOf(
                                    "index" to JsonPrimitive(i),
                                    "name" to JsonPrimitive(name),
                                    "state" to groupState,
                                ),
                            )
                        },
                    )
                    else -> JsonNull
                }
                val entry = mutableMapOf(
                    "index" to JsonPrimitive(index),
                    "name" to JsonPrimitive(filter.name),
                    "state" to stateJson,
                )
                if (filter is Filter.Select<*>) {
                    selectOptionName(filter, filter.state)?.let { entry["value"] = JsonPrimitive(it) }
                }
                JsonObject(entry)
            }
            return JsonArray(filterStates).toString()
        }

        /**
         * Restores saved state by filter name, so filters a source added, removed or reordered since
         * the preset was saved are skipped or still matched instead of receiving another filter's state.
         */
        fun applyPresetState(filters: FilterList, presetState: String) {
            val states = try {
                Json.parseToJsonElement(presetState).jsonArray
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "FilterPresets: Failed to parse preset: ${e.message}" }
                return
            }
            val slots = NameSlots(filters.map { it.name })
            states.forEach { element ->
                try {
                    val entry = element.jsonObject
                    val state = entry["state"] ?: JsonNull
                    val name = entry["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    // Stateless entries still claim their slot so a same-named filter after them keeps its own.
                    val filter = slots.next(name) { filters[it].holds(state) }?.let { filters[it] } ?: return@forEach
                    if (state !is JsonNull) applyFilterState(filter, entry, state)
                } catch (e: Exception) {
                    logcat(LogPriority.WARN) { "FilterPresets: Skipped a filter: ${e.message}" }
                }
            }
        }

        private fun applyFilterState(filter: Filter<*>, entry: JsonObject, state: JsonElement) {
            when (filter) {
                is Filter.CheckBox -> state.asPrimitive()?.booleanOrNull?.let { filter.state = it }
                is Filter.TriState -> state.asPrimitive()?.intOrNull?.takeIf { it in 0..2 }?.let { filter.state = it }
                is Filter.Text -> state.asPrimitive()?.takeIf { it.isString }?.let { filter.state = it.content }
                is Filter.Select<*> -> selectIndex(filter, entry, state)?.let { filter.state = it }
                is Filter.Sort -> {
                    val sort = state as? JsonObject ?: return
                    if (sort.isEmpty()) {
                        filter.state = null
                        return
                    }
                    val index = sort["index"]?.asPrimitive()?.intOrNull ?: return
                    if (index !in filter.values.indices) return
                    val ascending = sort["ascending"]?.asPrimitive()?.booleanOrNull ?: true
                    filter.state = Filter.Sort.Selection(index, ascending)
                }
                is Filter.Group<*> -> {
                    val items = filter.state
                    val slots = NameSlots(items.map { (it as? Filter<*>)?.name.orEmpty() })
                    (state as? JsonArray)?.forEach { element ->
                        val item = element as? JsonObject ?: return@forEach
                        val name = item["name"]?.asPrimitive()?.contentOrNull ?: return@forEach
                        val itemState = item["state"]?.asPrimitive()
                        val slot = slots.next(name) {
                            val stateful = items[it] is Filter.CheckBox || items[it] is Filter.TriState
                            stateful == (itemState != null)
                        }
                        when (val groupFilter = slot?.let { items[it] }) {
                            is Filter.CheckBox -> itemState?.booleanOrNull?.let { groupFilter.state = it }
                            is Filter.TriState ->
                                itemState?.intOrNull?.takeIf { it in 0..2 }?.let { groupFilter.state = it }
                            else -> Unit
                        }
                    }
                }
                else -> Unit
            }
        }

        private fun selectOptionName(filter: Filter.Select<*>, index: Int): String? =
            filter.values.getOrNull(index)?.toString()

        private fun selectIndex(filter: Filter.Select<*>, entry: JsonObject, state: JsonElement): Int? {
            val savedIndex = state.asPrimitive()?.intOrNull?.takeIf { it in filter.values.indices }
            val savedName = entry["value"]?.asPrimitive()?.contentOrNull ?: return savedIndex
            if (savedIndex != null && filter.values[savedIndex].toString() == savedName) return savedIndex
            val byName = filter.values.indexOfFirst { it.toString() == savedName }
            return when {
                byName >= 0 -> byName
                // Options without their own toString() get a new name every launch.
                IDENTITY_NAME.matches(savedName) -> savedIndex
                else -> null
            }
        }

        private fun Filter<*>.holds(state: JsonElement): Boolean = when (this) {
            is Filter.CheckBox, is Filter.TriState, is Filter.Text, is Filter.Select<*> ->
                state is JsonPrimitive && state !is JsonNull
            is Filter.Sort -> state is JsonObject
            is Filter.Group<*> -> state is JsonArray
            else -> state is JsonNull
        }

        private val IDENTITY_NAME = Regex("""[\w.$]+@[0-9a-f]+""")

        private fun JsonElement.asPrimitive(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

        private class NameSlots(names: List<String>) {
            private val slots = names.withIndex()
                .groupBy({ it.value }, { it.index })
                .mapValues { ArrayDeque(it.value) }

            fun next(name: String, fits: (Int) -> Boolean): Int? {
                val queue = slots[name] ?: return null
                val index = queue.firstOrNull(fits) ?: return null
                queue.remove(index)
                return index
            }
        }
    }
}
