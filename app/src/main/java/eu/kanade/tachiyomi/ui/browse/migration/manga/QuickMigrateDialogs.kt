package eu.kanade.tachiyomi.ui.browse.migration.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.CatalogueSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun QuickMigrateSourcePickerDialog(
    defaultIsNovel: Boolean,
    getSources: (Boolean) -> List<CatalogueSource>,
    onSourceSelected: (Long) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var showNovel by remember { mutableStateOf(defaultIsNovel) }
    val sources = remember(showNovel) { getSources(showNovel) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.quick_migrate_select_source)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "This option is for migrating between the same URL/extension, " +
                        "or when moving from JS to KT extensions. " +
                        "Cannot be used to migrate to completely different extensions.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                Row(modifier = Modifier.padding(bottom = 8.dp)) {
                    FilterChip(
                        selected = !showNovel,
                        onClick = { showNovel = false },
                        label = { Text("Manga") },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = showNovel,
                        onClick = { showNovel = true },
                        label = { Text("Novel") },
                    )
                }

                if (sources.isEmpty()) {
                    Text(
                        text = "No sources available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }

                sources.forEach { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSourceSelected(source.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = false,
                            onClick = { onSourceSelected(source.id) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = source.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = source.lang,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
fun QuickMigrateConfirmDialog(
    sourceName: String,
    targetSourceName: String,
    totalCount: Int,
    skipCount: Int,
    onConfirm: (String?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var createCategory by remember { mutableStateOf(true) }
    var categoryName by remember { mutableStateOf("$sourceName - $targetSourceName") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.action_quick_migrate)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        MR.strings.quick_migrate_confirm_message,
                        targetSourceName,
                        totalCount,
                    ),
                )

                if (skipCount > 0) {
                    Text(
                        text = stringResource(MR.strings.quick_migrate_skip_message, skipCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .clickable { createCategory = !createCategory },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = createCategory,
                        onCheckedChange = { createCategory = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Add to new category")
                }

                if (createCategory) {
                    OutlinedTextField(
                        value = categoryName,
                        onValueChange = { categoryName = it },
                        label = { Text("Category name") },
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (createCategory) categoryName else null) }) {
                Text(text = stringResource(MR.strings.migrate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
