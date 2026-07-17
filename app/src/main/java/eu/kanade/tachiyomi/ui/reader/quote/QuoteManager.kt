package eu.kanade.tachiyomi.ui.reader.quote

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Manager for handling quote storage and retrieval.
 *
 * Quotes are stored in a portable, human-readable layout that mirrors downloads:
 *   {quotes}/{source name}/{novel title}.json
 * so they survive re-imports and can be moved between installs.
 */
class QuoteManager(private val context: Context) {

    private val jsonFormat = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val storageManager: StorageManager by lazy {
        Injekt.get<StorageManager>()
    }

    private val quotesDir: UniFile? by lazy {
        storageManager.getQuotesDirectory()
    }

    // A fresh QuoteManager is handed out per Context access, so the write lock has to be
    // process-wide, not per-instance. Serialize load-modify-save mutators on a per-novel monitor
    // so concurrent add/remove/update/rename can't lose each other's writes.
    private fun <T> withNovelLock(sourceName: String, novelTitle: String, block: () -> T): T {
        val key = "${getSourceDirName(sourceName)}/${getNovelFileName(novelTitle)}"
        val lock = novelLocks.computeIfAbsent(key) { Any() }
        return synchronized(lock) { block() }
    }

    private fun getSourceDirName(sourceName: String): String =
        DiskUtil.buildValidFilename(sourceName).ifEmpty { "source" }

    private fun getNovelFileName(novelTitle: String): String =
        "${DiskUtil.buildValidFilename(novelTitle).ifEmpty { "novel" }}.json"

    private fun findSourceDir(sourceName: String): UniFile? {
        return quotesDir?.findFile(getSourceDirName(sourceName))
    }

    private fun getOrCreateSourceDir(sourceName: String): UniFile? {
        val name = getSourceDirName(sourceName)
        val dir = quotesDir ?: return null
        return dir.findFile(name)?.takeIf { it.isDirectory } ?: dir.createDirectory(name)
    }

    private fun getQuotesFile(sourceName: String, novelTitle: String): UniFile? {
        return findSourceDir(sourceName)?.findFile(getNovelFileName(novelTitle))
    }

    // If a save crashed after deleting the destination but before renaming its tmp in, the
    // just-written data survives only as "<name>.json.tmp". Promote it so no committed save is lost.
    private fun recoverPendingQuotes(sourceName: String, novelTitle: String): UniFile? {
        val dir = findSourceDir(sourceName) ?: return null
        val fileName = getNovelFileName(novelTitle)
        val tmp = dir.findFile("$fileName.tmp")?.takeIf { it.exists() } ?: return null
        // Only promote a scratch that parses cleanly; a crash mid-write can leave a truncated tmp
        // that must not be promoted and served as a complete quote set.
        val valid = runCatching {
            tmp.openInputStream().use { jsonFormat.decodeFromString<NovelQuotes>(String(it.readBytes())) }
        }.isSuccess
        if (!valid) {
            tmp.delete()
            return null
        }
        return if (tmp.renameTo(fileName)) dir.findFile(fileName)?.takeIf { it.exists() } else null
    }

    /**
     * Save quotes for a novel
     */
    fun saveQuotes(sourceName: String, novelTitle: String, quotes: List<Quote>): Boolean {
        try {
            val fileName = getNovelFileName(novelTitle)

            if (quotes.isEmpty()) {
                getQuotesFile(sourceName, novelTitle)?.takeIf { it.exists() }?.delete()
                deletePendingTmp(sourceName, novelTitle)
                return true
            }

            val json = jsonFormat.encodeToString(NovelQuotes(quotes))
            val dir = getOrCreateSourceDir(sourceName) ?: return false

            // Write to a temp file then swap it in, so a crash mid-write or a
            // concurrent save can't corrupt or duplicate the destination.
            val tmpName = "$fileName.tmp"
            dir.findFile(tmpName)?.takeIf { it.exists() }?.delete()
            val tmp = dir.createFile(tmpName) ?: return false
            tmp.openOutputStream().use { outputStream ->
                outputStream.write(json.toByteArray())
            }
            getQuotesFile(sourceName, novelTitle)?.takeIf { it.exists() }?.delete()
            if (!tmp.renameTo(fileName)) {
                // Leave the tmp in place; loadQuotes recovers it, so a failed rename
                // (or a crash before this point) doesn't lose the just-written data.
                logcat(LogPriority.ERROR) { "Failed to finalize quotes file for $sourceName/$novelTitle" }
                return false
            }
            logcat(LogPriority.DEBUG) { "Quotes saved for $sourceName/$novelTitle: ${quotes.size} quotes" }
            return true
        } catch (e: IOException) {
            logcat(LogPriority.ERROR) { "Failed to save quotes for $sourceName/$novelTitle: ${e.message}" }
            return false
        } catch (e: SerializationException) {
            logcat(LogPriority.ERROR) { "Failed to serialize quotes for $sourceName/$novelTitle: ${e.message}" }
            return false
        }
    }

