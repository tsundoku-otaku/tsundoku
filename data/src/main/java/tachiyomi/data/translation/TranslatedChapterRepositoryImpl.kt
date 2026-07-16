package tachiyomi.data.translation

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.translation.model.ChapterRef
import tachiyomi.domain.translation.model.TranslatedChapter
import tachiyomi.domain.translation.model.TranslationLocator
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import java.io.File

/**
 * Filesystem-only implementation of [TranslatedChapterRepository].
 *
 * Portable storage layout (shared storage via SAF / UniFile), mirroring downloads:
 *   {baseDir}/translations/{source name}/{novel title}/{language}/{chapter name}_{urlHash}.html
 *
 * Falls back to app-internal storage if shared storage is unavailable.
 *
 * Engine and date metadata are embedded as an HTML comment on the first line:
 *   <!-- tsundoku-meta:engineId:dateTranslated -->
 *
 * NOTE: This implementation uses [UniFile] throughout (same as the download system)
 * to avoid converting SAF URIs to file paths, which crashes on some devices/ROMs.
 */
class TranslatedChapterRepositoryImpl(
    private val context: Context,
    private val storageManager: StorageManager,
) : TranslatedChapterRepository {

    /** Legacy (app-internal) directory – used for fallback and migration. */
    private val legacyDir = File(context.filesDir, "translations")

    private val translationsDir: UniFile
        get() {
            val shared = storageManager.getTranslationsDirectory()
            if (shared != null && shared.exists()) return shared
            legacyDir.mkdirs()
            return UniFile.fromFile(legacyDir)!!
        }

    init {
        migrateFromLegacyDir()
    }

    private companion object {
        // Hex chars of the url MD5 appended to disambiguate chapters whose sanitized
        // names collide. 8 chars (~4.3B values) keeps per-novel collisions negligible.
        const val HASH_LENGTH = 8
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
            val targetPath = try {
                targetDir.filePath
            } catch (_: Exception) {
                null
            }
            if (targetPath != null && targetPath == legacyDir.absolutePath) return

            var migrated = 0
            for (file in files) {
                val name = file.name
                if (targetDir.findFile(name) != null) {
                    file.delete()
                    migrated++
                    continue
                }
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

    // ── Path helpers ──────────────────────────────────────────────────

    private fun sourceDirName(sourceName: String): String =
        DiskUtil.buildValidFilename(sourceName).ifEmpty { "source" }

    private fun novelDirName(novelTitle: String): String =
        DiskUtil.buildValidFilename(novelTitle).ifEmpty { "novel" }

    private fun langDirName(targetLanguage: String): String =
        DiskUtil.buildValidFilename(targetLanguage).ifEmpty { "unknown" }

    private fun chapterFileBase(chapterName: String, chapterUrl: String): String {
        // Reserve bytes for the longest suffix we append: "_<hash>" + ".html.tmp".
        val reserved = 1 + HASH_LENGTH + ".html.tmp".length
        val sanitized = DiskUtil.buildValidFilename(chapterName, DiskUtil.MAX_FILE_NAME_BYTES - reserved)
        // buildValidFilename can yield an empty string; keep a stable prefix so the
        // filename still carries a chapter marker before the url hash.
        val base = sanitized.ifEmpty { "chapter" }
        return base + "_" + Hash.md5(chapterUrl).take(HASH_LENGTH)
    }

    private fun fileName(locator: TranslationLocator): String =
        chapterFileBase(locator.chapterName, locator.chapterUrl) + ".html"

    private fun tmpFileName(locator: TranslationLocator): String =
        chapterFileBase(locator.chapterName, locator.chapterUrl) + ".html.tmp"

    private fun findNovelDir(sourceName: String, novelTitle: String): UniFile? =
        translationsDir.findFile(sourceDirName(sourceName))?.findFile(novelDirName(novelTitle))

    private fun findLangDir(locator: TranslationLocator, targetLanguage: String): UniFile? =
        findNovelDir(locator.sourceName, locator.novelTitle)?.findFile(langDirName(targetLanguage))

    private fun getOrCreateLangDir(locator: TranslationLocator, targetLanguage: String): UniFile? {
        val root = translationsDir
        val src = root.findChildDir(sourceDirName(locator.sourceName)) ?: return null
        val novel = src.findChildDir(novelDirName(locator.novelTitle)) ?: return null
        return novel.findChildDir(langDirName(targetLanguage))
    }

    private fun UniFile.findChildDir(name: String): UniFile? {
        val existing = findFile(name)
        if (existing != null) return existing.takeIf { it.isDirectory }
        return createDirectory(name)
    }

    private fun allFilesRecursive(dir: UniFile): Sequence<UniFile> = sequence {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) yieldAll(allFilesRecursive(f)) else yield(f)
        }
    }

    private fun deleteRecursive(file: UniFile) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        file.delete()
    }

    // ── Metadata helpers ──────────────────────────────────────────────

    private val metaPrefix = "<!-- tsundoku-meta:"
    private val metaSuffix = " -->"
    private val metaRegex = Regex("^<!-- tsundoku-meta:(.+?):(-?\\d+)(?::([a-f0-9]{64}))? -->\\n?")

    private fun buildMetaComment(engineId: String, dateTranslated: Long): String {
        return "$metaPrefix$engineId:$dateTranslated$metaSuffix\n"
    }

    private data class FileMeta(
        val engineId: String,
        val dateTranslated: Long,
        val content: String,
    )

    private fun parseUniFile(file: UniFile): FileMeta? {
        if (!file.exists()) return null
        val raw = try {
            file.openInputStream().bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read translation file: ${file.name}" }
            return null
        }
        val match = metaRegex.find(raw)
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

    private fun toTranslatedChapter(meta: FileMeta, targetLanguage: String): TranslatedChapter =
        TranslatedChapter(
            chapterId = 0,
            mangaId = 0,
            targetLanguage = targetLanguage,
            engineId = meta.engineId,
            translatedContent = meta.content,
            dateTranslated = meta.dateTranslated,
        )

    // ── Read operations ───────────────────────────────────────────────

    override suspend fun getTranslatedChapter(
        locator: TranslationLocator,
        targetLanguage: String,
    ): TranslatedChapter? = withContext(Dispatchers.IO) {
        val langDir = findLangDir(locator, targetLanguage) ?: return@withContext null
        val name = fileName(locator)
        val file = langDir.findFile(name)?.takeIf { it.exists() }
            ?: recoverPendingTranslation(langDir, name)
            ?: return@withContext null
        val meta = parseUniFile(file) ?: return@withContext null
        toTranslatedChapter(meta, targetLanguage)
    }

    // If upsertTranslation crashed after deleting the destination but before renaming its scratch
    // in, the data survives only as "<name>.saving". Promote it so no committed write is lost.
    private fun recoverPendingTranslation(langDir: UniFile, name: String): UniFile? {
        val scratch = langDir.findFile("$name.saving")?.takeIf { it.exists() } ?: return null
        return if (scratch.renameTo(name)) langDir.findFile(name)?.takeIf { it.exists() } else null
    }

    override suspend fun getAllTranslationsForChapter(locator: TranslationLocator): List<TranslatedChapter> =
        withContext(Dispatchers.IO) {
            val novelDir = findNovelDir(locator.sourceName, locator.novelTitle) ?: return@withContext emptyList()
            val target = fileName(locator)
            novelDir.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { langDir ->
                    val lang = langDir.name ?: return@mapNotNull null
                    val file = langDir.findFile(target) ?: return@mapNotNull null
                    val meta = parseUniFile(file) ?: return@mapNotNull null
                    toTranslatedChapter(meta, lang)
                }
                .orEmpty()
        }

    override suspend fun hasTranslation(locator: TranslationLocator, targetLanguage: String): Boolean =
        withContext(Dispatchers.IO) {
            findLangDir(locator, targetLanguage)?.findFile(fileName(locator)) != null
        }

    override suspend fun filterTranslatedChapters(
        sourceName: String,
        novelTitle: String,
        targetLanguage: String,
        chapters: Collection<ChapterRef>,
    ): Set<Long> = withContext(Dispatchers.IO) {
        if (chapters.isEmpty()) return@withContext emptySet()
        val langDir = findNovelDir(sourceName, novelTitle)?.findFile(langDirName(targetLanguage)) ?: return@withContext emptySet()
        val present = langDir.listFiles()
            ?.mapNotNull { it.name }
            ?.filterTo(HashSet()) { it.endsWith(".html") && !it.endsWith(".html.tmp") }
            ?: return@withContext emptySet()
        if (present.isEmpty()) return@withContext emptySet()
        chapters.asSequence()
            .filter { (chapterFileBase(it.name, it.url) + ".html") in present }
            .map { it.id }
            .toSet()
    }

    // ── Write operations ──────────────────────────────────────────────

    override suspend fun upsertTranslation(locator: TranslationLocator, translatedChapter: TranslatedChapter) =
        withContext(Dispatchers.IO) {
            val dir = getOrCreateLangDir(locator, translatedChapter.targetLanguage)
                ?: throw IllegalStateException("Failed to create translation dir for ${locator.novelTitle}")
            val name = fileName(locator)
            val meta = buildMetaComment(translatedChapter.engineId, translatedChapter.dateTranslated)
            val content = (meta + translatedChapter.translatedContent).toByteArray()

            // Write to a scratch file then swap it in so a crash mid-write can't leave a
            // truncated translation. ".saving" is not read back (reads require ".html").
            val savingName = "$name.saving"
            dir.findFile(savingName)?.delete()
            val scratch = dir.createFile(savingName)
                ?: throw IllegalStateException("Failed to create translation file: $name")
            scratch.openOutputStream().use { it.write(content) }

            dir.findFile(name)?.delete()
            if (!scratch.renameTo(name)) {
                // Leave the scratch; getTranslatedChapter recovers it, so a failed rename
                // (or a crash before this point) doesn't lose the just-written translation.
                throw IllegalStateException("Failed to finalize translation file: $name")
            }

            dir.findFile(tmpFileName(locator))?.delete()
            Unit
        }

    override suspend fun upsertTmpTranslation(locator: TranslationLocator, translatedChapter: TranslatedChapter) =
        withContext(Dispatchers.IO) {
            val dir = getOrCreateLangDir(locator, translatedChapter.targetLanguage)
                ?: throw IllegalStateException("Failed to create translation dir for ${locator.novelTitle}")
            val name = tmpFileName(locator)
            val meta = buildMetaComment(translatedChapter.engineId, translatedChapter.dateTranslated)
            val content = (meta + translatedChapter.translatedContent).toByteArray()

            dir.findFile(name)?.delete()
            val file = dir.createFile(name)
                ?: throw IllegalStateException("Failed to create tmp translation file: $name")
            file.openOutputStream().use { it.write(content) }
        }

    override suspend fun getTmpTranslation(
        locator: TranslationLocator,
        targetLanguage: String,
    ): TranslatedChapter? = withContext(Dispatchers.IO) {
        val file = findLangDir(locator, targetLanguage)?.findFile(tmpFileName(locator)) ?: return@withContext null
        val meta = parseUniFile(file) ?: return@withContext null
        toTranslatedChapter(meta, targetLanguage)
    }

    // ── Delete operations ─────────────────────────────────────────────

    override suspend fun deleteTranslation(locator: TranslationLocator, targetLanguage: String) =
        withContext(Dispatchers.IO) {
            findLangDir(locator, targetLanguage)?.let { dir ->
                dir.findFile(fileName(locator))?.delete()
                dir.findFile(tmpFileName(locator))?.delete()
            }
            Unit
        }

    override suspend fun deleteAllForChapter(locator: TranslationLocator) = withContext(Dispatchers.IO) {
        val novelDir = findNovelDir(locator.sourceName, locator.novelTitle) ?: return@withContext
        val target = fileName(locator)
        val tmp = tmpFileName(locator)
        novelDir.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { langDir ->
                langDir.findFile(target)?.delete()
                langDir.findFile(tmp)?.delete()
            }
        Unit
    }

    override suspend fun deleteAllForChapters(
        sourceName: String,
        novelTitle: String,
        chapters: Collection<ChapterRef>,
    ) = withContext(Dispatchers.IO) {
        if (chapters.isEmpty()) return@withContext
        val novelDir = findNovelDir(sourceName, novelTitle) ?: return@withContext
        val wanted = chapters.flatMapTo(HashSet()) {
            val base = chapterFileBase(it.name, it.url)
            listOf("$base.html", "$base.html.tmp")
        }
        novelDir.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { langDir ->
                langDir.listFiles()
                    ?.filter { it.name in wanted }
                    ?.forEach { it.delete() }
            }
        Unit
    }

    override suspend fun deleteAllForManga(sourceName: String, novelTitle: String) = withContext(Dispatchers.IO) {
        findNovelDir(sourceName, novelTitle)?.let { deleteRecursive(it) }
        Unit
    }

    override suspend fun renameNovel(sourceName: String, oldTitle: String, newTitle: String) =
        withContext(Dispatchers.IO) {
            val oldName = novelDirName(oldTitle)
            val newName = novelDirName(newTitle)
            if (oldName == newName) return@withContext
            val srcDir = translationsDir.findFile(sourceDirName(sourceName)) ?: return@withContext
            val oldDir = srcDir.findFile(oldName)?.takeIf { it.isDirectory && it.exists() } ?: return@withContext
            val existing = srcDir.findFile(newName)
            if (existing != null) {
                // Deny if a non-empty dir already holds translations; only reclaim an empty one.
                if (!existing.isDirectory || !existing.listFiles().isNullOrEmpty()) {
                    logcat(LogPriority.WARN) { "Translation dir '$newName' exists and is not empty; not renaming '$oldName'" }
                    return@withContext
                }
                existing.delete()
            }
            if (!oldDir.renameTo(newName)) {
                logcat(LogPriority.ERROR) { "Failed to rename translation dir '$oldName' -> '$newName'" }
            }
            Unit
        }

    override suspend fun moveNovel(
        oldSourceName: String,
        oldTitle: String,
        newSourceName: String,
        newTitle: String,
    ) = withContext(Dispatchers.IO) {
        val oldSrcName = sourceDirName(oldSourceName)
        val newSrcName = sourceDirName(newSourceName)
        val oldNovel = novelDirName(oldTitle)
        val newNovel = novelDirName(newTitle)
        if (oldSrcName == newSrcName && oldNovel == newNovel) return@withContext

        val oldDir = translationsDir.findFile(oldSrcName)?.findFile(oldNovel)
            ?.takeIf { it.isDirectory && it.exists() } ?: return@withContext
        val newSrcDir = translationsDir.findChildDir(newSrcName) ?: return@withContext

        val existing = newSrcDir.findFile(newNovel)
        if (existing != null) {
            if (!existing.isDirectory || !existing.listFiles().isNullOrEmpty()) {
                logcat(LogPriority.WARN) { "Translation dir '$newSrcName/$newNovel' exists and is not empty; not moving" }
                return@withContext
            }
            existing.delete()
        }

        if (oldSrcName == newSrcName) {
            // Same parent: a rename suffices (no cross-directory copy needed).
            if (!oldDir.renameTo(newNovel)) {
                logcat(LogPriority.ERROR) { "Failed to rename translation dir '$oldNovel' -> '$newNovel'" }
            }
            return@withContext
        }

        val destDir = newSrcDir.createDirectory(newNovel) ?: return@withContext
        copyContentsRecursive(oldDir, destDir)
        deleteRecursive(oldDir)
        Unit
    }

    // SAF renameTo only renames within the same parent, so a cross-source move is copy-then-delete.
    private fun copyContentsRecursive(src: UniFile, dest: UniFile) {
        src.listFiles()?.forEach { child ->
            val name = child.name ?: return@forEach
            if (child.isDirectory) {
                dest.findChildDir(name)?.let { copyContentsRecursive(child, it) }
            } else {
                val out = dest.createFile(name) ?: return@forEach
                child.openInputStream().use { input -> out.openOutputStream().use { input.copyTo(it) } }
            }
        }
    }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        translationsDir.listFiles()?.forEach { deleteRecursive(it) }
        Unit
    }

    override suspend fun clearTmpFiles(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        allFilesRecursive(translationsDir)
            .filter { it.name?.endsWith(".html.tmp") == true }
            .forEach {
                freed += it.length()
                it.delete()
            }
        freed
    }
}
