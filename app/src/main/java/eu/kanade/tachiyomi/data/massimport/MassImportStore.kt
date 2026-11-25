@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.data.massimport

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.massimport.MassImportStore.loadErrors
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Per batch: `mi_<batchId>.txt` (URL list) + `mi_<batchId>.json` (metadata)
 * + `mi_<batchId>_errors.csv` (`url,message` per line) / `mi_<batchId>_skipped.txt` (one raw url per line).
 */
object MassImportStore {

    private const val URLS_PREFIX = "mi_"
    private const val URLS_SUFFIX = ".txt"
    private const val META_SUFFIX = ".json"
    private const val NEW_SUFFIX = ".new"
    private const val LOG_SUFFIX = ".csv"
    private const val ERRORS_INFIX = "_errors"
    private const val SKIPPED_INFIX = "_skipped"
    private const val NO_SOURCE_NAME = "no_source.txt"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val ioLock = Any()

    @Serializable
    data class PersistedMeta(
        val id: String,
        val status: String,
        val progress: Int,
        val total: Int,
        val added: Int,
        val skipped: Int,
        val errored: Int,
        val erroredUrls: List<String> = emptyList(),
        val errorMessages: Map<String, String> = emptyMap(),
        val categoryId: Long = 0L,
        val addToLibrary: Boolean = true,
        val fetchDetails: Boolean = true,
        val fetchChapters: Boolean = false,
        val preferredSourceId: Long? = null,
        val excludedHosts: List<String> = emptyList(),
        val interrupted: Boolean = false,
        // Batches staged before this flag stored raw lines that resume has to tokenize again.
        val tokenized: Boolean = false,
        // Set once an old batch's progress was converted to URL tokens, before its list is rewritten.
        val progressInTokens: Boolean = false,
    )

    private fun dir(): UniFile? = runCatching {
        Injekt.get<StorageManager>().getMassImportDirectory()
    }.getOrNull()

    private fun urlsName(batchId: String) = "$URLS_PREFIX$batchId$URLS_SUFFIX"
    private fun urlsBackupName(batchId: String) = "${urlsName(batchId)}.bak"

    // A crash while a rewrite swaps the list in leaves only the moved-aside copy.
    private fun urlsFile(dir: UniFile, batchId: String): UniFile? =
        dir.findFile(urlsName(batchId)) ?: dir.findFile(urlsBackupName(batchId))
    private fun metaName(batchId: String) = "$URLS_PREFIX$batchId$META_SUFFIX"
    private fun errorsName(batchId: String) = "$URLS_PREFIX$batchId$ERRORS_INFIX$LOG_SUFFIX"

    // Skipped is single-column (url only), so it's a plain `.txt` url list, not CSV.
    private fun skippedName(batchId: String) = "$URLS_PREFIX$batchId$SKIPPED_INFIX$URLS_SUFFIX"

    // Pre-CSV error logs used `.txt` with `url<TAB>message` lines; still read/cleaned for old batches.
    private fun legacyErrorsName(batchId: String) = "$URLS_PREFIX$batchId$ERRORS_INFIX$URLS_SUFFIX"

    // Older builds wrote skipped urls to a `.csv`; still read/cleaned for old batches.
    private fun legacySkippedName(batchId: String) = "$URLS_PREFIX$batchId$SKIPPED_INFIX$LOG_SUFFIX"

    // Written beside the old file and swapped in, so the old one stays readable until the new one is
    // complete; readers fall back to the new file if a crash lands between the delete and the rename.
    private fun overwrite(dir: UniFile, name: String, content: String) {
        val newName = "$name$NEW_SUFFIX"
        dir.findFile(newName)?.delete()
        val file = dir.createFile(newName) ?: error("could not create $newName")
        file.openOutputStream().bufferedWriter().use { it.write(content) }
        dir.findFile(name)?.delete()
        check(file.renameTo(name)) { "could not rename $newName" }
    }

    private fun metaFile(dir: UniFile, batchId: String): UniFile? =
        dir.findFile(metaName(batchId)) ?: dir.findFile(metaName(batchId) + NEW_SUFFIX)

