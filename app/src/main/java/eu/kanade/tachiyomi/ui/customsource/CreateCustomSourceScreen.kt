package eu.kanade.tachiyomi.ui.customsource

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.core.common.i18n.stringResource as ctxStringResource

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

/** Pre-wizard page: enter the site URL and choose sections/pagination so the wizard shows only needed steps. */
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
                    .verticalScroll(rememberScrollState()),
            ) {
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
                // Content pagination (one chapter split across pages) is set in the manual editor.

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

/** Voyager wrapper for the element selector wizard. */
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
                screenModel.saveConfig(config, features)
            },
            onTestConfig = { config, section, onResult ->
                screenModel.testConfig(config, features, section, onResult)
            },
        )
    }
}
