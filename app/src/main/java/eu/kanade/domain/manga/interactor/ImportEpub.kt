package eu.kanade.domain.manga.interactor

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import mihon.domain.manga.model.toDomainManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    )

    suspend fun execute(
        context: Context,
        files: List<ImportFile>,
        customTitle: String?,
        combineAsOne: Boolean,
        autoAddToLibrary: Boolean,
        categoryId: Long?,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        var successCount = 0
        val importedNovelUrls = mutableListOf<String>()

        val localNovelsDir = storageManager.getLocalNovelSourceDirectory()
            ?: return@withContext Result(0, files.size, listOf("Local novels directory not found"))

        if (combineAsOne && files.size > 1) {
            onProgress(1, 1, customTitle ?: files.first().title)
            try {
                val novelTitle = customTitle ?: files.first().fileName.substringBeforeLast('.', files.first().fileName)
                val sanitizedTitle = sanitizeFileName(novelTitle)
                val novelDir = localNovelsDir.createDirectory(sanitizedTitle)

                if (novelDir == null) {
                    errors.add("Failed to create directory for: $novelTitle")
                } else {
                    importedNovelUrls.add(sanitizedTitle)

                    files.forEachIndexed { index, file ->
                        val chapterFileName = "Chapter ${index + 1} - ${file.fileName}"
                        val destFile = novelDir.createFile(chapterFileName)
                        if (destFile != null) {
                            context.contentResolver.openInputStream(file.uri)?.use { input ->
                                destFile.openOutputStream().use { output -> input.copyTo(output) }
                            }
                        }
                    }

                    files.firstOrNull()?.coverUri?.let {
                        copyCoverToNovelDir(context, novelDir, it)
                    }

                    val combinedDescription = files.mapNotNull { it.description }.firstOrNull { it.isNotBlank() }
                    val combinedGenres = files.mapNotNull { it.genres }
                        .flatMap { it.split(",") }
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()

                    writeDetailsJson(
                        novelDir = novelDir,
                        title = novelTitle,
                        author = files.firstOrNull()?.author,
                        description = combinedDescription,
                        genres = combinedGenres,
                    )

                    successCount = 1
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
                    var novelDir = localNovelsDir.findFile(sanitizedTitle)
                    if (novelDir == null) {
                        novelDir = localNovelsDir.createDirectory(sanitizedTitle)
                    }

                    if (novelDir == null) {
                        errors.add("Failed to create directory for: ${file.fileName}")
                    } else {
                        importedNovelUrls.add(sanitizedTitle)
                        val destFile = novelDir.createFile(file.fileName)
                        if (destFile != null) {
                            context.contentResolver.openInputStream(file.uri)?.use { input ->
                                destFile.openOutputStream().use { output -> input.copyTo(output) }
                            }

                            file.coverUri?.let {
                                copyCoverToNovelDir(context, novelDir, it)
                            }

                            val genres = file.genres
                                ?.split(",")
                                ?.map { it.trim() }
                                ?.filter { it.isNotBlank() }
                                .orEmpty()

                            writeDetailsJson(
                                novelDir = novelDir,
                                title = novelTitle,
                                author = file.author,
                                description = file.description,
                                genres = genres,
                            )

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
            errors += registerImportedLocalNovels(importedNovelUrls.distinct(), categoryId)
        }

        Result(successCount, errors.size, errors)
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

    private fun copyCoverToNovelDir(context: Context, novelDir: UniFile, coverUri: Uri) {
        val coverDestFile = novelDir.createFile("cover.png") ?: return
        context.contentResolver.openInputStream(coverUri)?.use { input ->
            coverDestFile.openOutputStream().use { output -> input.copyTo(output) }
        }
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
}