    /** Write the URL list once, when the batch is created. */
    fun saveUrls(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String, urls: List<String>) {
        if (batchId.isEmpty()) return
        val dir = dir() ?: run {
            @Suppress("ktlint:standard:max-line-length")
            logcat(LogPriority.WARN) {
                "MassImportStore: no mass_import directory (base storage not configured?); skipped saving urls for $batchId"
            }
            return
        }
        synchronized(ioLock) {
            runCatching {
                // Stream each URL instead of joinToString: a single joined String would
                // duplicate the whole (possibly multi-MB) list in memory before writing.
                val name = urlsName(batchId)
                dir.findFile(name)?.delete()
                val file = dir.createFile(name) ?: return@runCatching
                file.openOutputStream().bufferedWriter().use { writer ->
                    var first = true
                    for (url in urls) {
                        if (!first) writer.write("\n")
                        writer.write(url)
                        first = false
                    }
                }
                logcat(LogPriority.DEBUG) { "MassImportStore: saved ${urls.size} urls -> $name" }
            }.onFailure { logcat(LogPriority.WARN, it) { "MassImportStore: failed to save urls for $batchId" } }
        }
    }

    /**
     * Stream a (possibly lazy, single-use) sequence of URLs to disk without materializing it as a
     * List or joined String. Returns the number of URLs written.
     */
    @Suppress("ktlint:standard:max-line-length")
    fun saveUrlsStreaming(
        @Suppress("UNUSED_PARAMETER") context: Context,
        batchId: String,
        urls: Sequence<String>,
    ): Int {
        if (batchId.isEmpty()) return 0
        val dir = dir() ?: run {
            logcat(LogPriority.WARN) {
                "MassImportStore: no mass_import directory; skipped streaming urls for $batchId"
            }
            return 0
        }
        var count = 0
        val name = urlsName(batchId)
        // Written aside and swapped in, so a failed rewrite keeps the existing list.
        val tmpName = "$name.tmp"
        val backupName = urlsBackupName(batchId)
        synchronized(ioLock) {
            runCatching {
                dir.findFile(tmpName)?.delete()
                val file = dir.createFile(tmpName) ?: error("could not create $tmpName")
                file.openOutputStream().bufferedWriter().use { writer ->
                    for (url in urls) {
                        if (count > 0) writer.write("\n")
                        writer.write(url)
                        count++
                    }
                }
                // The old list is moved aside, not deleted, until the new one is in place.
                val previous = dir.findFile(name)
                if (previous != null) {
                    dir.findFile(backupName)?.delete()
                    check(previous.renameTo(backupName)) { "could not move $name aside" }
                }
                if (!file.renameTo(name)) {
                    if (previous != null) previous.renameTo(name)
                    error("could not rename $tmpName")
                }
                dir.findFile(backupName)?.delete()
                logcat(LogPriority.DEBUG) { "MassImportStore: streamed $count urls -> $name" }
            }.onFailure {
                logcat(LogPriority.WARN, it) { "MassImportStore: failed to stream urls for $batchId" }
                runCatching { dir.findFile(tmpName)?.delete() }
                count = -1
            }
        }
        return count
    }

    /** False when the meta couldn't be written. */
    fun saveMeta(@Suppress("UNUSED_PARAMETER") context: Context, meta: PersistedMeta): Boolean {
        if (meta.id.isEmpty()) return false
        val dir = dir() ?: return false
        return synchronized(ioLock) {
            runCatching { overwrite(dir, metaName(meta.id), json.encodeToString(PersistedMeta.serializer(), meta)) }
                .onFailure { logcat(LogPriority.WARN, it) { "MassImportStore: failed to save meta for ${meta.id}" } }
                .isSuccess
        }
    }

