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

    private fun sourceDirName(sourceName: String): String = DiskUtil.buildValidFilename(sourceName)

    private fun novelDirName(novelTitle: String): String = DiskUtil.buildValidFilename(novelTitle)

    private fun chapterFileBase(chapterName: String, chapterUrl: String): String {
        // Reserve bytes for the longest suffix we append: "_<6 hex>" + ".html.tmp".
        val base = DiskUtil.buildValidFilename(chapterName, DiskUtil.MAX_FILE_NAME_BYTES - 16)
        return base + "_" + Hash.md5(chapterUrl).take(6)
    }

    private fun fileName(locator: TranslationLocator): String =
        chapterFileBase(locator.chapterName, locator.chapterUrl) + ".html"

    private fun tmpFileName(locator: TranslationLocator): String =
        chapterFileBase(locator.chapterName, locator.chapterUrl) + ".html.tmp"

    private fun findNovelDir(sourceName: String, novelTitle: String): UniFile? =
        translationsDir.findFile(sourceDirName(sourceName))?.findFile(novelDirName(novelTitle))

    private fun findLangDir(locator: TranslationLocator, targetLanguage: String): UniFile? =
        findNovelDir(locator.sourceName, locator.novelTitle)?.findFile(targetLanguage)

    private fun getOrCreateLangDir(locator: TranslationLocator, targetLanguage: String): UniFile? {
        val root = translationsDir
        val src = root.findChildDir(sourceDirName(locator.sourceName)) ?: return null
        val novel = src.findChildDir(novelDirName(locator.novelTitle)) ?: return null
        return novel.findChildDir(targetLanguage)
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
        val file = findLangDir(locator, targetLanguage)?.findFile(fileName(locator)) ?: return@withContext null
        val meta = parseUniFile(file) ?: return@withContext null
        toTranslatedChapter(meta, targetLanguage)
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
        val langDir = findNovelDir(sourceName, novelTitle)?.findFile(targetLanguage) ?: return@withContext emptySet()
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

            dir.findFile(name)?.delete()
            val file = dir.createFile(name)
                ?: throw IllegalStateException("Failed to create translation file: $name")
            file.openOutputStream().use { it.write(content) }

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
