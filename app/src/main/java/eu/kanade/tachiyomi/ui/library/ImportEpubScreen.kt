package eu.kanade.tachiyomi.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.domain.manga.interactor.ImportEpub
import eu.kanade.domain.manga.interactor.ParseEpubPreview
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.library.components.EpubFileInfo
import eu.kanade.presentation.library.components.ImportProgress
import eu.kanade.presentation.library.components.ImportResult
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

private const val TOC_PREVIEW_SECTION_SIZE = 3
private const val TOC_PREVIEW_ALL_THRESHOLD = TOC_PREVIEW_SECTION_SIZE * 3

class ImportEpubScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val snackbarHostState = remember { SnackbarHostState() }
        val getCategories = remember { Injekt.get<GetCategories>() }
        val importEpub = remember { ImportEpub() }
        val parseEpubPreview = remember { ParseEpubPreview() }

        val volumeGroups = remember { mutableStateListOf<VolumeGroupState>() }
        val expandedTocByVolume = remember { mutableStateMapOf<String, Boolean>() }

        var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
        var selectedCategoryId by remember { mutableStateOf<Long?>(null) }
        var categoryMenuExpanded by remember { mutableStateOf(false) }
        var autoAddToLibrary by remember { mutableStateOf(true) }
        var isParsing by remember { mutableStateOf(false) }

        var importProgress by remember { mutableStateOf<ImportProgress?>(null) }
        var importResult by remember { mutableStateOf<ImportResult?>(null) }
        var successfullyImportedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var showDeleteImportedConfirm by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            categories = withContext(Dispatchers.IO) {
                getCategories.await().filter {
                    it.contentType == Category.CONTENT_TYPE_ALL || it.contentType == Category.CONTENT_TYPE_NOVEL
                }
            }
            if (selectedCategoryId == null) {
                selectedCategoryId = categories.firstOrNull()?.id
            }
        }

        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            persistImportUriPermissions(context, uris)

            scope.launch {
                isParsing = true
                importResult = null
                successfullyImportedUris = emptyList()

                val parsed = withContext(Dispatchers.IO) {
                    parseEpubPreview.parseSelected(context, uris)
                }

                isParsing = false

                parsed.errors.forEach { snackbarHostState.showSnackbar(it) }

                val parsedFiles = parsed.files.map {
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

                val existingFiles = volumeGroups.flatMap { group ->
                    group.volumes.map { volume -> volume.file }
                }
                val allFiles = (existingFiles + parsedFiles)
                    .distinctBy { file -> "${file.uri}|${file.fileName}" }

                volumeGroups.clear()
                volumeGroups.addAll(buildVolumeGroups(allFiles))
                expandedTocByVolume.clear()

                if (allFiles.isNotEmpty()) {
                    snackbarHostState.showSnackbar(
                        "Loaded ${allFiles.size} EPUB file(s) into ${volumeGroups.size} novel group(s)",
                    )
                } else if (parsed.errors.isEmpty()) {
                    snackbarHostState.showSnackbar("No valid EPUB files were parsed")
                }
            }
        }

        fun moveGroup(groupId: String, offset: Int) {
            val fromIndex = volumeGroups.indexOfFirst { it.id == groupId }
            if (fromIndex == -1) return
            volumeGroups.moveItem(fromIndex, fromIndex + offset)
        }

        fun removeGroup(groupId: String) {
            val groupIndex = volumeGroups.indexOfFirst { it.id == groupId }
            if (groupIndex == -1) return

            val removedVolumeIds = volumeGroups[groupIndex].volumes.map { it.id }
            removedVolumeIds.forEach { expandedTocByVolume.remove(it) }
            volumeGroups.removeAt(groupIndex)
        }

        fun moveVolumeInsideGroup(groupId: String, volumeId: String, offset: Int) {
            val groupIndex = volumeGroups.indexOfFirst { it.id == groupId }
            if (groupIndex == -1) return

            val group = volumeGroups[groupIndex]
            val fromIndex = group.volumes.indexOfFirst { it.id == volumeId }
            if (fromIndex == -1) return

            group.volumes.moveItem(fromIndex, fromIndex + offset)
        }

        fun moveVolumeToExistingGroup(sourceGroupId: String, volumeId: String, targetGroupId: String) {
            if (sourceGroupId == targetGroupId) return

            val sourceGroupIndex = volumeGroups.indexOfFirst { it.id == sourceGroupId }
            if (sourceGroupIndex == -1) return

            val sourceGroup = volumeGroups[sourceGroupIndex]
            val sourceVolumeIndex = sourceGroup.volumes.indexOfFirst { it.id == volumeId }
            if (sourceVolumeIndex == -1) return

            val volume = sourceGroup.volumes.removeAt(sourceVolumeIndex)

            val targetGroupIndex = volumeGroups.indexOfFirst { it.id == targetGroupId }
            if (targetGroupIndex == -1) {
                sourceGroup.volumes.add(sourceVolumeIndex, volume)
                return
            }

            volumeGroups[targetGroupIndex].volumes.add(volume)

            if (sourceGroup.volumes.isEmpty()) {
                val latestSourceIndex = volumeGroups.indexOfFirst { it.id == sourceGroupId }
                if (latestSourceIndex != -1) {
                    volumeGroups.removeAt(latestSourceIndex)
                }
            }
        }

        fun moveVolumeToNewGroup(sourceGroupId: String, volumeId: String) {
            val sourceGroupIndex = volumeGroups.indexOfFirst { it.id == sourceGroupId }
            if (sourceGroupIndex == -1) return

            val sourceGroup = volumeGroups[sourceGroupIndex]
            val sourceVolumeIndex = sourceGroup.volumes.indexOfFirst { it.id == volumeId }
            if (sourceVolumeIndex == -1) return

            val volume = sourceGroup.volumes.removeAt(sourceVolumeIndex)

            val newGroup = VolumeGroupState(
                id = newGroupId(),
                initialTitle = suggestedGroupTitle(listOf(volume.file)),
                volumes = listOf(volume),
            )

            val insertIndex = (sourceGroupIndex + 1).coerceAtMost(volumeGroups.size)
            volumeGroups.add(insertIndex, newGroup)

            if (sourceGroup.volumes.isEmpty()) {
                val latestSourceIndex = volumeGroups.indexOfFirst { it.id == sourceGroupId }
                if (latestSourceIndex != -1) {
                    volumeGroups.removeAt(latestSourceIndex)
                }
            }
        }

        fun removeVolumeFromGroup(groupId: String, volumeId: String) {
            val groupIndex = volumeGroups.indexOfFirst { it.id == groupId }
            if (groupIndex == -1) return

            val group = volumeGroups[groupIndex]
            val volumeIndex = group.volumes.indexOfFirst { it.id == volumeId }
            if (volumeIndex == -1) return

            group.volumes.removeAt(volumeIndex)
            expandedTocByVolume.remove(volumeId)

            if (group.volumes.isEmpty()) {
                volumeGroups.removeAt(groupIndex)
            }
        }

        val canNavigateBack = importProgress?.isRunning != true

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(TDMR.strings.epub_import_title),
                    navigateUp = if (canNavigateBack) {
                        { navigator.pop() }
                    } else {
                        null
                    },
                    actions = {
                        if (
                            volumeGroups.isNotEmpty() &&
                            !isParsing &&
                            importProgress == null &&
                            importResult == null
                        ) {
                            AppBarActions(
                                persistentListOf(
                                    AppBar.Action(
                                        title = "Auto rename & organize",
                                        icon = Icons.Outlined.Refresh,
                                        onClick = {
                                            autoOrganizeGroups(volumeGroups)
                                            expandedTocByVolume.clear()
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "Auto-organized groups and volume titles from EPUB metadata",
                                                )
                                            }
                                        },
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            when {
                importResult != null -> {
                    ImportResultContent(
                        result = importResult!!,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        canDeleteImportedFiles = successfullyImportedUris.isNotEmpty(),
                        onDeleteImportedFiles = { showDeleteImportedConfirm = true },
                        onDone = { navigator.pop() },
                    )

                    if (showDeleteImportedConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteImportedConfirm = false },
                            title = { Text("Delete imported EPUB files?") },
                            text = {
                                Text(
                                    "This will permanently delete successfully imported source files from storage (if permitted).",
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showDeleteImportedConfirm = false
                                        val targetUris = successfullyImportedUris

                                        scope.launch {
                                            val deletedUris = withContext(Dispatchers.IO) {
                                                targetUris.filter { uri -> deleteImportedDocument(context, uri) }
                                            }

                                            val deletedCount = deletedUris.size
                                            val failedCount = targetUris.size - deletedCount

                                            successfullyImportedUris = successfullyImportedUris - deletedUris.toSet()

                                            when {
                                                deletedCount == 0 -> {
                                                    snackbarHostState.showSnackbar("No imported EPUB files were deleted")
                                                }
                                                failedCount == 0 -> {
                                                    snackbarHostState.showSnackbar("Deleted $deletedCount imported EPUB file(s)")
                                                }
                                                else -> {
                                                    snackbarHostState.showSnackbar(
                                                        "Deleted $deletedCount file(s), failed to delete $failedCount",
                                                    )
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Text("Delete")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteImportedConfirm = false }) {
                                    Text(stringResource(MR.strings.action_cancel))
                                }
                            },
                        )
                    }
                }
                importProgress != null -> {
                    ImportProgressContent(
                        progress = importProgress!!,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    )
                }
                else -> {
                    ImportSelectionContent(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        volumeGroups = volumeGroups,
                        expandedTocByVolume = expandedTocByVolume,
                        isParsing = isParsing,
                        autoAddToLibrary = autoAddToLibrary,
                        onToggleAutoAddToLibrary = { autoAddToLibrary = !autoAddToLibrary },
                        categories = categories,
                        selectedCategoryId = selectedCategoryId,
                        categoryMenuExpanded = categoryMenuExpanded,
                        onCategoryMenuExpandedChange = { categoryMenuExpanded = it },
                        onCategorySelected = { selectedCategoryId = it },
                        onPickFiles = {
                            filePickerLauncher.launch(arrayOf("application/epub+zip"))
                        },
                        onMoveGroupUp = { groupId -> moveGroup(groupId, -1) },
                        onMoveGroupDown = { groupId -> moveGroup(groupId, 1) },
                        onRemoveGroup = { groupId -> removeGroup(groupId) },
                        onMoveVolumeUp = { groupId, volumeId ->
                            moveVolumeInsideGroup(groupId, volumeId, -1)
                        },
                        onMoveVolumeDown = { groupId, volumeId ->
                            moveVolumeInsideGroup(groupId, volumeId, 1)
                        },
                        onRemoveVolume = { groupId, volumeId ->
                            removeVolumeFromGroup(groupId, volumeId)
                        },
                        onMoveVolumeToExistingGroup = { sourceGroupId, volumeId, targetGroupId ->
                            moveVolumeToExistingGroup(sourceGroupId, volumeId, targetGroupId)
                        },
                        onMoveVolumeToNewGroup = { sourceGroupId, volumeId ->
                            moveVolumeToNewGroup(sourceGroupId, volumeId)
                        },
                        onToggleVolumeExpanded = { volumeId ->
                            expandedTocByVolume[volumeId] = !(expandedTocByVolume[volumeId] ?: false)
                        },
                        onImport = {
                            scope.launch {
                                val groupsToImport = volumeGroups.filter { it.volumes.isNotEmpty() }
                                if (groupsToImport.isEmpty()) {
                                    snackbarHostState.showSnackbar("Select at least one EPUB file")
                                    return@launch
                                }

                                val totalUnits = groupsToImport.sumOf {
                                    if (it.volumes.size > 1) 1 else it.volumes.size
                                }.coerceAtLeast(1)

                                var completedUnits = 0
                                var successCount = 0
                                val errors = mutableListOf<String>()
                                val importedUris = mutableListOf<Uri>()

                                importProgress = ImportProgress(
                                    current = 0,
                                    total = totalUnits,
                                    currentFileName = "",
                                    isRunning = true,
                                )

                                groupsToImport.forEach { group ->
                                    val importFiles = group.volumes.map { volume ->
                                        ImportEpub.ImportFile(
                                            uri = volume.file.uri,
                                            fileName = volume.file.fileName,
                                            title = volume.title.ifBlank { suggestedVolumeTitle(volume.file) },
                                            author = volume.file.author,
                                            description = volume.file.description,
                                            coverUri = volume.file.coverUri,
                                            genres = volume.file.genres,
                                        )
                                    }

                                    val combineAsOne = importFiles.size > 1
                                    val groupTitle = group.title.trim().ifBlank {
                                        suggestedGroupTitle(group.volumes.map { it.file })
                                    }
                                    val groupUnits = if (combineAsOne) 1 else importFiles.size

                                    try {
                                        val result = importEpub.execute(
                                            context = context,
                                            files = importFiles,
                                            customTitle = groupTitle.ifBlank { null },
                                            combineAsOne = combineAsOne,
                                            autoAddToLibrary = autoAddToLibrary,
                                            categoryId = if (autoAddToLibrary) selectedCategoryId else null,
                                            forceUniqueFolderName = true,
                                            onProgress = { current, _, fileName ->
                                                val normalizedCurrent = if (combineAsOne) {
                                                    1
                                                } else {
                                                    current.coerceAtLeast(1)
                                                }

                                                importProgress = ImportProgress(
                                                    current = (completedUnits + normalizedCurrent).coerceAtMost(totalUnits),
                                                    total = totalUnits,
                                                    currentFileName = fileName.ifBlank { groupTitle },
                                                    isRunning = true,
                                                )
                                            },
                                        )

                                        successCount += result.successCount
                                        errors += result.errors
                                        importedUris += result.importedUris
                                    } catch (e: Exception) {
                                        errors += "${groupTitle.ifBlank { "Novel" }}: ${e.message.orEmpty()}"
                                    }

                                    completedUnits += groupUnits
                                    importProgress = ImportProgress(
                                        current = completedUnits.coerceAtMost(totalUnits),
                                        total = totalUnits,
                                        currentFileName = groupTitle,
                                        isRunning = true,
                                    )
                                }

                                importProgress = null
                                importResult = ImportResult(
                                    successCount = successCount,
                                    errorCount = errors.size,
                                    errors = errors,
                                )
                                successfullyImportedUris = importedUris.distinct()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportSelectionContent(
    modifier: Modifier,
    volumeGroups: List<VolumeGroupState>,
    expandedTocByVolume: Map<String, Boolean>,
    isParsing: Boolean,
    autoAddToLibrary: Boolean,
    onToggleAutoAddToLibrary: () -> Unit,
    categories: List<Category>,
    selectedCategoryId: Long?,
    categoryMenuExpanded: Boolean,
    onCategoryMenuExpandedChange: (Boolean) -> Unit,
    onCategorySelected: (Long) -> Unit,
    onPickFiles: () -> Unit,
    onMoveGroupUp: (groupId: String) -> Unit,
    onMoveGroupDown: (groupId: String) -> Unit,
    onRemoveGroup: (groupId: String) -> Unit,
    onMoveVolumeUp: (groupId: String, volumeId: String) -> Unit,
    onMoveVolumeDown: (groupId: String, volumeId: String) -> Unit,
    onRemoveVolume: (groupId: String, volumeId: String) -> Unit,
    onMoveVolumeToExistingGroup: (sourceGroupId: String, volumeId: String, targetGroupId: String) -> Unit,
    onMoveVolumeToNewGroup: (sourceGroupId: String, volumeId: String) -> Unit,
    onToggleVolumeExpanded: (volumeId: String) -> Unit,
    onImport: () -> Unit,
) {
    val canImport = volumeGroups.any { it.volumes.isNotEmpty() }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedButton(
                onClick = onPickFiles,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.FileOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(TDMR.strings.epub_select_files))
            }
        }

        if (isParsing) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Parsing EPUB files...")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (volumeGroups.isNotEmpty()) {
            item {
                Text(
                    text = "Novel groups (${volumeGroups.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            itemsIndexed(
                items = volumeGroups,
                key = { _, group -> group.id },
            ) { groupIndex, group ->
                VolumeGroupCard(
                    group = group,
                    groupIndex = groupIndex,
                    groupCount = volumeGroups.size,
                    allGroups = volumeGroups,
                    expandedTocByVolume = expandedTocByVolume,
                    onMoveGroupUp = { onMoveGroupUp(group.id) },
                    onMoveGroupDown = { onMoveGroupDown(group.id) },
                    onRemoveGroup = { onRemoveGroup(group.id) },
                    onMoveVolumeUp = { volumeId -> onMoveVolumeUp(group.id, volumeId) },
                    onMoveVolumeDown = { volumeId -> onMoveVolumeDown(group.id, volumeId) },
                    onRemoveVolume = { volumeId -> onRemoveVolume(group.id, volumeId) },
                    onMoveVolumeToExistingGroup = { volumeId, targetGroupId ->
                        onMoveVolumeToExistingGroup(group.id, volumeId, targetGroupId)
                    },
                    onMoveVolumeToNewGroup = { volumeId ->
                        onMoveVolumeToNewGroup(group.id, volumeId)
                    },
                    onToggleVolumeExpanded = onToggleVolumeExpanded,
                )
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleAutoAddToLibrary),
                ) {
                    Checkbox(
                        checked = autoAddToLibrary,
                        onCheckedChange = { onToggleAutoAddToLibrary() },
                    )
                    Text(stringResource(TDMR.strings.epub_auto_add_to_category))
                }
            }

            if (autoAddToLibrary) {
                item {
                    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }
                    Box {
                        OutlinedButton(
                            onClick = { onCategoryMenuExpandedChange(true) },
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
                            onDismissRequest = { onCategoryMenuExpandedChange(false) },
                        ) {
                            categories.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.visualName) },
                                    onClick = {
                                        onCategorySelected(category.id)
                                        onCategoryMenuExpandedChange(false)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(TDMR.strings.epub_local_novels_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Button(
                onClick = onImport,
                enabled = canImport && !isParsing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(TDMR.strings.epub_action_import))
            }
        }
    }
}

@Composable
private fun VolumeGroupCard(
    group: VolumeGroupState,
    groupIndex: Int,
    groupCount: Int,
    allGroups: List<VolumeGroupState>,
    expandedTocByVolume: Map<String, Boolean>,
    onMoveGroupUp: () -> Unit,
    onMoveGroupDown: () -> Unit,
    onRemoveGroup: () -> Unit,
    onMoveVolumeUp: (volumeId: String) -> Unit,
    onMoveVolumeDown: (volumeId: String) -> Unit,
    onRemoveVolume: (volumeId: String) -> Unit,
    onMoveVolumeToExistingGroup: (volumeId: String, targetGroupId: String) -> Unit,
    onMoveVolumeToNewGroup: (volumeId: String) -> Unit,
    onToggleVolumeExpanded: (volumeId: String) -> Unit,
) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = group.title,
                    onValueChange = { group.title = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Novel title") },
                    singleLine = true,
                )

                IconButton(
                    onClick = onMoveGroupUp,
                    enabled = groupIndex > 0,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowUp,
                        contentDescription = "Move novel group up",
                    )
                }
                IconButton(
                    onClick = onMoveGroupDown,
                    enabled = groupIndex < groupCount - 1,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Move novel group down",
                    )
                }

                IconButton(onClick = onRemoveGroup) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Remove novel group",
                    )
                }
            }

            Text(
                text = "${group.volumes.size} volume(s) • ${groupTotalChapterCount(group)} chapter(s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            group.volumes.forEachIndexed { volumeIndex, volume ->
                HorizontalDivider()

                val isExpanded = expandedTocByVolume[volume.id] == true
                var moveMenuExpanded by remember(volume.id) { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        VolumeCoverThumbnail(
                            coverUri = volume.file.coverUri,
                            modifier = Modifier.width(64.dp),
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = buildVolumeOrderLabel(volumeIndex, volume.file.collectionPosition),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (group.volumes.size > 1) {
                                OutlinedTextField(
                                    value = volume.title,
                                    onValueChange = { volume.title = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Volume title") },
                                    singleLine = true,
                                )
                            }

                            Text(
                                text = volume.file.fileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End,
                            ) {
                                IconButton(
                                    onClick = { onMoveVolumeUp(volume.id) },
                                    enabled = volumeIndex > 0,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.KeyboardArrowUp,
                                        contentDescription = "Move volume up",
                                    )
                                }

                                IconButton(
                                    onClick = { onMoveVolumeDown(volume.id) },
                                    enabled = volumeIndex < group.volumes.size - 1,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = "Move volume down",
                                    )
                                }

                                Box {
                                    IconButton(onClick = { moveMenuExpanded = true }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.DriveFileMove,
                                            contentDescription = "Move to another novel group",
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = moveMenuExpanded,
                                        onDismissRequest = { moveMenuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Move to new novel group") },
                                            onClick = {
                                                moveMenuExpanded = false
                                                onMoveVolumeToNewGroup(volume.id)
                                            },
                                        )

                                        allGroups
                                            .filter { it.id != group.id }
                                            .forEachIndexed { destinationIndex, destinationGroup ->
                                                val destinationLabel = destinationGroup.title
                                                    .takeIf { it.isNotBlank() }
                                                    ?: "Novel group ${destinationIndex + 1}"

                                                DropdownMenuItem(
                                                    text = { Text("Move to $destinationLabel") },
                                                    onClick = {
                                                        moveMenuExpanded = false
                                                        onMoveVolumeToExistingGroup(volume.id, destinationGroup.id)
                                                    },
                                                )
                                            }
                                    }
                                }

                                IconButton(onClick = { onToggleVolumeExpanded(volume.id) }) {
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        contentDescription = if (isExpanded) "Collapse table of contents" else "Expand table of contents",
                                    )
                                }

                                IconButton(onClick = { onRemoveVolume(volume.id) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Remove volume",
                                    )
                                }
                            }
                        }
                    }

                    if (isExpanded) {
                        VolumeMetadataDetails(file = volume.file)
                        TocPreview(volume.file.tableOfContents)
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeCoverThumbnail(
    coverUri: Uri?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val request = remember(coverUri, context) {
        coverUri?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(192, 288)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .build()
        }
    }

    MangaCover.Book(
        data = request,
        contentDescription = "Volume cover",
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
    )
}

