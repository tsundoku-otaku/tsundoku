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
 * Per batch: `mi_<batchId>.txt` (URL list) + `mi_<batchId>.json` (metadata).
 */
object MassImportStore {

    private const val URLS_PREFIX = "mi_"
    private const val URLS_SUFFIX = ".txt"
    private const val META_SUFFIX = ".json"

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
                overwrite(dir, urlsName(batchId), urls.joinToString("\n"))
                logcat(LogPriority.DEBUG) { "MassImportStore: saved ${urls.size} urls -> ${urlsName(batchId)}" }
            }.onFailure { logcat(LogPriority.WARN, it) { "MassImportStore: failed to save urls for $batchId" } }
        }
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

    fun delete(@Suppress("UNUSED_PARAMETER") context: Context, batchId: String) {
        if (batchId.isEmpty()) return
        val dir = dir() ?: return
        synchronized(ioLock) {
            runCatching {
                dir.findFile(urlsName(batchId))?.delete()
                dir.findFile(metaName(batchId))?.delete()
            }
        }
    }
}
