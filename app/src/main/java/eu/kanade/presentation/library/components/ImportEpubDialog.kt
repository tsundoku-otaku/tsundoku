package eu.kanade.presentation.library.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hippo.unifile.UniFile
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.category.visualName
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.EpubReader
import mihon.core.archive.archiveReader
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalNovelSource
import tachiyomi.source.local.metadata.fillMetadata
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class EpubFileInfo(
    val uri: Uri,
    val fileName: String,
    val title: String,
    val author: String?,
    val description: String?,
    val coverUri: Uri? = null,
    val collection: String? = null,
    val genres: String? = null,
)

data class ImportProgress(
    val current: Int,
    val total: Int,
    val currentFileName: String,
    val isRunning: Boolean,
)

data class ImportResult(
    val successCount: Int,
    val errorCount: Int,
    val errors: List<String>,
)

@Composable
fun ImportEpubDialog(
    onDismissRequest: () -> Unit,
    onImportComplete: (success: Int, errors: Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedFiles = remember { mutableStateListOf<EpubFileInfo>() }
    var customTitle by remember { mutableStateOf("") }
    var combineAsOneNovel by remember { mutableStateOf(false) }
    var autoAddToLibrary by remember { mutableStateOf(true) }
    var selectedCategoryId by remember { mutableStateOf<Long?>(null) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }

    var importProgress by remember { mutableStateOf<ImportProgress?>(null) }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }
    var isLoadingFiles by remember { mutableStateOf(false) }

    val storageManager = remember { Injekt.get<StorageManager>() }
    val getCategories = remember { Injekt.get<GetCategories>() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        categories = withContext(Dispatchers.IO) {
            getCategories.await().filter {
                it.contentType == Category.CONTENT_TYPE_ALL || it.contentType == Category.CONTENT_TYPE_NOVEL
            }
        }
        if (selectedCategoryId == null) {
            selectedCategoryId = categories.firstOrNull()?.id
        }
    }

    // File picker for multiple EPUB files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            isLoadingFiles = true
            scope.launch {
                val fileInfos = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        try {
                            val inputStream = context.contentResolver.openInputStream(uri) ?: return@mapNotNull null
                            val fileName = getFileNameFromUri(context, uri) ?: "unknown.epub"

                            // Create a temporary file to read EPUB metadata
                            val tempFile = File.createTempFile("epub_import_", ".epub", context.cacheDir)
                            tempFile.outputStream().use { out ->
                                inputStream.copyTo(out)
                            }
                            inputStream.close()

                            val uniFile = UniFile.fromFile(tempFile)
                            val epubReader = EpubReader(uniFile!!.archiveReader(context))

                            val manga = SManga.create()
                            val chapter = SChapter.create()
                            epubReader.fillMetadata(manga, chapter)

                            // Extract title from chapter (fillMetadata puts it there) or use filename
                            val title = if (chapter.name.isNotBlank()) chapter.name else fileName.removeSuffix(".epub")
                            // Set manga title from chapter name for consistency
                            manga.title = title

                            // Extract cover image from EPUB
                            var coverUri: Uri? = null
                            try {
                                val coverHref = manga.thumbnail_url
                                val coverExt = coverHref
                                    ?.substringBefore('?')
                                    ?.substringAfterLast('.', "png")
                                    ?.takeIf { it.length <= 4 }
                                    ?: "png"
                                val coverStream = if (!coverHref.isNullOrBlank()) {
                                    if (coverHref.startsWith("http://") || coverHref.startsWith("https://")) {
                                        try {
                                            val connection = URL(coverHref).openConnection() as HttpURLConnection
                                            connection.connectTimeout = 10_000
                                            connection.readTimeout = 10_000
                                            val bytes = connection.inputStream.use { it.readBytes() }
                                            ByteArrayInputStream(bytes)
                                        } catch (_: Exception) {
                                            null
                                        }
                                    } else {
                                        epubReader.getInputStream(coverHref)
                                    }
                                } else {
                                    // Fallback: try common cover filenames
                                    val commonNames = listOf(
                                        "cover.jpg", "cover.jpeg", "cover.png",
                                        "OEBPS/cover.jpg", "OEBPS/cover.jpeg", "OEBPS/cover.png",
                                        "Images/cover.jpg", "Images/cover.jpeg", "Images/cover.png",
                                    )
                                    var stream: java.io.InputStream? = null
                                    for (name in commonNames) {
                                        stream = epubReader.getInputStream(name)
                                        if (stream != null) break
                                    }
                                    stream
                                }

                                coverStream?.use { stream ->
                                    val coverFile = File.createTempFile("epub_cover_", ".$coverExt", context.cacheDir)
                                    coverFile.outputStream().use { out ->
                                        stream.copyTo(out)
                                    }
                                    coverUri = UniFile.fromFile(coverFile)?.uri
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.DEBUG, e) { "Could not extract cover from EPUB" }
                            }

                            epubReader.close()

                            tempFile.delete()

                            EpubFileInfo(
                                uri = uri,
                                fileName = fileName,
                                title = title,
                                author = manga.author,
                                description = manga.description,
                                coverUri = coverUri,
                                collection = runCatching { manga.title }.getOrNull()?.takeIf { it.isNotBlank() },
                                genres = manga.genre,
                            )
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "Failed to parse EPUB: $uri" }
                            withContext<Unit>(Dispatchers.Main) {
                                context.toast("Failed to parse EPUB: ${e.message}")
                            }
                            null
                        }
                    }
                }
                selectedFiles.clear()
                selectedFiles.addAll(fileInfos)
                if (fileInfos.size == 1) {
                    customTitle = fileInfos.first().title
                } else if (fileInfos.size > 1) {
                    // Check if they share the same collection
                    val commonCollection = fileInfos.mapNotNull { it.collection }.distinct()
                    if (commonCollection.size == 1) {
                        customTitle = commonCollection.first()
                    } else {
                        // For combine mode, default title to first selected epub filename (without extension).
                        customTitle = fileInfos.first().fileName.substringBeforeLast('.', fileInfos.first().fileName)
                    }
                }
                isLoadingFiles = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (importProgress?.isRunning != true) {
                onDismissRequest()
            }
        },
        title = { Text(stringResource(TDMR.strings.epub_import_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    importResult != null -> {
                        ImportResultView(importResult!!)
                    }
                    importProgress != null -> {
                        ImportProgressView(importProgress!!)
                    }
                    else -> {
                        // Selection UI
                        OutlinedButton(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.FileOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(TDMR.strings.epub_select_files))
                        }

                        if (selectedFiles.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))

                            // Show selected files
                            Text(
                                text = stringResource(TDMR.strings.epub_selected_files),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            selectedFiles.forEach { file ->
                                Text(
                                    text = "• ${file.fileName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }

                            Spacer(Modifier.height(16.dp))

                            // Custom title (for single file or combined)
                            if (selectedFiles.size == 1 || combineAsOneNovel) {
                                OutlinedTextField(
                                    value = customTitle,
                                    onValueChange = { customTitle = it },
                                    label = { Text(stringResource(TDMR.strings.epub_novel_title)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            // Combine option for multiple files
                            if (selectedFiles.size > 1) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            combineAsOneNovel = !combineAsOneNovel
                                            if (combineAsOneNovel && customTitle.isBlank()) {
                                                customTitle = selectedFiles.firstOrNull()?.fileName
                                                    ?.substringBeforeLast('.', selectedFiles.first().fileName)
                                                    .orEmpty()
                                            }
                                        },
                                ) {
                                    Checkbox(
                                        checked = combineAsOneNovel,
                                        onCheckedChange = {
                                            combineAsOneNovel = it
                                            if (it && customTitle.isBlank()) {
                                                customTitle = selectedFiles.firstOrNull()?.fileName
                                                    ?.substringBeforeLast('.', selectedFiles.first().fileName)
                                                    .orEmpty()
                                            }
                                        },
                                    )
                                    Text(stringResource(TDMR.strings.epub_combine_files))
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            // Optional: auto-add imported local novels to library and category
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { autoAddToLibrary = !autoAddToLibrary },
                            ) {
                                Checkbox(
                                    checked = autoAddToLibrary,
                                    onCheckedChange = { autoAddToLibrary = it },
                                )
                                Text(stringResource(TDMR.strings.epub_auto_add_to_category))
                            }

                            if (autoAddToLibrary) {
                                val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }
                                OutlinedButton(
                                    onClick = { categoryMenuExpanded = true },
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
                                    onDismissRequest = { categoryMenuExpanded = false },
                                ) {
                                    categories.forEach { category ->
                                        DropdownMenuItem(
                                            text = { Text(category.visualName) },
                                            onClick = {
                                                selectedCategoryId = category.id
                                                categoryMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            Text(
                                text = stringResource(TDMR.strings.epub_local_novels_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                importResult != null -> {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(MR.strings.action_close))
                    }
                }
                importProgress != null -> {
                    // No button while importing
                }
                else -> {
                    TextButton(
                        onClick = {
                            if (selectedFiles.isNotEmpty()) {
                                scope.launch {
                                    importProgress = ImportProgress(0, selectedFiles.size, "", true)
                                    val result = importEpubFiles(
                                        context = context,
                                        files = selectedFiles.toList(),
                                        customTitle = customTitle.ifBlank { null },
                                        combineAsOne = combineAsOneNovel,
                                        autoAddToLibrary = autoAddToLibrary,
                                        categoryId = selectedCategoryId,
                                        storageManager = storageManager,
                                        onProgress = { current, total, fileName ->
                                            importProgress = ImportProgress(current, total, fileName, true)
                                        },
                                    )
                                    importProgress = null
                                    importResult = result
                                    onImportComplete(result.successCount, result.errorCount)
                                }
                            }
                        },
                        enabled = selectedFiles.isNotEmpty(),
                    ) {
                        Text(stringResource(TDMR.strings.epub_action_import))
                    }
                }
            }
        },
        dismissButton = {
            if (importProgress?.isRunning != true && importResult == null) {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            }
        },
    )
}

@Composable
private fun ImportProgressView(progress: ImportProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(TDMR.strings.epub_importing_progress, progress.current, progress.total),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress.current.toFloat() / progress.total.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = progress.currentFileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportResultView(result: ImportResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(TDMR.strings.epub_import_complete),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "✓ " + stringResource(TDMR.strings.epub_import_success_count, result.successCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (result.errorCount > 0) {
            Text(
                text = "✗ " + stringResource(TDMR.strings.epub_import_error_count, result.errorCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            result.errors.forEach { error ->
                Text(
                    text = "• $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun getFileNameFromUri(context: android.content.Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
            cursor.getString(nameIndex)
        } else {
            null
        }
    }
}

private suspend fun importEpubFiles(
    context: android.content.Context,
    files: List<EpubFileInfo>,
    customTitle: String?,
    combineAsOne: Boolean,
    autoAddToLibrary: Boolean,
    categoryId: Long?,
    storageManager: StorageManager,
    onProgress: (current: Int, total: Int, fileName: String) -> Unit,
): ImportResult = withContext(Dispatchers.IO) {
    val errors = mutableListOf<String>()
    var successCount = 0
    val importedNovelUrls = mutableListOf<String>()

    val localNovelsDir = storageManager.getLocalNovelSourceDirectory()
    if (localNovelsDir == null) {
        return@withContext ImportResult(0, files.size, listOf("Local novels directory not found"))
    }

    if (combineAsOne && files.size > 1) {
        // Combine all files into one novel folder
        onProgress(1, 1, customTitle ?: files.first().title)

        try {
            val firstFileBaseName = files.first().fileName.substringBeforeLast('.', files.first().fileName)
            val novelTitle = customTitle ?: firstFileBaseName
            val sanitizedTitle = sanitizeFileName(novelTitle)

            // Create novel folder
            val novelDir = localNovelsDir.createDirectory(sanitizedTitle)
            if (novelDir == null) {
                errors.add("Failed to create directory for: $novelTitle")
            } else {
                importedNovelUrls.add(sanitizedTitle)
                // Copy each file as a chapter
                files.forEachIndexed { index, file ->
                    val chapterFileName = "Chapter ${index + 1} - ${file.fileName}"
                    val destFile = novelDir.createFile(chapterFileName)
                    if (destFile != null) {
                        context.contentResolver.openInputStream(file.uri)?.use { input ->
                            destFile.openOutputStream()?.use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                // Copy cover from first file if available
                val firstCoverUri = files.firstOrNull()?.coverUri
                if (firstCoverUri != null) {
                    try {
                        val coverFileName = "cover.png"
                        val coverDestFile = novelDir.createFile(coverFileName)
                        if (coverDestFile != null) {
                            context.contentResolver.openInputStream(firstCoverUri)?.use { input ->
                                coverDestFile.openOutputStream()?.use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.DEBUG, e) { "Failed to copy cover for combined novel" }
                    }
                }

                // Write details.json if metadata exists
                val combinedDescription = files.mapNotNull { it.description }.firstOrNull { it.isNotBlank() }
                val combinedGenres = files.mapNotNull { it.genres }.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                val author = files.firstOrNull()?.author
                if (combinedDescription != null || combinedGenres.isNotEmpty() || author != null) {
                    try {
                        val detailsFile = novelDir.createFile("details.json")
                        val jsonStr = buildString {
                            append("{")
                            append("\"title\":\"${novelTitle.replace("\"", "\\\"")}\"")
                            if (author != null) append(",\"author\":\"${author.replace("\"", "\\\"")}\"")
                            if (combinedDescription != null) append(",\"description\":\"${combinedDescription.replace("\"", "\\\"").replace("\n", "\\n")}\"")
                            if (combinedGenres.isNotEmpty()) {
                                append(",\"genre\":[${combinedGenres.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }}]")
                            }
                            append("}")
                        }
                        detailsFile?.openOutputStream()?.use { it.write(jsonStr.toByteArray()) }
                    } catch (e: Exception) {
                        logcat(LogPriority.DEBUG, e) { "Failed to write details.json" }
                    }
                }

                successCount = 1
            }
        } catch (e: Exception) {
            errors.add("Failed to combine files: ${e.message}")
        }
    } else {
        // Import each file as a separate novel
        files.forEachIndexed { index, file ->
            onProgress(index + 1, files.size, file.fileName)

            try {
                val novelTitle = if (files.size == 1 && customTitle != null) {
                    customTitle
                } else {
                    file.title
                }
                val sanitizedTitle = sanitizeFileName(novelTitle)

                // Create novel folder
                var novelDir = localNovelsDir.findFile(sanitizedTitle)
                if (novelDir == null) {
                    novelDir = localNovelsDir.createDirectory(sanitizedTitle)
                }

                if (novelDir == null) {
                    errors.add("Failed to create directory for: ${file.fileName}")
                } else {
                    importedNovelUrls.add(sanitizedTitle)
                    // Copy the epub file
                    val destFile = novelDir.createFile(file.fileName)
                    if (destFile != null) {
                        context.contentResolver.openInputStream(file.uri)?.use { input ->
                            destFile.openOutputStream()?.use { output ->
                                input.copyTo(output)
                            }
                        }

                        // Copy cover if available
                        val coverUri = file.coverUri
                        if (coverUri != null) {
                            try {
                                val coverFileName = "cover.png"
                                val coverDestFile = novelDir.createFile(coverFileName)
                                if (coverDestFile != null) {
                                    context.contentResolver.openInputStream(coverUri)?.use { input ->
                                        coverDestFile.openOutputStream()?.use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.DEBUG, e) { "Failed to copy cover for: ${file.fileName}" }
                            }
                        }

                        // Write metadata
                        val description = file.description
                        val genres = file.genres?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                        val author = file.author
                        if (!description.isNullOrBlank() || genres.isNotEmpty() || !author.isNullOrBlank()) {
                            try {
                                val detailsFile = novelDir.createFile("details.json")
                                val jsonStr = buildString {
                                    append("{")
                                    append("\"title\":\"${novelTitle.replace("\"", "\\\"")}\"")
                                    if (!author.isNullOrBlank()) append(",\"author\":\"${author!!.replace("\"", "\\\"")}\"")
                                    if (!description.isNullOrBlank()) append(",\"description\":\"${description!!.replace("\"", "\\\"").replace("\n", "\\n")}\"")
                                    if (genres.isNotEmpty()) {
                                        append(",\"genre\":[${genres.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }}]")
                                    }
                                    append("}")
                                }
                                detailsFile?.openOutputStream()?.use { it.write(jsonStr.toByteArray()) }
                            } catch (e: Exception) {
                                logcat(LogPriority.DEBUG, e) { "Failed to write details.json for: ${file.fileName}" }
                            }
                        }

                        successCount++
                    } else {
                        errors.add("Failed to create file: ${file.fileName}")
                    }
                }
            } catch (e: Exception) {
                errors.add("${file.fileName}: ${e.message}")
            }
        }
    }

    if (autoAddToLibrary) {
        val registrationErrors = registerImportedLocalNovels(
            importedNovelUrls = importedNovelUrls.distinct(),
            categoryId = categoryId,
        )
        errors.addAll(registrationErrors)
    }

    ImportResult(successCount, errors.size, errors)
}

private suspend fun registerImportedLocalNovels(
    importedNovelUrls: List<String>,
    categoryId: Long?,
): List<String> {
    if (importedNovelUrls.isEmpty()) return emptyList()

    val networkToLocalManga = Injekt.get<NetworkToLocalManga>()
    val getMangaByUrlAndSourceId = Injekt.get<GetMangaByUrlAndSourceId>()
    val getLibraryManga = Injekt.get<GetLibraryManga>()
    val mangaRepository = Injekt.get<MangaRepository>()
    val setMangaCategories = Injekt.get<SetMangaCategories>()
    val sourceManager = Injekt.get<tachiyomi.domain.source.service.SourceManager>()
    val updateManga = Injekt.get<eu.kanade.domain.manga.interactor.UpdateManga>()
    val syncChaptersWithSource = Injekt.get<eu.kanade.domain.chapter.interactor.SyncChaptersWithSource>()

    val errors = mutableListOf<String>()
    val source = sourceManager.get(LocalNovelSource.ID) ?: run {
        return listOf("Local novel source not found")
    }

    importedNovelUrls.forEach { localUrl ->
        try {
            val existing = getMangaByUrlAndSourceId.await(localUrl, LocalNovelSource.ID)
            val manga = existing ?: run {
                val placeholder = SManga.create().apply {
                    title = localUrl
                    url = localUrl
                }
                networkToLocalManga(placeholder.toDomainManga(LocalNovelSource.ID, isNovel = true))
            }

            // Mark as favorite before fetching so the library shows it
            mangaRepository.update(
                MangaUpdate(
                    id = manga.id,
                    favorite = true,
                    dateAdded = System.currentTimeMillis(),
                    isNovel = true,
                ),
            )

            // Fetch details — this reads details.json and cover from disk
            val networkManga = source.getMangaDetails(manga.toSManga())
            updateManga.awaitUpdateFromSource(manga, networkManga, manualFetch = true)

            // Sync chapters from disk
            val chapters = source.getChapterList(manga.toSManga())
            syncChaptersWithSource.await(chapters, manga, source, manualFetch = true)

            // Re-fetch the updated manga from DB so the library cache has fresh data including cover
            val updatedManga = mangaRepository.getMangaById(manga.id)
            getLibraryManga.addToLibrary(updatedManga.id)
            getLibraryManga.applyMangaDetailUpdate(updatedManga.id) { updatedManga }

            if (categoryId != null && categoryId != 0L) {
                setMangaCategories.await(manga.id, listOf(categoryId))
            }
        } catch (e: Exception) {
            errors.add("$localUrl: ${e.message}")
        }
    }

    return errors
}

private fun sanitizeFileName(name: String): String {
    return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(200)
}
