package eu.kanade.domain.manga.interactor

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

class ImportEpub(
    private val storageManager: StorageManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
) {
    private companion object {
        const val LOCAL_NOVEL_SOURCE_ID = 1L
    }

    data class ImportFile(
        val uri: Uri,
        val fileName: String,
        val title: String,
        val author: String?,
        val description: String?,
        val coverUri: Uri?,
        val genres: String?,
    )

    data class Result(
        val successCount: Int,
        val errorCount: Int,
        val errors: List<String>,
        val importedUris: List<Uri>,
    )

    suspend fun execute(
        context: Context,
        files: List<ImportFile>,
        customTitle: String?,
        combineAsOne: Boolean,
        autoAddToLibrary: Boolean,
        categoryId: Long?,
        forceUniqueFolderName: Boolean = false,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        var successCount = 0
        val importedNovelUrls = mutableListOf<String>()
        val importedUris = mutableListOf<Uri>()

        val localNovelsDir = storageManager.getLocalNovelSourceDirectory()
            ?: return@withContext Result(
                successCount = 0,
                errorCount = files.size,
                errors = listOf("Local novels directory not found"),
                importedUris = emptyList(),
            )

        if (combineAsOne && files.size > 1) {
            onProgress(1, 1, customTitle ?: files.first().title)
            try {
                val novelTitle = customTitle ?: files.first().fileName.substringBeforeLast('.', files.first().fileName)
                val sanitizedTitle = sanitizeFileName(novelTitle)
                val novelFolderName = if (forceUniqueFolderName) {
                    createUniqueFolderName(sanitizedTitle)
                } else {
                    sanitizedTitle
                }
                val novelDir = localNovelsDir.createDirectory(novelFolderName)

                if (novelDir == null) {
                    errors.add("Failed to create directory for: $novelTitle")
                } else {
                    var copiedCount = 0

                    files.forEachIndexed { index, file ->
                        val chapterFileName = buildVolumeFileName(file, index)
                        val copied = copyImportFileToDirectory(
                            context = context,
                            sourceFile = file,
                            novelDir = novelDir,
                            destinationFileName = chapterFileName,
                            createFileErrorLabel = chapterFileName,
                            errors = errors,
                        )

                        if (copied) {
                            copiedCount++
                            importedUris += file.uri
                        }
                    }

                    if (copiedCount > 0) {
                        importedNovelUrls.add(novelFolderName)
                    }

                    files.firstNotNullOfOrNull { it.coverUri }?.let {
                        copyCoverToNovelDir(context, novelDir, it)
                    }

                    val combinedDescription = buildCombinedDescription(files)
                    val combinedGenres = mergeGenres(files)
                    val combinedAuthor = mergeAuthors(files)

                    writeDetailsJson(
                        novelDir = novelDir,
                        title = novelTitle,
                        author = combinedAuthor,
                        description = combinedDescription,
                        genres = combinedGenres,
                    )

                    if (copiedCount > 0) {
                        successCount = 1
                    }
                }
            } catch (e: Exception) {
                errors.add("Failed to combine files: ${e.message}")
            }
        } else {
            files.forEachIndexed { index, file ->
                onProgress(index + 1, files.size, file.fileName)
                try {
                    val novelTitle = if (files.size == 1 && customTitle != null) customTitle else file.title
                    val sanitizedTitle = sanitizeFileName(novelTitle)
                    val novelFolderName = if (forceUniqueFolderName) {
                        createUniqueFolderName(sanitizedTitle)
                    } else {
                        sanitizedTitle
                    }
                    var novelDir = if (forceUniqueFolderName) {
                        localNovelsDir.createDirectory(novelFolderName)
                    } else {
                        localNovelsDir.findFile(novelFolderName) ?: localNovelsDir.createDirectory(novelFolderName)
                    }

                    if (novelDir == null) {
                        errors.add("Failed to create directory for: ${file.fileName}")
                    } else {
                        val targetFileName = buildVolumeFileName(file)
                        val copied = copyImportFileToDirectory(
                            context = context,
                            sourceFile = file,
                            novelDir = novelDir,
                            destinationFileName = targetFileName,
                            createFileErrorLabel = file.fileName,
                            errors = errors,
                        )

                        if (copied) {
                            importedNovelUrls.add(novelFolderName)
                            importedUris += file.uri

                            file.coverUri?.let {
                                copyCoverToNovelDir(context, novelDir, it)
                            }

                            val genres = file.genres
                                ?.let(::splitGenres)
                                .orEmpty()

                            writeDetailsJson(
                                novelDir = novelDir,
                                title = novelTitle,
                                author = file.author,
                                description = file.description,
                                genres = genres,
                            )

                            successCount++
                        }
                    }
                } catch (e: Exception) {
                    errors.add("${file.fileName}: ${e.message}")
                }
            }
        }

        if (autoAddToLibrary) {
            errors += registerImportedLocalNovels(importedNovelUrls.distinct(), categoryId)
        }

        Result(
            successCount = successCount,
            errorCount = errors.size,
            errors = errors,
            importedUris = importedUris.distinct(),
        )
    }

    private suspend fun registerImportedLocalNovels(
        importedNovelUrls: List<String>,
        categoryId: Long?,
    ): List<String> {
        if (importedNovelUrls.isEmpty()) return emptyList()

        val source = sourceManager.get(LOCAL_NOVEL_SOURCE_ID) ?: return listOf("Local novel source not found")
        val errors = mutableListOf<String>()

        importedNovelUrls.forEach { localUrl ->
            try {
                val existing = getMangaByUrlAndSourceId.await(localUrl, LOCAL_NOVEL_SOURCE_ID)
                val manga = existing ?: run {
                    val placeholder = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                        title = localUrl
                        url = localUrl
                    }
                    networkToLocalManga(placeholder.toDomainManga(LOCAL_NOVEL_SOURCE_ID, isNovel = true))
                }

                mangaRepository.update(
                    MangaUpdate(
                        id = manga.id,
                        favorite = true,
                        dateAdded = System.currentTimeMillis(),
                        isNovel = true,
                    ),
                )

                val networkManga = source.getMangaDetails(manga.toSManga())
                updateManga.awaitUpdateFromSource(manga, networkManga, manualFetch = true)

                val chapters = source.getChapterList(manga.toSManga())
                syncChaptersWithSource.await(chapters, manga, source, manualFetch = true)

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

    private fun createUniqueFolderName(baseName: String): String {
        val safeBase = sanitizeFileName(baseName).ifBlank { "Imported EPUB" }
        val suffix = "-" + UUID.randomUUID().toString().substring(0, 8)
        val maxBaseLength = (200 - suffix.length).coerceAtLeast(1)
        val trimmedBase = safeBase.take(maxBaseLength).trimEnd(' ', '.', '_')
        return "$trimmedBase$suffix"
    }

    private fun buildVolumeFileName(file: ImportFile, orderIndex: Int? = null): String {
        val rawExtension = file.fileName.substringAfterLast('.', "epub")
        val extension = rawExtension
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .ifBlank { "epub" }

        val rawTitle = file.title.ifBlank {
            file.fileName.substringBeforeLast('.', file.fileName)
        }
        val sanitizedTitle = sanitizeFileName(rawTitle).ifBlank { "Volume" }

        val orderedTitle = if (orderIndex != null) {
            "${(orderIndex + 1).toString().padStart(3, '0')} - $sanitizedTitle"
        } else {
            sanitizedTitle
        }

        val maxStemLength = (200 - extension.length - 1).coerceAtLeast(1)
        val stem = orderedTitle.take(maxStemLength).trimEnd(' ', '.', '_').ifBlank { "Volume" }

        return "$stem.$extension"
    }

    private fun copyCoverToNovelDir(context: Context, novelDir: UniFile, coverUri: Uri) {
        val coverDestFile = novelDir.createFile("cover.png") ?: return
        context.contentResolver.openInputStream(coverUri)?.use { input ->
            coverDestFile.openOutputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun splitGenres(rawGenres: String): List<String> {
        return rawGenres
            .split(',', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun mergeGenres(files: List<ImportFile>): List<String> {
        val mergedGenres = linkedMapOf<String, String>()
        files
            .mapNotNull { it.genres }
            .flatMap(::splitGenres)
            .forEach { genre ->
                mergedGenres.putIfAbsent(genre.lowercase(), genre)
            }
        return mergedGenres.values.toList()
    }

    private fun mergeAuthors(files: List<ImportFile>): String? {
        val mergedAuthors = linkedMapOf<String, String>()
        files
            .mapNotNull { it.author?.trim()?.takeIf { author -> author.isNotBlank() } }
            .forEach { author ->
                mergedAuthors.putIfAbsent(author.lowercase(), author)
            }
        return mergedAuthors.values.joinToString(", ").takeIf { it.isNotBlank() }
    }

    private fun buildCombinedDescription(files: List<ImportFile>): String? {
        val combinedEntries = files.mapIndexedNotNull { index, file ->
            val description = file.description
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null

            val volumeTitle = file.title
                .trim()
                .ifBlank { file.fileName.substringBeforeLast('.', file.fileName).trim() }
                .ifBlank { "Volume ${index + 1}" }

            "(${index + 1})($volumeTitle): $description"
        }

        return combinedEntries.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun writeDetailsJson(
        novelDir: UniFile,
        title: String,
        author: String?,
        description: String?,
        genres: List<String>,
    ) {
        if (author.isNullOrBlank() && description.isNullOrBlank() && genres.isEmpty()) return

        val detailsFile = novelDir.createFile("details.json") ?: return
        val payload = buildString {
            append("{")
            append("\"title\":\"${title.replace("\"", "\\\"")}\"")
            if (!author.isNullOrBlank()) append(",\"author\":\"${author.replace("\"", "\\\"")}\"")
            if (!description.isNullOrBlank()) {
                append(",\"description\":\"${description.replace("\"", "\\\"").replace("\n", "\\n")}\"")
            }
            if (genres.isNotEmpty()) {
                append(",\"genre\":[${genres.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }}]")
            }
            append("}")
        }
        detailsFile.openOutputStream().use { it.write(payload.toByteArray()) }
    }

    private fun copyImportFileToDirectory(
        context: Context,
        sourceFile: ImportFile,
        novelDir: UniFile,
        destinationFileName: String,
        createFileErrorLabel: String,
        errors: MutableList<String>,
    ): Boolean {
        val destinationFile = novelDir.createFile(destinationFileName)
        if (destinationFile == null) {
            errors.add("Failed to create file: $createFileErrorLabel")
            return false
        }

        val inputStream = context.contentResolver.openInputStream(sourceFile.uri)
        if (inputStream == null) {
            errors.add("Failed to read file: ${sourceFile.fileName}")
            return false
        }

        inputStream.use { input ->
            destinationFile.openOutputStream().use { output -> input.copyTo(output) }
        }

        return true
    }
}
