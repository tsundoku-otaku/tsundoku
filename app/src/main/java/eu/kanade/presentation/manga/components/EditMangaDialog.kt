package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.TabTitle
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.components.imeAwareDialogProperties
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun EditMangaDialog(
    manga: Manga,
    sourceInfo: CustomMangaInfo? = null,
    onDismissRequest: () -> Unit,
    onSaveTitle: (String) -> Unit,
    onSaveUrl: (String) -> Unit,
    onSaveAltTitles: (List<String>) -> Unit,
    onSaveInfo: (
        description: String,
        tags: List<String>,
        author: String,
        artist: String,
        status: Long,
    ) -> Unit,
    onSwapMainTitle: ((newMainTitle: String, updatedAltTitles: List<String>) -> Unit)? = null,
) {
    var title by remember { mutableStateOf(manga.title) }
    var description by remember { mutableStateOf(manga.description.orEmpty()) }
    var url by remember { mutableStateOf(manga.url) }
    var tags by remember { mutableStateOf(manga.genre.orEmpty()) }
    var altTitles by remember { mutableStateOf(manga.alternativeTitles) }
    var author by remember { mutableStateOf(manga.author.orEmpty()) }
    var artist by remember { mutableStateOf(manga.artist.orEmpty()) }
    var status by remember { mutableStateOf(manga.status) }

    val tabTitles = listOf(
        TabTitle.Text(stringResource(MR.strings.pref_category_general)),
        TabTitle.Text("Description"),
    )

    TabbedDialog(
        onDismissRequest = {
            onSaveTitle(title)
            onSaveUrl(url)
            onSaveAltTitles(altTitles)
            onSaveInfo(description, tags, author, artist, status)
            onDismissRequest()
        },
        tabTitles = tabTitles,
        properties = imeAwareDialogProperties,
    ) { page ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(
                    horizontal = TabbedDialogPaddings.Horizontal,
                    vertical = TabbedDialogPaddings.Vertical,
                )
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sourceInfo != null) {
                        SourceValuesSection(sourceInfo, manga)
                    }
                    EditTextField(
                        label = stringResource(MR.strings.title),
                        value = title,
                        onValueChange = { title = it },
                    )
                    EditTextField(
                        label = stringResource(TDMR.strings.js_plugin_repo_url),
                        value = url,
                        onValueChange = { url = it },
                        keyboardType = KeyboardType.Uri,
                        singleLine = false,
                        maxLines = 3,
                    )
                    EditTextField(
                        label = stringResource(MR.strings.author),
                        value = author,
                        onValueChange = { author = it },
                    )
                    EditTextField(
                        label = stringResource(MR.strings.artist),
                        value = artist,
                        onValueChange = { artist = it },
                    )
                    EditStatusField(
                        status = status,
                        onStatusChange = { status = it },
                    )
                    EditTagsTab(
                        tags = tags,
                        onTagsChange = { tags = it },
                    )
                    EditAltTitlesTab(
                        mainTitle = manga.title,
                        altTitles = altTitles,
                        onAltTitlesChange = { altTitles = it },
                        onSwapMainTitle = onSwapMainTitle?.let { swap ->
                            { newMain, updatedAlts ->
                                swap(newMain, updatedAlts)
                                onDismissRequest()
                            }
                        },
                    )
                }
                1 -> EditTextField(
                    label = "Description",
                    value = description,
                    onValueChange = { description = it },
                    minLines = 1,
                    maxLines = 16,
                )
            }
        }
    }
}

@Composable
private fun EditTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    minLines: Int = 1,
    maxLines: Int = 1,
    singleLine: Boolean = maxLines == 1,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        maxLines = maxLines,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable
