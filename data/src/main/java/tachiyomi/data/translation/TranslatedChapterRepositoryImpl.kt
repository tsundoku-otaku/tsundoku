package tachiyomi.data.translation

import android.content.Context
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.translation.model.TranslatedChapter
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import java.io.File

/**
 * Filesystem-only implementation of [TranslatedChapterRepository].
 *
 * Storage layout (shared storage via SAF / UniFile):
 *   {baseDir}/translations/{chapterId}_{targetLanguage}.html
 *
 * Falls back to app-internal storage if shared storage is unavailable:
 *   filesDir/translations/{chapterId}_{targetLanguage}.html
 *
 * Engine and date metadata are embedded as an HTML comment on the first line:
 *   <!-- tsundoku-meta:engineId:dateTranslated -->
 *
 * NOTE: This implementation uses [UniFile] throughout (same as the download system)
 * to avoid converting SAF URIs to file paths, which causes crashes on some devices/ROMs
 * when [android.content.Context.getExternalCacheDirs] fails.
 */
class TranslatedChapterRepositoryImpl(
    private val context: Context,
    private val storageManager: StorageManager,
) : TranslatedChapterRepository {

    /** Legacy (app-internal) directory – used for fallback and migration. */
    private val legacyDir = File(context.filesDir, "translations")

    /**
     * Resolve the translations directory via SAF, falling back to app-internal.
     * Returns a [UniFile] that can be used for all I/O operations without needing
     * to convert to a filesystem path.
     */
    private val translationsDir: UniFile
        get() {
            val shared = storageManager.getTranslationsDirectory()
            if (shared != null && shared.exists()) return shared
            // Fallback to app-internal wrapped as UniFile
            legacyDir.mkdirs()
            return UniFile.fromFile(legacyDir)!!
        }

    init {
        // Migrate existing translations from legacy location to shared storage on first access
        migrateFromLegacyDir()
    }

    /**
     * Move translation files from old app-internal dir to the shared storage dir.
     * Safe to call multiple times – it only moves files that still exist in the legacy dir.
     */
    private fun migrateFromLegacyDir() {
        try {
            if (!legacyDir.exists()) return
            val files = legacyDir.listFiles { f -> f.name.endsWith(".html") } ?: return
            if (files.isEmpty()) return

            val targetDir = storageManager.getTranslationsDirectory() ?: return
            // Don't migrate if target is effectively the same as legacy (both are app-internal)
            val targetPath = try { targetDir.filePath } catch (_: Exception) { null }
            if (targetPath != null && targetPath == legacyDir.absolutePath) return

            var migrated = 0
            for (file in files) {
                val name = file.name
                // Check if already exists in target
                if (targetDir.findFile(name) != null) {
                    file.delete()
                    migrated++
                    continue
                }
                // Copy file content via SAF
                val dest = targetDir.createFile(name) ?: continue
                try {
                    file.inputStream().use { input ->
                        dest.openOutputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    file.delete()
                    migrated++
                } catch (e: Exception) {
                    dest.delete()
                    logcat(LogPriority.WARN, e) { "Failed to migrate translation file: $name" }
                }
            }

            // Clean up empty legacy dir
            if (legacyDir.listFiles()?.isEmpty() == true) {
                legacyDir.delete()
            }

            if (migrated > 0) {
                logcat(LogPriority.INFO) { "Migrated $migrated translation files to shared storage" }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to migrate translation files" }
        }
    }

    private fun getTranslationFileName(chapterId: Long, targetLanguage: String): String {
        return "${chapterId}_$targetLanguage.html"
    }

    private fun getTmpTranslationFileName(chapterId: Long, targetLanguage: String): String {
        return "${chapterId}_$targetLanguage.html.tmp"
    }

    private fun findTranslationFile(chapterId: Long, targetLanguage: String): UniFile? {
        val name = getTranslationFileName(chapterId, targetLanguage)
        return translationsDir.findFile(name)
    }

    // ── Metadata helpers ──────────────────────────────────────────────

    private val META_PREFIX = "<!-- tsundoku-meta:"
    private val META_SUFFIX = " -->"
    private val META_REGEX = Regex("^<!-- tsundoku-meta:(.+?):(-?\\d+) -->\\n?")

    private fun buildMetaComment(engineId: String, dateTranslated: Long): String {
        return "$META_PREFIX$engineId:$dateTranslated$META_SUFFIX\n"
    }

    private data class FileMeta(val engineId: String, val dateTranslated: Long, val content: String)

    /** Read a translation file, parsing the optional metadata comment from the first line. */
    private fun parseUniFile(file: UniFile): FileMeta? {
        if (!file.exists()) return null
        val raw = try {
            file.openInputStream().bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read translation file: ${file.name}" }
            return null
        }
        val match = META_REGEX.find(raw)
        return if (match != null) {
            FileMeta(
                engineId = match.groupValues[1],
                dateTranslated = match.groupValues[2].toLong(),
                content = raw.removeRange(match.range).trimStart('\n'),
            )
        } else {
            FileMeta(engineId = "unknown", dateTranslated = 0L, content = raw)
        }
    }

    // ── Read operations ───────────────────────────────────────────────

    override suspend fun getTranslatedChapter(chapterId: Long, targetLanguage: String): TranslatedChapter? =
        withContext(Dispatchers.IO) {
            val file = findTranslationFile(chapterId, targetLanguage) ?: return@withContext null
            val meta = parseUniFile(file) ?: return@withContext null
            TranslatedChapter(
                chapterId = chapterId,
                mangaId = 0,
                targetLanguage = targetLanguage,
                engineId = meta.engineId,
                translatedContent = meta.content,
                dateTranslated = meta.dateTranslated,
            )
        }

    override suspend fun getAllTranslationsForChapter(chapterId: Long): List<TranslatedChapter> =
        withContext(Dispatchers.IO) {
            val prefix = "${chapterId}_"
            val dir = translationsDir
            dir.listFiles()
                ?.filter { it.name?.startsWith(prefix) == true && it.name?.endsWith(".html") == true }
                ?.mapNotNull { file ->
                    val nameWithoutExt = file.nameWithoutExtension ?: return@mapNotNull null
                    val lang = nameWithoutExt.removePrefix(prefix)
                    val meta = parseUniFile(file) ?: return@mapNotNull null
                    TranslatedChapter(
                        chapterId = chapterId,
                        mangaId = 0,
                        targetLanguage = lang,
                        engineId = meta.engineId,
                        translatedContent = meta.content,
                        dateTranslated = meta.dateTranslated,
                    )
                }
                .orEmpty()
        }

    override suspend fun hasTranslation(chapterId: Long, targetLanguage: String): Boolean =
        withContext(Dispatchers.IO) {
            findTranslationFile(chapterId, targetLanguage) != null
        }

    /**
     * Check if a partial (tmp) translation exists for a chapter.
     */
    suspend fun hasTmpTranslation(chapterId: Long, targetLanguage: String): Boolean =
        withContext(Dispatchers.IO) {
            val name = getTmpTranslationFileName(chapterId, targetLanguage)
            translationsDir.findFile(name) != null
        }

    override suspend fun getTranslatedChapterIds(chapterIds: Collection<Long>): Set<Long> =
        withContext(Dispatchers.IO) {
            if (chapterIds.isEmpty()) return@withContext emptySet()
            val dir = translationsDir
            val allFiles = dir.listFiles() ?: return@withContext emptySet()
            val wantedIds = chapterIds.toHashSet()
            allFiles.asSequence()
                .filter { it.name?.endsWith(".html") == true && it.name?.endsWith(".html.tmp") != true }
                .mapNotNull { it.nameWithoutExtension?.substringBefore('_')?.toLongOrNull() }
                .filter { it in wantedIds }
                .toSet()
        }

    override suspend fun getTranslatedLanguagesForChapters(chapterIds: Collection<Long>): List<String> =
        withContext(Dispatchers.IO) {
            if (chapterIds.isEmpty()) return@withContext emptyList()
            val dir = translationsDir
            val allFiles = dir.listFiles() ?: return@withContext emptyList()
            val wantedIds = chapterIds.toHashSet()
            allFiles.asSequence()
                .filter { it.name?.endsWith(".html") == true && it.name?.endsWith(".html.tmp") != true }
                .filter { it.nameWithoutExtension?.substringBefore('_')?.toLongOrNull() in wantedIds }
                .mapNotNull { it.nameWithoutExtension?.substringAfter('_') }
                .distinct()
                .toList()
        }

    override suspend fun getAll(): List<TranslatedChapter> = withContext(Dispatchers.IO) {
        val dir = translationsDir
        dir.listFiles()
            ?.filter { it.name?.endsWith(".html") == true && it.name?.endsWith(".html.tmp") != true }
            ?.mapNotNull { file ->
                val nameWithoutExt = file.nameWithoutExtension ?: return@mapNotNull null
                val parts = nameWithoutExt.split('_', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val chapterId = parts[0].toLongOrNull() ?: return@mapNotNull null
                val lang = parts[1]
                val meta = parseUniFile(file) ?: return@mapNotNull null
                TranslatedChapter(
                    chapterId = chapterId,
                    mangaId = 0,
                    targetLanguage = lang,
                    engineId = meta.engineId,
                    translatedContent = meta.content,
                    dateTranslated = meta.dateTranslated,
                )
            }
            .orEmpty()
    }

    // ── Write operations ──────────────────────────────────────────────

    override suspend fun upsertTranslation(translatedChapter: TranslatedChapter) =
        withContext(Dispatchers.IO) {
            val dir = translationsDir
            val name = getTranslationFileName(translatedChapter.chapterId, translatedChapter.targetLanguage)
            val meta = buildMetaComment(translatedChapter.engineId, translatedChapter.dateTranslated)
            val content = (meta + translatedChapter.translatedContent).toByteArray()

            // Delete existing file first (createFile may fail if it already exists on some backends)
            dir.findFile(name)?.delete()
            val file = dir.createFile(name)
                ?: throw IllegalStateException("Failed to create translation file: $name")
            file.openOutputStream().use { it.write(content) }

            // Remove any tmp file for this chapter+language
            val tmpName = getTmpTranslationFileName(translatedChapter.chapterId, translatedChapter.targetLanguage)
            dir.findFile(tmpName)?.delete()
            Unit
        }

    /**
     * Save a partial (in-progress) translation with .tmp extension.
     * This allows resuming incomplete translations.
     */
    suspend fun upsertTmpTranslation(translatedChapter: TranslatedChapter) =
        withContext(Dispatchers.IO) {
            val dir = translationsDir
            val name = getTmpTranslationFileName(translatedChapter.chapterId, translatedChapter.targetLanguage)
            val meta = buildMetaComment(translatedChapter.engineId, translatedChapter.dateTranslated)
            val content = (meta + translatedChapter.translatedContent).toByteArray()

            dir.findFile(name)?.delete()
            val file = dir.createFile(name)
                ?: throw IllegalStateException("Failed to create tmp translation file: $name")
            file.openOutputStream().use { it.write(content) }
        }

    /**
     * Read a partial (in-progress) translation.
     */
    suspend fun getTmpTranslation(chapterId: Long, targetLanguage: String): TranslatedChapter? =
        withContext(Dispatchers.IO) {
            val name = getTmpTranslationFileName(chapterId, targetLanguage)
            val file = translationsDir.findFile(name) ?: return@withContext null
            val meta = parseUniFile(file) ?: return@withContext null
            TranslatedChapter(
                chapterId = chapterId,
                mangaId = 0,
                targetLanguage = targetLanguage,
                engineId = meta.engineId,
                translatedContent = meta.content,
                dateTranslated = meta.dateTranslated,
            )
        }

    /**
     * Promote a tmp translation to a final one (remove .tmp extension).
     */
    suspend fun promoteTmpTranslation(chapterId: Long, targetLanguage: String): Boolean =
        withContext(Dispatchers.IO) {
            val tmpName = getTmpTranslationFileName(chapterId, targetLanguage)
            val tmpFile = translationsDir.findFile(tmpName) ?: return@withContext false
            val meta = parseUniFile(tmpFile) ?: return@withContext false

            // Write final file
            val dir = translationsDir
            val finalName = getTranslationFileName(chapterId, targetLanguage)
            dir.findFile(finalName)?.delete()
            val finalFile = dir.createFile(finalName) ?: return@withContext false
            val metaComment = buildMetaComment(meta.engineId, meta.dateTranslated)
            finalFile.openOutputStream().use { it.write((metaComment + meta.content).toByteArray()) }

            // Delete tmp
            tmpFile.delete()
            true
        }

    // ── Delete operations ─────────────────────────────────────────────

    override suspend fun deleteTranslation(chapterId: Long, targetLanguage: String) =
        withContext(Dispatchers.IO) {
            findTranslationFile(chapterId, targetLanguage)?.delete()
            // Also delete any tmp file
            val tmpName = getTmpTranslationFileName(chapterId, targetLanguage)
            translationsDir.findFile(tmpName)?.delete()
            Unit
        }

    override suspend fun deleteAllForChapter(chapterId: Long) = withContext(Dispatchers.IO) {
        val prefix = "${chapterId}_"
        val dir = translationsDir
        dir.listFiles()
            ?.filter { it.name?.startsWith(prefix) == true }
            ?.forEach { it.delete() }
        Unit
    }

    override suspend fun deleteAllForChapters(chapterIds: Collection<Long>) = withContext(Dispatchers.IO) {
        if (chapterIds.isEmpty()) return@withContext
        val wantedIds = chapterIds.toHashSet()
        val dir = translationsDir
        dir.listFiles()
            ?.filter {
                val name = it.name ?: return@filter false
                name.endsWith(".html") || name.endsWith(".html.tmp")
            }
            ?.filter {
                val nameNoExt = it.name?.substringBefore('_') ?: return@filter false
                nameNoExt.toLongOrNull() in wantedIds
            }
            ?.forEach { it.delete() }
        Unit
    }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        val dir = translationsDir
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    // ── Cache management ──────────────────────────────────────────────

    override suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        val dir = translationsDir
        dir.listFiles()
            ?.filter { it.isFile }
            ?.sumOf { it.length() }
            ?: 0L
    }

    override suspend fun clearOldCache(olderThan: Long) = withContext(Dispatchers.IO) {
        val dir = translationsDir
        dir.listFiles()
            ?.filter { it.name?.endsWith(".html") == true || it.name?.endsWith(".html.tmp") == true }
            ?.filter { it.lastModified() < olderThan }
            ?.forEach { it.delete() }
        Unit
    }

    override suspend fun clearTmpFiles(): Long = withContext(Dispatchers.IO) {
        val dir = translationsDir
        var freed = 0L
        dir.listFiles()
            ?.filter { it.name?.endsWith(".html.tmp") == true }
            ?.forEach {
                freed += it.length()
                it.delete()
            }
        freed
    }
}
