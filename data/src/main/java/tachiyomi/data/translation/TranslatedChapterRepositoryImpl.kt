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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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

        // Process-wide per-novel monitors, mirroring QuoteManager's withNovelLock: serializes
        // writes/relocations for the same novel so a rename/move can't race an in-flight upsert,
        // and closes the read-side recovery race (see getTranslatedChapter).
        val novelLocks = ConcurrentHashMap<String, Any>()
    }

    private fun novelLockKey(sourceName: String, novelTitle: String): String =
        "${sourceDirName(sourceName)}/${novelDirName(novelTitle)}"

    private fun <T> withNovelLock(sourceName: String, novelTitle: String, block: () -> T): T {
        val lock = novelLocks.computeIfAbsent(novelLockKey(sourceName, novelTitle)) { Any() }
        return synchronized(lock) { block() }
    }

    // Locks every distinct key in sorted order so two concurrent calls that reference the same
    // pair of novels (e.g. a move and its reverse) always acquire locks in the same order.
    private fun <T> withNovelLocks(keys: List<Pair<String, String>>, block: () -> T): T {
        val lockKeys = keys.map { (s, t) -> novelLockKey(s, t) }.distinct().sorted()
        fun lockNext(remaining: List<String>): T {
            if (remaining.isEmpty()) return block()
            val lock = novelLocks.computeIfAbsent(remaining.first()) { Any() }
            return synchronized(lock) { lockNext(remaining.drop(1)) }
        }
        return lockNext(lockKeys)
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

    // Path helpers

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

    // Metadata helpers

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

    // Read operations

    override suspend fun getTranslatedChapter(
        locator: TranslationLocator,
        targetLanguage: String,
    ): TranslatedChapter? = withContext(Dispatchers.IO) {
        val langDir = findLangDir(locator, targetLanguage) ?: return@withContext null
        val name = fileName(locator)
        // Resolving (and possibly promoting a pending ".saving" scratch) under the same lock
        // upsertTranslation uses for its own delete+rename finalize closes the race where a
        // concurrent read could win the promotion and make the writer's own rename fail.
        val file = withNovelLock(locator.sourceName, locator.novelTitle) {
            langDir.findFile(name)?.takeIf { it.exists() }
                ?: recoverPendingTranslation(langDir, name)
        } ?: return@withContext null
        val meta = parseUniFile(file) ?: return@withContext null
        toTranslatedChapter(meta, targetLanguage)
    }

    // If upsertTranslation crashed after deleting the destination but before renaming its scratch
    // in, the data survives only as "<name>.saving". Promote it so no committed write is lost, but
    // only when it holds content: a crash right after createFile leaves an empty/header-only scratch
    // that must not be promoted and served as a complete translation.
    private fun recoverPendingTranslation(langDir: UniFile, name: String): UniFile? {
        val scratch = langDir.findFile("$name.saving")?.takeIf { it.exists() } ?: return null
        val meta = parseUniFile(scratch)
        if (meta == null || meta.content.isBlank()) {
            scratch.delete()
            return null
        }
        return if (scratch.renameTo(name)) langDir.findFile(name)?.takeIf { it.exists() } else null
    }

    // A ".saving" scratch only counts as a real translation once it holds content: a crash right
    // after createFile (before the write) leaves an empty/header-only scratch that recovery discards,
    // so the badge/skip-translated predicates must not treat it as translated either. Read-only: it
    // never promotes or deletes, unlike recoverPendingTranslation.
    private fun savingHasContent(scratch: UniFile?): Boolean {
        val file = scratch?.takeIf { it.exists() } ?: return false
        val meta = parseUniFile(file)
        return meta != null && meta.content.isNotBlank()
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

    override suspend fun getAllTranslationsForNovel(
        sourceName: String,
        novelTitle: String,
        chapters: Collection<ChapterRef>,
    ): Map<Long, List<TranslatedChapter>> = withContext(Dispatchers.IO) {
        if (chapters.isEmpty()) return@withContext emptyMap()
        val novelDir = findNovelDir(sourceName, novelTitle) ?: return@withContext emptyMap()
        // List each language dir once up front (name -> file) so the per-chapter lookup below is an
        // in-memory map hit rather than a SAF findFile call.
        val langFiles = novelDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { langDir ->
                val lang = langDir.name ?: return@mapNotNull null
                val files = langDir.listFiles()?.mapNotNull { f -> f.name?.let { it to f } }?.toMap().orEmpty()
                lang to files
            }
            .orEmpty()
        if (langFiles.isEmpty()) return@withContext emptyMap()
        val result = HashMap<Long, List<TranslatedChapter>>()
        chapters.forEach { ref ->
            val target = "${chapterFileBase(ref.name, ref.url)}.html"
            val translations = langFiles.mapNotNull { (lang, files) ->
                val file = files[target] ?: return@mapNotNull null
                val meta = parseUniFile(file) ?: return@mapNotNull null
                toTranslatedChapter(meta, lang)
            }
            if (translations.isNotEmpty()) result[ref.id] = translations
        }
        result
    }

    override suspend fun hasTranslation(locator: TranslationLocator, targetLanguage: String): Boolean =
        withContext(Dispatchers.IO) {
            val langDir = findLangDir(locator, targetLanguage) ?: return@withContext false
            val name = fileName(locator)
            // A recoverable ".saving" scratch counts as translated so a crash before the final rename
            // doesn't make the badge/skip-translated logic re-translate over the recovered write; an
            // empty scratch (crash before the write) does not, matching recoverPendingTranslation.
            langDir.findFile(name) != null || savingHasContent(langDir.findFile("$name.saving"))
        }

    override suspend fun filterTranslatedChapters(
        sourceName: String,
        novelTitle: String,
        targetLanguage: String,
        chapters: Collection<ChapterRef>,
    ): Set<Long> = withContext(Dispatchers.IO) {
        if (chapters.isEmpty()) return@withContext emptySet()
        val langDir =
            findNovelDir(sourceName, novelTitle)?.findFile(langDirName(targetLanguage)) ?: return@withContext emptySet()
        val files = langDir.listFiles() ?: return@withContext emptySet()
        // Count a recoverable ".saving" scratch as its committed ".html" so a crashed-but-recoverable
        // write isn't reported as untranslated, but only when it holds content (an empty scratch is
        // discarded on the read path, so reporting it translated would wrongly skip re-translation).
        val present = HashSet<String>()
        files.forEach { file ->
            val n = file.name ?: return@forEach
            when {
                n.endsWith(".html.saving") -> if (savingHasContent(file)) present.add(n.removeSuffix(".saving"))
                n.endsWith(".html") -> present.add(n)
            }
        }
        if (present.isEmpty()) return@withContext emptySet()
        chapters.asSequence()
            .filter { (chapterFileBase(it.name, it.url) + ".html") in present }
            .map { it.id }
            .toSet()
    }

    // Write operations

    override suspend fun upsertTranslation(locator: TranslationLocator, translatedChapter: TranslatedChapter) =
        withContext(Dispatchers.IO) {
            withNovelLock(locator.sourceName, locator.novelTitle) {
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
        }

    override suspend fun upsertTmpTranslation(locator: TranslationLocator, translatedChapter: TranslatedChapter) =
        withContext(Dispatchers.IO) {
            withNovelLock(locator.sourceName, locator.novelTitle) {
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
        }

    override suspend fun getTmpTranslation(
        locator: TranslationLocator,
        targetLanguage: String,
    ): TranslatedChapter? = withContext(Dispatchers.IO) {
        val file = findLangDir(locator, targetLanguage)?.findFile(tmpFileName(locator)) ?: return@withContext null
        val meta = parseUniFile(file) ?: return@withContext null
        toTranslatedChapter(meta, targetLanguage)
    }

    // Delete operations

    override suspend fun deleteTranslation(locator: TranslationLocator, targetLanguage: String) =
        withContext(Dispatchers.IO) {
            withNovelLock(locator.sourceName, locator.novelTitle) {
                findLangDir(locator, targetLanguage)?.let { dir ->
                    val name = fileName(locator)
                    dir.findFile(name)?.delete()
                    dir.findFile("$name.saving")?.delete()
                    dir.findFile(tmpFileName(locator))?.delete()
                }
            }
            Unit
        }

    override suspend fun deleteAllForChapter(locator: TranslationLocator) = withContext(Dispatchers.IO) {
        withNovelLock(locator.sourceName, locator.novelTitle) {
            val novelDir = findNovelDir(locator.sourceName, locator.novelTitle) ?: return@withNovelLock
            val target = fileName(locator)
            val tmp = tmpFileName(locator)
            novelDir.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { langDir ->
                    langDir.findFile(target)?.delete()
                    langDir.findFile("$target.saving")?.delete()
                    langDir.findFile(tmp)?.delete()
                }
        }
        Unit
    }

    override suspend fun deleteAllForChapters(
        sourceName: String,
        novelTitle: String,
        chapters: Collection<ChapterRef>,
    ) = withContext(Dispatchers.IO) {
        if (chapters.isEmpty()) return@withContext
        withNovelLock(sourceName, novelTitle) {
            val novelDir = findNovelDir(sourceName, novelTitle) ?: return@withNovelLock
            val wanted = chapters.flatMapTo(HashSet()) {
                val base = chapterFileBase(it.name, it.url)
                listOf("$base.html", "$base.html.saving", "$base.html.tmp")
            }
            novelDir.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { langDir ->
                    langDir.listFiles()
                        ?.filter { it.name in wanted }
                        ?.forEach { it.delete() }
                }
        }
        Unit
    }

    override suspend fun deleteAllForManga(sourceName: String, novelTitle: String) = withContext(Dispatchers.IO) {
        withNovelLock(sourceName, novelTitle) {
            findNovelDir(sourceName, novelTitle)?.let { deleteRecursive(it) }
        }
        Unit
    }

    override suspend fun renameChapter(
        sourceName: String,
        novelTitle: String,
        oldChapterName: String,
        newChapterName: String,
        chapterUrl: String,
    ) = withContext(Dispatchers.IO) {
        withNovelLock(sourceName, novelTitle) {
            val oldBase = chapterFileBase(oldChapterName, chapterUrl)
            val newBase = chapterFileBase(newChapterName, chapterUrl)
            if (oldBase == newBase) return@withNovelLock
            val novelDir = findNovelDir(sourceName, novelTitle) ?: return@withNovelLock
            novelDir.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { langDir ->
                    listOf(".html", ".html.saving", ".html.tmp").forEach { suffix ->
                        val oldFile = langDir.findFile("$oldBase$suffix") ?: return@forEach
                        val newName = "$newBase$suffix"
                        if (langDir.findFile(newName) == null) {
                            if (!oldFile.renameTo(newName)) {
                                logcat(LogPriority.ERROR) {
                                    "Failed to rename translation file '$oldBase$suffix' -> '$newName'"
                                }
                            }
                        }
                    }
                }
        }
        Unit
    }

    override suspend fun renameNovel(sourceName: String, oldTitle: String, newTitle: String) =
        withContext(Dispatchers.IO) {
            // Lock both the old and new keys so this can't race a concurrent upsert/delete
            // targeting either the source or destination novel.
            withNovelLocks(listOf(sourceName to oldTitle, sourceName to newTitle)) {
                val oldName = novelDirName(oldTitle)
                val newName = novelDirName(newTitle)
                if (oldName == newName) return@withNovelLocks
                val srcDir = translationsDir.findFile(sourceDirName(sourceName)) ?: return@withNovelLocks
                val oldDir = srcDir.findFile(oldName)?.takeIf { it.isDirectory && it.exists() }
                    ?: return@withNovelLocks
                val existing = srcDir.findFile(newName)
                if (existing != null) {
                    // Deny if a non-empty dir already holds translations; only reclaim an empty one.
                    if (!existing.isDirectory || !existing.listFiles().isNullOrEmpty()) {
                        logcat(LogPriority.WARN) {
                            "Translation dir '$newName' exists and is not empty; not renaming '$oldName'"
                        }
                        return@withNovelLocks
                    }
                    existing.delete()
                }
                if (!oldDir.renameTo(newName)) {
                    logcat(LogPriority.ERROR) { "Failed to rename translation dir '$oldName' -> '$newName'" }
                }
            }
            Unit
        }

    override suspend fun moveNovel(
        oldSourceName: String,
        oldTitle: String,
        newSourceName: String,
        newTitle: String,
    ) = withContext(Dispatchers.IO) {
        withNovelLocks(listOf(oldSourceName to oldTitle, newSourceName to newTitle)) {
            val oldSrcName = sourceDirName(oldSourceName)
            val newSrcName = sourceDirName(newSourceName)
            val oldNovel = novelDirName(oldTitle)
            val newNovel = novelDirName(newTitle)
            if (oldSrcName == newSrcName && oldNovel == newNovel) return@withNovelLocks

            val oldDir = translationsDir.findFile(oldSrcName)?.findFile(oldNovel)
                ?.takeIf { it.isDirectory && it.exists() } ?: return@withNovelLocks
            val newSrcDir = translationsDir.findChildDir(newSrcName) ?: return@withNovelLocks

            val existing = newSrcDir.findFile(newNovel)
            if (existing != null) {
                if (!existing.isDirectory || !existing.listFiles().isNullOrEmpty()) {
                    logcat(LogPriority.WARN) {
                        "Translation dir '$newSrcName/$newNovel' exists and is not empty; not moving"
                    }
                    return@withNovelLocks
                }
                existing.delete()
            }

            if (oldSrcName == newSrcName) {
                // Same parent: a rename suffices (no cross-directory copy needed).
                if (!oldDir.renameTo(newNovel)) {
                    logcat(LogPriority.ERROR) { "Failed to rename translation dir '$oldNovel' -> '$newNovel'" }
                }
                return@withNovelLocks
            }

            val destDir = newSrcDir.createDirectory(newNovel) ?: return@withNovelLocks
            if (copyContentsRecursive(oldDir, destDir)) {
                deleteRecursive(oldDir)
            } else {
                // Keep the source (it still holds every translation) but drop the half-copied dest,
                // otherwise the non-empty-existing guard above would block every future retry.
                logcat(LogPriority.ERROR) {
                    "Copy incomplete moving '$oldSrcName/$oldNovel' -> '$newSrcName/$newNovel'; reverting"
                }
                deleteRecursive(destDir)
            }
        }
        Unit
    }

    // SAF renameTo only renames within the same parent, so a cross-source move is copy-then-delete.
    // Returns true only if every child copied; a false result must prevent deleting the source.
    private fun copyContentsRecursive(src: UniFile, dest: UniFile): Boolean {
        var ok = true
        src.listFiles()?.forEach { child ->
            val name = child.name
            if (name == null) {
                ok = false
                return@forEach
            }
            if (child.isDirectory) {
                val destChild = dest.findChildDir(name)
                if (destChild == null || !copyContentsRecursive(child, destChild)) ok = false
            } else {
                val out = dest.createFile(name)
                if (out == null) {
                    ok = false
                } else {
                    try {
                        child.openInputStream().use { input -> out.openOutputStream().use { input.copyTo(it) } }
                    } catch (e: IOException) {
                        logcat(LogPriority.ERROR) { "Failed to copy translation '$name': ${e.message}" }
                        ok = false
                    }
                }
            }
        }
        return ok
    }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        // Delete each novel dir under its own lock so this can't race an in-flight upsert/rename for
        // that novel. The on-disk dir names equal the sanitized lock-key components, so the key
        // built here matches the one writers use (see novelLockKey).
        translationsDir.listFiles()?.forEach { srcDir ->
            val srcName = srcDir.name
            if (srcName == null || !srcDir.isDirectory) {
                srcDir.delete()
                return@forEach
            }
            srcDir.listFiles()?.forEach { novelDir ->
                val novelName = novelDir.name
                if (novelName == null || !novelDir.isDirectory) {
                    novelDir.delete()
                    return@forEach
                }
                val lock = novelLocks.computeIfAbsent("$srcName/$novelName") { Any() }
                synchronized(lock) { deleteRecursive(novelDir) }
            }
            srcDir.delete()
        }
        Unit
    }

    override suspend fun clearTmpFiles(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        // Same per-novel locking as deleteAll: a tmp sweep must not race the writer creating/renaming
        // scratch files for the novel it is scanning.
        translationsDir.listFiles()?.forEach { srcDir ->
            val srcName = srcDir.name ?: return@forEach
            if (!srcDir.isDirectory) return@forEach
            srcDir.listFiles()?.forEach { novelDir ->
                val novelName = novelDir.name ?: return@forEach
                if (!novelDir.isDirectory) return@forEach
                val lock = novelLocks.computeIfAbsent("$srcName/$novelName") { Any() }
                synchronized(lock) {
                    allFilesRecursive(novelDir)
                        .filter { it.name?.endsWith(".html.tmp") == true }
                        .forEach {
                            freed += it.length()
                            it.delete()
                        }
                }
            }
        }
        freed
    }
}