    /**
     * Load quotes for a novel
     */
    fun loadQuotes(sourceName: String, novelTitle: String): List<Quote> {
        return try {
            val file = getQuotesFile(sourceName, novelTitle)?.takeIf { it.exists() }
                ?: recoverPendingQuotes(sourceName, novelTitle)
            if (file == null) {
                emptyList()
            } else {
                val json = file.openInputStream().use { inputStream ->
                    String(inputStream.readBytes())
                }
                jsonFormat.decodeFromString<NovelQuotes>(json).quotes
            }
        } catch (e: IOException) {
            logcat(LogPriority.ERROR) { "Failed to load quotes for $sourceName/$novelTitle: ${e.message}" }
            emptyList()
        } catch (e: SerializationException) {
            logcat(LogPriority.ERROR) { "Failed to deserialize quotes for $sourceName/$novelTitle: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Add a new quote for a novel
     */
    fun addQuote(sourceName: String, novelTitle: String, quote: Quote) = withNovelLock(sourceName, novelTitle) {
        val existingQuotes = loadQuotes(sourceName, novelTitle).toMutableList()
        existingQuotes.add(quote)
        saveQuotes(sourceName, novelTitle, existingQuotes)
    }

    /**
     * Remove a quote by ID
     */
    fun removeQuote(sourceName: String, novelTitle: String, quoteId: String) = withNovelLock(sourceName, novelTitle) {
        val existingQuotes = loadQuotes(sourceName, novelTitle).toMutableList()
        existingQuotes.removeAll { it.id == quoteId }
        saveQuotes(sourceName, novelTitle, existingQuotes)
    }

    /**
     * Update an existing quote
     */
    fun updateQuote(
        sourceName: String,
        novelTitle: String,
        updatedQuote: Quote,
    ) = withNovelLock(sourceName, novelTitle) {
        val existingQuotes = loadQuotes(sourceName, novelTitle).toMutableList()
        val index = existingQuotes.indexOfFirst { it.id == updatedQuote.id }
        if (index >= 0) {
            existingQuotes[index] = updatedQuote
            saveQuotes(sourceName, novelTitle, existingQuotes)
        }
    }

    /**
     * Get all quotes for a novel, preserving the stored order
     */
    fun getQuotes(sourceName: String, novelTitle: String): List<Quote> {
        return loadQuotes(sourceName, novelTitle)
    }

    /**
     * Clear all quotes for a novel
     */
    fun clearQuotes(sourceName: String, novelTitle: String) = withNovelLock(sourceName, novelTitle) {
        val file = getQuotesFile(sourceName, novelTitle)
        if (file?.exists() == true) {
            file.delete()
        }
        deletePendingTmp(sourceName, novelTitle)
        Unit
    }

    private fun deletePendingTmp(sourceName: String, novelTitle: String) {
        val dir = findSourceDir(sourceName) ?: return
        dir.findFile("${getNovelFileName(novelTitle)}.tmp")?.takeIf { it.exists() }?.delete()
    }

    /**
     * Get quote count for a novel
     */
    fun getQuoteCount(sourceName: String, novelTitle: String): Int {
        return loadQuotes(sourceName, novelTitle).size
    }

    /**
     * Reorder quotes for a novel
     */
    fun reorderQuotes(
        sourceName: String,
        novelTitle: String,
        quotes: List<Quote>,
    ) = withNovelLock(sourceName, novelTitle) {
        saveQuotes(sourceName, novelTitle, quotes)
        logcat(LogPriority.DEBUG) { "Quotes reordered for $sourceName/$novelTitle: ${quotes.size} quotes" }
    }

    /**
     * Move a novel's quotes file when its title changes (quotes are keyed by title on disk).
     */
    fun renameNovel(sourceName: String, oldTitle: String, newTitle: String): Boolean =
        withNovelLock(sourceName, oldTitle) {
            val dir = findSourceDir(sourceName) ?: return@withNovelLock true
            val oldName = getNovelFileName(oldTitle)
            val newName = getNovelFileName(newTitle)
            if (oldName == newName) return@withNovelLock true
            val file = dir.findFile(oldName)?.takeIf { it.exists() } ?: return@withNovelLock true
            // Don't clobber existing quotes at the destination; deny rather than overwrite.
            if (dir.findFile(newName)?.exists() == true) {
                logcat(LogPriority.WARN) { "Quotes '$newName' already exists; not overwriting on rename" }
                return@withNovelLock false
            }
            file.renameTo(newName).also {
                if (!it) logcat(LogPriority.ERROR) { "Failed to rename quotes $oldName -> $newName" }
            }
        }

    /**
     * Move a novel's quotes to a different source and/or title (e.g. on migration).
     * Denies the move if a quotes file already exists at the destination.
     */
    fun moveNovel(oldSourceName: String, oldTitle: String, newSourceName: String, newTitle: String): Boolean {
        if (getSourceDirName(oldSourceName) == getSourceDirName(newSourceName)) {
            return renameNovel(oldSourceName, oldTitle, newTitle)
        }
        return withNovelLock(oldSourceName, oldTitle) {
            val oldFile = getQuotesFile(oldSourceName, oldTitle)?.takeIf { it.exists() } ?: return@withNovelLock true
            val newDir = getOrCreateSourceDir(newSourceName) ?: return@withNovelLock false
            val newName = getNovelFileName(newTitle)
            if (newDir.findFile(newName)?.exists() == true) {
                logcat(LogPriority.WARN) { "Quotes already exist at destination for $newSourceName/$newTitle; not moving" }
                return@withNovelLock false
            }
            try {
                val dest = newDir.createFile(newName) ?: return@withNovelLock false
                oldFile.openInputStream().use { input -> dest.openOutputStream().use { input.copyTo(it) } }
                oldFile.delete()
                true
            } catch (e: IOException) {
                logcat(LogPriority.ERROR) { "Failed to move quotes for $oldTitle: ${e.message}" }
                // A partially written destination is worse than none; loadQuotes would parse a
                // truncated file. Drop it so the source remains the single source of truth.
                newDir.findFile(newName)?.takeIf { it.exists() }?.delete()
                false
            }
        }
    }

    companion object {
        private val novelLocks = ConcurrentHashMap<String, Any>()
    }
}

/**
 * Get QuoteManager instance
 */
val Context.quoteManager: QuoteManager
    get() = QuoteManager(this)
