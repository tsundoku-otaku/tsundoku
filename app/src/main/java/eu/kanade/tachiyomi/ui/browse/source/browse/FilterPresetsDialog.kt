package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.source.model.FilterPreset
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun FilterPresetsDialog(
    onDismissRequest: () -> Unit,
    presets: List<FilterPreset>,
    currentSearchQuery: String?,
    autoApplyEnabled: Boolean,
    onSavePreset: (name: String, setAsDefault: Boolean, includeSearchText: Boolean) -> Unit,
    onLoadPreset: (Long) -> Unit,
    onDeletePreset: (Long) -> Unit,
    onSetDefaultPreset: (Long?) -> Unit,
    onToggleAutoApply: (Boolean) -> Unit,
) {
    var showSaveDialog by remember { mutableStateOf(false) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        LazyColumn(
            modifier = Modifier.padding(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(TDMR.strings.filter_presets_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = autoApplyEnabled,
                        onCheckedChange = onToggleAutoApply,
                    )
                    Text(
                        text = stringResource(TDMR.strings.filter_preset_auto_apply),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            item {
                Button(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    Text(stringResource(TDMR.strings.filter_preset_save_current))
                }
            }

            items(presets) { preset ->
                PresetItem(
                    preset = preset,
                    onLoad = { onLoadPreset(preset.id) },
                    onDelete = { onDeletePreset(preset.id) },
                    onSetDefault = {
                        val newDefaultId = if (preset.isDefault) null else preset.id
                        onSetDefaultPreset(newDefaultId)
                    },
                )
            }

            if (presets.isEmpty()) {
                item {
                    Text(
                        text = stringResource(TDMR.strings.filter_preset_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            hasSearchText = !currentSearchQuery.isNullOrBlank(),
            onDismiss = { showSaveDialog = false },
            onSave = { name, setAsDefault, includeSearchText ->
                onSavePreset(name, setAsDefault, includeSearchText)
                showSaveDialog = false
            },
        )
    }
}

@Composable
private fun PresetItem(
    preset: FilterPreset,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLoad)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (preset.isDefault) {
                Text(
                    text = stringResource(TDMR.strings.filter_preset_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            preset.searchQuery?.let { query ->
                Text(
                    text = stringResource(TDMR.strings.filter_preset_search_text, query),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(TDMR.strings.filter_preset_search_not_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row {
            // Auto-apply only restores filters, so a preset with search text can't be the default
            IconButton(
                onClick = onSetDefault,
                enabled = preset.isDefault || preset.searchQuery == null,
            ) {
                Icon(
                    imageVector = if (preset.isDefault) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (preset.isDefault) {
                            TDMR.strings.filter_preset_remove_default
                        } else {
                            TDMR.strings.filter_preset_set_default
                        },
                    ),
                    tint = if (preset.isDefault) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(TDMR.strings.filter_preset_delete),
                )
            }
        }
    }
}

@Composable
internal fun SavePresetDialog(
    hasSearchText: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Boolean, Boolean) -> Unit,
) {
    var presetName by remember { mutableStateOf("") }
    var setAsDefault by remember { mutableStateOf(false) }
    var includeSearchText by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.filter_preset_save_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(TDMR.strings.filter_preset_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = setAsDefault,
                        onCheckedChange = { setAsDefault = it },
                    )
                    Text(
                        text = stringResource(TDMR.strings.filter_preset_set_default),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                if (hasSearchText && !setAsDefault) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = includeSearchText,
                            onCheckedChange = { includeSearchText = it },
                        )
                        Text(
                            text = stringResource(TDMR.strings.filter_preset_include_search_text),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(presetName, setAsDefault, includeSearchText) },
                enabled = presetName.isNotBlank(),
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
