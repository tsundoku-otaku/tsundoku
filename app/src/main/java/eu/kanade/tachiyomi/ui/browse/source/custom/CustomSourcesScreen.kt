@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.browse.source.custom

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.source.custom.CustomNovelSource
import eu.kanade.tachiyomi.source.custom.CustomSourceConfig
import eu.kanade.tachiyomi.source.custom.CustomSourceManager
import eu.kanade.tachiyomi.source.custom.SourceTestResult
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Screen for managing custom novel sources
 */
class CustomSourcesScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CustomSourcesScreenModel() }
        val sources by screenModel.customSources.collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        var showCreateDialog by remember { mutableStateOf(false) }
        var sourceToDelete by remember { mutableStateOf<Long?>(null) }
        var showImportDialog by remember { mutableStateOf(false) }
        var importJsonText by remember { mutableStateOf("") }
        var importError by remember { mutableStateOf<String?>(null) }

        // File picker loads file content into the import dialog so errors can be shown inline.
        val importLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            uri?.let {
                scope.launch {
                    try {
                        val inputStream = context.contentResolver.openInputStream(it)
                        val json = inputStream?.bufferedReader()?.use { reader -> reader.readText() } ?: ""
                        inputStream?.close()
                        importJsonText = json
                        importError = null
                        showImportDialog = true
                    } catch (e: Exception) {
                        importError = context.stringResource(
                            TDMR.strings.custom_source_read_error,
                            e.message ?: "",
                        )
                        showImportDialog = true
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(TDMR.strings.custom_sources_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(MR.strings.action_webview_back),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            importJsonText = ""
                            importError = null
                            showImportDialog = true
                        }) {
                            Icon(
                                Icons.Outlined.FileUpload,
                                contentDescription = stringResource(TDMR.strings.custom_sources_import_source),
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = stringResource(TDMR.strings.custom_sources_add_source),
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            if (sources.isEmpty()) {
                EmptyState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    onCreateClick = { showCreateDialog = true },
                    onImportClick = {
                        importJsonText = ""
                        importError = null
                        showImportDialog = true
                    },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sources, key = { it.id }) { source ->
                        CustomSourceCard(
                            name = source.name,
                            baseUrl = source.baseUrl,
                            lang = source.lang,
                            onEdit = {
                                navigator.push(CustomSourceEditorScreen(source.id))
                            },
                            onTest = {
                                navigator.push(CustomSourceTestScreen(source.id))
                            },
                            onDelete = { sourceToDelete = source.id },
                            onExport = {
                                // Export and share
                                scope.launch {
                                    val json = screenModel.exportSource(source.id)
                                    if (json != null) {
                                        try {
                                            // Create temp file
                                            val exportDir = File(context.cacheDir, "exports")
                                            exportDir.mkdirs()
                                            val exportFile = File(exportDir, "${source.name.replace(" ", "_")}.json")
                                            exportFile.writeText(json)

                                            // Get content URI via FileProvider
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.provider",
                                                exportFile,
                                            )

                                            // Share intent
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/json"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(
                                                    Intent.EXTRA_SUBJECT,
                                                    context.stringResource(
                                                        TDMR.strings.custom_source_export_subject,
                                                        source.name,
                                                    ),
                                                )
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }

                                            context.startActivity(
                                                Intent.createChooser(
                                                    shareIntent,
                                                    context.stringResource(TDMR.strings.custom_source_export_title),
                                                ),
                                            )
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar(
                                                context.stringResource(
                                                    TDMR.strings.custom_source_export_failed,
                                                    e.message ?: "",
                                                ),
                                            )
                                        }
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            context.stringResource(TDMR.strings.custom_source_export_error),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        if (showCreateDialog) {
            CreateSourceDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { name, baseUrl ->
                    navigator.push(
                        CustomSourceEditorScreen(
                            sourceId = null,
                            initialName = name,
                            initialBaseUrl = baseUrl,
                        ),
                    )
                    showCreateDialog = false
                },
                onUseWebViewSelector = { baseUrl ->
                    // Route through the pre-setup screen so the user picks features/steps first.
                    navigator.push(eu.kanade.tachiyomi.ui.customsource.WizardSetupScreen(baseUrl))
                    showCreateDialog = false
                },
                onBaseOnExtension = { name, baseUrl, extensionSourceId ->
                    navigator.push(
                        CustomSourceEditorScreen(
                            sourceId = null,
                            initialName = name,
                            initialBaseUrl = baseUrl,
                            basedOnSourceId = extensionSourceId,
                        ),
                    )
                    showCreateDialog = false
                },
            )
        }

        if (showImportDialog) {
            ImportSourceDialog(
                jsonText = importJsonText,
                error = importError,
                onJsonChange = {
                    importJsonText = it
                    importError = null
                },
                onPickFile = { importLauncher.launch("application/json") },
                onCopyTemplate = { importJsonText = screenModel.blankTemplateJson() },
                onImport = {
                    scope.launch {
                        screenModel.importSource(importJsonText).fold(
                            onSuccess = {
                                showImportDialog = false
                                snackbarHostState.showSnackbar(
                                    context.stringResource(TDMR.strings.custom_source_imported),
                                )
                            },
                            onFailure = { e ->
                                importError = e.message ?: context.stringResource(
                                    TDMR.strings.custom_source_import_failed,
                                    "",
                                )
                            },
                        )
                    }
                },
                onDismiss = { showImportDialog = false },
            )
        }

        sourceToDelete?.let { id ->
            AlertDialog(
                onDismissRequest = { sourceToDelete = null },
                title = { Text(stringResource(TDMR.strings.custom_sources_delete_title)) },
                text = { Text(stringResource(TDMR.strings.custom_sources_delete_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        screenModel.deleteSource(id)
                        sourceToDelete = null
                    }) {
                        Text(stringResource(MR.strings.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { sourceToDelete = null }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun ImportSourceDialog(
    jsonText: String,
    error: String?,
    onJsonChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onCopyTemplate: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.custom_source_import_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onPickFile) {
                        Icon(
                            Icons.Outlined.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(TDMR.strings.custom_source_import_from_file))
                    }
                    TextButton(onClick = onCopyTemplate) {
                        Text(stringResource(TDMR.strings.custom_source_paste_template))
                    }
                }

                OutlinedTextField(
                    value = jsonText,
                    onValueChange = onJsonChange,
                    label = { Text(stringResource(TDMR.strings.custom_source_import_paste_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    isError = error != null,
                )

                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onImport,
                enabled = jsonText.isNotBlank(),
            ) {
                Text(stringResource(TDMR.strings.custom_sources_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    onCreateClick: () -> Unit,
    onImportClick: () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(TDMR.strings.custom_sources_empty_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(TDMR.strings.custom_sources_empty_subtitle),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onCreateClick) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(TDMR.strings.custom_sources_create_source))
            }
            Button(onClick = onImportClick) {
                Icon(Icons.Outlined.FileUpload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(TDMR.strings.custom_sources_import))
            }
        }
    }
}

@Composable
private fun CustomSourceCard(
    name: String,
    baseUrl: String,
    lang: String,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(TDMR.strings.custom_sources_language_format, lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onTest) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = stringResource(TDMR.strings.custom_sources_test),
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(MR.strings.action_edit))
                }
                IconButton(onClick = onExport) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(TDMR.strings.custom_sources_export))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.action_delete))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSourceDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, baseUrl: String) -> Unit,
    onUseWebViewSelector: (baseUrl: String) -> Unit = {},
    onBaseOnExtension: (name: String, baseUrl: String, sourceId: Long) -> Unit = { _, _, _ -> },
) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://") }
    var useWebView by remember { mutableStateOf(false) }
    var showExtensionPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.custom_source_create_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_source_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_base_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://example.com") },
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = useWebView,
                        onCheckedChange = { useWebView = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(TDMR.strings.custom_sources_use_webview_selector),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(TDMR.strings.custom_sources_use_webview_selector_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { showExtensionPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(TDMR.strings.custom_source_base_on_extension))
                }
                Text(
                    text = stringResource(TDMR.strings.custom_source_base_on_extension_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                if (!useWebView) {
                    Text(
                        text = stringResource(TDMR.strings.custom_source_use_repos_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (useWebView) {
                        onUseWebViewSelector(baseUrl)
                    } else {
                        onCreate(name, baseUrl)
                    }
                },
                enabled = (useWebView && baseUrl.startsWith("http")) ||
                    (!useWebView && name.isNotBlank() && baseUrl.startsWith("http")),
            ) {
                Text(
                    if (useWebView) {
                        stringResource(TDMR.strings.custom_sources_open_webview_wizard)
                    } else {
                        stringResource(MR.strings.action_create)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )

    if (showExtensionPicker) {
        BaseSourcePickerDialog(
            selectedSourceId = null,
            onDismiss = { showExtensionPicker = false },
            onPick = { pickedSourceName, pickedSourceId, pickedBaseUrl, _ ->
                showExtensionPicker = false
                onBaseOnExtension(pickedSourceName, pickedBaseUrl, pickedSourceId)
            },
        )
    }
}

/**
 * Screen for testing a custom source
 */
class CustomSourceTestScreen(
    private val sourceId: Long,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CustomSourcesScreenModel() }
        val scope = rememberCoroutineScope()

        var testResult by remember { mutableStateOf<SourceTestResult?>(null) }
        var isLoading by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(TDMR.strings.custom_source_test_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(MR.strings.action_webview_back),
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(TDMR.strings.custom_source_testing))
                } else if (testResult == null) {
                    Button(onClick = {
                        isLoading = true
                        scope.launch {
                            testResult = screenModel.testSource(sourceId)
                            isLoading = false
                        }
                    }) {
                        Text(stringResource(TDMR.strings.custom_source_run_test))
                    }
                } else {
                    TestResultView(result = testResult!!)

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(onClick = {
                        testResult = null
                    }) {
                        Text(stringResource(TDMR.strings.custom_source_test_again))
                    }
                }
            }
        }
    }
}

@Composable
private fun TestResultView(result: SourceTestResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = result.sourceName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (result.overallSuccess) {
                stringResource(
                    TDMR.strings.custom_source_all_tests_passed,
                )
            } else {
                stringResource(TDMR.strings.custom_source_some_tests_failed)
            },
            color = if (result.overallSuccess) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        result.steps.forEach { (stepName, stepResult) ->
            val containerColor = if (stepResult.success) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
            val contentColor = if (stepResult.success) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stepName.replaceFirstChar { it.uppercase() },
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                    )
                    Text(
                        text = stepResult.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                    )
                    stepResult.data?.forEach { (key, value) ->
                        Text(
                            text = "$key: $value",
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Screen for editing a custom source configuration
 */
class CustomSourceEditorScreen(
    private val sourceId: Long?,
    private val initialName: String? = null,
    private val initialBaseUrl: String? = null,
    private val basedOnSourceId: Long? = null,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CustomSourcesScreenModel() }
        val scope = rememberCoroutineScope()

        // Load existing config or create blank
        val initialConfig = remember {
            if (sourceId != null) {
                screenModel.getSourceConfig(sourceId)
            } else if (initialName != null && initialBaseUrl != null) {
                screenModel.createBlankConfig(initialName, initialBaseUrl)
            } else {
                null
            }
        }

        var selectedBasedOnSourceId by remember {
            mutableStateOf(basedOnSourceId ?: initialConfig?.basedOnSourceId)
        }
        var showBaseSourcePicker by remember { mutableStateOf(false) }

        // Resolve language from base source when available
        val baseLang = remember(selectedBasedOnSourceId) {
            selectedBasedOnSourceId?.let { id ->
                (Injekt.get<SourceManager>().get(id))?.lang
            }
        }

        // State for form fields
        var name by remember { mutableStateOf(initialConfig?.name ?: "") }
        var baseUrl by remember { mutableStateOf(initialConfig?.baseUrl ?: "https://") }
        var language by remember {
            // For new sources based on a base extension, inherit the base source's language
            val isNew = sourceId == null
            mutableStateOf(
                if (isNew && baseLang != null) baseLang else initialConfig?.language ?: "en",
            )
        }
        var popularUrl by remember { mutableStateOf(initialConfig?.popularUrl ?: "") }
        var latestUrl by remember { mutableStateOf(initialConfig?.latestUrl ?: "") }
        var searchUrl by remember { mutableStateOf(initialConfig?.searchUrl ?: "") }

        var reverseChapters by remember { mutableStateOf(initialConfig?.reverseChapters ?: false) }
        var postSearch by remember { mutableStateOf(initialConfig?.postSearch ?: false) }
        var isNovel by remember { mutableStateOf(initialConfig?.isNovel ?: true) }

        // Which sections this source has — drives which selector inputs are shown. Inferred from an
        // existing config, defaults to popular+latest+search for a new one.
        var features by remember {
            val c = initialConfig
            mutableStateOf(
                eu.kanade.tachiyomi.ui.customsource.SourceFeatures(
                    hasPopular = c == null || c.popularUrl.isNotBlank(),
                    hasLatest = c == null || c.latestUrl != null,
                    hasSearch = c == null || c.searchUrl.isNotBlank(),
                    popularPagination = c?.selectors?.popular?.nextPage != null || c?.popularPagedUrl != null,
                    latestPagination = c?.selectors?.latest?.nextPage != null || c?.latestPagedUrl != null,
                    searchPagination = c?.selectors?.search?.nextPage != null || c?.searchPagedUrl != null,
                    chapterListPagination = c?.selectors?.chapters?.nextPage != null ||
                        c?.selectors?.chapters?.pagedUrlPattern != null,
                    chapterListSeparatePage = c?.selectors?.chapters?.indexLinkSelector != null,
                    chapterGenerateFromPattern = c?.selectors?.chapters?.urlPattern != null,
                ),
            )
        }

        // Selectors
        var popularListSelector by remember {
            mutableStateOf(initialConfig?.selectors?.popular?.list ?: "")
        }
        var popularTitleSelector by remember {
            mutableStateOf(initialConfig?.selectors?.popular?.title ?: "")
        }
        var popularCoverSelector by remember {
            mutableStateOf(initialConfig?.selectors?.popular?.cover ?: "")
        }
        var popularNextPageSelector by remember {
            mutableStateOf(initialConfig?.selectors?.popular?.nextPage ?: "")
        }
        var latestNextPageSelector by remember {
            mutableStateOf(initialConfig?.selectors?.latest?.nextPage ?: "")
        }
        var searchNextPageSelector by remember {
            mutableStateOf(initialConfig?.selectors?.search?.nextPage ?: "")
        }
        var detailsTitleSelector by remember {
            mutableStateOf(initialConfig?.selectors?.details?.title ?: "")
        }
        var detailsDescriptionSelector by remember {
            mutableStateOf(initialConfig?.selectors?.details?.description ?: "")
        }
        var detailsCoverSelector by remember {
            mutableStateOf(initialConfig?.selectors?.details?.cover ?: "")
        }
        var detailsAuthorSelector by remember {
            mutableStateOf(initialConfig?.selectors?.details?.author ?: "")
        }
        var detailsGenreSelector by remember {
            mutableStateOf(initialConfig?.selectors?.details?.genre ?: "")
        }
        var detailsArtistSelector by remember {
            mutableStateOf(initialConfig?.selectors?.details?.artist ?: "")
        }
        var detailsStatusSelector by remember {
            mutableStateOf(initialConfig?.selectors?.details?.status ?: "")
        }
        // Status mapping as editable text: "word=ongoing, 完结=completed". Parsed in buildConfig.
        var statusMappingText by remember {
            mutableStateOf(
                initialConfig?.statusMapping
                    ?.entries?.joinToString(", ") { "${it.key}=${it.value}" }
                    ?: "",
            )
        }
        var chaptersListSelector by remember {
            mutableStateOf(initialConfig?.selectors?.chapters?.list ?: "")
        }
        var chapterLinkSelector by remember {
            mutableStateOf(initialConfig?.selectors?.chapters?.link ?: "")
        }
        var chapterNameSelector by remember {
            mutableStateOf(initialConfig?.selectors?.chapters?.name ?: "")
        }
        var chapterDateSelector by remember {
            mutableStateOf(initialConfig?.selectors?.chapters?.date ?: "")
        }
        var chapterNextPageSelector by remember {
            mutableStateOf(initialConfig?.selectors?.chapters?.nextPage ?: "")
        }
        var chapterIndexLinkSelector by remember {
            mutableStateOf(initialConfig?.selectors?.chapters?.indexLinkSelector ?: "")
        }
        var chapterUrlPattern by remember {
            mutableStateOf(initialConfig?.selectors?.chapters?.urlPattern ?: "")
        }
        var chapterCountSelector by remember {
            mutableStateOf(initialConfig?.selectors?.chapters?.countSelector ?: "")
        }
        var chapterFirstNumber by remember {
            mutableStateOf(initialConfig?.selectors?.chapters?.firstNumber?.toString() ?: "")
        }
        var chapterLastNumber by remember {
            mutableStateOf(initialConfig?.selectors?.chapters?.lastNumber?.toString() ?: "")
        }
        var contentPrimarySelector by remember {
            mutableStateOf(initialConfig?.selectors?.content?.primary ?: "")
        }
        var contentFallbacksSelector by remember {
            mutableStateOf(initialConfig?.selectors?.content?.fallbacks?.joinToString(", ") ?: "")
        }
        var contentRemoveSelectors by remember {
            mutableStateOf(initialConfig?.selectors?.content?.removeSelectors?.joinToString(", ") ?: "")
        }
        var chapterNameTemplate by remember {
            mutableStateOf(initialConfig?.selectors?.chapters?.nameTemplate ?: "")
        }
        // Advanced fields, previously only reachable via JSON import.
        var dateFormat by remember { mutableStateOf(initialConfig?.dateFormat ?: "") }
        var headersText by remember {
            mutableStateOf(
                initialConfig?.headers
                    ?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }
                    ?: "",
            )
        }
        var testSearchQuery by remember { mutableStateOf(initialConfig?.testSearchQuery ?: "") }
        var sampleNovelUrl by remember { mutableStateOf(initialConfig?.sampleNovelUrl ?: "") }

        var isSaving by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        // WebView element-picker: opened from the trailing icon next to a selector field. pickerTarget
        // holds the setter for the field being edited; it's non-null while the picker is shown.
        var pickerTarget by remember { mutableStateOf<((String) -> Unit)?>(null) }
        var pickerUrl by remember { mutableStateOf("") }
        var pickerLabel by remember { mutableStateOf("") }
        fun firstPageUrl(u: String): String =
            u.replace("{page}", "1").replace("{query}", "a").ifBlank { baseUrl }
        fun pickTrailing(url: String, fieldLabel: String, set: (String) -> Unit): @Composable () -> Unit = {
            IconButton(onClick = {
                pickerUrl = url.ifBlank { baseUrl }
                pickerLabel = fieldLabel
                pickerTarget = set
            }) {
                Icon(
                    Icons.Outlined.Language,
                    contentDescription = stringResource(TDMR.strings.custom_source_pick_from_site),
                )
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (sourceId !=
                                null
                            ) {
                                stringResource(TDMR.strings.custom_source_edit_title)
                            } else {
                                stringResource(TDMR.strings.custom_source_create_source)
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(MR.strings.action_webview_back),
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(TDMR.strings.custom_source_basic_info),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_source_name_required)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_base_url_required)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = language,
                    onValueChange = { language = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_language_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(TDMR.strings.custom_source_base_on_extension),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = selectedBasedOnSourceId?.let { id ->
                                Injekt.get<SourceManager>().get(id)?.let { source ->
                                    "${source.name} (${source.lang})"
                                }
                            } ?: stringResource(TDMR.strings.custom_source_not_based),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showBaseSourcePicker = true }) {
                                Text(
                                    if (selectedBasedOnSourceId == null) {
                                        stringResource(TDMR.strings.custom_source_base_on_extension)
                                    } else {
                                        stringResource(MR.strings.action_edit)
                                    },
                                )
                            }
                            if (selectedBasedOnSourceId != null) {
                                TextButton(onClick = { selectedBasedOnSourceId = null }) {
                                    Text(stringResource(MR.strings.action_delete))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(TDMR.strings.custom_source_options),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = reverseChapters,
                        onCheckedChange = { reverseChapters = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            stringResource(TDMR.strings.custom_source_reverse_chapters),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(TDMR.strings.custom_source_reverse_chapters_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = postSearch,
                        onCheckedChange = { postSearch = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            stringResource(TDMR.strings.custom_source_post_search),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(TDMR.strings.custom_source_post_search_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = isNovel,
                        onCheckedChange = { isNovel = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            stringResource(TDMR.strings.custom_source_novel_source),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(TDMR.strings.custom_source_novel_source_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Manga (non-novel) sources can't be built from CSS selectors here — they must
                // delegate to an installed extension. Force the user toward an extension base.
                if (!isNovel && selectedBasedOnSourceId == null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(TDMR.strings.custom_source_manga_requires_base),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Sections — choose what the source has; hides the inputs you don't need.
                if (selectedBasedOnSourceId == null && isNovel) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(TDMR.strings.selector_features_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    SectionToggle(TDMR.strings.selector_feature_popular, features.hasPopular) {
                        features = features.copy(hasPopular = it)
                    }
                    if (features.hasPopular) {
                        @Suppress("ktlint:standard:max-line-length")
                        SectionToggle(
                            TDMR.strings.selector_feature_popular_pagination,
                            features.popularPagination,
                            true,
                        ) {
                            features = features.copy(popularPagination = it)
                        }
                    }
                    SectionToggle(TDMR.strings.selector_feature_latest, features.hasLatest) {
                        features = features.copy(hasLatest = it)
                    }
                    if (features.hasLatest) {
                        @Suppress("ktlint:standard:max-line-length")
                        SectionToggle(
                            TDMR.strings.selector_feature_latest_pagination,
                            features.latestPagination,
                            true,
                        ) {
                            features = features.copy(latestPagination = it)
                        }
                    }
                    SectionToggle(TDMR.strings.selector_feature_search, features.hasSearch) {
                        features = features.copy(hasSearch = it)
                    }
                    if (features.hasSearch) {
                        @Suppress("ktlint:standard:max-line-length")
                        SectionToggle(
                            TDMR.strings.selector_feature_search_pagination,
                            features.searchPagination,
                            true,
                        ) {
                            features = features.copy(searchPagination = it)
                        }
                    }
                    SectionToggle(TDMR.strings.selector_feature_chapter_generate, features.chapterGenerateFromPattern) {
                        features = features.copy(chapterGenerateFromPattern = it)
                    }
                    if (!features.chapterGenerateFromPattern) {
                        @Suppress("ktlint:standard:max-line-length")
                        SectionToggle(
                            TDMR.strings.selector_feature_chapter_separate_page,
                            features.chapterListSeparatePage,
                        ) {
                            features = features.copy(chapterListSeparatePage = it)
                        }
                        @Suppress("ktlint:standard:max-line-length")
                        SectionToggle(
                            TDMR.strings.selector_feature_chapter_pagination,
                            features.chapterListPagination,
                        ) {
                            features = features.copy(chapterListPagination = it)
                        }
                    }
                }

                if (selectedBasedOnSourceId == null && isNovel) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(TDMR.strings.custom_source_url_patterns),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(TDMR.strings.custom_source_url_placeholders),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (features.hasPopular) {
                        OutlinedTextField(
                            value = popularUrl,
                            onValueChange = { popularUrl = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_popular_url)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(TDMR.strings.custom_source_popular_url_hint)) },
                        )
                    }

                    if (features.hasLatest) {
                        OutlinedTextField(
                            value = latestUrl,
                            onValueChange = { latestUrl = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_latest_url)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (features.hasSearch) {
                        OutlinedTextField(
                            value = searchUrl,
                            onValueChange = { searchUrl = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_search_url)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(TDMR.strings.custom_source_search_url_hint)) },
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(TDMR.strings.custom_source_css_selectors),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(stringResource(TDMR.strings.custom_source_novel_list), fontWeight = FontWeight.Medium)
                    OutlinedTextField(
                        value = popularListSelector,
                        onValueChange = { popularListSelector = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_list_item_selector)) },
                        trailingIcon = pickTrailing(
                            firstPageUrl(popularUrl),
                            stringResource(TDMR.strings.custom_source_list_item_selector),
                        ) {
                            popularListSelector =
                                it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(TDMR.strings.custom_source_list_item_hint)) },
                    )
                    OutlinedTextField(
                        value = popularTitleSelector,
                        onValueChange = { popularTitleSelector = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_title_selector)) },
                        trailingIcon = pickTrailing(
                            firstPageUrl(popularUrl),
                            stringResource(TDMR.strings.custom_source_title_selector),
                        ) {
                            popularTitleSelector =
                                it
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = popularCoverSelector,
                        onValueChange = { popularCoverSelector = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_cover_selector)) },
                        trailingIcon = pickTrailing(
                            firstPageUrl(popularUrl),
                            stringResource(TDMR.strings.custom_source_cover_selector),
                        ) {
                            popularCoverSelector =
                                it
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Pagination is per-section (popular / latest / search can differ).
                    if (features.hasPopular && features.popularPagination) {
                        OutlinedTextField(
                            value = popularNextPageSelector,
                            onValueChange = { popularNextPageSelector = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_pagination_popular)) },
                            trailingIcon = pickTrailing(
                                firstPageUrl(popularUrl),
                                stringResource(TDMR.strings.custom_source_pagination_popular),
                            ) {
                                popularNextPageSelector =
                                    it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(TDMR.strings.custom_source_pagination_selector_hint)) },
                        )
                    }
                    if (features.hasLatest && features.latestPagination) {
                        OutlinedTextField(
                            value = latestNextPageSelector,
                            onValueChange = { latestNextPageSelector = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_pagination_latest)) },
                            trailingIcon = pickTrailing(
                                firstPageUrl(latestUrl),
                                stringResource(TDMR.strings.custom_source_pagination_latest),
                            ) {
                                latestNextPageSelector =
                                    it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(TDMR.strings.custom_source_pagination_selector_hint)) },
                        )
                    }
                    if (features.hasSearch && features.searchPagination) {
                        OutlinedTextField(
                            value = searchNextPageSelector,
                            onValueChange = { searchNextPageSelector = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_pagination_search)) },
                            trailingIcon = pickTrailing(
                                firstPageUrl(searchUrl),
                                stringResource(TDMR.strings.custom_source_pagination_search),
                            ) {
                                searchNextPageSelector =
                                    it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(TDMR.strings.custom_source_pagination_selector_hint)) },
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(stringResource(TDMR.strings.custom_source_novel_details), fontWeight = FontWeight.Medium)
                    OutlinedTextField(
                        value = detailsTitleSelector,
                        onValueChange = { detailsTitleSelector = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_title_selector_required)) },
                        trailingIcon = pickTrailing(
                            baseUrl,
                            stringResource(TDMR.strings.custom_source_title_selector_required),
                        ) {
                            detailsTitleSelector =
                                it
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = detailsDescriptionSelector,
                        onValueChange = { detailsDescriptionSelector = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_description_selector)) },
                        trailingIcon = pickTrailing(
                            baseUrl,
                            stringResource(TDMR.strings.custom_source_description_selector),
                        ) {
                            detailsDescriptionSelector =
                                it
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = detailsCoverSelector,
                        onValueChange = { detailsCoverSelector = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_details_cover_selector)) },
                        trailingIcon = pickTrailing(
                            baseUrl,
                            stringResource(TDMR.strings.custom_source_details_cover_selector),
                        ) {
                            detailsCoverSelector =
                                it
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = detailsAuthorSelector,
                        onValueChange = { detailsAuthorSelector = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_author_selector)) },
                        trailingIcon = pickTrailing(
                            baseUrl,
                            stringResource(TDMR.strings.custom_source_author_selector),
                        ) {
                            detailsAuthorSelector =
                                it
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = detailsGenreSelector,
                        onValueChange = { detailsGenreSelector = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_genre_selector)) },
                        trailingIcon = pickTrailing(
                            baseUrl,
                            stringResource(TDMR.strings.custom_source_genre_selector),
                        ) {
                            detailsGenreSelector =
                                it
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = detailsArtistSelector,
                        onValueChange = { detailsArtistSelector = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_artist_selector)) },
                        trailingIcon = pickTrailing(
                            baseUrl,
                            stringResource(TDMR.strings.custom_source_artist_selector),
                        ) { detailsArtistSelector = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = detailsStatusSelector,
                        onValueChange = { detailsStatusSelector = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_status_selector)) },
                        trailingIcon = pickTrailing(
                            baseUrl,
                            stringResource(TDMR.strings.custom_source_status_selector),
                        ) { detailsStatusSelector = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = statusMappingText,
                        onValueChange = { statusMappingText = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_status_mapping)) },
                        supportingText = {
                            Text(stringResource(TDMR.strings.custom_source_status_mapping_desc))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(stringResource(TDMR.strings.custom_source_chapters), fontWeight = FontWeight.Medium)
                    if (features.chapterGenerateFromPattern) {
                        // Generated chapter list (mode B): numeric URL pattern + range/count.
                        OutlinedTextField(
                            value = chapterUrlPattern,
                            onValueChange = { chapterUrlPattern = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_chapter_url_pattern)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("{novelUrl}/chapter-{n}") },
                            supportingText = {
                                Text(stringResource(TDMR.strings.custom_source_chapter_url_pattern_desc))
                            },
                        )
                        OutlinedTextField(
                            value = chapterCountSelector,
                            onValueChange = { chapterCountSelector = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_chapter_count_selector)) },
                            trailingIcon = pickTrailing(
                                baseUrl,
                                stringResource(TDMR.strings.custom_source_chapter_count_selector),
                            ) {
                                chapterCountSelector =
                                    it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = {
                                Text(stringResource(TDMR.strings.custom_source_chapter_count_selector_desc))
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = chapterFirstNumber,
                                onValueChange = { chapterFirstNumber = it.filter(Char::isDigit) },
                                label = { Text(stringResource(TDMR.strings.custom_source_chapter_first_number)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                supportingText = {
                                    Text(stringResource(TDMR.strings.custom_source_chapter_first_number_desc))
                                },
                            )
                            OutlinedTextField(
                                value = chapterLastNumber,
                                onValueChange = { chapterLastNumber = it.filter(Char::isDigit) },
                                label = { Text(stringResource(TDMR.strings.custom_source_chapter_last_number)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                supportingText = {
                                    Text(stringResource(TDMR.strings.custom_source_chapter_last_number_desc))
                                },
                            )
                        }
                        OutlinedTextField(
                            value = chapterNameTemplate,
                            onValueChange = { chapterNameTemplate = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_chapter_name_template)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Chapter {n}") },
                            supportingText = {
                                Text(stringResource(TDMR.strings.custom_source_chapter_name_template_desc))
                            },
                        )
                    } else {
                        OutlinedTextField(
                            value = chaptersListSelector,
                            onValueChange = { chaptersListSelector = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_chapter_list_selector)) },
                            trailingIcon = pickTrailing(
                                baseUrl,
                                stringResource(TDMR.strings.custom_source_chapter_list_selector),
                            ) {
                                chaptersListSelector =
                                    it
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = chapterLinkSelector,
                            onValueChange = { chapterLinkSelector = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_chapter_link_selector)) },
                            trailingIcon = pickTrailing(
                                baseUrl,
                                stringResource(TDMR.strings.custom_source_chapter_link_selector),
                            ) {
                                chapterLinkSelector =
                                    it
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = chapterNameSelector,
                            onValueChange = { chapterNameSelector = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_chapter_name_selector)) },
                            trailingIcon = pickTrailing(
                                baseUrl,
                                stringResource(TDMR.strings.custom_source_chapter_name_selector),
                            ) {
                                chapterNameSelector =
                                    it
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = chapterDateSelector,
                            onValueChange = { chapterDateSelector = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_chapter_date_selector)) },
                            trailingIcon = pickTrailing(
                                baseUrl,
                                stringResource(TDMR.strings.custom_source_chapter_date_selector),
                            ) {
                                chapterDateSelector =
                                    it
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (features.chapterListPagination) {
                            OutlinedTextField(
                                value = chapterNextPageSelector,
                                onValueChange = { chapterNextPageSelector = it },
                                label = {
                                    Text(stringResource(TDMR.strings.custom_source_chapter_pagination_selector))
                                },
                                trailingIcon = pickTrailing(
                                    baseUrl,
                                    stringResource(TDMR.strings.custom_source_chapter_pagination_selector),
                                ) {
                                    chapterNextPageSelector = it
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (features.chapterListSeparatePage) {
                            OutlinedTextField(
                                value = chapterIndexLinkSelector,
                                onValueChange = { chapterIndexLinkSelector = it },
                                label = { Text(stringResource(TDMR.strings.custom_source_chapter_index_selector)) },
                                trailingIcon = pickTrailing(
                                    baseUrl,
                                    stringResource(TDMR.strings.custom_source_chapter_index_selector),
                                ) {
                                    chapterIndexLinkSelector =
                                        it
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(stringResource(TDMR.strings.custom_source_chapter_content), fontWeight = FontWeight.Medium)
                    OutlinedTextField(
                        value = contentPrimarySelector,
                        onValueChange = { contentPrimarySelector = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_content_selector)) },
                        trailingIcon = pickTrailing(
                            baseUrl,
                            stringResource(TDMR.strings.custom_source_content_selector),
                        ) {
                            contentPrimarySelector =
                                it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(TDMR.strings.custom_source_content_selector_hint)) },
                    )
                    OutlinedTextField(
                        value = contentFallbacksSelector,
                        onValueChange = { contentFallbacksSelector = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_content_fallbacks)) },
                        trailingIcon = pickTrailing(
                            baseUrl,
                            stringResource(TDMR.strings.custom_source_content_fallbacks),
                        ) {
                            contentFallbacksSelector =
                                it
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = contentRemoveSelectors,
                        onValueChange = { contentRemoveSelectors = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_remove_selectors)) },
                        trailingIcon = pickTrailing(
                            baseUrl,
                            stringResource(TDMR.strings.custom_source_remove_selectors),
                        ) { contentRemoveSelectors = it },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text(stringResource(TDMR.strings.custom_source_remove_selectors_desc))
                        },
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(TDMR.strings.custom_source_advanced),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedTextField(
                        value = dateFormat,
                        onValueChange = { dateFormat = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_date_format)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("yyyy-MM-dd") },
                        supportingText = { Text(stringResource(TDMR.strings.custom_source_date_format_desc)) },
                    )
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_headers)) },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(stringResource(TDMR.strings.custom_source_headers_desc)) },
                    )
                    OutlinedTextField(
                        value = testSearchQuery,
                        onValueChange = { testSearchQuery = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_test_search_query)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(TDMR.strings.custom_source_test_search_query_desc)) },
                    )
                    OutlinedTextField(
                        value = sampleNovelUrl,
                        onValueChange = { sampleNovelUrl = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_sample_novel_url)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(TDMR.strings.custom_source_sample_novel_url_desc)) },
                    )
                } // end if (selectedBasedOnSourceId == null)

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                @Suppress("ktlint:standard:max-line-length")
                Button(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            errorMessage = null

                            val config = buildConfig(
                                existing = initialConfig,
                                name = name, baseUrl = baseUrl, popularUrl = popularUrl,
                                latestUrl = latestUrl, searchUrl = searchUrl,
                                popularListSelector = popularListSelector,
                                popularTitleSelector = popularTitleSelector,
                                popularCoverSelector = popularCoverSelector,
                                popularNextPageSelector = popularNextPageSelector,
                                latestNextPageSelector = latestNextPageSelector,
                                searchNextPageSelector = searchNextPageSelector,
                                detailsTitleSelector = detailsTitleSelector,
                                detailsDescriptionSelector = detailsDescriptionSelector,
                                detailsCoverSelector = detailsCoverSelector,
                                detailsAuthorSelector = detailsAuthorSelector,
                                detailsArtistSelector = detailsArtistSelector,
                                detailsGenreSelector = detailsGenreSelector,
                                detailsStatusSelector = detailsStatusSelector,
                                statusMappingText = statusMappingText,
                                chaptersListSelector = chaptersListSelector,
                                chapterLinkSelector = chapterLinkSelector,
                                chapterNameSelector = chapterNameSelector,
                                chapterDateSelector = chapterDateSelector,
                                chapterNextPageSelector = chapterNextPageSelector,
                                chapterIndexLinkSelector = chapterIndexLinkSelector,
                                chapterUrlPattern = chapterUrlPattern,
                                chapterCountSelector = chapterCountSelector,
                                chapterFirstNumber = chapterFirstNumber,
                                chapterLastNumber = chapterLastNumber,
                                chapterNameTemplate = chapterNameTemplate,
                                contentPrimarySelector = contentPrimarySelector,
                                contentFallbacksSelector = contentFallbacksSelector,
                                contentRemoveSelectors = contentRemoveSelectors,
                                dateFormat = dateFormat,
                                headersText = headersText,
                                testSearchQuery = testSearchQuery,
                                sampleNovelUrl = sampleNovelUrl,
                                existingId = sourceId,
                                reverseChapters = reverseChapters,
                                postSearch = postSearch,
                                basedOnSourceId = selectedBasedOnSourceId,
                                isNovel = isNovel,
                                language = language,
                                features = features,
                            )

                            val result = if (sourceId != null) {
                                screenModel.updateSource(sourceId, config)
                            } else {
                                screenModel.createSource(config)
                            }

                            isSaving = false

                            result.fold(
                                onSuccess = { navigator.pop() },
                                onFailure = { errorMessage = it.message },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving && name.isNotBlank() && baseUrl.isNotBlank() &&
                        (
                            selectedBasedOnSourceId != null || (
                                isNovel &&
                                    (
                                        !features.hasPopular ||
                                            (popularUrl.isNotBlank() && popularListSelector.isNotBlank())
                                        ) &&
                                    (!features.hasSearch || searchUrl.isNotBlank()) &&
                                    (features.hasPopular || features.hasLatest) &&
                                    detailsTitleSelector.isNotBlank() && contentPrimarySelector.isNotBlank() &&
                                    (
                                        if (features.chapterGenerateFromPattern) {
                                            chapterUrlPattern.isNotBlank()
                                        } else {
                                            chaptersListSelector.isNotBlank()
                                        }
                                        )
                                )
                            ),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.height(24.dp))
                    } else {
                        Text(stringResource(TDMR.strings.custom_source_save))
                    }
                }
            }
        }

        if (showBaseSourcePicker) {
            BaseSourcePickerDialog(
                selectedSourceId = selectedBasedOnSourceId,
                onDismiss = { showBaseSourcePicker = false },
                onPick = { _, sourceId, baseUrlValue, lang ->
                    selectedBasedOnSourceId = sourceId
                    baseUrl = baseUrlValue
                    language = lang
                    showBaseSourcePicker = false
                },
            )
        }

        pickerTarget?.let { setter ->
            eu.kanade.tachiyomi.ui.customsource.ElementPickerDialog(
                initialUrl = pickerUrl.ifBlank { baseUrl },
                label = pickerLabel,
                onDismiss = { pickerTarget = null },
                onPicked = { selector -> setter(selector) },
            )
        }
    }

    // Parses "word=ongoing, 完结=completed" into a status map. Blank/malformed entries are skipped.
    private fun parseStatusMapping(text: String): Map<String, String>? {
        val map = text.split(',').mapNotNull { entry ->
            val parts = entry.split('=', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val key = parts[0].trim()
            val value = parts[1].trim()
            if (key.isBlank() || value.isBlank()) null else key to value
        }.toMap()
        return map.ifEmpty { null }
    }

    private fun parseHeaders(text: String): Map<String, String> {
        return text.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val sep = trimmed.indexOfFirst { it == ':' || it == '=' }
            if (sep <= 0) return@mapNotNull null
            val key = trimmed.substring(0, sep).trim()
            val value = trimmed.substring(sep + 1).trim()
            if (key.isBlank() || value.isBlank()) null else key to value
        }.toMap()
    }

    private fun splitSelectorList(text: String): List<String>? =
        text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null }

    @Suppress("LongParameterList")
    private fun buildConfig(
        existing: CustomSourceConfig?,
        name: String,
        baseUrl: String,
        popularUrl: String,
        latestUrl: String,
        searchUrl: String,
        popularListSelector: String,
        popularTitleSelector: String,
        popularCoverSelector: String,
        popularNextPageSelector: String,
        latestNextPageSelector: String,
        searchNextPageSelector: String,
        detailsTitleSelector: String,
        detailsDescriptionSelector: String,
        detailsCoverSelector: String,
        detailsAuthorSelector: String,
        detailsArtistSelector: String,
        detailsGenreSelector: String,
        detailsStatusSelector: String,
        statusMappingText: String,
        chaptersListSelector: String,
        chapterLinkSelector: String,
        chapterNameSelector: String,
        chapterDateSelector: String,
        chapterNextPageSelector: String,
        chapterIndexLinkSelector: String,
        chapterUrlPattern: String,
        chapterCountSelector: String,
        chapterFirstNumber: String,
        chapterLastNumber: String,
        chapterNameTemplate: String,
        contentPrimarySelector: String,
        contentFallbacksSelector: String,
        contentRemoveSelectors: String,
        dateFormat: String,
        headersText: String,
        testSearchQuery: String,
        sampleNovelUrl: String,
        existingId: Long?,
        reverseChapters: Boolean,
        postSearch: Boolean,
        basedOnSourceId: Long? = null,
        isNovel: Boolean = true,
        language: String = "en",
        features: eu.kanade.tachiyomi.ui.customsource.SourceFeatures,
    ): CustomSourceConfig {
        // Gate every field by its section/mode toggle so unchecking a box actually clears the saved
        // data (otherwise a disabled section's stale URL/selector lingers and the box re-appears
        // ticked on the next edit). The shared list/title/cover inputs feed whichever listing is on.
        val cardTitle = popularTitleSelector.ifBlank { null }
        val cardCover = popularCoverSelector.ifBlank { null }
        val generate = features.chapterGenerateFromPattern
        val selectors = eu.kanade.tachiyomi.source.custom.SourceSelectors(
            popular = eu.kanade.tachiyomi.source.custom.MangaListSelectors(
                list = if (features.hasPopular) popularListSelector else "",
                title = if (features.hasPopular) cardTitle else null,
                cover = if (features.hasPopular) cardCover else null,
                nextPage = if (features.hasPopular && features.popularPagination) {
                    popularNextPageSelector.ifBlank { null }
                } else {
                    null
                },
            ),
            latest = if (features.hasLatest) {
                eu.kanade.tachiyomi.source.custom.MangaListSelectors(
                    list = popularListSelector,
                    title = cardTitle,
                    cover = cardCover,
                    nextPage = if (features.latestPagination) latestNextPageSelector.ifBlank { null } else null,
                )
            } else {
                null
            },
            search = if (features.hasSearch) {
                eu.kanade.tachiyomi.source.custom.MangaListSelectors(
                    list = popularListSelector,
                    title = cardTitle,
                    cover = cardCover,
                    nextPage = if (features.searchPagination) searchNextPageSelector.ifBlank { null } else null,
                )
            } else {
                null
            },
            details = eu.kanade.tachiyomi.source.custom.DetailSelectors(
                title = detailsTitleSelector,
                description = detailsDescriptionSelector.ifBlank { null },
                cover = detailsCoverSelector.ifBlank { null },
                author = detailsAuthorSelector.ifBlank { null },
                artist = detailsArtistSelector.ifBlank { null },
                genre = detailsGenreSelector.ifBlank { null },
                status = detailsStatusSelector.ifBlank { null },
            ),
            chapters = eu.kanade.tachiyomi.source.custom.ChapterSelectors(
                list = chaptersListSelector,
                link = if (generate) null else chapterLinkSelector.ifBlank { null },
                name = if (generate) null else chapterNameSelector.ifBlank { null },
                date = if (generate) null else chapterDateSelector.ifBlank { null },
                nextPage = if (!generate && features.chapterListPagination) {
                    chapterNextPageSelector.ifBlank { null }
                } else {
                    null
                },
                // No dedicated input; preserve the wizard-derived numbered-pagination template only
                // while chapter-list pagination stays enabled.
                pagedUrlPattern = if (!generate && features.chapterListPagination) {
                    existing?.selectors?.chapters?.pagedUrlPattern
                } else {
                    null
                },
                indexLinkSelector = if (!generate && features.chapterListSeparatePage) {
                    chapterIndexLinkSelector.ifBlank { null }
                } else {
                    null
                },
                urlPattern = if (generate) chapterUrlPattern.ifBlank { null } else null,
                countSelector = if (generate) chapterCountSelector.ifBlank { null } else null,
                firstNumber = if (generate) chapterFirstNumber.toIntOrNull() else null,
                lastNumber = if (generate) chapterLastNumber.toIntOrNull() else null,
                nameTemplate = if (generate) chapterNameTemplate.ifBlank { null } else null,
            ),
            content = eu.kanade.tachiyomi.source.custom.ContentSelectors(
                primary = contentPrimarySelector,
                fallbacks = splitSelectorList(contentFallbacksSelector),
                removeSelectors = splitSelectorList(contentRemoveSelectors),
                // No UI toggle for this; preserve whatever an imported config set instead of
                // resetting to the default on every edit.
                removeBoilerplate = existing?.selectors?.content?.removeBoilerplate ?: true,
            ),
        )

        // Start from the existing config so fields without a dedicated input survive a round-trip.
        val base = existing ?: CustomSourceConfig(
            name = name,
            baseUrl = baseUrl.trimEnd('/'),
            popularUrl = popularUrl,
            searchUrl = searchUrl,
            selectors = selectors,
        )
        return base.copy(
            name = name,
            baseUrl = baseUrl.trimEnd('/'),
            language = language,
            id = existingId,
            popularUrl = if (features.hasPopular) popularUrl else "",
            latestUrl = if (features.hasLatest) latestUrl.ifBlank { null } else null,
            searchUrl = if (features.hasSearch) searchUrl else "",
            // Per-section paged URLs are wizard-derived (no field here); keep them only while the
            // matching section + pagination toggle are both on.
            popularPagedUrl = if (features.hasPopular && features.popularPagination) base.popularPagedUrl else null,
            latestPagedUrl = if (features.hasLatest && features.latestPagination) base.latestPagedUrl else null,
            searchPagedUrl = if (features.hasSearch && features.searchPagination) base.searchPagedUrl else null,
            statusMapping = parseStatusMapping(statusMappingText),
            dateFormat = dateFormat.ifBlank { null },
            headers = parseHeaders(headersText),
            testSearchQuery = testSearchQuery.ifBlank { null },
            sampleNovelUrl = sampleNovelUrl.ifBlank { null },
            selectors = selectors,
            reverseChapters = reverseChapters,
            postSearch = postSearch,
            basedOnSourceId = basedOnSourceId,
            isNovel = isNovel,
        )
    }
}

