package tachiyomi.domain.manga.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Per-field user overrides for a manga's source-fetched metadata. Stored inside the manga's
 * [Manga.memo] JSON under the [MEMO_KEY] key. A null field means "not overridden": the source
 * value flows through on refresh. Title and thumbnail are intentionally excluded: title keeps its
 * own update behaviour (and alternative titles), and the cover has its own custom-cover mechanism.
 */
@Serializable
data class CustomMangaInfo(
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: Long? = null,
) {
    fun isEmpty(): Boolean =
        author == null && artist == null && description == null && genre == null && status == null

    companion object {
        const val MEMO_KEY = "customInfo"

        // Snapshot of the last source-fetched values, kept so the override can be reverted (and the
        // originals shown) without a network refresh.
        const val SOURCE_KEY = "sourceInfo"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /** Decode the override stored in [memo], or null when none is set. */
        fun from(memo: JsonObject): CustomMangaInfo? = read(memo, MEMO_KEY)?.takeUnless { it.isEmpty() }

        /** Decode the source snapshot stored in [memo], or null when none is set. */
        fun fromSource(memo: JsonObject): CustomMangaInfo? = read(memo, SOURCE_KEY)

        private fun read(memo: JsonObject, key: String): CustomMangaInfo? {
            val element = memo[key] ?: return null
            return try {
                json.decodeFromJsonElement(serializer(), element)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Return a copy of [memo] with this override merged in under [MEMO_KEY], preserving every
         * other key. An empty/all-null override removes the key entirely.
         */
        fun CustomMangaInfo?.writeInto(memo: JsonObject): JsonObject =
            write(memo, MEMO_KEY, this?.takeUnless { it.isEmpty() })

        /** Return a copy of [memo] with [this] stored as the source snapshot under [SOURCE_KEY]. */
        fun CustomMangaInfo?.writeSourceInto(memo: JsonObject): JsonObject = write(memo, SOURCE_KEY, this)

        private fun write(memo: JsonObject, key: String, info: CustomMangaInfo?): JsonObject =
            buildJsonObject {
                memo.forEach { (k, value) -> if (k != key) put(k, value) }
                if (info != null) {
                    put(key, json.encodeToJsonElement(serializer(), info))
                }
            }
    }
}
