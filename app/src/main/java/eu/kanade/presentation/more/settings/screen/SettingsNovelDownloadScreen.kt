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
import androidx.compose.material.icons.outlined.Add
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
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.collections.immutable.persistentListOf
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
import kotlin.random.Random

object SettingsNovelDownloadScreen : SearchableSettings {

    private const val LOW_DELAY_THRESHOLD_MS = 3000

    override val supportsReset: Boolean get() = true

    @Composable
    override fun getAdditionalResetPreferences(): List<tachiyomi.core.common.preference.Preference<*>> {
        val prefs = remember { Injekt.get<NovelDownloadPreferences>() }
        return listOf(
            prefs.downloadDelay(),
            prefs.randomDelayRange(),
            prefs.parallelNovelDownloads(),
            prefs.maxImageSizeKb(),
            prefs.imageCompressionQuality(),
            prefs.updateDelay(),
            prefs.parallelNovelUpdates(),
            prefs.massImportDelay(),
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
            getDownloadThrottlingGroup(novelDownloadPreferences, downloadPreferences),
            getImageEmbeddingGroup(novelDownloadPreferences),
            getUpdateThrottlingGroup(novelDownloadPreferences),
            getMassImportThrottlingGroup(novelDownloadPreferences),
            getPerExtensionGroup(novelDownloadPreferences) { showOverridesDialog = true },
        )
    }

