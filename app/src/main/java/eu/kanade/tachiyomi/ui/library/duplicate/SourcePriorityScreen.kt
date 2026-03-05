package eu.kanade.tachiyomi.ui.library.duplicate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import kotlinx.coroutines.launch
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SourcePriorityScreen : Screen {

    private fun readResolve(): Any = SourcePriorityScreen

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { SourcePriorityScreenModel() }
        val state by screenModel.state.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.duplicate_source_priority)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = null,
                            )
                        }
                    },
                )
            },
        ) { contentPadding ->
            val sourceTypes = listOf(
                DuplicateDetectionScreenModel.SourceType.JS to stringResource(MR.strings.duplicate_source_type_js_extensions),
                DuplicateDetectionScreenModel.SourceType.KT to stringResource(MR.strings.duplicate_source_type_kt_extensions),
                DuplicateDetectionScreenModel.SourceType.CUSTOM to stringResource(MR.strings.duplicate_source_type_custom_extensions),
                DuplicateDetectionScreenModel.SourceType.LOCAL to stringResource(MR.strings.duplicate_source_type_local_source),
            )

            LazyColumn(
                contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Source Type Priority section
                item(key = "type_header") {
                    Text(
                        text = stringResource(MR.strings.duplicate_source_priority),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    Text(
                        text = stringResource(MR.strings.duplicate_source_priority_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                items(sourceTypes, key = { it.first.name }) { (type, label) ->
                    val priority = state.typePriorities[type] ?: 0
                    PrioritySliderRow(
                        label = label,
                        priority = priority,
                        onPriorityChange = { screenModel.setTypePriority(type, it) },
                    )
                }

                item(key = "divider") {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Specific Source Priority section
                item(key = "specific_header") {
                    Text(
                        text = stringResource(MR.strings.duplicate_specific_source_priority),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Text(
                        text = stringResource(MR.strings.duplicate_specific_source_priority_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                items(state.sourceItems, key = { it.id }) { sourceItem ->
                    val priority = state.sourcePriorities[sourceItem.id] ?: 0
                    PrioritySliderRow(
                        label = sourceItem.displayName,
                        priority = priority,
                        onPriorityChange = { screenModel.setSourcePriority(sourceItem.id, it) },
                    )
                }

                if (state.sourceItems.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(MR.strings.duplicate_no_sources_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun PrioritySliderRow(
    label: String,
    priority: Int,
    onPriorityChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(120.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = priority.toFloat(),
            onValueChange = { onPriorityChange(it.toInt()) },
            valueRange = -5f..5f,
            steps = 9,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = priority.toString(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.End,
        )
    }
}

data class SourcePriorityItem(
    val id: Long,
    val displayName: String,
)

class SourcePriorityScreenModel(
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) : StateScreenModel<SourcePriorityScreenModel.State>(State()) {

    data class State(
        val typePriorities: Map<DuplicateDetectionScreenModel.SourceType, Int> =
            DuplicateDetectionScreenModel.SourceType.entries.associateWith { 0 },
        val sourcePriorities: Map<Long, Int> = emptyMap(),
        val sourceItems: List<SourcePriorityItem> = emptyList(),
    )

    init {
        loadTypePriorities()
        loadSourcePriorities()
        loadSourceItems()
    }

    private fun loadTypePriorities() {
        val raw = libraryPreferences.sourceTypePriorities().get()
        if (raw.isBlank()) return
        val map = raw.split(";").filter { it.isNotBlank() }.mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                try {
                    DuplicateDetectionScreenModel.SourceType.valueOf(parts[0]) to parts[1].toInt()
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }.toMap()
        mutableState.value = state.value.copy(
            typePriorities = DuplicateDetectionScreenModel.SourceType.entries.associateWith { map[it] ?: 0 },
        )
    }

    private fun loadSourcePriorities() {
        val raw = libraryPreferences.specificSourcePriorities().get()
        if (raw.isBlank()) return
        val map = raw.split(";").filter { it.isNotBlank() }.mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                try {
                    parts[0].toLong() to parts[1].toInt()
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }.toMap()
        mutableState.value = state.value.copy(sourcePriorities = map)
    }

    private fun loadSourceItems() {
        screenModelScope.launch {
            sourceManager.catalogueSources.collect { catalogueSources ->
                val items = catalogueSources
                    .filter { !it.isLocal() }
                    .map { source ->
                        val isJs = source is JsSource
                        val suffix = if (isJs) " (JS)" else ""
                        SourcePriorityItem(
                            id = source.id,
                            displayName = "${source.name}$suffix",
                        )
                    }
                    .sortedBy { it.displayName.lowercase() }
                mutableState.value = state.value.copy(sourceItems = items)
            }
        }
    }

    fun setTypePriority(type: DuplicateDetectionScreenModel.SourceType, priority: Int) {
        val newMap = state.value.typePriorities + (type to priority)
        mutableState.value = state.value.copy(typePriorities = newMap)
        saveTypePriorities(newMap)
    }

    fun setSourcePriority(sourceId: Long, priority: Int) {
        val newMap = if (priority == 0) {
            state.value.sourcePriorities - sourceId
        } else {
            state.value.sourcePriorities + (sourceId to priority)
        }
        mutableState.value = state.value.copy(sourcePriorities = newMap)
        saveSourcePriorities(newMap)
    }

    private fun saveTypePriorities(map: Map<DuplicateDetectionScreenModel.SourceType, Int>) {
        val serialized = map.entries
            .filter { it.value != 0 }
            .joinToString(";") { "${it.key.name}:${it.value}" }
        libraryPreferences.sourceTypePriorities().set(serialized)
    }

    private fun saveSourcePriorities(map: Map<Long, Int>) {
        val serialized = map.entries
            .filter { it.value != 0 }
            .joinToString(";") { "${it.key}:${it.value}" }
        libraryPreferences.specificSourcePriorities().set(serialized)
    }
}
