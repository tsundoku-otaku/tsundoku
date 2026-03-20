package eu.kanade.presentation.more.settings.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.data.CreateBackupScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.more.settings.screen.data.StorageInfo
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.LNReaderImportJob
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.export.LibraryExportJob
import eu.kanade.tachiyomi.data.export.LibraryExporter.ExportOptions
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsDataScreen : SearchableSettings {

    val restorePreferenceKeyString = MR.strings.label_backup
    const val HELP_URL = "https://tsundoku-otaku.github.io/docs/faq/storage"

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_data_storage

    @Composable
    override fun RowScope.AppBarAction() {
        val uriHandler = LocalUriHandler.current
        IconButton(onClick = { uriHandler.openUri(HELP_URL) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = stringResource(MR.strings.tracking_guide),
            )
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val backupPreferences = Injekt.get<BackupPreferences>()
        val storagePreferences = Injekt.get<StoragePreferences>()

        return persistentListOf(
            getStorageLocationPref(storagePreferences = storagePreferences),
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_storage_location_info)),

            getBackupAndRestoreGroup(backupPreferences = backupPreferences),
            getDataGroup(),
            getExportGroup(),
        )
    }

    @Composable
    fun storageLocationPicker(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): ManagedActivityResultLauncher<Uri?, Uri?> {
        val context = LocalContext.current

        return rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                // For some reason InkBook devices do not implement the SAF properly. Persistable URI grants do not
                // work. However, simply retrieving the URI and using it works fine for these devices. Access is not
                // revoked after the app is closed or the device is restarted.
                // This also holds for some Samsung devices. Thus, we simply execute inside of a try-catch block and
                // ignore the exception if it is thrown.
                try {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: SecurityException) {
                    logcat(LogPriority.ERROR, e)
                    context.toast(MR.strings.file_picker_uri_permission_unsupported)
                }

                UniFile.fromUri(context, uri)?.let {
                    storageDirPref.set(it.uri.toString())
                }
            }
        }
    }

    @Composable
    fun storageLocationText(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): String {
        val context = LocalContext.current
        val storageDir by storageDirPref.collectAsState()

        if (storageDir == storageDirPref.defaultValue()) {
            return stringResource(MR.strings.no_location_set)
        }

        return remember(storageDir) {
            val uri = storageDir.toUri()
            var displayPath: String? = try {
                UniFile.fromUri(context, uri)?.displayablePath
            } catch (_: Exception) {
                null
            }

            // Parse common SAF document IDs to produce a human-readable /storage path.
            if (displayPath == null || displayPath.startsWith("content://")) {
                try {
                    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                        ?: runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                    if (!docId.isNullOrBlank()) {
                        val decodedDocId = Uri.decode(docId)
                        when {
                            decodedDocId.startsWith("raw:") -> {
                                displayPath = decodedDocId.removePrefix("raw:")
                            }
                            uri.authority == "com.android.externalstorage.documents" -> {
                                val parts = decodedDocId.split(":", limit = 2)
                                if (parts.size == 2) {
                                    val root = if (parts[0] == "primary") "/storage/emulated/0" else "/storage/${parts[0]}"
                                    displayPath = if (parts[1].isEmpty()) root else "$root/${parts[1]}"
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore and fall back below.
                }
            }
            displayPath
        } ?: stringResource(MR.strings.invalid_location, storageDir)
    }

    @Composable
    private fun getStorageLocationPref(
        storagePreferences: StoragePreferences,
    ): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val pickStorageLocation = storageLocationPicker(storagePreferences.baseStorageDirectory())

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_storage_location),
            subtitle = storageLocationText(storagePreferences.baseStorageDirectory()),
            onClick = {
                try {
                    pickStorageLocation.launch(null)
                } catch (e: ActivityNotFoundException) {
                    context.toast(MR.strings.file_picker_error)
                }
            },
        )
    }

    @Composable
    private fun getBackupAndRestoreGroup(backupPreferences: BackupPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        var lnReaderImportStatus by remember { mutableStateOf<String?>(null) }
        var pendingLNReaderUri by remember { mutableStateOf<Uri?>(null) }

        val lastAutoBackup by backupPreferences.lastAutoBackupTimestamp().collectAsState()

        val chooseBackup = rememberLauncherForActivityResult(
            object : ActivityResultContracts.GetContent() {
                override fun createIntent(context: Context, input: String): Intent {
                    val intent = super.createIntent(context, input)
                    return Intent.createChooser(intent, context.stringResource(MR.strings.file_select_backup))
                }
            },
        ) {
            if (it == null) {
                context.toast(MR.strings.file_null_uri_error)
                return@rememberLauncherForActivityResult
            }

            navigator.push(RestoreBackupScreen(it.toString()))
        }

        val chooseLNReaderBackup = rememberLauncherForActivityResult(
            object : ActivityResultContracts.GetContent() {
                override fun createIntent(context: Context, input: String): Intent {
                    val intent = super.createIntent(context, input)
                    return Intent.createChooser(intent, "Select LNReader backup file")
                }
            },
        ) { uri ->
            if (uri == null) {
                context.toast(MR.strings.file_null_uri_error)
                return@rememberLauncherForActivityResult
            }

            pendingLNReaderUri = uri
        }

        pendingLNReaderUri?.let { uri ->
            LNReaderImportOptionsDialog(
                onDismissRequest = { pendingLNReaderUri = null },
                onConfirm = { novels, chapters, categories, history, plugins, missingPlugins ->
                    pendingLNReaderUri = null
                    LNReaderImportJob.start(
                        context,
                        uri,
                        restoreNovels = novels,
                        restoreChapters = chapters,
                        restoreCategories = categories,
                        restoreHistory = history,
                        restorePlugins = plugins,
                        restoreMissingPlugins = missingPlugins,
                    )
                    lnReaderImportStatus = "Import started (check notifications for progress)"
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        lnReaderImportStatus = null
                    }
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_backup),
            preferenceItems = persistentListOf(
                // Manual actions
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(restorePreferenceKeyString),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            MultiChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(intrinsicSize = IntrinsicSize.Min)
                                    .padding(horizontal = PrefsHorizontalPadding),
                            ) {
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = { navigator.push(CreateBackupScreen()) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                ) {
                                    Text(stringResource(MR.strings.pref_create_backup))
                                }
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = {
                                        if (!BackupRestoreJob.isRunning(context)) {
                                            if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                                                context.toast(MR.strings.restore_miui_warning)
                                            }

                                            // no need to catch because it's wrapped with a chooser
                                            chooseBackup.launch("*/*")
                                        } else {
                                            context.toast(MR.strings.restore_in_progress)
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                ) {
                                    Text(stringResource(MR.strings.pref_restore_backup))
                                }
                            }
                        },
                    )
                },

                Preference.PreferenceItem.TextPreference(
                    title = "Import LNReader backup",
                    subtitle = lnReaderImportStatus ?: "Import novels from an LNReader backup file (.zip)",
                    onClick = {
                        chooseLNReaderBackup.launch("application/zip")
                    },
                ),

                // Automatic backups
                Preference.PreferenceItem.ListPreference(
                    preference = backupPreferences.backupInterval(),
                    entries = persistentMapOf(
                        0 to stringResource(MR.strings.off),
                        6 to stringResource(MR.strings.update_6hour),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                        48 to stringResource(MR.strings.update_48hour),
                        168 to stringResource(MR.strings.update_weekly),
                    ),
                    title = stringResource(MR.strings.pref_backup_interval),
                    onValueChanged = {
                        BackupCreateJob.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(MR.strings.backup_info) + "\n\n" +
                        stringResource(MR.strings.last_auto_backup_info, relativeTimeSpanString(lastAutoBackup)),
                ),
            ),
        )
    }

    @Composable
    private fun getDataGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }

        val chapterCache = remember { Injekt.get<ChapterCache>() }
        var cacheReadableSizeSema by remember { mutableIntStateOf(0) }
        var cacheReadableSize by remember { mutableStateOf(context.stringResource(MR.strings.calculating)) }
        LaunchedEffect(cacheReadableSizeSema) {
            cacheReadableSize = chapterCache.getReadableSize()
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_storage_usage),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_storage_usage),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            StorageInfo(
                                modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
                            )
                        },
                    )
                },

                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_chapter_cache),
                    subtitle = stringResource(MR.strings.used_cache, cacheReadableSize),
                    onClick = {
                        scope.launchNonCancellable {
                            try {
                                val deletedFiles = chapterCache.clear()
                                withUIContext {
                                    context.toast(context.stringResource(MR.strings.cache_deleted, deletedFiles))
                                    cacheReadableSizeSema++
                                }
                            } catch (e: Throwable) {
                                logcat(LogPriority.ERROR, e)
                                withUIContext { context.toast(MR.strings.cache_delete_error) }
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.autoClearChapterCache(),
                    title = stringResource(MR.strings.pref_auto_clear_chapter_cache),
                ),
            ),
        )
    }

    @Composable
    private fun getExportGroup(): Preference.PreferenceGroup {
        var showDialog by remember { mutableStateOf(false) }
        var exportOptions by remember {
            mutableStateOf(
                ExportOptions(
                    includeTitle = true,
                    includeAuthor = true,
                    includeArtist = true,
                ),
            )
        }

        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val saveFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            uri?.let {
                LibraryExportJob.start(context, it, exportOptions)
            }
        }

        if (showDialog) {
            ColumnSelectionDialog(
                options = exportOptions,
                onConfirm = { options ->
                    exportOptions = options
                    saveFileLauncher.launch("tsundoku_library.csv")
                },
                onDismissRequest = { showDialog = false },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.export),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.library_list),
                    onClick = { showDialog = true },
                ),
            ),
        )
    }

    @Composable
    private fun ColumnSelectionDialog(
        options: ExportOptions,
        onConfirm: (ExportOptions) -> Unit,
        onDismissRequest: () -> Unit,
    ) {
        var titleSelected by remember { mutableStateOf(options.includeTitle) }
        var authorSelected by remember { mutableStateOf(options.includeAuthor) }
        var artistSelected by remember { mutableStateOf(options.includeArtist) }
        var urlSelected by remember { mutableStateOf(options.includeUrl) }
        var chapterCountSelected by remember { mutableStateOf(options.includeChapterCount) }
        var categorySelected by remember { mutableStateOf(options.includeCategory) }
        var isNovelSelected by remember { mutableStateOf(options.includeIsNovel) }
        var descriptionSelected by remember { mutableStateOf(options.includeDescription) }
        var tagsSelected by remember { mutableStateOf(options.includeTags) }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = {
                Text(text = stringResource(MR.strings.migration_dialog_what_to_include))
            },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = titleSelected,
                            onCheckedChange = { checked ->
                                titleSelected = checked
                                if (!checked) {
                                    authorSelected = false
                                    artistSelected = false
                                }
                            },
                        )
                        Text(text = stringResource(MR.strings.title))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = authorSelected,
                            onCheckedChange = { authorSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.author))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = artistSelected,
                            onCheckedChange = { artistSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.artist))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = urlSelected,
                            onCheckedChange = { urlSelected = it },
                        )
                        Text(text = "Full URL")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = chapterCountSelected,
                            onCheckedChange = { chapterCountSelected = it },
                        )
                        Text(text = "Chapter Count")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = categorySelected,
                            onCheckedChange = { categorySelected = it },
                        )
                        Text(text = "Categories")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isNovelSelected,
                            onCheckedChange = { isNovelSelected = it },
                        )
                        Text(text = "Is Novel")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = descriptionSelected,
                            onCheckedChange = { descriptionSelected = it },
                        )
                        Text(text = "Description")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = tagsSelected,
                            onCheckedChange = { tagsSelected = it },
                        )
                        Text(text = "Tags (comma-separated)")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm(
                            ExportOptions(
                                includeTitle = titleSelected,
                                includeAuthor = authorSelected,
                                includeArtist = artistSelected,
                                includeUrl = urlSelected,
                                includeChapterCount = chapterCountSelected,
                                includeCategory = categorySelected,
                                includeIsNovel = isNovelSelected,
                                includeDescription = descriptionSelected,
                                includeTags = tagsSelected,
                            ),
                        )
                        onDismissRequest()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun LNReaderImportOptionsDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (
        novels: Boolean,
        chapters: Boolean,
        categories: Boolean,
        history: Boolean,
        plugins: Boolean,
        missingPlugins: Boolean,
    ) -> Unit,
) {
    var restoreNovels by remember { mutableStateOf(true) }
    var restoreChapters by remember { mutableStateOf(true) }
    var restoreCategories by remember { mutableStateOf(true) }
    var restoreHistory by remember { mutableStateOf(true) }
    var restorePlugins by remember { mutableStateOf(true) }
    var restoreMissingPlugins by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("LNReader Import Options") },
        text = {
            Column {
                Text(
                    "Choose what to restore from the backup:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = restoreNovels, onCheckedChange = { restoreNovels = it })
                    Text("Novels", modifier = Modifier.padding(start = 4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = restoreChapters,
                        onCheckedChange = { restoreChapters = it },
                        enabled = restoreNovels,
                    )
                    Text("Chapters", modifier = Modifier.padding(start = 4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = restoreCategories, onCheckedChange = { restoreCategories = it })
                    Text("Categories", modifier = Modifier.padding(start = 4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = restoreHistory,
                        onCheckedChange = { restoreHistory = it },
                        enabled = restoreNovels,
                    )
                    Text("Reading history", modifier = Modifier.padding(start = 4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = restorePlugins, onCheckedChange = { restorePlugins = it })
                    Text("Plugins (extensions)", modifier = Modifier.padding(start = 4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = restoreMissingPlugins,
                        onCheckedChange = { restoreMissingPlugins = it },
                        enabled = restoreNovels,
                    )
                    Text("Restore with missing plugins (as stubs)", modifier = Modifier.padding(start = 4.dp))
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    onConfirm(
                        restoreNovels,
                        restoreChapters,
                        restoreCategories,
                        restoreHistory,
                        restorePlugins,
                        restoreMissingPlugins,
                    )
                },
                enabled = restoreNovels || restoreCategories || restorePlugins,
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
