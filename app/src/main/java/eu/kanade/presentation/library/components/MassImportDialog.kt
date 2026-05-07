package eu.kanade.presentation.library.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.interactor.MassImport
import eu.kanade.presentation.category.visualName
import eu.kanade.tachiyomi.data.massimport.MassImportJob
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast
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

@Composable
fun MassImportDialog(
    onDismissRequest: () -> Unit,
    onImportComplete: (added: Int, skipped: Int, errored: Int) -> Unit,
    initialText: String = "",
    isNovelMode: Boolean = true, // Default to novel mode for backward compatibility
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val dialogScope = rememberCoroutineScope()
    var pendingUrls by remember { mutableStateOf(initialText) }
    var urlText by remember { mutableStateOf("") }
    var showClearCompletedConfirm by remember { mutableStateOf(false) }
    var showClearPendingConfirm by remember { mutableStateOf(false) }
    var showCancelAllConfirm by remember { mutableStateOf(false) }

    val toastErrorReadingFile = stringResource(TDMR.strings.mass_import_toast_error_reading_file)
    val toastAddedUrlsFromFiles = stringResource(TDMR.strings.mass_import_toast_added_urls_from_files)
    val toastNoReadableUrls = stringResource(TDMR.strings.mass_import_toast_no_readable_urls)
    val toastReportSaved = stringResource(TDMR.strings.mass_import_toast_report_saved)
    val toastErrorSavingReport = stringResource(TDMR.strings.mass_import_toast_error_saving_report)
    val toastExportedUrls = stringResource(TDMR.strings.mass_import_toast_exported_urls)
    val toastErrorExportingUrls = stringResource(TDMR.strings.mass_import_toast_error_exporting_urls)
    val toastCopiedUrls = stringResource(TDMR.strings.mass_import_toast_copied_urls)
    val toastCopiedErrors = stringResource(TDMR.strings.mass_import_toast_copied_errors)
    val toastReportCopied = stringResource(TDMR.strings.mass_import_toast_report_copied)
    val toastRequeuedErrors = stringResource(TDMR.strings.mass_import_toast_requeued_errors)
    val toastRequeuedRemaining = stringResource(TDMR.strings.mass_import_toast_requeued_remaining)
    val clipboardUrlsLabel = stringResource(TDMR.strings.mass_import_clipboard_label_urls)
    val clipboardErrorsLabel = stringResource(TDMR.strings.mass_import_clipboard_label_errors)
    val clipboardReportLabel = stringResource(TDMR.strings.mass_import_clipboard_label_report)

    // File picker launcher for reading URLs from files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult

            var loadedFiles = 0
            val mergedBuilder = StringBuilder()

            uris.forEach { uri ->
                try {
                    var fileHadContent = false
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.useLines { lines ->
                            lines.forEach { rawLine ->
                                val line = rawLine.trim()
                                if (line.isNotBlank()) {
                                    if (mergedBuilder.isNotEmpty()) {
                                        mergedBuilder.append('\n')
                                    }
                                    mergedBuilder.append(line)
                                    fileHadContent = true
                                }
                            }
                        }

                    if (fileHadContent) {
                        loadedFiles++
                    }
                } catch (e: Exception) {
                    context.toast("${toastErrorReadingFile}: ${e.message.orEmpty()}")
                }
            }

            if (mergedBuilder.isNotEmpty()) {
                val merged = mergedBuilder.toString()
                pendingUrls = if (pendingUrls.isBlank()) {
                    merged
                } else {
                    "$pendingUrls\n$merged"
                }

                val addedCount = merged.lines().count { it.isNotBlank() }
                context.toast(String.format(toastAddedUrlsFromFiles, addedCount, loadedFiles))
            } else {
                context.toast(toastNoReadableUrls)
            }
        },
    )

    // State to track which batch report to save
    var batchToSave by remember { mutableStateOf<MassImportJob.Batch?>(null) }

    // State to track which batch to export URLs from
    var batchToExport by remember { mutableStateOf<MassImportJob.Batch?>(null) }

    // File save launcher for saving reports
    val saveReportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            uri?.let { outputUri ->
                batchToSave?.let { batch ->
                    try {
                        val report = MassImportJob.generateReport(batch)
                        context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                            outputStream.bufferedWriter().use { writer ->
                                writer.write(report)
                            }
                        }
                        context.toast(toastReportSaved)
                    } catch (e: Exception) {
                        context.toast("${toastErrorSavingReport}: ${e.message.orEmpty()}")
                    } finally {
                        batchToSave = null
                    }
                }
            }
        },
    )

    // File save launcher for exporting URLs
    val exportUrlsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            uri?.let { outputUri ->
                batchToExport?.let { batch ->
                    try {
                        val urls = MassImportJob.exportBatchUrls(batch)
                        context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                            outputStream.bufferedWriter().use { writer ->
                                writer.write(urls)
                            }
                        }
                                    context.toast(String.format(toastExportedUrls, batch.urls.size))
                    } catch (e: Exception) {
                                    context.toast("${toastErrorExportingUrls}: ${e.message.orEmpty()}")
                    } finally {
                        batchToExport = null
                    }
                }
            }
        },
    )

    var selectedCategoryId by remember { mutableStateOf<Long?>(null) }
    var categoryDropdownExpanded by remember { mutableStateOf(false) }
    var fetchDetails by remember { mutableStateOf(true) }
    var syncChapterList by remember { mutableStateOf(false) }

    val massImportNovels = remember { Injekt.get<MassImport>() }

    val queue by MassImportJob.sharedQueue.collectAsState()

    val getCategories = remember { Injekt.get<GetCategories>() }
    // Filter categories by content type (manga vs novel)
    val contentType = if (isNovelMode) Category.CONTENT_TYPE_NOVEL else Category.CONTENT_TYPE_MANGA
    val categories by getCategories.subscribeByContentType(contentType).collectAsState(initial = emptyList())

    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .fillMaxWidth(0.98f)
            .wrapContentHeight()
            .heightIn(max = 700.dp),
        title = { Text(stringResource(TDMR.strings.mass_import_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .verticalScroll(rememberScrollState()),
            ) {
                // Queue Section
                if (queue.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(TDMR.strings.mass_import_queue_title, queue.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row {
                            // Clear completed button
                            val hasCompleted = queue.any {
                                it.status == MassImportJob.BatchStatus.Completed ||
                                    it.status == MassImportJob.BatchStatus.Cancelled
                            }
                            if (hasCompleted) {
                                TextButton(
                                    onClick = { showClearCompletedConfirm = true },
                                ) {
                                    Text(stringResource(TDMR.strings.mass_import_clear_done), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            // Cancel all button
                            val hasActive = queue.any {
                                it.status == MassImportJob.BatchStatus.Pending ||
                                    it.status == MassImportJob.BatchStatus.Running
                            }
                            if (hasActive) {
                                TextButton(
                                    onClick = { showCancelAllConfirm = true },
                                ) {
                                    Text(stringResource(TDMR.strings.mass_import_cancel_all), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 250.dp)
                            .padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Use distinct batch IDs to prevent duplicates
                        val distinctBatches = queue.distinctBy { it.id }.reversed()
                        items(
                            items = distinctBatches,
                            key = { it.id },
                        ) { batch ->
                            BatchItem(
                                batch = batch,
                                onCancel = { MassImportJob.cancelBatch(context, batch.id) },
                                onCopyUrls = {
                                    context.copyToClipboard(clipboardUrlsLabel, batch.urls.joinToString("\n"))
                                    context.toast(String.format(toastCopiedUrls, batch.urls.size))
                                },
                                onCopyErrors = {
                                    // Include error messages with URLs
                                    val errors = MassImportJob.generateErrorsWithMessages(batch)
                                    if (errors.isNotBlank()) {
                                        context.copyToClipboard(clipboardErrorsLabel, errors)
                                        context.toast(String.format(toastCopiedErrors, batch.erroredUrls.size))
                                    }
                                },
                                onCopyReport = {
                                    val report = MassImportJob.generateReport(batch)
                                    context.copyToClipboard(clipboardReportLabel, report)
                                    context.toast(toastReportCopied)
                                },
                                onSaveReport = {
                                    batchToSave = batch
                                    val timestamp = java.text.SimpleDateFormat(
                                        "yyyyMMdd_HHmmss",
                                        java.util.Locale.getDefault(),
                                    ).format(java.util.Date())
                                    saveReportLauncher.launch("mass_import_report_$timestamp.txt")
                                },
                                onRemove = { MassImportJob.removeBatch(batch.id) },
                                onReinsertErrors = {
                                    MassImportJob.reinsertErrored(context, batch)
                                    context.toast(String.format(toastRequeuedErrors, batch.erroredUrls.size))
                                },
                                onExportUrls = {
                                    batchToExport = batch
                                    val timestamp = java.text.SimpleDateFormat(
                                        "yyyyMMdd_HHmmss",
                                        java.util.Locale.getDefault(),
                                    ).format(java.util.Date())
                                    exportUrlsLauncher.launch("mass_import_urls_$timestamp.txt")
                                },
                                onRequeue = {
                                    MassImportJob.requeueCancelled(context, batch)
                                    context.toast(toastRequeuedRemaining)
                                },
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))
                }

                // Add New Section
                Text(
                    text = stringResource(TDMR.strings.mass_import_add_new_batch),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                // Category Selection
                val userCategories = remember(categories) {
                    categories
                        .asSequence()
                        .filterNot(Category::isSystemCategory)
                        .filter { it.contentType != Category.CONTENT_TYPE_MANGA }
                        .toList()
                }
                val defaultCategoryName = stringResource(MR.strings.default_category)
                val selectedCategoryName = if (selectedCategoryId == null) {
                    defaultCategoryName
                } else {
                    userCategories.firstOrNull { it.id == selectedCategoryId }?.visualName ?: defaultCategoryName
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        enabled = false,
                        value = selectedCategoryName,
                        onValueChange = {},
                        label = { Text(stringResource(TDMR.strings.mass_import_label_category)) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.ArrowDropDown,
                                contentDescription = null,
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { categoryDropdownExpanded = true },
                    )

                    DropdownMenu(
                        expanded = categoryDropdownExpanded,
                        onDismissRequest = { categoryDropdownExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(defaultCategoryName) },
                            onClick = {
                                selectedCategoryId = null
                                categoryDropdownExpanded = false
                            },
                        )
                        userCategories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.visualName) },
                                onClick = {
                                    selectedCategoryId = category.id
                                    categoryDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Sync chapter list checkbox with description
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(
                        checked = syncChapterList,
                        onCheckedChange = { syncChapterList = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(TDMR.strings.mass_import_sync_chapter_list),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(TDMR.strings.mass_import_sync_chapter_list_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Fetch metadata checkbox with description
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(
                        checked = fetchDetails,
                        onCheckedChange = { fetchDetails = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(TDMR.strings.mass_import_fetch_metadata),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(TDMR.strings.mass_import_fetch_metadata_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Pending URLs section (URLs waiting to be queued)
                if (pendingUrls.isNotBlank()) {
                    val pendingCount = pendingUrls.lines().filter { it.isNotBlank() }.size
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(TDMR.strings.mass_import_pending_count, pendingCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = { showClearPendingConfirm = true }) {
                            Text(stringResource(TDMR.strings.mass_import_button_clear))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // URL Input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(TDMR.strings.mass_import_add_urls_label)) },
                        placeholder = { Text(stringResource(TDMR.strings.mass_import_add_urls_placeholder)) },
                        minLines = 3,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )

                    Column {
                        IconButton(
                            onClick = {
                                clipboardManager.getText()?.let {
                                    urlText = it.text
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentPaste,
                                contentDescription = stringResource(TDMR.strings.mass_import_cd_paste),
                            )
                        }
                        IconButton(
                            onClick = {
                                filePickerLauncher.launch(arrayOf("text/*"))
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileOpen,
                                contentDescription = stringResource(TDMR.strings.mass_import_cd_load_file),
                            )
                        }
                        if (urlText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    // Add to pending URLs
                                    pendingUrls = if (pendingUrls.isBlank()) {
                                        urlText
                                    } else {
                                        "$pendingUrls\n$urlText"
                                    }
                                    urlText = ""
                                },
                            ) {
                                    Icon(
                                    imageVector = Icons.Outlined.ArrowDropDown,
                                    contentDescription = stringResource(TDMR.strings.mass_import_cd_add_to_pending),
                                )
                            }
                        }
                    }
                }

                // Analysis
                var analysisResult by remember { mutableStateOf<MassImport.UrlAnalysisResult?>(null) }
                var isAnalyzing by remember { mutableStateOf(false) }

                LaunchedEffect(urlText) {
                    kotlinx.coroutines.delay(300)
                    if (urlText.isNotBlank()) {
                        isAnalyzing = true
                        analysisResult = massImportNovels.analyzeUrls(urlText)
                        isAnalyzing = false
                    } else {
                        analysisResult = null
                    }
                }

                if (isAnalyzing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(TDMR.strings.mass_import_analyzing),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    analysisResult?.let { analysis ->
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                text = stringResource(TDMR.strings.mass_import_analysis_valid, analysis.totalValid),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (analysis.alreadyInLibrary.isNotEmpty()) {
                                Text(
                                    text = stringResource(TDMR.strings.mass_import_analysis_already_in_library, analysis.alreadyInLibrary.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val hasUrls = pendingUrls.isNotBlank() || urlText.isNotBlank()

            TextButton(
                onClick = {
                    // Run URL parsing and job enqueue off main thread
                    dialogScope.launch(Dispatchers.Default) {
                        val uniqueUrls = LinkedHashSet<String>()
                        if (pendingUrls.isNotBlank()) {
                            uniqueUrls.addAll(massImportNovels.parseUrls(pendingUrls))
                        }
                        if (urlText.isNotBlank()) {
                            uniqueUrls.addAll(massImportNovels.parseUrls(urlText))
                        }

                        withContext(Dispatchers.Main) {
                            MassImportJob.start(
                                context = context,
                                urls = uniqueUrls.toList(),
                                addToLibrary = true,
                                fetchDetails = fetchDetails,
                                categoryId = selectedCategoryId ?: 0L,
                                fetchChapters = syncChapterList,
                            )
                            urlText = ""
                            pendingUrls = ""
                        }
                    }
                },
                enabled = hasUrls,
            ) {
                Text(stringResource(TDMR.strings.mass_import_add_to_queue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(TDMR.strings.mass_import_close))
            }
        },
    )

    if (showClearCompletedConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCompletedConfirm = false },
            title = { Text(stringResource(TDMR.strings.mass_import_confirm_clear_completed_title)) },
            text = { Text(stringResource(TDMR.strings.mass_import_confirm_clear_completed_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        MassImportJob.clearCompleted()
                        showClearCompletedConfirm = false
                    }
                ) {
                    Text(stringResource(TDMR.strings.mass_import_button_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCompletedConfirm = false }) {
                    Text(stringResource(TDMR.strings.mass_import_button_cancel))
                }
            },
        )
    }

    if (showClearPendingConfirm) {
        AlertDialog(
            onDismissRequest = { showClearPendingConfirm = false },
            title = { Text(stringResource(TDMR.strings.mass_import_confirm_clear_pending_title)) },
            text = { Text(stringResource(TDMR.strings.mass_import_confirm_clear_pending_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUrls = ""
                        showClearPendingConfirm = false
                    }
                ) {
                    Text(stringResource(TDMR.strings.mass_import_button_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearPendingConfirm = false }) {
                    Text(stringResource(TDMR.strings.mass_import_button_cancel))
                }
            },
        )
    }

    if (showCancelAllConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelAllConfirm = false },
            title = { Text(stringResource(TDMR.strings.mass_import_confirm_cancel_all_title)) },
            text = { Text(stringResource(TDMR.strings.mass_import_confirm_cancel_all_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        MassImportJob.stop(context)
                        showCancelAllConfirm = false
                    }
                ) {
                    Text(stringResource(TDMR.strings.mass_import_button_cancel_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAllConfirm = false }) {
                    Text(stringResource(TDMR.strings.mass_import_button_keep_going))
                }
            },
        )
    }
}

@Composable
private fun BatchItem(
    batch: MassImportJob.Batch,
    onCancel: () -> Unit,
    onCopyUrls: () -> Unit,
    onCopyErrors: () -> Unit,
    onCopyReport: () -> Unit,
    onSaveReport: () -> Unit,
    onRemove: () -> Unit,
    onReinsertErrors: () -> Unit,
    onExportUrls: () -> Unit,
    onRequeue: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = when (batch.status) {
                            MassImportJob.BatchStatus.Pending -> "⏳ Pending"
                            MassImportJob.BatchStatus.Running -> "▶ Running"
                            MassImportJob.BatchStatus.Completed -> "✓ Completed"
                            MassImportJob.BatchStatus.Cancelled -> "✕ Cancelled"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (batch.status) {
                            MassImportJob.BatchStatus.Running -> MaterialTheme.colorScheme.primary
                            MassImportJob.BatchStatus.Completed -> MaterialTheme.colorScheme.tertiary
                            MassImportJob.BatchStatus.Cancelled -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${batch.progress}/${batch.total}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                // Action buttons
                Row(horizontalArrangement = Arrangement.End) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = {
                            PlainTooltip {
                                Text(stringResource(if (expanded) TDMR.strings.mass_import_tooltip_collapse_details else TDMR.strings.mass_import_tooltip_expand_details))
                            }
                        },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = stringResource(TDMR.strings.mass_import_cd_details),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    if (batch.status == MassImportJob.BatchStatus.Pending ||
                        batch.status == MassImportJob.BatchStatus.Running
                    ) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = {
                                PlainTooltip {
                                    Text(stringResource(TDMR.strings.mass_import_tooltip_cancel_batch))
                                }
                            },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier.size(24.dp),
                            ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Cancel,
                                        contentDescription = stringResource(TDMR.strings.mass_import_cd_cancel),
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                            }
                        }
                    }

                    if (batch.status == MassImportJob.BatchStatus.Completed ||
                        batch.status == MassImportJob.BatchStatus.Cancelled
                    ) {
                        if (batch.status == MassImportJob.BatchStatus.Cancelled && batch.progress < batch.total) {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = {
                                    PlainTooltip {
                                        Text(stringResource(TDMR.strings.mass_import_tooltip_requeue_failed))
                                    }
                                },
                                state = rememberTooltipState(),
                            ) {
                                IconButton(
                                    onClick = onRequeue,
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = stringResource(TDMR.strings.mass_import_cd_requeue),
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = {
                                PlainTooltip {
                                    Text(stringResource(TDMR.strings.mass_import_tooltip_remove_batch))
                                }
                            },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(
                                onClick = {
                                    if (batch.errored > 0) {
                                        showRemoveConfirm = true
                                    } else {
                                        onRemove()
                                    }
                                },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(TDMR.strings.mass_import_cd_remove),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (batch.status == MassImportJob.BatchStatus.Running) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { batch.progress.toFloat() / batch.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }

            if (batch.status == MassImportJob.BatchStatus.Completed ||
                batch.status == MassImportJob.BatchStatus.Cancelled
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "✓${batch.added}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "○${batch.skipped}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (batch.errored > 0) {
                        Text(
                            text = "✕${batch.errored}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Copy URLs
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = {
                            PlainTooltip {
                                Text(stringResource(TDMR.strings.mass_import_tooltip_copy_urls))
                            }
                        },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(onClick = onCopyUrls, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(TDMR.strings.mass_import_cd_copy_urls),
                                    modifier = Modifier.size(18.dp),
                                )
                        }
                    }

                    // Export URLs
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = {
                            PlainTooltip {
                                Text(stringResource(TDMR.strings.mass_import_tooltip_export_urls))
                            }
                        },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(onClick = onExportUrls, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Outlined.Save,
                                contentDescription = stringResource(TDMR.strings.mass_import_cd_export_urls),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    // Copy Errors (if any)
                    if (batch.errored > 0) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = {
                                PlainTooltip {
                                    Text(stringResource(TDMR.strings.mass_import_tooltip_copy_errors))
                                }
                            },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(onClick = onCopyErrors, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Outlined.ContentCopy,
                                        contentDescription = stringResource(TDMR.strings.mass_import_cd_copy_errors),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                            }
                        }
                    }

                    // For completed batches: Copy Report, Save Report, Retry Errors
                    if (batch.status == MassImportJob.BatchStatus.Completed ||
                        batch.status == MassImportJob.BatchStatus.Cancelled
                    ) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = {
                                PlainTooltip {
                                    Text(stringResource(TDMR.strings.mass_import_tooltip_copy_report))
                                }
                            },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(onClick = onCopyReport, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Outlined.Description,
                                        contentDescription = stringResource(TDMR.strings.mass_import_cd_copy_report),
                                        modifier = Modifier.size(18.dp),
                                    )
                            }
                        }

                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = {
                                PlainTooltip {
                                    Text(stringResource(TDMR.strings.mass_import_tooltip_save_report))
                                }
                            },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(onClick = onSaveReport, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Outlined.Save,
                                        contentDescription = stringResource(TDMR.strings.mass_import_cd_save_report),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                            }
                        }

                        if (batch.errored > 0) {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = {
                                    PlainTooltip {
                                        Text(stringResource(TDMR.strings.mass_import_tooltip_retry_errors))
                                    }
                                },
                                state = rememberTooltipState(),
                            ) {
                                IconButton(onClick = onReinsertErrors, modifier = Modifier.size(32.dp)) {
                                        Icon(
                                            Icons.Outlined.Refresh,
                                            contentDescription = stringResource(TDMR.strings.mass_import_cd_retry_errors),
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                }
                            }
                        }
                    }
                }

                // Show first few URLs as preview
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(TDMR.strings.mass_import_urls_header, batch.urls.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                batch.urls.take(3).forEach { url ->
                    Text(
                        text = url.take(50) + if (url.length > 50) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (batch.urls.size > 3) {
                    Text(
                        text = "... and ${batch.urls.size - 3} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Show errors if any
                if (batch.erroredUrls.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(TDMR.strings.mass_import_errors_header, batch.erroredUrls.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    batch.erroredUrls.take(3).forEach { url ->
                        Text(
                            text = url.take(50) + if (url.length > 50) "..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    }
                    if (batch.erroredUrls.size > 3) {
                        Text(
                            text = "... and ${batch.erroredUrls.size - 3} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text(stringResource(TDMR.strings.mass_import_confirm_remove_title)) },
            text = { Text(stringResource(TDMR.strings.mass_import_confirm_remove_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove()
                        showRemoveConfirm = false
                    }
                ) {
                    Text(stringResource(TDMR.strings.mass_import_confirm_remove_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text(stringResource(TDMR.strings.mass_import_confirm_remove_dismiss))
                }
            },
        )
    }
}