    @Composable
    private fun getDownloadThrottlingGroup(
        prefs: NovelDownloadPreferences,
        downloadPreferences: DownloadPreferences,
    ): Preference.PreferenceGroup {
        val enabled = prefs.enableThrottling().collectAsState().value
        val downloadDelay = prefs.downloadDelay().collectAsState().value
        val randomDelayMin = prefs.randomDelayMin().collectAsState().value
        val randomDelay = prefs.randomDelayRange().collectAsState().value
        val parallelDownloads = prefs.parallelNovelDownloads().collectAsState().value
        val compressionLevel = prefs.zipCompressionLevel().collectAsState().value
        val epubCompressionLevel = downloadPreferences.epubCompressionLevel().collectAsState().value
        val resumeOnNew = prefs.resumeQueueOnNewChapters().collectAsState().value

        val lowDelayWarning = if (downloadDelay < LOW_DELAY_THRESHOLD_MS && enabled) {
            stringResource(TDMR.strings.pref_novel_low_delay_warning)
        } else {
            ""
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_downloads),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.enableThrottling(),
                    title = stringResource(TDMR.strings.pref_novel_download_throttling),
                    subtitle = stringResource(TDMR.strings.pref_novel_download_throttling_summary),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = downloadDelay,
                    valueRange = 0..120000,
                    steps = 1000,
                    title = stringResource(TDMR.strings.pref_novel_download_delay),
                    subtitle = stringResource(TDMR.strings.pref_novel_download_delay_summary) + lowDelayWarning,
                    valueString = "${downloadDelay}ms",
                    onValueChanged = { prefs.downloadDelay().set(it + Random.nextInt(0, 999)) },
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = randomDelayMin,
                    valueRange = 0..10000,
                    steps = 1000,
                    title = stringResource(TDMR.strings.pref_novel_random_delay_min),
                    subtitle = stringResource(TDMR.strings.pref_novel_random_delay_min_summary),
                    valueString = "${randomDelayMin}ms",
                    onValueChanged = {
                        prefs.randomDelayMin().set(it + Random.nextInt(0, 999))
                        if (it > prefs.randomDelayRange().get()) {
                            prefs.randomDelayRange().set(it)
                        }
                    },
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = randomDelay,
                    valueRange = 0..60000,
                    steps = 1000,
                    title = stringResource(TDMR.strings.pref_novel_random_delay),
                    subtitle = stringResource(TDMR.strings.pref_novel_random_delay_summary),
                    valueString = "$randomDelayMin-${randomDelay}ms",
                    onValueChanged = {
                        prefs.randomDelayRange().set(it + Random.nextInt(0, 999))
                        if (it < prefs.randomDelayMin().get()) {
                            prefs.randomDelayMin().set(it)
                        }
                    },
                    enabled = enabled,
                ),
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
                    onValueChanged = { downloadPreferences.epubCompressionLevel().set(it - 1) },
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
            preferenceItems = persistentListOf(
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
    private fun getUpdateThrottlingGroup(
        prefs: NovelDownloadPreferences,
    ): Preference.PreferenceGroup {
        val enabled = prefs.enableUpdateThrottling().collectAsState().value
        val updateDelay = prefs.updateDelay().collectAsState().value
        val updateStagger = prefs.enableUpdateStaggering().collectAsState().value
        val parallelUpdates = prefs.parallelNovelUpdates().collectAsState().value

        val lowDelayWarning = if (updateDelay < LOW_DELAY_THRESHOLD_MS && enabled) {
            stringResource(TDMR.strings.pref_novel_low_delay_warning)
        } else {
            ""
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_library_update),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.enableUpdateThrottling(),
                    title = stringResource(TDMR.strings.pref_novel_update_throttling),
                    subtitle = stringResource(TDMR.strings.pref_novel_update_throttling_summary),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = updateDelay,
                    valueRange = 0..120000,
                    steps = 1000,
                    title = stringResource(TDMR.strings.pref_novel_update_delay),
                    subtitle = stringResource(TDMR.strings.pref_novel_update_delay_subtitle) + lowDelayWarning,
                    valueString = "${updateDelay}ms",
                    onValueChanged = { prefs.updateDelay().set(it + Random.nextInt(0, 999)) },
                    enabled = enabled,
                ),
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
    private fun getMassImportThrottlingGroup(
        prefs: NovelDownloadPreferences,
    ): Preference.PreferenceGroup {
        val enabled = prefs.enableMassImportThrottling().collectAsState().value
        val massImportDelay = prefs.massImportDelay().collectAsState().value

        val lowDelayWarning = if (massImportDelay < LOW_DELAY_THRESHOLD_MS && enabled) {
            stringResource(TDMR.strings.pref_novel_low_delay_warning)
        } else {
            ""
        }

        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_novel_mass_import_category),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.enableMassImportThrottling(),
                    title = stringResource(TDMR.strings.pref_novel_mass_import_throttling),
                    subtitle = stringResource(TDMR.strings.pref_novel_mass_import_throttling_subtitle),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = massImportDelay,
                    valueRange = 0..120000,
                    steps = 1000,
                    title = stringResource(TDMR.strings.pref_novel_mass_import_delay),
                    subtitle = stringResource(TDMR.strings.pref_novel_mass_import_delay_subtitle) + lowDelayWarning,
                    valueString = "${massImportDelay}ms",
                    onValueChanged = { prefs.massImportDelay().set(it + Random.nextInt(0, 999)) },
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = prefs.parallelMassImport().collectAsState().value,
                    valueRange = 1..15,
                    title = stringResource(TDMR.strings.pref_novel_concurrent_imports),
                    subtitle = stringResource(TDMR.strings.pref_novel_concurrent_imports_summary),
                    valueString = "${prefs.parallelMassImport().collectAsState().value}",
                    onValueChanged = { prefs.parallelMassImport().set(it) },
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
            preferenceItems = persistentListOf(
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
                                            override.downloadDelay?.let { add("DL: ${it}ms") }
                                            override.randomDelayRange?.let { add("Rand: ${it}ms") }
                                            override.updateDelay?.let { add("Upd: ${it}ms") }
                                            override.massImportDelay?.let { add("MI: ${it}ms") }
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
                                            contentDescription = "Edit",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    IconButton(onClick = {
                                        prefs.removeSourceOverride(override.sourceId)
                                    }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "Delete",
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
            sourceManager.getCatalogueSources()
                .filter { it.isNovelSource() }
        }

        var selectedSourceId by remember { mutableStateOf(existing?.sourceId ?: 0L) }
        var downloadDelay by remember { mutableIntStateOf(existing?.downloadDelay ?: 2000) }
        var randomDelay by remember { mutableIntStateOf(existing?.randomDelayRange ?: 1000) }
        var updateDelay by remember { mutableIntStateOf(existing?.updateDelay ?: 1500) }
        var massImportDelay by remember { mutableIntStateOf(existing?.massImportDelay ?: 1000) }
        var sourceExpanded by remember { mutableStateOf(false) }

        val selectedSourceName = novelSources.find { it.id == selectedSourceId }?.name
            ?: if (selectedSourceId != 0L) "Source #$selectedSourceId" else "Select source..."

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

                    HorizontalDivider()

                    // Download delay
                    Text(
                        "Download delay: ${downloadDelay}ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = downloadDelay.toFloat(),
                        onValueChange = { downloadDelay = it.toInt() },
                        valueRange = 0f..30000f,
                        steps = 29,
                    )

                    // Random delay
                    Text(
                        "Random range: 0-${randomDelay}ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = randomDelay.toFloat(),
                        onValueChange = { randomDelay = it.toInt() },
                        valueRange = 0f..5000f,
                        steps = 9,
                    )

                    // Update delay
                    Text(
                        "Update delay: ${updateDelay}ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = updateDelay.toFloat(),
                        onValueChange = { updateDelay = it.toInt() },
                        valueRange = 0f..15000f,
                        steps = 14,
                    )

                    // Mass import delay
                    Text(
                        "Mass import delay: ${massImportDelay}ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = massImportDelay.toFloat(),
                        onValueChange = { massImportDelay = it.toInt() },
                        valueRange = 0f..15000f,
                        steps = 14,
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
                                downloadDelay = downloadDelay,
                                randomDelayRange = randomDelay,
                                updateDelay = updateDelay,
                                massImportDelay = massImportDelay,
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
