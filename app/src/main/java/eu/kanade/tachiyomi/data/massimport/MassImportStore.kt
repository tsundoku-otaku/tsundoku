package eu.kanade.tachiyomi.data.massimport

import android.content.Context
import com.hippo.unifile.UniFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Per batch: `mi_<batchId>.txt` (URL list) + `mi_<batchId>.json` (metadata)
 * + `mi_<batchId>_errors.txt` (error log, `url<TAB>message` per line).
 */
object MassImportStore {

    private const val URLS_PREFIX = "mi_"
    private const val URLS_SUFFIX = ".txt"
    private const val META_SUFFIX = ".json"
    private const val ERRORS_INFIX = "_errors"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
    )

    private fun dir(): UniFile? = runCatching {
        Injekt.get<StorageManager>().getMassImportDirectory()
    }.getOrNull()

    private fun urlsName(batchId: String) = "$URLS_PREFIX$batchId$URLS_SUFFIX"
    private fun metaName(batchId: String) = "$URLS_PREFIX$batchId$META_SUFFIX"
    private fun errorsName(batchId: String) = "$URLS_PREFIX$batchId$ERRORS_INFIX$URLS_SUFFIX"

    private fun overwrite(dir: UniFile, name: String, content: String) {
        dir.findFile(name)?.delete()
        val file = dir.createFile(name) ?: return
        file.openOutputStream().bufferedWriter().use { it.write(content) }
    }

    /** Write the URL list once, when the batch is created. */
    fun saveUrls(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String, urls: List<String>) {
        if (batchId.isEmpty()) return
        val dir = dir() ?: run {
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
    fun saveUrlsStreaming(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String, urls: Sequence<String>): Int {
        if (batchId.isEmpty()) return 0
        val dir = dir() ?: run {
            logcat(LogPriority.WARN) {
                "MassImportStore: no mass_import directory; skipped streaming urls for $batchId"
            }
            return 0
        }
        var count = 0
        val name = urlsName(batchId)
        synchronized(ioLock) {
            runCatching {
                dir.findFile(name)?.delete()
                val file = dir.createFile(name) ?: return@runCatching
                file.openOutputStream().bufferedWriter().use { writer ->
                    for (url in urls) {
                        if (count > 0) writer.write("\n")
                        writer.write(url)
                        count++
                    }
                }
                logcat(LogPriority.DEBUG) { "MassImportStore: streamed $count urls -> $name" }
            }.onFailure {
                logcat(LogPriority.WARN, it) { "MassImportStore: failed to stream urls for $batchId" }
                runCatching { dir.findFile(name)?.delete() }
                count = -1
            }
        }
        return count
    }

    fun saveMeta(@Suppress("UNUSED_PARAMETER") context: Context, meta: PersistedMeta) {
        if (meta.id.isEmpty()) return
        val dir = dir() ?: return
        synchronized(ioLock) {
            runCatching { overwrite(dir, metaName(meta.id), json.encodeToString(PersistedMeta.serializer(), meta)) }
                .onFailure { logcat(LogPriority.WARN, it) { "MassImportStore: failed to save meta for ${meta.id}" } }
        }
    }

    fun loadUrls(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String): List<String> {
        val dir = dir() ?: return emptyList()
        return runCatching {
            dir.findFile(urlsName(batchId))?.openInputStream()?.bufferedReader()?.use { reader ->
                reader.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** First [limit] URLs only — for queue previews */
    fun loadUrlsPreview(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String, limit: Int): List<String> {
        if (batchId.isEmpty()) return emptyList()
        val dir = dir() ?: return emptyList()
        return runCatching {
            dir.findFile(urlsName(batchId))?.openInputStream()?.bufferedReader()?.use { reader ->
                reader.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(limit).toList()
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun loadAll(@Suppress("UNUSED_PARAMETER") context: Context): List<PersistedMeta> {
        val dir = dir() ?: return emptyList()
        return runCatching {
            dir.listFiles().orEmpty()
                .filter { it.name?.startsWith(URLS_PREFIX) == true && it.name?.endsWith(META_SUFFIX) == true }
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
            dir.findFile(metaName(batchId))?.openInputStream()?.bufferedReader()?.use { reader ->
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
            dir.findFile(urlsName(batchId))?.openInputStream()?.bufferedReader()
        }.getOrNull()
    }

    /** Truncate the error log; each worker run re-evaluates every URL, so old entries are stale. */
    fun clearErrors(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String) {
        if (batchId.isEmpty()) return
        val dir = dir() ?: return
        synchronized(ioLock) {
            runCatching { dir.findFile(errorsName(batchId))?.delete() }
        }
    }

    /** Append `url<TAB>message` lines to the error log. Messages are flattened to one line. */
    fun appendErrors(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String, entries: List<Pair<String, String>>) {
        if (batchId.isEmpty() || entries.isEmpty()) return
        val dir = dir() ?: return
        synchronized(ioLock) {
            runCatching {
                val name = errorsName(batchId)
                val file = dir.findFile(name) ?: dir.createFile(name) ?: return@runCatching
                file.openOutputStream(true).bufferedWriter().use { writer ->
                    for ((url, message) in entries) {
                        writer.write(url)
                        writer.write("\t")
                        writer.write(message.replace('\t', ' ').replace('\n', ' ').take(200))
                        writer.write("\n")
                    }
                }
            }.onFailure { logcat(LogPriority.WARN, it) { "MassImportStore: failed to append errors for $batchId" } }
        }
    }

    /** Load the full error log as (url, message) pairs, deduped by url keeping first message. */
    fun loadErrors(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String): List<Pair<String, String>> {
        if (batchId.isEmpty()) return emptyList()
        val dir = dir() ?: return emptyList()
        return runCatching {
            dir.findFile(errorsName(batchId))?.openInputStream()?.bufferedReader()?.use { reader ->
                val seen = LinkedHashMap<String, String>()
                reader.lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val sep = line.indexOf('\t')
                    val url = (if (sep >= 0) line.substring(0, sep) else line).trim()
                    if (url.isEmpty()) return@forEach
                    val msg = if (sep >= 0) line.substring(sep + 1) else ""
                    seen.putIfAbsent(url, msg)
                }
                seen.map { it.key to it.value }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun delete(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String) {
        if (batchId.isEmpty()) return
        val dir = dir() ?: return
        synchronized(ioLock) {
            runCatching {
                dir.findFile(urlsName(batchId))?.delete()
                dir.findFile(metaName(batchId))?.delete()
                dir.findFile(errorsName(batchId))?.delete()
            }
        }
    }
}
