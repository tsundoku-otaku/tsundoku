package eu.kanade.tachiyomi.ui.customsource

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.extension.NovelExtensionReposScreen
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.i18n.stringResource as ctxStringResource
import eu.kanade.tachiyomi.source.CatalogueSource

private data class SourceTemplateOption(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val type: String,
)

/**
 * Entry screen for creating a custom novel source via WebView element selection.
 */
class CreateCustomSourceScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        var showInstalledTemplateDialog by remember { mutableStateOf(false) }
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val installedTemplateSources = remember {
            sourceManager.getAll().filterIsInstance<CatalogueSource>()
                .mapNotNull { source ->
                    val baseUrl = when (source) {
                        is HttpSource -> source.baseUrl
                        is JsSource -> source.baseUrl
                        else -> null
                    }?.trim()
                    if (baseUrl.isNullOrBlank() || !baseUrl.startsWith("http")) return@mapNotNull null
                    SourceTemplateOption(
                        id = source.id,
                        name = source.name,
                        baseUrl = baseUrl,
                        type = if (source is JsSource) "JS" else "KT",
                    )
                }
                .sortedBy { it.name.lowercase() }
        }

        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Header
                Icon(
                    Icons.Filled.Code,
                    contentDescription = null,
                    modifier = Modifier.height(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(TDMR.strings.custom_source_create_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(TDMR.strings.custom_source_create_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Method selection cards
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    onClick = { navigator.push(WizardSetupScreen()) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Launch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.weight(0.1f))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(TDMR.strings.custom_source_method_guided_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = stringResource(TDMR.strings.custom_source_method_guided_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Alternative: Import JSON config
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    onClick = { showInstalledTemplateDialog = true },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(modifier = Modifier.weight(0.1f))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(TDMR.strings.custom_source_method_template_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = stringResource(TDMR.strings.custom_source_method_template_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                    onClick = { navigator.push(NovelExtensionReposScreen()) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(modifier = Modifier.weight(0.1f))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(TDMR.strings.custom_source_method_repos_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(
                                text = stringResource(TDMR.strings.custom_source_method_repos_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Info text
                Text(
                    text = stringResource(TDMR.strings.custom_source_steps_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                listOf(
                    stringResource(TDMR.strings.custom_source_step_1),
                    stringResource(TDMR.strings.custom_source_step_2),
                    stringResource(TDMR.strings.custom_source_step_3),
                    stringResource(TDMR.strings.custom_source_step_4),
                    stringResource(TDMR.strings.custom_source_step_5),
                    stringResource(TDMR.strings.custom_source_step_6),
                    stringResource(TDMR.strings.custom_source_step_7),
                    stringResource(TDMR.strings.custom_source_step_8),
                ).forEach { step ->
                    Text(
                        text = step,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (showInstalledTemplateDialog) {
            InstalledTemplateDialog(
                templates = installedTemplateSources,
                onDismiss = { showInstalledTemplateDialog = false },
                onStart = { selected, nameOverride, urlOverride ->
                    showInstalledTemplateDialog = false
                    navigator.push(
                        ElementSelectorVoyagerScreen(
                            initialUrl = urlOverride,
                            initialSourceName = nameOverride.ifBlank { selected.name },
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun FeatureToggle(
    labelRes: dev.icerock.moko.resources.StringResource,
    checked: Boolean,
    indent: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(start = if (indent) 24.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Pre-wizard setup page: enter the site URL and choose which sections/pagination the source has,
 * so the WebView wizard only walks the user through the steps that apply.
 */
class WizardSetupScreen(
    private val initialUrl: String = "",
) : Screen {

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        var websiteUrl by remember { mutableStateOf(initialUrl) }
        var features by remember { mutableStateOf(SourceFeatures()) }
        // At least one listing is required to reach novels in the browse UI.
        val canStart = websiteUrl.isNotBlank() && (features.hasPopular || features.hasLatest)

        Scaffold(
            topBar = {
                androidx.compose.material3.TopAppBar(
                    title = { Text(stringResource(TDMR.strings.custom_source_url_dialog_title)) },
                    navigationIcon = {
                        androidx.compose.material3.IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(MR.strings.action_webview_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                Text(
                    text = stringResource(TDMR.strings.custom_source_url_dialog_message),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Dynamic-site caveat: HTML parsing can't see JS-rendered content.
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        text = stringResource(TDMR.strings.selector_setup_dynamic_notice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = websiteUrl,
                    onValueChange = { websiteUrl = it },
                    label = { Text(stringResource(TDMR.strings.custom_source_url_label)) },
                    placeholder = { Text("https://example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(TDMR.strings.selector_features_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(TDMR.strings.selector_features_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FeatureToggle(TDMR.strings.selector_feature_popular, features.hasPopular) {
                    features = features.copy(hasPopular = it)
                }
                if (features.hasPopular) {
                    FeatureToggle(
                        TDMR.strings.selector_feature_popular_pagination,
                        features.popularPagination,
                        indent = true,
                    ) { features = features.copy(popularPagination = it) }
                }
                FeatureToggle(TDMR.strings.selector_feature_latest, features.hasLatest) {
                    features = features.copy(hasLatest = it)
                }
                if (features.hasLatest) {
                    FeatureToggle(
                        TDMR.strings.selector_feature_latest_pagination,
                        features.latestPagination,
                        indent = true,
                    ) { features = features.copy(latestPagination = it) }
                }
                FeatureToggle(TDMR.strings.selector_feature_search, features.hasSearch) {
                    features = features.copy(hasSearch = it)
                }
                if (features.hasSearch) {
                    FeatureToggle(
                        TDMR.strings.selector_feature_search_pagination,
                        features.searchPagination,
                        indent = true,
                    ) { features = features.copy(searchPagination = it) }
                }
                FeatureToggle(
                    TDMR.strings.selector_feature_chapter_generate,
                    features.chapterGenerateFromPattern,
                ) { features = features.copy(chapterGenerateFromPattern = it) }
                if (!features.chapterGenerateFromPattern) {
                    FeatureToggle(
                        TDMR.strings.selector_feature_chapter_separate_page,
                        features.chapterListSeparatePage,
                    ) { features = features.copy(chapterListSeparatePage = it) }
                    FeatureToggle(
                        TDMR.strings.selector_feature_chapter_pagination,
                        features.chapterListPagination,
                    ) { features = features.copy(chapterListPagination = it) }
                }
                // Content pagination (a single chapter split across pages) is rarely useful for
                // novels — set it in the manual editor when actually needed.

                if (!features.hasPopular && !features.hasLatest) {
                    Text(
                        text = stringResource(TDMR.strings.selector_features_need_listing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        val url = if (!websiteUrl.startsWith("http")) "https://$websiteUrl" else websiteUrl
                        navigator.push(ElementSelectorVoyagerScreen(initialUrl = url, features = features))
                    },
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(TDMR.strings.custom_source_start_wizard))
                }
            }
        }
    }
}

@Composable
private fun InstalledTemplateDialog(
    templates: List<SourceTemplateOption>,
    onDismiss: () -> Unit,
    onStart: (SourceTemplateOption, String, String) -> Unit,
) {
    var selected by remember { mutableStateOf<SourceTemplateOption?>(null) }
    var sourceName by remember { mutableStateOf("") }
    var sourceUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(TDMR.strings.custom_source_choose_installed)) },
        text = {
            Column {
                if (templates.isEmpty()) {
                    Text(
                        text = stringResource(TDMR.strings.custom_source_choose_installed_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text = stringResource(TDMR.strings.custom_source_choose_installed_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    ) {
                        templates.forEach { template ->
                            OutlinedButton(
                                onClick = {
                                    selected = template
                                    sourceName = template.name
                                    sourceUrl = template.baseUrl
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            ) {
                                Text("${template.name} (${template.type})")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = sourceName,
                        onValueChange = { sourceName = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_source_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sourceUrl,
                        onValueChange = { sourceUrl = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_base_url)) },
                        placeholder = { Text("https://example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selectedTemplate = selected ?: return@Button
                    val normalizedUrl = if (sourceUrl.startsWith("http")) sourceUrl else "https://$sourceUrl"
                    onStart(selectedTemplate, sourceName.trim(), normalizedUrl.trim())
                },
                enabled = selected != null && sourceUrl.isNotBlank(),
            ) {
                Text(stringResource(TDMR.strings.custom_source_use_template))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

/**
 * Voyager screen wrapper for the Element Selector
 */
class ElementSelectorVoyagerScreen(
    private val initialUrl: String,
    private val initialSourceName: String = "",
    private val features: SourceFeatures = SourceFeatures(),
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { ElementSelectorScreenModel(initialUrl, initialSourceName) }
        val state by screenModel.state.collectAsState()

        // Handle success
        LaunchedEffect(state.savedSuccessfully) {
            if (state.savedSuccessfully) {
                Toast.makeText(
                    context,
                    context.ctxStringResource(TDMR.strings.custom_source_created),
                    Toast.LENGTH_LONG,
                ).show()
                navigator.popUntilRoot()
            }
        }

        // Handle error
        LaunchedEffect(state.error) {
            state.error?.let { error ->
                Toast.makeText(
                    context,
                    context.ctxStringResource(TDMR.strings.custom_source_error_format, error),
                    Toast.LENGTH_LONG,
                ).show()
                screenModel.clearError()
            }
        }

        ElementSelectorScreen(
            screenModel = screenModel,
            initialUrl = initialUrl,
            initialSourceName = initialSourceName,
            features = features,
            onNavigateUp = { navigator.pop() },
            onSaveConfig = { config ->
                screenModel.saveConfig(config)
            },
            onTestConfig = { config, section, onResult ->
                screenModel.testConfig(config, section, onResult)
            },
        )
    }
}
