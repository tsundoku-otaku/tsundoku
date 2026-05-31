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
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.FileOpen
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
    initialText: String = "",
    isNovelMode: Boolean = true, // Default to novel mode for backward compatibility
    preferredSourceId: Long? = null,
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
    val toastExportedUrls = stringResource(TDMR.strings.mass_import_toast_exported_urls)
    val toastErrorExportingUrls = stringResource(TDMR.strings.mass_import_toast_error_exporting_urls)
    val toastCopiedUrls = stringResource(TDMR.strings.mass_import_toast_copied_urls)
    val toastCopiedErrors = stringResource(TDMR.strings.mass_import_toast_copied_errors)
    val toastRequeuedErrors = stringResource(TDMR.strings.mass_import_toast_requeued_errors)
    val clipboardUrlsLabel = stringResource(TDMR.strings.mass_import_clipboard_label_urls)
    val clipboardErrorsLabel = stringResource(TDMR.strings.mass_import_clipboard_label_errors)

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

    // State to track which batch to export URLs from
    var batchToExport by remember { mutableStateOf<MassImportJob.Batch?>(null) }

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
                        context.toast(String.format(toastExportedUrls, batch.sourceUrls.size))
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
                            val hasCompleted = queue.any {
                                it.status == MassImportJob.BatchStatus.Completed ||
                                    it.status == MassImportJob.BatchStatus.Cancelled
                            }
                            if (hasCompleted) {
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                    tooltip = { PlainTooltip { Text(stringResource(TDMR.strings.mass_import_clear_done)) } },
                                    state = rememberTooltipState(),
                                ) {
                                    IconButton(
                                        onClick = { showClearCompletedConfirm = true },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ClearAll,
                                            contentDescription = stringResource(TDMR.strings.mass_import_clear_done),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            val hasRunningState = queue.any {
                                it.status == MassImportJob.BatchStatus.Pending ||
                                    it.status == MassImportJob.BatchStatus.Running
                            }
                            val hasPausedState = queue.any {
                                it.status == MassImportJob.BatchStatus.Paused
                            }

                            if (hasRunningState) {
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                    tooltip = { PlainTooltip { Text("Pause All") } },
                                    state = rememberTooltipState(),
                                ) {
                                    IconButton(
                                        onClick = { MassImportJob.pauseAll(context) },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Pause,
                                            contentDescription = "Pause All",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            if (hasPausedState) {
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                    tooltip = { PlainTooltip { Text("Resume All") } },
                                    state = rememberTooltipState(),
                                ) {
                                    IconButton(
                                        onClick = { MassImportJob.resumeAll(context) },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.PlayArrow,
                                            contentDescription = "Resume All",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            if (hasRunningState || hasPausedState) {
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                    tooltip = { PlainTooltip { Text(stringResource(TDMR.strings.mass_import_cancel_all)) } },
                                    state = rememberTooltipState(),
                                ) {
                                    IconButton(
                                        onClick = { showCancelAllConfirm = true },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Cancel,
                                            contentDescription = stringResource(TDMR.strings.mass_import_cancel_all),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
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
                                onPause = { MassImportJob.pauseBatch(context, batch.id) },
                                onResume = { MassImportJob.resumeBatch(context, batch.id) },
                                onRetryFailed = {
                                    MassImportJob.retryFailed(context, batch.id)
                                    context.toast(String.format(toastRequeuedErrors, batch.errored))
                                },
                                onCopyUrls = {
                                    val urls = MassImportJob.exportBatchUrls(batch)
                                    context.copyToClipboard(clipboardUrlsLabel, urls)
                                    context.toast(String.format(toastCopiedUrls, batch.sourceUrls.size))
                                },
                                onCopyErrors = {
                                    if (batch.erroredUrls.isNotEmpty()) {
                                        context.copyToClipboard(clipboardErrorsLabel, batch.erroredUrls.joinToString("\n"))
                                        context.toast(String.format(toastCopiedErrors, batch.errored))
                                    }
                                },
                                onRemove = { MassImportJob.removeBatch(context, batch.id) },
                                onExportUrls = {
                                    batchToExport = batch
                                    val timestamp = java.text.SimpleDateFormat(
                                        "yyyyMMdd_HHmmss",
                                        java.util.Locale.getDefault(),
                                    ).format(java.util.Date())
                                    exportUrlsLauncher.launch("mass_import_urls_$timestamp.txt")
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
                // categories already filtered by contentType from subscribeByContentType — just strip system categories
                val userCategories = remember(categories) {
                    categories
                        .asSequence()
                        .filterNot(Category::isSystemCategory)
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
                    dialogScope.launch {
                        val combinedRawText = when {
                            pendingUrls.isNotBlank() && urlText.isNotBlank() -> pendingUrls + "\n" + urlText
                            pendingUrls.isNotBlank() -> pendingUrls
                            else -> urlText
                        }

                        val useRawTextFastPath = combinedRawText.length > 100_000
                        if (useRawTextFastPath) {
                            MassImportJob.start(
                                context = context,
                                urls = emptyList(),
                                addToLibrary = true,
                                fetchDetails = fetchDetails,
                                categoryId = selectedCategoryId ?: 0L,
                                fetchChapters = syncChapterList,
                                rawText = combinedRawText,
                                preferredSourceId = preferredSourceId,
                            )
                        } else {
                            val uniqueUrls = withContext(Dispatchers.Default) {
                                val set = LinkedHashSet<String>()
                                if (pendingUrls.isNotBlank()) set.addAll(massImportNovels.parseUrls(pendingUrls))
                                if (urlText.isNotBlank()) set.addAll(massImportNovels.parseUrls(urlText))
                                set.toList()
                            }

                            MassImportJob.start(
                                context = context,
                                urls = uniqueUrls,
                                addToLibrary = true,
                                fetchDetails = fetchDetails,
                                categoryId = selectedCategoryId ?: 0L,
                                fetchChapters = syncChapterList,
                                preferredSourceId = preferredSourceId,
                            )
                        }
                        urlText = ""
                        pendingUrls = ""
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
                        MassImportJob.clearCompleted(context)
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
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCopyUrls: () -> Unit,
    onCopyErrors: () -> Unit,
    onRemove: () -> Unit,
    onExportUrls: () -> Unit,
    onRetryFailed: () -> Unit,
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
                            MassImportJob.BatchStatus.Paused -> "⏸ Paused"
                            MassImportJob.BatchStatus.Completed -> "✓ Completed"
                            MassImportJob.BatchStatus.Cancelled -> "✕ Cancelled"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (batch.status) {
                            MassImportJob.BatchStatus.Running -> MaterialTheme.colorScheme.primary
                            MassImportJob.BatchStatus.Paused -> MaterialTheme.colorScheme.secondary
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
                        batch.status == MassImportJob.BatchStatus.Running ||
                        batch.status == MassImportJob.BatchStatus.Paused
                    ) {
                        if (batch.status == MassImportJob.BatchStatus.Paused) {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = {
                                    PlainTooltip { Text("Resume Batch") }
                                },
                                state = rememberTooltipState(),
                            ) {
                                IconButton(
                                    onClick = onResume,
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.PlayArrow,
                                        contentDescription = "Resume",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        } else {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = {
                                    PlainTooltip { Text("Pause Batch") }
                                },
                                state = rememberTooltipState(),
                            ) {
                                IconButton(
                                    onClick = onPause,
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Pause,
                                        contentDescription = "Pause",
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

                    // Retry failed: re-queue the errored URLs as a new batch (terminal batches only)
                    val isTerminal = batch.status == MassImportJob.BatchStatus.Completed ||
                        batch.status == MassImportJob.BatchStatus.Cancelled
                    if (batch.errored > 0 && isTerminal && batch.erroredUrls.isNotEmpty()) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = {
                                PlainTooltip {
                                    Text(stringResource(TDMR.strings.mass_import_tooltip_retry_errors))
                                }
                            },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(onClick = onRetryFailed, modifier = Modifier.size(32.dp)) {
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

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Processed: ${batch.added}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Skipped: ${batch.skipped}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (batch.errored > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(TDMR.strings.mass_import_errors_header, batch.errored),
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
                    if (batch.errored > batch.erroredUrls.size) {
                        Text(
                            text = "... and ${batch.errored - batch.erroredUrls.size} more",
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

