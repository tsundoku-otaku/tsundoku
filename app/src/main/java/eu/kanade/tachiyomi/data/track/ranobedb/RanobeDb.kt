package eu.kanade.tachiyomi.data.track.ranobedb

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.FormBody
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * RanobeDB tracker.
 *  - Search / metadata: public read API at /api/v0.
 *  - Add / update: SuperForms POST to /api/i/user/book/{id} (per-book).
 *  - Delete: SuperForms POST to /api/i/user/series/{id} with type=delete.
 *
 * Auth: session cookie `auth_session`, stored in [getPassword] as either the full Cookie
 * header or the bare value.
 */
class RanobeDb(id: Long) : BaseTracker(id, "RanobeDB"), DeletableTracker {

    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://ranobedb.org"
    private val apiUrl = "$baseUrl/api/v0"

    override fun getLogo() = R.drawable.ic_tracker_ranobedb

    override fun getStatusList() = listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ, OTHER)

    override fun getStatus(status: Long): StringResource? {
        return when (status) {
            READING -> MR.strings.reading
            COMPLETED -> MR.strings.completed
            ON_HOLD -> MR.strings.on_hold
            DROPPED -> MR.strings.dropped
            PLAN_TO_READ -> MR.strings.plan_to_read
            OTHER -> MR.strings.repeating
            else -> null
        }
    }

    override fun getReadingStatus() = READING
    override fun getRereadingStatus() = READING
    override fun getCompletionStatus() = COMPLETED

    override fun getScoreList(): ImmutableList<String> = persistentListOf(
        "10", "9", "8", "7", "6", "5", "4", "3", "2", "1", "",
    )

    override fun indexToScore(index: Int): Double {
        return if (index == 10) 0.0 else (10 - index).toDouble()
    }

    override fun get10PointScore(track: DomainTrack): Double = track.score

    override fun displayScore(track: DomainTrack): String {
        return if (track.score == 0.0) "-" else track.score.toString()
    }

    private fun apiHeaders(extraContentType: String? = null): okhttp3.Headers {
        val pw = getPassword()
        val cookie = when {
            pw.isBlank() -> null
            pw.contains("auth_session=") -> pw
            else -> "auth_session=$pw"
        }
        val builder = okhttp3.Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Accept", "application/json")
            .add("Origin", baseUrl)
            .add("Referer", "$baseUrl/")
        if (cookie != null) builder.add("Cookie", cookie)
        if (extraContentType != null) builder.add("Content-Type", extraContentType)
        return builder.build()
    }

    override suspend fun search(query: String): List<TrackSearch> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$apiUrl/books?q=$encoded"
            logcat(LogPriority.DEBUG) { "RanobeDB search GET $url" }
            val response = client.newCall(GET(url, apiHeaders())).awaitSuccess()
            val body = response.body.string()
            val root = (json.parseToJsonElement(body) as? JsonObject) ?: return emptyList()
            val books = (root["books"] as? JsonArray) ?: return emptyList()
            books.mapNotNull { (it as? JsonObject)?.let(::parseBookSummary) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "RanobeDB search failed" }
            emptyList()
        }
    }

    override suspend fun searchNovels(query: String): List<TrackSearch> = search(query)

    private fun parseBookSummary(obj: JsonObject): TrackSearch? {
        val id = obj.prim("id")?.intOrNull?.toLong() ?: return null
        val title = obj.prim("title")?.contentOrNull
            ?: obj.prim("romaji")?.contentOrNull
            ?: return null
        val track = TrackSearch.create(this.id)
        track.remote_id = id
        track.title = title
        track.tracking_url = "$baseUrl/book/$id"
        val image = obj["image"] as? JsonObject
        image?.prim("filename")?.contentOrNull?.takeIf { it.isNotBlank() }?.let { filename ->
            track.cover_url = "$IMAGES_HOST/$filename"
        }
        val synonyms = mutableListOf<String>()
        listOf("title_orig", "romaji", "romaji_orig").forEach { key ->
            obj.prim(key)?.contentOrNull?.let { synonyms += it }
        }
        track.synonyms = synonyms.filter { it.isNotBlank() && it != title }.distinct()
        return track
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val bookId = extractBookId(track) ?: return track
        track.remote_id = bookId
        if (getPassword().isNotBlank()) {
            val initialStatus = if (hasReadChapters) READING else PLAN_TO_READ
            track.status = initialStatus
            submitForm(
                bookId = bookId,
                status = initialStatus,
                score = track.score,
                started = track.started_reading_date.toIsoDate(),
                finished = track.finished_reading_date.toIsoDate(),
                action = ACTION_ADD,
            )
        }
        return track
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (getPassword().isBlank()) return track
        val bookId = track.remote_id.takeIf { it > 0 } ?: extractBookId(track) ?: return track
        // The Svelte route upserts on `add`; always submit as `add` to create-or-replace.
        submitForm(
            bookId = bookId,
            status = track.status,
            score = track.score,
            started = track.started_reading_date.toIsoDate(),
            finished = track.finished_reading_date.toIsoDate(),
            action = ACTION_ADD,
        )
        return track
    }

    override suspend fun setRemoteLastChapterRead(track: Track, chapterNumber: Int) {
        if (!trackPreferences.ranobeDbMarkChaptersAsRead.get()) return
        super.setRemoteLastChapterRead(track, chapterNumber)
    }

    override suspend fun setRemoteStatus(track: Track, status: Long) {
        if (!trackPreferences.ranobeDbSyncReadingList.get()) return
        super.setRemoteStatus(track, status)
    }

    override suspend fun refresh(track: Track): Track {
        val bookId = track.remote_id.takeIf { it > 0 } ?: extractBookId(track) ?: return track
        track.remote_id = bookId
        try {
            val response = client.newCall(GET("$apiUrl/book/$bookId", apiHeaders())).awaitSuccess()
            val body = response.body.string()
            val obj = (json.parseToJsonElement(body) as? JsonObject) ?: return track
            obj.prim("title")?.contentOrNull?.let { track.title = it }
            (obj["series"] as? JsonObject)?.let { series ->
                (series["books"] as? JsonArray)?.size?.toLong()?.let { track.total_chapters = it }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "RanobeDB metadata refresh failed for book=$bookId" }
        }
        return track
    }

    override suspend fun delete(track: DomainTrack) {
        if (getPassword().isBlank()) return
        val bookId = track.remoteId.takeIf { it > 0 } ?: return
        // Deletion is a series-level form action, not book-level.
        val seriesId = fetchSeriesId(bookId) ?: return
        submitSeriesForm(
            seriesId = seriesId,
            status = track.status,
            score = track.score,
            action = ACTION_DELETE,
        )
    }

    private suspend fun fetchSeriesId(bookId: Long): Long? {
        return try {
            val response = client.newCall(GET("$apiUrl/book/$bookId", apiHeaders())).awaitSuccess()
            val obj = (json.parseToJsonElement(response.body.string()) as? JsonObject) ?: return null
            (obj["series"] as? JsonObject)?.prim("id")?.intOrNull?.toLong()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "RanobeDB series id lookup failed for book=$bookId" }
            null
        }
    }

    override suspend fun login(username: String, password: String) {
        saveCredentials(username.ifBlank { "cookie_auth" }, password)
    }

    private fun extractBookId(track: Track): Long? {
        if (track.remote_id > 0) return track.remote_id
        val url = track.tracking_url
        return Regex("/book/(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("/series/(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun submitForm(
        bookId: Long,
        status: Long,
        score: Double,
        started: String?,
        finished: String?,
        action: String,
    ) {
        val statusStr = mapStatusToApi(status)
        val labelId = mapStatusToLabelId(status)
        val payload = RanobeDbSuperForm.encode(
            labelId = labelId,
            statusName = statusStr,
            score = score,
            started = started,
            finished = finished,
            action = action,
        )
        val body = FormBody.Builder()
            .add("__superform_json", payload)
            .add("__superform_id", SUPERFORM_ID)
            .build()
        val request = POST(
            "$baseUrl/api/i/user/book/$bookId",
            apiHeaders(extraContentType = "application/x-www-form-urlencoded"),
            body,
        )
        logcat(LogPriority.DEBUG) {
            "RanobeDB POST book/$bookId action=$action status=$statusStr score=$score " +
                "started=$started finished=$finished payload=$payload"
        }
        try {
            client.newCall(request).execute().use { resp ->
                val responseBody = runCatching { resp.body.string() }.getOrNull().orEmpty()
                logcat(if (resp.isSuccessful) LogPriority.DEBUG else LogPriority.WARN) {
                    "RanobeDB POST book/$bookId -> ${resp.code} body=$responseBody"
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "RanobeDB submitForm $action threw" }
        }
    }

    private fun submitSeriesForm(
        seriesId: Long,
        status: Long,
        score: Double,
        action: String,
    ) {
        val payload = RanobeDbSuperForm.encodeSeries(
            labelId = mapStatusToLabelId(status),
            statusName = mapStatusToApi(status),
            score = score,
            action = action,
        )
        val body = FormBody.Builder()
            .add("__superform_json", payload)
            .add("__superform_id", SUPERFORM_ID)
            .build()
        val request = POST(
            "$baseUrl/api/i/user/series/$seriesId",
            apiHeaders(extraContentType = "application/x-www-form-urlencoded"),
            body,
        )
        logcat(LogPriority.DEBUG) { "RanobeDB POST series/$seriesId action=$action payload=$payload" }
        try {
            client.newCall(request).execute().use { resp ->
                val responseBody = runCatching { resp.body.string() }.getOrNull().orEmpty()
                logcat(if (resp.isSuccessful) LogPriority.DEBUG else LogPriority.WARN) {
                    "RanobeDB POST series/$seriesId -> ${resp.code} body=$responseBody"
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "RanobeDB submitSeriesForm $action threw" }
        }
    }

    private fun mapStatusToApi(status: Long): String = when (status) {
        READING -> "Reading"
        COMPLETED -> "Finished"
        ON_HOLD -> "Stalled"
        DROPPED -> "Dropped"
        PLAN_TO_READ -> "Plan to read"
        OTHER -> "Other"
        else -> "Reading"
    }

    /**
     * RanobeDB reading-list label IDs:
     *  1 = Reading, 2 = Finished, 3 = Plan to read, 4 = Stalled, 5 = Dropped, 6 = Other
     */
    private fun mapStatusToLabelId(status: Long): Int = when (status) {
        READING -> 1
        COMPLETED -> 2
        PLAN_TO_READ -> 3
        ON_HOLD -> 4
        DROPPED -> 5
        OTHER -> 6
        else -> 1
    }

    private fun Long.toIsoDate(): String? {
        if (this <= 0) return null
        return SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(this))
    }

    /** Safe JsonPrimitive accessor. */
    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val DROPPED = 4L
        const val PLAN_TO_READ = 5L
        const val OTHER = 6L

        const val ACTION_ADD = "add"
        const val ACTION_DELETE = "delete"

        private const val IMAGES_HOST = "https://images.ranobedb.org"
        private const val SUPERFORM_ID = "tsundoku"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"
    }
}

/** SuperForms JSON encoder for RanobeDB's `/api/i/user/...` form endpoints. */
internal object RanobeDbSuperForm {
    /**
     * Book form (`/api/i/user/book/{id}`). Slot layout:
     *  0 schema, 1 labels [2], 2 {id:3,label:4}, 3 labelId, 4 labelName, 5 selectedCustLabels [],
     *  6 readingStatus, 7 score (1..10|null), 8 started, 9 finished, 10 notes, 11 type.
     */
    fun encode(
        labelId: Int,
        statusName: String,
        score: Double,
        started: String?,
        finished: String?,
        action: String,
        notes: String = "",
    ): String {
        val scoreInt = score.toInt()
        val arr = buildJsonArray {
            add(
                buildJsonObject {
                    put("labels", 1)
                    put("selectedCustLabels", 5)
                    put("readingStatus", 6)
                    put("score", 7)
                    put("started", 8)
                    put("finished", 9)
                    put("notes", 10)
                    put("type", 11)
                },
            )
            add(buildJsonArray { add(JsonPrimitive(2)) })
            add(
                buildJsonObject {
                    put("id", 3)
                    put("label", 4)
                },
            )
            add(JsonPrimitive(labelId))
            add(JsonPrimitive(statusName))
            add(buildJsonArray { })
            add(JsonPrimitive(statusName))
            if (scoreInt in 1..10) {
                add(JsonPrimitive(scoreInt))
            } else {
                add(JsonNull)
            }
            add(JsonPrimitive(started ?: ""))
            add(JsonPrimitive(finished ?: ""))
            add(JsonPrimitive(notes))
            add(JsonPrimitive(action))
        }
        return Json.encodeToString(JsonArray.serializer(), arr)
    }

    /**
     * Series-level form used by `/api/i/user/series/{id}` (delete path).
     * Schema keys: labels, notify_book, notify_when_released, show_upcoming, volumes_read,
     * selectedCustLabels, langs, formats, readingStatus, score, started, finished, notes, type.
     */
    fun encodeSeries(
        labelId: Int,
        statusName: String,
        score: Double,
        action: String,
        notes: String = "",
    ): String {
        val scoreInt = score.toInt()
        val arr = buildJsonArray {
            add(
                buildJsonObject {
                    put("labels", 1)
                    put("notify_book", 5)
                    put("notify_when_released", 6)
                    put("show_upcoming", 7)
                    put("volumes_read", 8)
                    put("selectedCustLabels", 9)
                    put("langs", 10)
                    put("formats", 11)
                    put("readingStatus", 12)
                    put("score", 13)
                    put("started", 14)
                    put("finished", 15)
                    put("notes", 16)
                    put("type", 17)
                },
            )
            add(buildJsonArray { add(JsonPrimitive(2)) })
            add(
                buildJsonObject {
                    put("id", 3)
                    put("label", 4)
                },
            )
            add(JsonPrimitive(labelId))
            add(JsonPrimitive(statusName))
            add(JsonPrimitive(false))
            add(JsonPrimitive(false))
            add(JsonPrimitive(false))
            add(JsonPrimitive(0))
            add(buildJsonArray { })
            add(buildJsonArray { })
            add(buildJsonArray { })
            add(JsonPrimitive(statusName))
            if (scoreInt in 1..10) {
                add(JsonPrimitive(scoreInt))
            } else {
                add(JsonNull)
            }
            add(JsonPrimitive(""))
            add(JsonPrimitive(""))
            add(JsonPrimitive(notes))
            add(JsonPrimitive(action))
        }
        return Json.encodeToString(JsonArray.serializer(), arr)
    }
}