@Composable
private fun VolumeMetadataDetails(file: EpubFileInfo) {
    val author = file.author?.trim()?.takeIf { it.isNotBlank() }
    val tags = file.genres
        ?.split(',', ';')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val description = file.description?.trim()?.takeIf { it.isNotBlank() }

    if (author == null && tags.isEmpty() && description == null) {
        return
    }

    Column(
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        author?.let {
            Text(
                text = "Author: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (tags.isNotEmpty()) {
            Text(
                text = "Tags: ${tags.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        description?.let {
            Text(
                text = "Description: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TocPreview(tableOfContents: List<String>) {
    if (tableOfContents.isEmpty()) {
        Text(
            text = "No table of contents found",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        )
        return
    }

    if (tableOfContents.size <= TOC_PREVIEW_ALL_THRESHOLD) {
        tableOfContents.forEachIndexed { index, chapterTitle ->
            Text(
                text = "${index + 1}. $chapterTitle",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
            )
        }
        return
    }

    val sections = buildTocPreviewSections(tableOfContents.size)

    sections.forEachIndexed { sectionIndex, section ->
        Text(
            text = section.title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = if (sectionIndex == 0) 0.dp else 6.dp, bottom = 2.dp),
        )

        section.indices.forEach { chapterIndex ->
            Text(
                text = "${chapterIndex + 1}. ${tableOfContents[chapterIndex]}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
            )
        }
    }
}

private fun buildTocPreviewSections(totalChapters: Int): List<TocSection> {
    if (totalChapters <= TOC_PREVIEW_ALL_THRESHOLD) {
        return listOf(
            TocSection(
                title = "Chapters",
                indices = (0 until totalChapters).toList(),
            ),
        )
    }

    val startIndices = (0 until TOC_PREVIEW_SECTION_SIZE).toList()

    val middleStart = ((totalChapters - TOC_PREVIEW_SECTION_SIZE) / 2)
        .coerceAtLeast(TOC_PREVIEW_SECTION_SIZE)
    val middleIndices = (middleStart until (middleStart + TOC_PREVIEW_SECTION_SIZE)).toList()

    val endStart = totalChapters - TOC_PREVIEW_SECTION_SIZE
    val endIndices = (endStart until totalChapters).toList()

    return listOf(
        TocSection("Start", startIndices),
        TocSection("Middle", middleIndices),
        TocSection("End", endIndices),
    )
}

@Composable
private fun ImportProgressContent(
    progress: ImportProgress,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(TDMR.strings.epub_importing_progress, progress.current, progress.total),
                style = MaterialTheme.typography.titleMedium,
            )
            LinearProgressIndicator(
                progress = { progress.current.toFloat() / progress.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            if (progress.currentFileName.isNotBlank()) {
                Text(
                    text = progress.currentFileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ImportResultContent(
    result: ImportResult,
    modifier: Modifier = Modifier,
    canDeleteImportedFiles: Boolean,
    onDeleteImportedFiles: () -> Unit,
    onDone: () -> Unit,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = stringResource(TDMR.strings.epub_import_complete),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "✓ " + stringResource(TDMR.strings.epub_import_success_count, result.successCount),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (result.errorCount > 0) {
                Text(
                    text = "✗ " + stringResource(TDMR.strings.epub_import_error_count, result.errorCount),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                result.errors.take(10).forEach { error ->
                    Text(
                        text = "• $error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (result.errors.size > 10) {
                    Text(
                        text = "... and ${result.errors.size - 10} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            if (canDeleteImportedFiles) {
                OutlinedButton(
                    onClick = onDeleteImportedFiles,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete imported EPUB files")
                }
            }

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(MR.strings.action_close))
            }
        }
    }
}

private class VolumeItemState(
    val id: String,
    val file: EpubFileInfo,
    initialTitle: String,
) {
    var title by mutableStateOf(initialTitle)
}

private class VolumeGroupState(
    val id: String,
    initialTitle: String,
    volumes: List<VolumeItemState>,
) {
    var title by mutableStateOf(initialTitle)
    val volumes = mutableStateListOf<VolumeItemState>().apply { addAll(volumes) }
}

private data class TocSection(
    val title: String,
    val indices: List<Int>,
)

private fun autoOrganizeGroups(volumeGroups: MutableList<VolumeGroupState>) {
    val files = volumeGroups.flatMap { group ->
        group.volumes.map { volume -> volume.file }
    }

    volumeGroups.clear()
    volumeGroups.addAll(buildVolumeGroups(files))
}

private fun buildVolumeGroups(files: List<EpubFileInfo>): List<VolumeGroupState> {
    if (files.isEmpty()) return emptyList()

    val grouped = linkedMapOf<String, MutableList<EpubFileInfo>>()
    files.forEach { file ->
        val key = volumeGroupKey(file)
        grouped.getOrPut(key) { mutableListOf() }.add(file)
    }

    return grouped.values
        .map { groupFiles ->
            val sortedFiles = groupFiles.sortedWith(::compareVolumeFiles)
            val volumeStates = sortedFiles.map { file ->
                VolumeItemState(
                    id = newVolumeId(),
                    file = file,
                    initialTitle = suggestedVolumeTitle(file),
                )
            }

            VolumeGroupState(
                id = newGroupId(),
                initialTitle = suggestedGroupTitle(sortedFiles),
                volumes = volumeStates,
            )
        }
        .sortedBy { normalizeVolumeKey(it.title) }
}

private fun newGroupId(): String = "group-${UUID.randomUUID()}"

private fun newVolumeId(): String = "volume-${UUID.randomUUID()}"

private fun buildVolumeOrderLabel(orderIndex: Int, collectionPosition: Int?): String {
    val orderNumber = orderIndex + 1
    return if (collectionPosition != null && collectionPosition != orderNumber) {
        "Order $orderNumber (EPUB index $collectionPosition)"
    } else {
        "Order $orderNumber"
    }
}

private fun groupTotalChapterCount(group: VolumeGroupState): Int {
    return group.volumes.sumOf { volume ->
        volume.file.tableOfContents.size.takeIf { it > 0 } ?: 1
    }
}

private fun deleteImportedDocument(context: Context, uri: Uri): Boolean {
    val resolver = context.contentResolver

    val deletedViaDocumentFile = runCatching {
        DocumentFile.fromSingleUri(context, uri)?.delete() == true
    }.getOrDefault(false)
    if (deletedViaDocumentFile) return true

    val deletedViaDocumentsContract = runCatching {
        DocumentsContract.deleteDocument(resolver, uri)
    }.getOrDefault(false)
    if (deletedViaDocumentsContract) return true

    return runCatching {
        resolver.delete(uri, null, null) > 0
    }.getOrDefault(false)
}

private fun persistImportUriPermissions(context: Context, uris: List<Uri>) {
    val resolver = context.contentResolver
    val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    uris.forEach { uri ->
        runCatching {
            resolver.takePersistableUriPermission(uri, readWriteFlags)
        }.recoverCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

private fun compareVolumeFiles(first: EpubFileInfo, second: EpubFileInfo): Int {
    val firstCollectionPosition = first.collectionPosition
    val secondCollectionPosition = second.collectionPosition

    if (firstCollectionPosition != null && secondCollectionPosition != null) {
        val compared = firstCollectionPosition.compareTo(secondCollectionPosition)
        if (compared != 0) return compared
    } else if (firstCollectionPosition != null) {
        return -1
    } else if (secondCollectionPosition != null) {
        return 1
    }

    val firstNumber = extractVolumeNumber(suggestedVolumeTitle(first))
        ?: extractVolumeNumber(first.fileName.substringBeforeLast('.', first.fileName))
    val secondNumber = extractVolumeNumber(suggestedVolumeTitle(second))
        ?: extractVolumeNumber(second.fileName.substringBeforeLast('.', second.fileName))

    if (firstNumber != null && secondNumber != null) {
        val compared = firstNumber.compareTo(secondNumber)
        if (compared != 0) return compared
    } else if (firstNumber != null) {
        return -1
    } else if (secondNumber != null) {
        return 1
    }

    return normalizeVolumeKey(suggestedVolumeTitle(first))
        .compareTo(normalizeVolumeKey(suggestedVolumeTitle(second)))
}

private fun volumeGroupKey(file: EpubFileInfo): String {
    val explicitCollection = file.collection
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let(::extractSeriesTitle)
        ?.let(::normalizeVolumeKey)

    if (!explicitCollection.isNullOrBlank()) {
        return explicitCollection
    }

    val fromTitle = extractSeriesTitle(
        file.title.ifBlank {
            file.fileName.substringBeforeLast('.', file.fileName)
        },
    )

    val fromFileName = extractSeriesTitle(
        file.fileName.substringBeforeLast('.', file.fileName),
    )

    return normalizeVolumeKey(
        fromTitle
            .takeIf { it.isNotBlank() }
            ?: fromFileName.takeIf { it.isNotBlank() }
            ?: file.fileName,
    )
}

private fun suggestedGroupTitle(files: List<EpubFileInfo>): String {
    if (files.isEmpty()) return "Imported EPUB"

    val collectionNames = files
        .mapNotNull { it.collection?.trim()?.takeIf(String::isNotBlank) }
        .map(::extractSeriesTitle)
        .filter { it.isNotBlank() }

    val collectionGroup = collectionNames
        .groupBy { normalizeVolumeKey(it) }
        .maxByOrNull { it.value.size }

    if (collectionGroup != null && collectionGroup.value.size >= 2) {
        return collectionGroup.value.first()
    }

    val seriesNames = files
        .map { extractSeriesTitle(suggestedVolumeTitle(it)) }
        .filter { it.isNotBlank() }

    val seriesGroup = seriesNames
        .groupBy { normalizeVolumeKey(it) }
        .maxByOrNull { it.value.size }

    if (seriesGroup != null && seriesGroup.value.isNotEmpty()) {
        return seriesGroup.value.first()
    }

    return extractSeriesTitle(
        files.first().title.ifBlank {
            files.first().fileName.substringBeforeLast('.', files.first().fileName)
        },
    ).ifBlank {
        files.first().fileName.substringBeforeLast('.', files.first().fileName)
    }
}

private fun suggestedVolumeTitle(file: EpubFileInfo): String {
    val fileBaseName = file.fileName.substringBeforeLast('.', file.fileName).trim()
    val metadataTitle = file.title.trim()

    if (metadataTitle.isBlank()) return fileBaseName

    val metadataSeries = extractSeriesTitle(metadataTitle)
    val fileSeries = extractSeriesTitle(fileBaseName)
    val metadataLooksLikeSeriesOnly = metadataSeries.equals(metadataTitle, ignoreCase = true)

    return when {
        metadataLooksLikeSeriesOnly &&
            fileSeries.equals(metadataSeries, ignoreCase = true) &&
            fileBaseName.length > metadataTitle.length + 2 -> fileBaseName

        else -> metadataTitle
    }
}

private fun extractSeriesTitle(raw: String): String {
    val normalized = raw.replace(MULTI_SPACE_REGEX, " ").trim()
    if (normalized.isBlank()) return ""

    NUMBERED_SUBTITLE_REGEX.find(normalized)?.let { match ->
        return match.groupValues[1].trim()
    }

    VOLUME_MARKER_REGEX.find(normalized)?.let { match ->
        return match.groupValues[1].trim()
    }

    val withoutBrackets = normalized
        .replace(BRACKET_CONTENT_REGEX, " ")
        .replace(MULTI_SPACE_REGEX, " ")
        .trim()

    val stripped = withoutBrackets
        .replace(VOLUME_ANYWHERE_REGEX, " ")
        .replace(MULTI_SPACE_REGEX, " ")
        .trim()
        .trim('-', '–', '—', ':', ',', '.')

    return if (stripped.isNotBlank()) stripped else withoutBrackets
}

private fun extractVolumeNumber(raw: String): Int? {
    val normalized = raw.replace(MULTI_SPACE_REGEX, " ").trim()

    VOLUME_MARKER_NUMBER_REGEX.find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::parseVolumeToken)
        ?.let { return it }

    VOLUME_NUMBER_BEFORE_SEPARATOR_REGEX.find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::parseVolumeToken)
        ?.let { return it }

    TRAILING_NUMBER_REGEX.find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::parseVolumeToken)
        ?.let { return it }

    return null
}

private fun parseVolumeToken(token: String): Int? {
    token.toIntOrNull()?.let { return it }
    return romanToInt(token)
}

private fun romanToInt(input: String): Int? {
    if (input.isBlank()) return null
    if (!input.matches(ROMAN_NUMERAL_REGEX)) return null

    val values = mapOf(
        'I' to 1,
        'V' to 5,
        'X' to 10,
        'L' to 50,
        'C' to 100,
        'D' to 500,
        'M' to 1000,
    )

    var total = 0
    var previous = 0

    for (char in input.uppercase().reversed()) {
        val value = values[char] ?: return null
        if (value < previous) {
            total -= value
        } else {
            total += value
            previous = value
        }
    }

    return total.takeIf { it > 0 }
}

private fun normalizeVolumeKey(raw: String): String {
    return raw
        .lowercase()
        .replace(NON_WORD_REGEX, " ")
        .replace(MULTI_SPACE_REGEX, " ")
        .trim()
}

private fun <T> MutableList<T>.moveItem(fromIndex: Int, toIndex: Int) {
    if (fromIndex == toIndex) return
    if (fromIndex !in indices || toIndex !in indices) return

    val item = removeAt(fromIndex)
    add(toIndex, item)
}

private val NUMBERED_SUBTITLE_REGEX =
    Regex("""(?i)^(.+?)\s+(?:(?:vol(?:ume)?|book|part)\s*)?([0-9]+|[ivxlcdm]+)\s*[–—\-:]\s+.+$""")
private val VOLUME_MARKER_REGEX =
    Regex("""(?i)^(.+?)\s+(?:vol(?:ume)?|book|part|episode|ep|chapter|ch)\s*([0-9]+|[ivxlcdm]+)\b.*$""")
private val VOLUME_MARKER_NUMBER_REGEX =
    Regex("""(?i)\b(?:vol(?:ume)?|book|part|episode|ep|chapter|ch)\s*([0-9]+|[ivxlcdm]+)\b""")
private val VOLUME_NUMBER_BEFORE_SEPARATOR_REGEX =
    Regex("""(?i)\b([0-9]+|[ivxlcdm]+)\b(?=\s*[–—\-:])""")
private val TRAILING_NUMBER_REGEX = Regex("""(?i)\b([0-9]+|[ivxlcdm]+)\b\s*$""")
private val VOLUME_ANYWHERE_REGEX =
    Regex("""(?i)\b(?:vol(?:ume)?|book|part|episode|ep|chapter|ch)\s*[0-9ivxlcdm]+\b""")
private val BRACKET_CONTENT_REGEX = Regex("""\[[^]]*]|\([^)]*\)""")
private val NON_WORD_REGEX = Regex("""[^a-z0-9]+""")
private val MULTI_SPACE_REGEX = Regex("""\s+""")
private val ROMAN_NUMERAL_REGEX = Regex("""(?i)^[ivxlcdm]+$""")