@Composable
private fun SectionToggle(
    labelRes: dev.icerock.moko.resources.StringResource,
    checked: Boolean,
    indent: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 24.dp else 0.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BaseSourcePickerDialog(
    selectedSourceId: Long?,
    onDismiss: () -> Unit,
    onPick: (sourceName: String, sourceId: Long, baseUrl: String, lang: String) -> Unit,
) {
    val extensionManager: ExtensionManager = remember { Injekt.get() }
    val jsPluginManager: JsPluginManager = remember { Injekt.get() }
    val installedExtensions by extensionManager.installedExtensionsFlow.collectAsState()
    val jsSources by jsPluginManager.jsSources.collectAsState()

    data class SourceEntry(
        val sourceId: Long,
        val sourceName: String,
        val baseUrl: String,
        val extensionName: String,
        val lang: String,
    )

    val novelSources = remember(installedExtensions, jsSources) {
        val apkSources = installedExtensions.flatMap { ext ->
            ext.sources.mapNotNull { source ->
                val httpSource = source as? HttpSource ?: return@mapNotNull null
                SourceEntry(
                    sourceId = httpSource.id,
                    sourceName = httpSource.name,
                    baseUrl = httpSource.baseUrl,
                    extensionName = ext.name,
                    lang = httpSource.lang,
                )
            }
        }

        val jsEntries = jsSources.mapNotNull { source ->
            val jsSource = source as? eu.kanade.tachiyomi.jsplugin.source.JsSource ?: return@mapNotNull null
            SourceEntry(
                sourceId = jsSource.id,
                sourceName = jsSource.name,
                baseUrl = jsSource.baseUrl,
                extensionName = "[JS] ${jsSource.name}",
                lang = jsSource.lang,
            )
        }

        (apkSources + jsEntries).sortedBy { it.extensionName }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.custom_source_base_on_extension)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (novelSources.isEmpty()) {
                    Text(
                        text = stringResource(TDMR.strings.custom_source_no_extensions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(TDMR.strings.custom_source_pick_extension_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    novelSources.forEach { entry ->
                        val isSelected = selectedSourceId == entry.sourceId
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                            onClick = { onPick(entry.sourceName, entry.sourceId, entry.baseUrl, entry.lang) },
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = entry.sourceName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "${entry.extensionName} · ${entry.lang} · ${entry.baseUrl}",
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