private fun SourceValuesSection(source: CustomMangaInfo, current: Manga) {
    // Only the fields the user actually changed, shown with their original source value.
    val changedRows = buildList {
        if (source.author.orEmpty() != current.author.orEmpty()) {
            add(stringResource(MR.strings.author) to source.author)
        }
        if (source.artist.orEmpty() != current.artist.orEmpty()) {
            add(stringResource(MR.strings.artist) to source.artist)
        }
        if (source.status != null && source.status != current.status) {
            add(stringResource(MR.strings.status) to statusLabel(source.status))
        }
        if (source.genre?.toSet() != current.genre?.toSet()) {
            add(stringResource(TDMR.strings.edit_label_tags) to source.genre?.joinToString(", "))
        }
        if (source.description.orEmpty() != current.description.orEmpty()) {
            add(stringResource(TDMR.strings.edit_label_description) to source.description)
        }
    }

    if (changedRows.isEmpty()) return

    val noneLabel = stringResource(MR.strings.none)
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(TDMR.strings.edit_original_source_values),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
            )
        }
        if (expanded) {
            changedRows.forEach { (label, value) ->
                SourceValueRow(
                    label,
                    value.takeUnless { it.isNullOrBlank() } ?: noneLabel,
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun SourceValueRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun statusLabel(status: Long?): String? = when (status?.toInt()) {
    null -> null
    SManga.ONGOING -> stringResource(MR.strings.ongoing)
    SManga.COMPLETED -> stringResource(MR.strings.completed)
    SManga.LICENSED -> stringResource(MR.strings.licensed)
    SManga.PUBLISHING_FINISHED -> stringResource(MR.strings.publishing_finished)
    SManga.CANCELLED -> stringResource(MR.strings.cancelled)
    SManga.ON_HIATUS -> stringResource(MR.strings.on_hiatus)
    else -> stringResource(MR.strings.unknown)
}

@Composable
private fun EditStatusField(
    status: Long,
    onStatusChange: (Long) -> Unit,
) {
    val statusOptions = listOf(
        SManga.UNKNOWN to "Unknown",
        SManga.ONGOING to "Ongoing",
        SManga.COMPLETED to "Completed",
        SManga.LICENSED to "Licensed",
        SManga.PUBLISHING_FINISHED to "Publishing finished",
        SManga.CANCELLED to "Cancelled",
        SManga.ON_HIATUS to "On hiatus",
    )

    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = statusOptions.find { it.first.toLong() == status }?.second ?: "Unknown"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(MR.strings.track_status)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            statusOptions.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onStatusChange(value.toLong())
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EditTagsTab(
    tags: List<String>,
    onTagsChange: (List<String>) -> Unit,
) {
    var newTag by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newTag,
                onValueChange = { newTag = it },
                label = { Text("New tag") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            IconButton(
                onClick = {
                    if (newTag.isNotBlank()) {
                        onTagsChange(tags + newTag.trim())
                        newTag = ""
                    }
                },
                enabled = newTag.isNotBlank(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Add")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { onTagsChange(tags - tag) },
                    label = { Text(tag) },
                    trailingIcon = {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                    },
                )
            }
        }
    }
}

@Composable
private fun EditAltTitlesTab(
    mainTitle: String,
    altTitles: List<String>,
    onAltTitlesChange: (List<String>) -> Unit,
    onSwapMainTitle: ((String, List<String>) -> Unit)? = null,
) {
    var newTitle by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Int?>(null) }
    val clipboardManager = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                modifier = Modifier.weight(1f),
                label = { Text("New title") },
                singleLine = true,
            )
            IconButton(
                onClick = {
                    if (newTitle.isNotBlank()) {
                        onAltTitlesChange(altTitles + newTitle.trim())
                        newTitle = ""
                    }
                },
                enabled = newTitle.isNotBlank(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Add")
            }
        }

        HorizontalDivider()

        if (altTitles.isEmpty()) {
            Text(
                text = "No alternative titles",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            altTitles.forEachIndexed { index, title ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(
                        onClick = { clipboardManager.setText(AnnotatedString(title)) },
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
                        )
                    }
                    if (onSwapMainTitle != null && mainTitle.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val updatedAlts = altTitles.toMutableList().apply {
                                    removeAt(index)
                                    add(0, mainTitle)
                                }
                                onSwapMainTitle(title, updatedAlts)
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Star,
                                contentDescription = "Make main title",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(
                        onClick = { pendingDelete = index },
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { index ->
        val title = altTitles.getOrNull(index)
        if (title == null) {
            pendingDelete = null
            return@let
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(MR.strings.action_delete)) },
            text = { Text(title) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAltTitlesChange(altTitles.toMutableList().apply { removeAt(index) })
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
