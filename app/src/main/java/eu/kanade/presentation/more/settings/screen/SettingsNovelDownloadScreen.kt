package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.RateLimited
import eu.kanade.tachiyomi.source.isNovelSource
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.download.service.NovelDownloadPreferences
import tachiyomi.domain.download.service.NovelDownloadPreferences.Companion.SourceOverride
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsNovelDownloadScreen : SearchableSettings {

    @Suppress("unused")
    private fun readResolve(): Any = this

    private const val LOW_DELAY_THRESHOLD_MS = 3000

    override val supportsReset: Boolean get() = true

    @Composable
    override fun getAdditionalResetPreferences(): List<tachiyomi.core.common.preference.Preference<*>> {
        val prefs = remember { Injekt.get<NovelDownloadPreferences>() }
        return listOf(
            prefs.requestDelay(),
            prefs.requestJitter(),
            prefs.parallelNovelDownloads(),
            prefs.maxImageSizeKb(),
            prefs.imageCompressionQuality(),
            prefs.parallelNovelUpdates(),
            prefs.parallelMassImport(),
        )
    }

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = TDMR.strings.pref_category_novel_downloads

    @Composable
    override fun getPreferences(): List<Preference> {
        val novelDownloadPreferences = remember { Injekt.get<NovelDownloadPreferences>() }
        val downloadPreferences = remember { Injekt.get<DownloadPreferences>() }

        // Dialog state for per-extension overrides
        var showOverridesDialog by remember { mutableStateOf(false) }
        var showAddOverrideDialog by remember { mutableStateOf(false) }
        var editingOverride by remember { mutableStateOf<SourceOverride?>(null) }

        if (showOverridesDialog) {
            PerExtensionOverridesDialog(
                prefs = novelDownloadPreferences,
                onDismissRequest = { showOverridesDialog = false },
                onAddNew = {
                    editingOverride = null
                    showAddOverrideDialog = true
                },
                onEdit = { override ->
                    editingOverride = override
                    showAddOverrideDialog = true
                },
            )
        }

        if (showAddOverrideDialog) {
            AddEditOverrideDialog(
                prefs = novelDownloadPreferences,
                existing = editingOverride,
                onDismissRequest = {
                    showAddOverrideDialog = false
                    editingOverride = null
                },
                onSaved = {
                    showAddOverrideDialog = false
                    editingOverride = null
                    // Reopen the overrides dialog to show updated list
                    showOverridesDialog = true
                },
            )
        }

        return listOf(
            getRequestThrottlingGroup(novelDownloadPreferences),
            getDownloadSettingsGroup(novelDownloadPreferences),
            getImageEmbeddingGroup(novelDownloadPreferences),
            getUpdateSettingsGroup(novelDownloadPreferences),
            getMassImportSettingsGroup(novelDownloadPreferences),
            getPerExtensionGroup(novelDownloadPreferences) { showOverridesDialog = true },
        )
    }

    @Composable
    private fun getRequestThrottlingGroup(
        prefs: NovelDownloadPreferences,
    ): Preference.PreferenceGroup {
        val enabled = prefs.enableRequestThrottling().collectAsState().value
        val requestDelay = prefs.requestDelay().collectAsState().value
        val requestJitter = prefs.requestJitter().collectAsState().value

        val lowDelayWarning = if (requestDelay < LOW_DELAY_THRESHOLD_MS && enabled) {
            stringResource(TDMR.strings.pref_novel_low_delay_warning)
        } else {
            ""
        }

        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_novel_request_throttling_category),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.enableRequestThrottling(),
                    title = stringResource(TDMR.strings.pref_novel_request_throttling),
                    subtitle = stringResource(TDMR.strings.pref_novel_request_throttling_summary),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = requestDelay,
                    valueRange = 0..120000,
                    steps = 1000,
                    title = stringResource(TDMR.strings.pref_novel_request_delay),
                    subtitle = stringResource(TDMR.strings.pref_novel_request_delay_summary) + lowDelayWarning,
                    valueString = "${requestDelay}ms",
                    onValueChanged = { prefs.requestDelay().set(it) },
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = requestJitter,
                    valueRange = 0..10000,
                    steps = 1000,
                    title = stringResource(TDMR.strings.pref_novel_request_jitter),
                    subtitle = stringResource(TDMR.strings.pref_novel_request_jitter_summary),
                    valueString = "0-${requestJitter}ms",
                    onValueChanged = { prefs.requestJitter().set(it) },
                    enabled = enabled,
                ),
            ),
        )
    }

    @Composable
    private fun getDownloadSettingsGroup(
        prefs: NovelDownloadPreferences,
    ): Preference.PreferenceGroup {
        val downloadPreferences = Injekt.get<DownloadPreferences>()
        val parallelDownloads = prefs.parallelNovelDownloads().collectAsState().value
        val compressionLevel = prefs.zipCompressionLevel().collectAsState().value
        val epubCompressionLevel = downloadPreferences.epubCompressionLevel.collectAsState().value

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_downloads),
            preferenceItems = listOf(
                Preference.PreferenceItem.SliderPreference(
                    value = parallelDownloads,
                    valueRange = 1..50,
                    title = stringResource(TDMR.strings.pref_novel_parallel_downloads),
                    onValueChanged = { prefs.parallelNovelDownloads().set(it) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.resumeQueueOnNewChapters(),
                    title = stringResource(TDMR.strings.pref_novel_resume_queue_on_new),
                    subtitle = stringResource(TDMR.strings.pref_novel_resume_queue_on_new_summary),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = compressionLevel,
                    valueRange = 0..9,
                    title = stringResource(TDMR.strings.pref_novel_compression_level),
                    subtitle = stringResource(TDMR.strings.pref_novel_compression_level_summary),
                    valueString = if (compressionLevel == 0) {
                        stringResource(MR.strings.action_save) + " (store)"
                    } else {
                        stringResource(TDMR.strings.pref_novel_deflate_level, compressionLevel)
                    },
                    onValueChanged = { prefs.zipCompressionLevel().set(it) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = epubCompressionLevel + 1,
                    valueRange = 0..10,
                    title = stringResource(MR.strings.pref_epub_compression_level),
                    subtitle = when (epubCompressionLevel) {
                        -1 -> stringResource(MR.strings.pref_epub_compression_default)
                        0 -> stringResource(MR.strings.pref_epub_compression_none)
                        in 1..3 -> stringResource(MR.strings.pref_epub_compression_low)
                        in 4..6 -> stringResource(MR.strings.pref_epub_compression_medium)
                        in 7..9 -> stringResource(MR.strings.pref_epub_compression_high)
                        else -> stringResource(MR.strings.pref_epub_compression_level_label, epubCompressionLevel)
                    },
                    valueString = if (epubCompressionLevel == -1) {
                        stringResource(MR.strings.pref_epub_compression_default_label)
                    } else {
                        "$epubCompressionLevel"
                    },
                    onValueChanged = { downloadPreferences.epubCompressionLevel.set(it - 1) },
                ),
            ),
        )
    }

    @Composable
    private fun getImageEmbeddingGroup(
        prefs: NovelDownloadPreferences,
    ): Preference.PreferenceGroup {
        val enabled = prefs.downloadChapterImages().collectAsState().value
        val maxSizeKb = prefs.maxImageSizeKb().collectAsState().value
        val compressionQuality = prefs.imageCompressionQuality().collectAsState().value

        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_category_novel_images),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.downloadChapterImages(),
                    title = stringResource(TDMR.strings.pref_novel_download_images),
                    subtitle = stringResource(TDMR.strings.pref_novel_download_images_summary),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = maxSizeKb,
                    valueRange = 0..2000,
                    title = stringResource(TDMR.strings.pref_novel_max_image_size),
                    subtitle = stringResource(TDMR.strings.pref_novel_max_image_size_summary),
                    valueString = if (maxSizeKb == 0) stringResource(TDMR.strings.no_limit) else "${maxSizeKb}KB",
                    onValueChanged = { prefs.maxImageSizeKb().set(it) },
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = compressionQuality,
                    valueRange = 10..100,
                    title = stringResource(TDMR.strings.pref_novel_image_quality),
                    subtitle = stringResource(TDMR.strings.pref_novel_image_quality_summary),
                    valueString = "$compressionQuality%",
                    onValueChanged = { prefs.imageCompressionQuality().set(it) },
                    enabled = enabled,
                ),
            ),
        )
    }

    @Composable
    private fun getUpdateSettingsGroup(
        prefs: NovelDownloadPreferences,
    ): Preference.PreferenceGroup {
        val parallelUpdates = prefs.parallelNovelUpdates().collectAsState().value

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_library_update),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.enableUpdateStaggering(),
                    title = stringResource(TDMR.strings.pref_novel_update_stagger),
                    subtitle = stringResource(TDMR.strings.pref_novel_update_staggersummary),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = parallelUpdates,
                    valueRange = 1..15,
                    title = stringResource(TDMR.strings.pref_novel_parallel_updates),
                    subtitle = stringResource(TDMR.strings.pref_novel_parallel_updates_summary),
                    valueString = "$parallelUpdates",
                    onValueChanged = { prefs.parallelNovelUpdates().set(it) },
                ),
            ),
        )
    }

    @Composable
    private fun getMassImportSettingsGroup(
        prefs: NovelDownloadPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_novel_mass_import_category),
            preferenceItems = listOf(
                Preference.PreferenceItem.SliderPreference(
                    value = prefs.parallelMassImport().collectAsState().value,
                    valueRange = 1..30,
                    title = stringResource(TDMR.strings.pref_novel_concurrent_imports),
                    subtitle = stringResource(TDMR.strings.pref_novel_concurrent_imports_summary),
                    valueString = "${prefs.parallelMassImport().collectAsState().value}",
                    onValueChanged = { prefs.parallelMassImport().set(it) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = prefs.skipSourceIfFailedXTimes().collectAsState().value,
                    valueRange = 0..20,
                    title = stringResource(TDMR.strings.skip_source_on_consecutive_errors),
                    subtitle = stringResource(TDMR.strings.skip_source_on_consecutive_errors_description),
                    valueString = if (prefs.skipSourceIfFailedXTimes().collectAsState().value ==
                        0
                    ) {
                        "Disabled"
                    } else {
                        "After ${prefs.skipSourceIfFailedXTimes().collectAsState().value} errors"
                    },
                    onValueChanged = { prefs.skipSourceIfFailedXTimes().set(it) },
                ),
            ),
        )
    }

    @Composable
    private fun getPerExtensionGroup(
        prefs: NovelDownloadPreferences,
        onManageClick: () -> Unit,
    ): Preference.PreferenceGroup {
        val overrides = remember(prefs.sourceOverrides().collectAsState().value) {
            prefs.getAllSourceOverrides()
        }
        return Preference.PreferenceGroup(
            title = "Per-Extension Overrides",
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = "Manage source overrides",
                    subtitle = "${overrides.size} override(s) configured",
                    onClick = onManageClick,
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = "Override throttle delays for specific extensions. " +
                        "Overrides take priority over global settings.",
                ),
            ),
        )
    }

    @Composable
    private fun PerExtensionOverridesDialog(
        prefs: NovelDownloadPreferences,
        onDismissRequest: () -> Unit,
        onAddNew: () -> Unit,
        onEdit: (SourceOverride) -> Unit,
    ) {
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val overrides = remember(prefs.sourceOverrides().collectAsState().value) {
            prefs.getAllSourceOverrides()
                .sortedBy { override ->
                    sourceManager.get(override.sourceId)?.name?.lowercase() ?: "zzz_${override.sourceId}"
                }
        }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text("Per-Extension Overrides") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (overrides.isEmpty()) {
                        Text(
                            "No overrides configured. Tap + to add one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(overrides, key = { it.sourceId }) { override ->
                                val sourceName = sourceManager.get(override.sourceId)?.name
                                    ?: "Unknown (#${override.sourceId})"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            sourceName,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        val details = buildList {
                                            override.delayMillis?.let { add("Delay: ${it}ms") }
                                            override.jitterMillis?.let { add("Jitter: 0-${it}ms") }
                                        }.joinToString(", ")
                                        if (details.isNotEmpty()) {
                                            Text(
                                                details,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    IconButton(onClick = { onEdit(override) }) {
                                        Icon(
                                            Icons.Outlined.Edit,
                                            contentDescription = stringResource(MR.strings.action_edit),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    IconButton(onClick = {
                                        prefs.removeSourceOverride(override.sourceId)
                                    }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = stringResource(MR.strings.action_delete),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onDismissRequest()
                    onAddNew()
                }) {
                    Text("Add Override")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(MR.strings.action_close))
                }
            },
        )
    }

    @Composable
    private fun AddEditOverrideDialog(
        prefs: NovelDownloadPreferences,
        existing: SourceOverride?,
        onDismissRequest: () -> Unit,
        onSaved: () -> Unit,
    ) {
        val sourceManager = remember { Injekt.get<SourceManager>() }
        val novelSources = remember {
            sourceManager.getAll().filterIsInstance<CatalogueSource>()
                .filter { it.isNovelSource() }
        }

        var selectedSourceId by remember { mutableStateOf(existing?.sourceId ?: 0L) }
        var delayMillis by remember { mutableIntStateOf(existing?.delayMillis ?: 3000) }
        var jitterMillis by remember { mutableIntStateOf(existing?.jitterMillis ?: 1000) }
        var sourceExpanded by remember { mutableStateOf(false) }

        val selectedSource = novelSources.find { it.id == selectedSourceId }
        val selectedSourceName = selectedSource?.name
            ?: if (selectedSourceId != 0L) "Source #$selectedSourceId" else "Select source..."
        // An extension can declare its own floor via RateLimited; the user can't configure
        // less delay than that, no matter what they drag the slider to.
        val declaredMinimum = (selectedSource as? RateLimited)?.minimumDelayMillis?.toInt() ?: 0
        val effectiveDelay = delayMillis.coerceAtLeast(declaredMinimum)

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(if (existing != null) "Edit Override" else "Add Override") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 450.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Source picker
                    Text("Extension", style = MaterialTheme.typography.labelMedium)
                    Box {
                        OutlinedButton(
                            onClick = { sourceExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = existing == null, // Can't change source when editing
                        ) {
                            Text(
                                selectedSourceName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DropdownMenu(
                            expanded = sourceExpanded,
                            onDismissRequest = { sourceExpanded = false },
                        ) {
                            novelSources.forEach { source ->
                                DropdownMenuItem(
                                    text = { Text(source.name) },
                                    onClick = {
                                        selectedSourceId = source.id
                                        sourceExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    if (declaredMinimum > 0) {
                        Text(
                            "Extension declared minimum: ${declaredMinimum}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HorizontalDivider()

                    // Base delay
                    Text(
                        "Request delay: ${effectiveDelay}ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = effectiveDelay.toFloat(),
                        onValueChange = { delayMillis = it.toInt().coerceAtLeast(declaredMinimum) },
                        valueRange = declaredMinimum.toFloat()..30000f,
                        steps = 29,
                    )

                    // Random jitter
                    Text(
                        "Random jitter: 0-${jitterMillis}ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = jitterMillis.toFloat(),
                        onValueChange = { jitterMillis = it.toInt() },
                        valueRange = 0f..5000f,
                        steps = 9,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedSourceId != 0L,
                    onClick = {
                        prefs.setSourceOverride(
                            SourceOverride(
                                sourceId = selectedSourceId,
                                delayMillis = effectiveDelay,
                                jitterMillis = jitterMillis,
                                enabled = true,
                            ),
                        )
                        onSaved()
                    },
                ) {
                    Text(stringResource(MR.strings.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
