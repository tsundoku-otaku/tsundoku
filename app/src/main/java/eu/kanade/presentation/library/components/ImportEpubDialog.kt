package eu.kanade.presentation.library.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hippo.unifile.UniFile
import eu.kanade.domain.manga.interactor.ImportEpub
import eu.kanade.domain.manga.interactor.ParseEpubPreview
import eu.kanade.presentation.category.visualName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class EpubFileInfo(
    val uri: Uri,
    val fileName: String,
    val title: String,
    val author: String?,
    val description: String?,
    val coverUri: Uri? = null,
    val collection: String? = null,
    val collectionPosition: Int? = null,
    val genres: String? = null,
    val tableOfContents: List<String> = emptyList(),
)

data class ImportProgress(
    val current: Int,
    val total: Int,
    val currentFileName: String,
    val isRunning: Boolean,
)

data class ImportResult(
    val successCount: Int,
    val errorCount: Int,
    val errors: List<String>,
)

@Composable
fun ImportEpubDialog(
    onDismissRequest: () -> Unit,
    onImportComplete: (success: Int, errors: Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedFiles = remember { mutableStateListOf<EpubFileInfo>() }
    var customTitle by remember { mutableStateOf("") }
    var combineAsOneNovel by remember { mutableStateOf(false) }
    var autoAddToLibrary by remember { mutableStateOf(true) }
    var selectedCategoryId by remember { mutableStateOf<Long?>(null) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }

    var importProgress by remember { mutableStateOf<ImportProgress?>(null) }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }

    val getCategories = remember { Injekt.get<GetCategories>() }
    val importEpub = remember { ImportEpub() }
    val parseEpubPreview = remember { ParseEpubPreview() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        categories = withContext(Dispatchers.IO) {
            getCategories.await().filter {
                it.contentType == Category.CONTENT_TYPE_ALL || it.contentType == Category.CONTENT_TYPE_NOVEL
            }
        }
        if (selectedCategoryId == null) {
            selectedCategoryId = categories.firstOrNull()?.id
        }
    }

    // File picker for multiple EPUB files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val parsed = withContext(Dispatchers.IO) {
                    parseEpubPreview.parseSelected(context, uris)
                }
                parsed.errors.forEach {
                    android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
                }

                val fileInfos = parsed.files.map {
                    EpubFileInfo(
                        uri = it.uri,
                        fileName = it.fileName,
                        title = it.title,
                        author = it.author,
                        description = it.description,
                        coverUri = it.coverUri,
                        collection = it.collection,
                        collectionPosition = it.collectionPosition,
                        genres = it.genres,
                        tableOfContents = it.tableOfContents,
                    )
                }

                selectedFiles.clear()
                selectedFiles.addAll(fileInfos)

                customTitle = parseEpubPreview.defaultCustomTitle(parsed.files)
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (importProgress?.isRunning != true) {
                onDismissRequest()
            }
        },
        title = { Text(stringResource(TDMR.strings.epub_import_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    importResult != null -> {
                        ImportResultView(importResult!!)
                    }
                    importProgress != null -> {
                        ImportProgressView(importProgress!!)
                    }
                    else -> {
                        // Selection UI
                        OutlinedButton(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.FileOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(TDMR.strings.epub_select_files))
                        }

                        if (selectedFiles.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))

                            // Show selected files
                            Text(
                                text = stringResource(TDMR.strings.epub_selected_files),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            selectedFiles.forEach { file ->
                                Text(
                                    text = "• ${file.fileName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }

                            Spacer(Modifier.height(16.dp))

                            // Custom title (for single file or combined)
                            if (selectedFiles.size == 1 || combineAsOneNovel) {
                                OutlinedTextField(
                                    value = customTitle,
                                    onValueChange = { customTitle = it },
                                    label = { Text(stringResource(TDMR.strings.epub_novel_title)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            // Combine option for multiple files
                            if (selectedFiles.size > 1) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            combineAsOneNovel = !combineAsOneNovel
                                            if (combineAsOneNovel && customTitle.isBlank()) {
                                                customTitle = selectedFiles.firstOrNull()?.fileName
                                                    ?.substringBeforeLast('.', selectedFiles.first().fileName)
                                                    .orEmpty()
                                            }
                                        },
                                ) {
                                    Checkbox(
                                        checked = combineAsOneNovel,
                                        onCheckedChange = {
                                            combineAsOneNovel = it
                                            if (it && customTitle.isBlank()) {
                                                customTitle = selectedFiles.firstOrNull()?.fileName
                                                    ?.substringBeforeLast('.', selectedFiles.first().fileName)
                                                    .orEmpty()
                                            }
                                        },
                                    )
                                    Text(stringResource(TDMR.strings.epub_combine_files))
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            // Optional: auto-add imported local novels to library and category
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { autoAddToLibrary = !autoAddToLibrary },
                            ) {
                                Checkbox(
                                    checked = autoAddToLibrary,
                                    onCheckedChange = { autoAddToLibrary = it },
                                )
                                Text(stringResource(TDMR.strings.epub_auto_add_to_category))
                            }

                            if (autoAddToLibrary) {
                                val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }
                                OutlinedButton(
                                    onClick = { categoryMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = selectedCategory?.visualName
                                            ?: stringResource(TDMR.strings.epub_select_category),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                                }
                                DropdownMenu(
                                    expanded = categoryMenuExpanded,
                                    onDismissRequest = { categoryMenuExpanded = false },
                                ) {
                                    categories.forEach { category ->
                                        DropdownMenuItem(
                                            text = { Text(category.visualName) },
                                            onClick = {
                                                selectedCategoryId = category.id
                                                categoryMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            Text(
                                text = stringResource(TDMR.strings.epub_local_novels_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                importResult != null -> {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(MR.strings.action_close))
                    }
                }
                importProgress != null -> {
                    // No button while importing
                }
                else -> {
                    TextButton(
                        onClick = {
                            if (selectedFiles.isNotEmpty()) {
                                scope.launch {
                                    importProgress = ImportProgress(0, selectedFiles.size, "", true)
                                    val result = importEpub.execute(
                                        context = context,
                                        files = selectedFiles.map {
                                            ImportEpub.ImportFile(
                                                uri = it.uri,
                                                fileName = it.fileName,
                                                title = it.title,
                                                author = it.author,
                                                description = it.description,
                                                coverUri = it.coverUri,
                                                genres = it.genres,
                                            )
                                        },
                                        customTitle = customTitle.ifBlank { null },
                                        combineAsOne = combineAsOneNovel,
                                        autoAddToLibrary = autoAddToLibrary,
                                        categoryId = selectedCategoryId,
                                        onProgress = { current, total, fileName ->
                                            importProgress = ImportProgress(current, total, fileName, true)
                                        },
                                    )
                                    importProgress = null
                                    importResult = ImportResult(
                                        successCount = result.successCount,
                                        errorCount = result.errorCount,
                                        errors = result.errors,
                                    )
                                    onImportComplete(result.successCount, result.errorCount)
                                }
                            }
                        },
                        enabled = selectedFiles.isNotEmpty(),
                    ) {
                        Text(stringResource(TDMR.strings.epub_action_import))
                    }
                }
            }
        },
        dismissButton = {
            if (importProgress?.isRunning != true && importResult == null) {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            }
        },
    )
}

@Composable
private fun ImportProgressView(progress: ImportProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(TDMR.strings.epub_importing_progress, progress.current, progress.total),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress.current.toFloat() / progress.total.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = progress.currentFileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportResultView(result: ImportResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(TDMR.strings.epub_import_complete),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "✓ " + stringResource(TDMR.strings.epub_import_success_count, result.successCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (result.errorCount > 0) {
            Text(
                text = "✗ " + stringResource(TDMR.strings.epub_import_error_count, result.errorCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            result.errors.forEach { error ->
                Text(
                    text = "• $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
