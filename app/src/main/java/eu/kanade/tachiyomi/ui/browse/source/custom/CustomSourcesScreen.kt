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
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileUpload
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.source.custom.CustomNovelSource
import eu.kanade.tachiyomi.source.custom.CustomSourceConfig
import eu.kanade.tachiyomi.source.custom.CustomSourceManager
import tachiyomi.domain.source.service.SourceManager
import eu.kanade.tachiyomi.source.custom.SourceTestResult
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
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

        // File picker for import
        val importLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            uri?.let {
                scope.launch {
                    try {
                        val inputStream = context.contentResolver.openInputStream(it)
                        val json = inputStream?.bufferedReader()?.use { reader -> reader.readText() } ?: ""
                        inputStream?.close()

                        val result = screenModel.importSource(json)
                        result.fold(
                            onSuccess = {
                                snackbarHostState.showSnackbar(context.stringResource(TDMR.strings.custom_source_imported))
                            },
                            onFailure = { e ->
                                snackbarHostState.showSnackbar(context.stringResource(TDMR.strings.custom_source_import_failed, e.message ?: ""))
                            },
                        )
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(context.stringResource(TDMR.strings.custom_source_read_error, e.message ?: ""))
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
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(MR.strings.action_webview_back))
                        }
                    },
                    actions = {
                        // Import button
                        IconButton(onClick = { importLauncher.launch("application/json") }) {
                            Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(TDMR.strings.custom_sources_import_source))
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(TDMR.strings.custom_sources_add_source))
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
                    onImportClick = { importLauncher.launch("application/json") },
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
                                                putExtra(Intent.EXTRA_SUBJECT, context.stringResource(TDMR.strings.custom_source_export_subject, source.name))
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }

                                            context.startActivity(Intent.createChooser(shareIntent, context.stringResource(TDMR.strings.custom_source_export_title)))
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar(context.stringResource(TDMR.strings.custom_source_export_failed, e.message ?: ""))
                                        }
                                    } else {
                                        snackbarHostState.showSnackbar(context.stringResource(TDMR.strings.custom_source_export_error))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        // Create dialog
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
                    navigator.push(eu.kanade.tachiyomi.ui.customsource.ElementSelectorVoyagerScreen(baseUrl))
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

        // Delete confirmation
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
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(TDMR.strings.custom_sources_test))
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

                // WebView selector option
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

                // Base on Extension option
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

                // Hint about extension repos for pre-built themes
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

    // Extension picker dialog
    if (showExtensionPicker) {
        ExtensionSourcePickerDialog(
            onDismiss = { showExtensionPicker = false },
            onPick = { pickedName, pickedBaseUrl, pickedSourceId ->
                showExtensionPicker = false
                onBaseOnExtension(pickedName, pickedBaseUrl, pickedSourceId)
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
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(MR.strings.action_webview_back))
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
            text = if (result.overallSuccess) stringResource(TDMR.strings.custom_source_all_tests_passed) else stringResource(TDMR.strings.custom_source_some_tests_failed),
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

        // Resolve effective basedOnSourceId: prefer constructor param, fall back to loaded config
        val effectiveBasedOnSourceId = remember {
            basedOnSourceId ?: initialConfig?.basedOnSourceId
        }

        // Resolve language from base source when available
        val baseLang = remember(effectiveBasedOnSourceId) {
            effectiveBasedOnSourceId?.let { id ->
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

        // New fields for source type and cloudflare
        var useCloudflare by remember { mutableStateOf(initialConfig?.useCloudflare ?: true) }
        var reverseChapters by remember { mutableStateOf(initialConfig?.reverseChapters ?: false) }
        var postSearch by remember { mutableStateOf(initialConfig?.postSearch ?: false) }
        var isNovel by remember { mutableStateOf(initialConfig?.isNovel ?: true) }

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
        var detailsTitleSelector by remember {
            mutableStateOf(initialConfig?.selectors?.details?.title ?: "")
        }
        var detailsDescriptionSelector by remember {
            mutableStateOf(initialConfig?.selectors?.details?.description ?: "")
        }
        var chaptersListSelector by remember {
            mutableStateOf(initialConfig?.selectors?.chapters?.list ?: "")
        }
        var contentPrimarySelector by remember {
            mutableStateOf(initialConfig?.selectors?.content?.primary ?: "")
        }

        var isSaving by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (sourceId != null) stringResource(TDMR.strings.custom_source_edit_title) else stringResource(TDMR.strings.custom_source_create_source)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(MR.strings.action_webview_back))
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
                // Basic Info Section
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
                    label = { Text("Language code (e.g. en, ja, zh)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Show info card when based on extension
                if (effectiveBasedOnSourceId != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(TDMR.strings.custom_source_base_on_extension),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = stringResource(TDMR.strings.custom_source_base_on_extension_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }

                // Source Options Section
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(TDMR.strings.custom_source_options),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Cloudflare option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = useCloudflare,
                        onCheckedChange = { useCloudflare = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(stringResource(TDMR.strings.custom_source_use_cloudflare), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(TDMR.strings.custom_source_use_cloudflare_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Reverse chapters option
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
                        Text(stringResource(TDMR.strings.custom_source_reverse_chapters), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(TDMR.strings.custom_source_reverse_chapters_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // POST search option
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
                        Text(stringResource(TDMR.strings.custom_source_post_search), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(TDMR.strings.custom_source_post_search_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Novel source type option
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
                        Text(stringResource(TDMR.strings.custom_source_novel_source), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(TDMR.strings.custom_source_novel_source_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // URLs Section (only for manual/selector-based sources)
                if (effectiveBasedOnSourceId == null) {
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

                OutlinedTextField(
                    value = popularUrl,
                    onValueChange = { popularUrl = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_popular_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(TDMR.strings.custom_source_popular_url_hint)) },
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = latestUrl,
                    onValueChange = { latestUrl = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_latest_url)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchUrl,
                    onValueChange = { searchUrl = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_search_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(TDMR.strings.custom_source_search_url_hint)) },
                )

                // Selectors Section
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(TDMR.strings.custom_source_css_selectors),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(TDMR.strings.custom_source_css_selectors_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Popular/List selectors
                Text(stringResource(TDMR.strings.custom_source_novel_list), fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = popularListSelector,
                    onValueChange = { popularListSelector = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_list_item_selector)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(TDMR.strings.custom_source_list_item_hint)) },
                )
                OutlinedTextField(
                    value = popularTitleSelector,
                    onValueChange = { popularTitleSelector = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_title_selector)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = popularCoverSelector,
                    onValueChange = { popularCoverSelector = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_cover_selector)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Details selectors
                Text(stringResource(TDMR.strings.custom_source_novel_details), fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = detailsTitleSelector,
                    onValueChange = { detailsTitleSelector = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_title_selector_required)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = detailsDescriptionSelector,
                    onValueChange = { detailsDescriptionSelector = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_description_selector)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Chapter selectors
                Text(stringResource(TDMR.strings.custom_source_chapters), fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = chaptersListSelector,
                    onValueChange = { chaptersListSelector = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_chapter_list_selector)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Content selector
                Text(stringResource(TDMR.strings.custom_source_chapter_content), fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = contentPrimarySelector,
                    onValueChange = { contentPrimarySelector = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_content_selector)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(TDMR.strings.custom_source_content_selector_hint)) },
                )
                } // end if (effectiveBasedOnSourceId == null)

                // Error message
                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // Save button
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            errorMessage = null

                            val config = buildConfig(
                                name, baseUrl, popularUrl, latestUrl, searchUrl,
                                popularListSelector, popularTitleSelector, popularCoverSelector,
                                detailsTitleSelector, detailsDescriptionSelector,
                                chaptersListSelector, contentPrimarySelector,
                                sourceId, useCloudflare, reverseChapters, postSearch,
                                effectiveBasedOnSourceId, isNovel, language,
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
                        (effectiveBasedOnSourceId != null || (
                            popularUrl.isNotBlank() && searchUrl.isNotBlank() &&
                            popularListSelector.isNotBlank() && detailsTitleSelector.isNotBlank() &&
                            chaptersListSelector.isNotBlank() && contentPrimarySelector.isNotBlank()
                        )),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.height(24.dp))
                    } else {
                        Text(stringResource(TDMR.strings.custom_source_save))
                    }
                }
            }
        }
    }

    private fun buildConfig(
        name: String,
        baseUrl: String,
        popularUrl: String,
        latestUrl: String,
        searchUrl: String,
        popularListSelector: String,
        popularTitleSelector: String,
        popularCoverSelector: String,
        detailsTitleSelector: String,
        detailsDescriptionSelector: String,
        chaptersListSelector: String,
        contentPrimarySelector: String,
        existingId: Long?,
        useCloudflare: Boolean,
        reverseChapters: Boolean,
        postSearch: Boolean,
        basedOnSourceId: Long? = null,
        isNovel: Boolean = true,
        language: String = "en",
    ): CustomSourceConfig {
        return CustomSourceConfig(
            name = name,
            baseUrl = baseUrl.trimEnd('/'),
            language = language,
            id = existingId,
            popularUrl = popularUrl,
            latestUrl = latestUrl.ifBlank { null },
            searchUrl = searchUrl,
            selectors = eu.kanade.tachiyomi.source.custom.SourceSelectors(
                popular = eu.kanade.tachiyomi.source.custom.MangaListSelectors(
                    list = popularListSelector,
                    title = popularTitleSelector.ifBlank { null },
                    cover = popularCoverSelector.ifBlank { null },
                ),
                details = eu.kanade.tachiyomi.source.custom.DetailSelectors(
                    title = detailsTitleSelector,
                    description = detailsDescriptionSelector.ifBlank { null },
                ),
                chapters = eu.kanade.tachiyomi.source.custom.ChapterSelectors(
                    list = chaptersListSelector,
                ),
                content = eu.kanade.tachiyomi.source.custom.ContentSelectors(
                    primary = contentPrimarySelector,
                ),
            ),
            useCloudflare = useCloudflare,
            reverseChapters = reverseChapters,
            postSearch = postSearch,
            basedOnSourceId = basedOnSourceId,
            isNovel = isNovel,
        )
    }
}

/**
 * Dialog to pick an installed extension source to base a custom source on.
 * Shows all installed extensions (APK and JS plugins).
 */
@Composable
private fun ExtensionSourcePickerDialog(
    onDismiss: () -> Unit,
    onPick: (name: String, baseUrl: String, sourceId: Long) -> Unit,
) {
    val extensionManager: ExtensionManager = remember { Injekt.get() }
    val jsPluginManager: JsPluginManager = remember { Injekt.get() }
    val installedExtensions by extensionManager.installedExtensionsFlow.collectAsState()
    val jsSources by jsPluginManager.jsSources.collectAsState()

    // Collect novel extension sources: extension name → list of (Source, ExtensionName)
    data class SourceEntry(val sourceId: Long, val sourceName: String, val baseUrl: String, val extensionName: String, val lang: String)

    val novelSources = remember(installedExtensions, jsSources) {
        val apkSources = installedExtensions
            .flatMap { ext ->
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

    var nameOverride by remember { mutableStateOf("") }
    var baseUrlOverride by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<SourceEntry?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.custom_source_pick_extension)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
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
                        val isSelected = selectedSource == entry
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
                            onClick = {
                                selectedSource = entry
                                nameOverride = entry.sourceName + " (Custom)"
                                baseUrlOverride = entry.baseUrl
                            },
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

                    // Override fields (shown when a source is selected)
                    if (selectedSource != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = nameOverride,
                            onValueChange = { nameOverride = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_source_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = baseUrlOverride,
                            onValueChange = { baseUrlOverride = it },
                            label = { Text(stringResource(TDMR.strings.custom_source_base_url)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedSource?.let { entry ->
                        onPick(nameOverride, baseUrlOverride, entry.sourceId)
                    }
                },
                enabled = selectedSource != null && nameOverride.isNotBlank() && baseUrlOverride.startsWith("http"),
            ) {
                Text(stringResource(MR.strings.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