    fun loadUrls(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String): List<String> {
        val dir = dir() ?: return emptyList()
        return runCatching {
            urlsFile(dir, batchId)?.openInputStream()?.bufferedReader()?.use { reader ->
                reader.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** First [limit] URLs only — for queue previews */
    fun loadUrlsPreview(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String, limit: Int): List<String> {
        if (batchId.isEmpty()) return emptyList()
        val dir = dir() ?: return emptyList()
        return runCatching {
            urlsFile(dir, batchId)?.openInputStream()?.bufferedReader()?.use { reader ->
                reader.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(limit).toList()
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun loadAll(@Suppress("UNUSED_PARAMETER") context: Context): List<PersistedMeta> {
        val dir = dir() ?: return emptyList()
        return runCatching {
            val files = dir.listFiles().orEmpty().filter { it.name?.startsWith(URLS_PREFIX) == true }
            val names = files.mapNotNull { it.name }.toSet()
            files
                .filter { file ->
                    val name = file.name.orEmpty()
                    name.endsWith(META_SUFFIX) ||
                        (name.endsWith(META_SUFFIX + NEW_SUFFIX) && name.removeSuffix(NEW_SUFFIX) !in names)
                }
                .mapNotNull { file ->
                    runCatching {
                        file.openInputStream().bufferedReader().use { reader ->
                            json.decodeFromString(PersistedMeta.serializer(), reader.readText())
                        }
                    }.getOrNull()
                }
        }.getOrDefault(emptyList())
    }

    fun loadMeta(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String): PersistedMeta? {
        if (batchId.isEmpty()) return null
        val dir = dir() ?: return null
        return runCatching {
            metaFile(dir, batchId)?.openInputStream()?.bufferedReader()?.use { reader ->
                json.decodeFromString(PersistedMeta.serializer(), reader.readText())
            }
        }.getOrNull()
    }

    /**
     * Streaming access to the persisted URL list. Returns null when the batch has no list on
     * disk. The caller owns the reader.
     */
    fun openUrlsReader(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String): java.io.BufferedReader? {
        if (batchId.isEmpty()) return null
        val dir = dir() ?: return null
        return runCatching {
            urlsFile(dir, batchId)?.openInputStream()?.bufferedReader()
        }.getOrNull()
    }

    /** Truncate a log; each worker run re-evaluates every URL, so old entries are stale. */
    fun clearErrors(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String) =
        clearLog(batchId, ::errorsName, ::legacyErrorsName)
    fun clearSkipped(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String) =
        clearLog(batchId, ::skippedName, ::legacySkippedName)

    /** Append CSV lines: `url,message` for errors, bare url for skipped. Messages flattened to one line. */
    @Suppress("ktlint:standard:max-line-length")
    fun appendErrors(
        @Suppress("UNUSED_PARAMETER") context: Context,
        batchId: String,
        entries: List<Pair<String, String>>,
    ) =
        appendLog(batchId, ::errorsName, entries, withMessage = true)

    /**
     * Append urls no installed source can handle. Cross-batch on purpose: the list answers "which
     * sources am I missing", and the per-batch skipped log keeps no reason.
     */
    fun appendNoSource(urls: List<String>) {
        if (urls.isEmpty()) return
        val dir = dir() ?: return
        synchronized(ioLock) {
            runCatching {
                val file = dir.findFile(NO_SOURCE_NAME) ?: dir.createFile(NO_SOURCE_NAME) ?: return@runCatching
                file.openOutputStream(true).bufferedWriter().use { writer ->
                    for (url in urls) {
                        writer.write(url.trim())
                        writer.write("\n")
                    }
                }
            }.onFailure { logcat(LogPriority.WARN, it) { "MassImportStore: failed to append the no-source log" } }
        }
    }

    @Suppress("ktlint:standard:max-line-length")
    fun appendSkipped(
        @Suppress("UNUSED_PARAMETER") context: Context,
        batchId: String,
        entries: List<Pair<String, String>>,
    ) =
        appendLog(batchId, ::skippedName, entries, withMessage = false)

    /** Load a full log as (url, message) pairs, deduped by url keeping first message. */
    fun loadErrors(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String) =
        loadLog(batchId, ::errorsName, ::legacyErrorsName)
    fun loadSkipped(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String) =
        loadLog(batchId, ::skippedName, ::legacySkippedName, plainUrls = true)

    private fun clearLog(batchId: String, vararg names: (String) -> String) {
        if (batchId.isEmpty()) return
        val dir = dir() ?: return
        synchronized(ioLock) {
            runCatching { names.forEach { dir.findFile(it(batchId))?.delete() } }
        }
    }

    private fun csvField(value: String): String =
        if (value.contains(',') || value.contains('"')) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun appendLog(
        batchId: String,
        nameFor: (String) -> String,
        entries: List<Pair<String, String>>,
        withMessage: Boolean,
    ) {
        if (batchId.isEmpty() || entries.isEmpty()) return
        val dir = dir() ?: return
        synchronized(ioLock) {
            runCatching {
                val name = nameFor(batchId)
                val file = dir.findFile(name) ?: dir.createFile(name) ?: return@runCatching
                file.openOutputStream(true).bufferedWriter().use { writer ->
                    for ((url, message) in entries) {
                        if (withMessage) {
                            // CSV: `url,message`
                            writer.write(csvField(url.trim()))
                            writer.write(",")
                            writer.write(csvField(message.replace('\t', ' ').replace('\n', ' ').take(200)))
                        } else {
                            // Single-column `.txt`: raw url, directly re-importable.
                            writer.write(url.trim())
                        }
                        writer.write("\n")
                    }
                }
            }.onFailure { logcat(LogPriority.WARN, it) { "MassImportStore: failed to append log for $batchId" } }
        }
    }

    /** First CSV field + rest of line (unquoted). Tab lines are legacy `url<TAB>message`. */
    private fun parseLogLine(line: String): Pair<String, String> {
        val tab = line.indexOf('\t')
        if (tab >= 0) return line.substring(0, tab).trim() to line.substring(tab + 1)
        if (!line.startsWith("\"")) {
            val sep = line.indexOf(',')
            return if (sep >= 0) {
                line.substring(0, sep).trim() to unquoteCsv(line.substring(sep + 1))
            } else {
                line.trim() to ""
            }
        }
        val url = StringBuilder()
        var i = 1
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (i + 1 < line.length && line[i + 1] == '"') {
                    url.append('"')
                    i += 2
                } else {
                    i++
                    break
                }
            } else {
                url.append(c)
                i++
            }
        }
        val rest = if (i < line.length && line[i] == ',') unquoteCsv(line.substring(i + 1)) else ""
        return url.toString().trim() to rest
    }

    private fun unquoteCsv(value: String): String =
        if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
            value.substring(1, value.length - 1).replace("\"\"", "\"")
        } else {
            value
        }

    @Suppress("ktlint:standard:max-line-length")
    private fun loadLog(
        batchId: String,
        nameFor: (String) -> String,
        legacyNameFor: (String) -> String,
        plainUrls: Boolean = false,
    ): List<Pair<String, String>> {
        if (batchId.isEmpty()) return emptyList()
        val dir = dir() ?: return emptyList()
        return runCatching {
            val current = dir.findFile(nameFor(batchId))
            val file = current ?: dir.findFile(legacyNameFor(batchId))
            // The current skipped list is one whole URL per line; URLs can hold commas.
            val wholeLine = plainUrls && current != null
            file?.openInputStream()?.bufferedReader()?.use { reader ->
                val seen = LinkedHashMap<String, String>()
                reader.lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val (url, msg) = if (wholeLine) line.trim() to "" else parseLogLine(line)
                    if (url.isEmpty()) return@forEach
                    seen.putIfAbsent(url, msg)
                }
                seen.map { it.key to it.value }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * Stream (url, message) error entries in file order. No dedup, O(1) memory, for callers that
     * pipe straight to disk (result file, export, retry) where the on-disk log can be huge and a
     * full [loadErrors] materialization would OOM.
     */
    fun forEachError(
        @Suppress("UNUSED_PARAMETER") context: Context,
        batchId: String,
        action: (String, String) -> Unit,
    ) {
        if (batchId.isEmpty()) return
        val dir = dir() ?: return
        runCatching {
            val file = dir.findFile(errorsName(batchId)) ?: dir.findFile(legacyErrorsName(batchId)) ?: return
            file.openInputStream().bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val (url, msg) = parseLogLine(line)
                    if (url.isNotEmpty()) action(url, msg)
                }
            }
        }
    }

    fun delete(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String) {
        if (batchId.isEmpty()) return
        val dir = dir() ?: return
        synchronized(ioLock) {
            runCatching {
                dir.findFile(urlsName(batchId))?.delete()
                dir.findFile(urlsBackupName(batchId))?.delete()
                dir.findFile(metaName(batchId))?.delete()
                dir.findFile(metaName(batchId) + NEW_SUFFIX)?.delete()
                dir.findFile(errorsName(batchId))?.delete()
                dir.findFile(skippedName(batchId))?.delete()
                dir.findFile(legacyErrorsName(batchId))?.delete()
                dir.findFile(legacySkippedName(batchId))?.delete()
            }
        }
    }
}
