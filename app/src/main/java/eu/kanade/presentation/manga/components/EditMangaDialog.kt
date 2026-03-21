package eu.kanade.presentation.manga.components

import android.R.attr.label
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Star
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.TabTitle
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun EditMangaDialog(
    manga: Manga,
    onDismissRequest: () -> Unit,
    onSaveTitle: (String) -> Unit,
    onSaveDescription: (String) -> Unit,
    onSaveUrl: (String) -> Unit,
    onSaveTags: (List<String>) -> Unit,
    onSaveAltTitles: (List<String>) -> Unit,
    onSaveAuthor: (String) -> Unit,
    onSaveStatus: (Long) -> Unit,
    onSwapMainTitle: ((newMainTitle: String, updatedAltTitles: List<String>) -> Unit)? = null,
) {
    var title by remember { mutableStateOf(manga.title) }
    var description by remember { mutableStateOf(manga.description.orEmpty()) }
    var url by remember { mutableStateOf(manga.url) }
    var tags by remember { mutableStateOf(manga.genre.orEmpty()) }
    var altTitles by remember { mutableStateOf(manga.alternativeTitles) }
    var author by remember { mutableStateOf(manga.author.orEmpty()) }
    var status by remember { mutableStateOf(manga.status) }

    val tabTitles = persistentListOf(
        TabTitle.Text(stringResource(MR.strings.pref_category_general)),
        TabTitle.Text("Description"),
    )

    TabbedDialog(
        onDismissRequest = {
            onSaveTitle(title)
            onSaveDescription(description)
            onSaveUrl(url)
            onSaveTags(tags)
            onSaveAltTitles(altTitles)
            onSaveAuthor(author)
            onSaveStatus(status)
            onDismissRequest()
        },
        tabTitles = tabTitles,
    ) { page ->
        Column(
            modifier = Modifier
                .padding(
                    horizontal = TabbedDialogPaddings.Horizontal,
                    vertical = TabbedDialogPaddings.Vertical,
                )
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    minLines = 8,
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
                        onClick = {
                            onAltTitlesChange(altTitles.toMutableList().apply { removeAt(index) })
                        },
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
}
