package eu.kanade.tachiyomi.data.cache

import android.content.Context
import eu.kanade.tachiyomi.ui.library.ExtensionInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException

/**
 * Cache for library settings data (extensions, tags) to avoid expensive DB queries
 */
class LibrarySettingsCache(private val context: Context) {

    private val cacheDir by lazy {
        File(context.filesDir, "library_cache").apply { mkdirs() }
    }

    private val tagsFile by lazy { File(cacheDir, "tags.json") }

    private val json = Json { ignoreUnknownKeys = true }

    // The list is saved already filtered by library type (manga/novel/all), so each type needs
    // its own file: a shared one served the manga tab's list to the novel tab until a manual
    // refresh, and vice versa. The pre-split "extensions.json" is removed on first save.
    private fun extensionsFile(typeKey: String) = File(cacheDir, "extensions_$typeKey.json")

    fun saveExtensions(typeKey: String, extensions: List<ExtensionInfo>) {
        try {
            val data = extensions.map { ExtensionData(it.sourceId, it.sourceName, it.isStub, it.isNovel, it.isCustom) }
            File(cacheDir, "extensions.json").delete()
            extensionsFile(typeKey).writeText(json.encodeToString(data))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to save extensions cache" }
        }
    }

    fun loadExtensions(typeKey: String): List<ExtensionInfo>? {
        return try {
            val file = extensionsFile(typeKey)
            if (!file.exists()) return null
            val text = file.readText()
            val data = json.decodeFromString<List<ExtensionData>>(text)
            data.map { ExtensionInfo(it.id, it.name, it.isStub, it.isNovel, it.isCustom) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to load extensions cache" }
            null
        }
    }

    fun saveTags(tags: List<Pair<String, Int>>, noTagsCount: Int) {
        try {
            val data = TagsCacheData(
                tags = tags.map { TagData(it.first, it.second) },
                noTagsCount = noTagsCount,
            )
            tagsFile.writeText(json.encodeToString(data))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to save tags cache" }
        }
    }

    fun loadTags(): Pair<List<Pair<String, Int>>, Int>? {
        return try {
            if (!tagsFile.exists()) return null
            val text = tagsFile.readText()
            val data = json.decodeFromString<TagsCacheData>(text)
            data.tags.map { it.name to it.count } to data.noTagsCount
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to load tags cache" }
            null
        }
    }

    @Serializable
    private data class ExtensionData(
        val id: Long,
        val name: String,
        val isStub: Boolean = false,
        val isNovel: Boolean = false,
        val isCustom: Boolean = false,
    )

    @Serializable
    private data class TagData(
        val name: String,
        val count: Int,
    )

    @Serializable
    private data class TagsCacheData(
        val tags: List<TagData>,
        val noTagsCount: Int,
    )
}
