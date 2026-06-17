package eu.kanade.tachiyomi.data.track.mangabaka

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * MangaBaka tracker (https://mangabaka.org).
 *
 * Auth: Personal Access Token (PAT) passed via `x-api-key`. Login UI just collects
 * the token. The token is stored in [getPassword]; username is a placeholder.
 *
 * Status mapping (local 5-state ↔ MangaBaka 7-state):
 *  - READING ↔ reading (incoming `rereading` is treated as reading)
 *  - COMPLETED ↔ completed
 *  - ON_HOLD ↔ paused
 *  - DROPPED ↔ dropped
 *  - PLAN_TO_READ ↔ plan_to_read (incoming `considering` collapses here)
 */
class MangaBaka(id: Long) : BaseTracker(id, "MangaBaka"), DeletableTracker {

    private val json = Json { ignoreUnknownKeys = true }
    private val apiUrl = "https://api.mangabaka.org"
    private val webUrl = "https://mangabaka.org"

    override val supportsReadingDates: Boolean = true
    override val supportsPrivateTracking: Boolean = true

    override fun getLogo() = R.drawable.ic_tracker_mangabaka

    override fun getStatusList() = listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ)

    override fun getStatus(status: Long): StringResource? {
        return when (status) {
            READING -> MR.strings.reading
            COMPLETED -> MR.strings.completed
            ON_HOLD -> MR.strings.on_hold
            DROPPED -> MR.strings.dropped
            PLAN_TO_READ -> MR.strings.plan_to_read
            else -> null
        }
    }

    override fun getReadingStatus() = READING
    override fun getRereadingStatus() = READING
    override fun getCompletionStatus() = COMPLETED

    override fun getScoreList(): List<String> = listOf(
        "10", "9", "8", "7", "6", "5", "4", "3", "2", "1", "",
    )

    override fun indexToScore(index: Int): Double {
        return if (index == 10) 0.0 else (10 - index).toDouble()
    }

    override fun get10PointScore(track: DomainTrack): Double = track.score

    override fun displayScore(track: DomainTrack): String {
        return if (track.score == 0.0) "-" else track.score.toString()
    }

    private fun authHeaders(): Headers {
        val token = getPassword()
        val builder = Headers.Builder()
            .add("Accept", "application/json")
            .add("User-Agent", USER_AGENT)
        if (token.isNotBlank()) {
            // Two auth shapes are supported:
            //   1. Personal access token: `mb-...` → sent as x-api-key.
            //   2. Better-Auth session cookies: `__Secure-better-auth.session_*=...` → Cookie header.
            if (token.contains("__Secure-better-auth") || token.contains("session_token=")) {
                builder.add("Cookie", token)
                builder.add("Origin", webUrl)
                builder.add("Referer", "$webUrl/")
            } else {
                builder.add("x-api-key", token)
            }
        }
        return builder.build()
    }

    override suspend fun search(query: String): List<TrackSearch> {
        return runSearch(query, novelOnly = false)
    }

    override suspend fun searchNovels(query: String): List<TrackSearch> {
        return runSearch(query, novelOnly = true)
    }

    private suspend fun runSearch(query: String, novelOnly: Boolean): List<TrackSearch> {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            // Valid type enum (per OpenAPI): manga | novel | manhwa | manhua | oel | other.
            val typeParam = if (novelOnly) "&type=novel" else ""
            val response = client.newCall(
                GET("$apiUrl/v1/series/search?q=$encoded$typeParam", authHeaders()),
            ).awaitSuccess()
            val body = response.body.string()
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            data.mapNotNull { parseSeries(it.jsonObject) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "MangaBaka search failed" }
            emptyList()
        }
    }

    private fun parseSeries(obj: JsonObject): TrackSearch? {
        val id = obj.prim("id")?.intOrNull?.toLong() ?: return null
        val title = obj.prim("title")?.contentOrNull
            ?: obj.prim("romanized_title")?.contentOrNull
            ?: obj.prim("native_title")?.contentOrNull
            ?: return null
        val track = TrackSearch.create(this.id)
        track.remote_id = id
        track.title = title
        track.tracking_url = "$webUrl/$id"
        track.cover_url = extractCoverUrl(obj["cover"])
        track.summary = obj.prim("description")?.contentOrNull ?: ""
        // status enum: cancelled | completed | hiatus | releasing | unknown | upcoming
        track.publishing_status = obj.prim("status")?.contentOrNull?.replaceFirstChar { it.uppercase() } ?: ""
        track.publishing_type = obj.prim("type")?.contentOrNull?.replaceFirstChar { it.uppercase() } ?: ""
        obj.prim("total_chapters")?.contentOrNull?.toLongOrNull()?.let { track.total_chapters = it }
        if (track.total_chapters == 0L) {
            obj.prim("final_volume")?.contentOrNull?.toLongOrNull()?.let { track.total_chapters = it }
        }
        obj.prim("rating")?.doubleOrNull?.let { track.score = it }
        (obj["published"] as? JsonObject)?.prim("start_date")?.contentOrNull?.let { track.start_date = it }
        track.authors = (obj["authors"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        track.artists = (obj["artists"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val synonyms = mutableListOf<String>()
        (obj["titles"] as? JsonArray)?.forEach { entry ->
            (entry as? JsonObject)?.prim("title")?.contentOrNull?.let { synonyms += it }
        }
        listOf("native_title", "romanized_title").forEach { key ->
            obj.prim(key)?.contentOrNull?.let { synonyms += it }
        }
        track.synonyms = synonyms.filter { it.isNotBlank() && it != title }.distinct()
        return track
    }

    /** Safe JsonPrimitive accessor — returns null if the key is missing or not a primitive. */
    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    /**
     * MangaBaka `cover` shape (per actual search payload):
     *   "cover": {
     *     "raw":  { "url": "...", "size": ..., ... },
     *     "x150": { "x1": "...", "x2": "...", "x3": "..." },
     *     "x250": { ... },
     *     "x350": { ... }
     *   }
     * Pick the lowest-res variant with fallbacks. Falls back to raw.url, then a flat
     * string cover, then empty.
     */
    private fun extractCoverUrl(element: JsonElement?): String {
        if (element == null) return ""
        (element as? JsonPrimitive)?.contentOrNull?.let { return it }
        val obj = element as? JsonObject ?: return ""

        listOf("x150", "x250", "x350").forEach { key ->
            (obj[key] as? JsonObject)?.let { sized ->
                sized.prim("x1")?.contentOrNull?.let { return it }
                sized.prim("x2")?.contentOrNull?.let { return it }
                sized.prim("x3")?.contentOrNull?.let { return it }
            }
        }
        (obj["raw"] as? JsonObject)?.prim("url")?.contentOrNull?.let { return it }
        obj.prim("raw")?.contentOrNull?.let { return it }
        obj.prim("default")?.contentOrNull?.let { return it }
        return ""
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val seriesId = track.remote_id.takeIf { it > 0 } ?: extractSeriesId(track.tracking_url) ?: return track
        track.remote_id = seriesId

        val existing = fetchLibraryEntry(seriesId)
        if (existing != null) {
            existing.applyTo(track)
        } else {
            val initialState = if (hasReadChapters) "reading" else "plan_to_read"
            track.status = if (hasReadChapters) READING else PLAN_TO_READ
            submitLibraryForm(
                seriesId = seriesId,
                state = initialState,
                progressChapter = track.last_chapter_read,
                progressVolume = 0.0,
                rating = null,
                startDateIso = null,
                finishDateIso = null,
                isPrivate = track.private,
                note = "",
            )
        }
        return track
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        val seriesId = track.remote_id.takeIf { it > 0 } ?: extractSeriesId(track.tracking_url) ?: return track
        val rating = if (track.score > 0) (track.score * 10).toInt().coerceIn(0, 100) else null
        submitLibraryForm(
            seriesId = seriesId,
            state = statusToApi(track.status),
            progressChapter = track.last_chapter_read,
            progressVolume = 0.0,
            rating = rating,
            startDateIso = track.started_reading_date.takeIf { it > 0 }?.toIsoDateTime(),
            finishDateIso = track.finished_reading_date.takeIf { it > 0 }?.toIsoDateTime(),
            isPrivate = track.private,
            note = "",
        )
        return track
    }

    override suspend fun refresh(track: Track): Track {
        val seriesId = track.remote_id.takeIf { it > 0 } ?: extractSeriesId(track.tracking_url) ?: return track
        track.remote_id = seriesId
        fetchLibraryEntry(seriesId)?.applyTo(track)

        try {
            val response = client.newCall(GET("$apiUrl/v1/series/$seriesId", authHeaders())).awaitSuccess()
            val body = response.body.string()
            val series = (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonObject
                ?: return track
            series.prim("title")?.contentOrNull?.let { track.title = it }
            series.prim("final_volume")?.contentOrNull?.toLongOrNull()?.let { track.total_chapters = it }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "MangaBaka metadata refresh failed for $seriesId" }
        }
        return track
    }

    override suspend fun setRemoteLastChapterRead(track: Track, chapterNumber: Int) {
        if (!trackPreferences.mangaBakaMarkChaptersAsRead.get()) return
        super.setRemoteLastChapterRead(track, chapterNumber)
    }

    override suspend fun setRemoteStatus(track: Track, status: Long) {
        if (!trackPreferences.mangaBakaSyncReadingList.get()) return
        super.setRemoteStatus(track, status)
    }

    override suspend fun delete(track: DomainTrack) {
        val seriesId = track.remoteId.takeIf { it > 0 } ?: return
        submitLibraryForm(
            seriesId = seriesId,
            state = "dropped",
            progressChapter = track.lastChapterRead,
            progressVolume = 0.0,
            rating = if (track.score > 0) (track.score * 10).toInt().coerceIn(0, 100) else null,
            startDateIso = null,
            finishDateIso = null,
            isPrivate = track.private,
            note = "",
        )
    }

    override suspend fun login(username: String, password: String) {
        saveCredentials(username.ifBlank { "api_user" }, password)
    }

    private data class LibraryEntry(
        val state: Long?,
        val progressChapter: Double?,
        val rating: Double?,
        val startDate: Long?,
        val finishDate: Long?,
        val isPrivate: Boolean?,
    ) {
        fun applyTo(track: Track) {
            state?.let { track.status = it }
            progressChapter?.let { track.last_chapter_read = it }
            rating?.let { track.score = it }
            startDate?.let { track.started_reading_date = it }
            finishDate?.let { track.finished_reading_date = it }
            isPrivate?.let { track.private = it }
        }
    }

    private suspend fun fetchLibraryEntry(seriesId: Long): LibraryEntry? {
        if (getPassword().isBlank()) return null
        return try {
            val response = client.newCall(GET("$apiUrl/v1/my/library/$seriesId", authHeaders())).awaitSuccess()
            val body = response.body.string()
            val data = (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonObject
                ?: return null
            LibraryEntry(
                state = data.prim("state")?.contentOrNull?.let(::statusFromApi),
                progressChapter = data.prim("progress_chapter")?.doubleOrNull,
                rating = data.prim("rating")?.doubleOrNull?.let { it / 10.0 },
                startDate = data.prim("start_date")?.contentOrNull?.parseIsoDateTime(),
                finishDate = data.prim("finish_date")?.contentOrNull?.parseIsoDateTime(),
                isPrivate = data.prim("is_private")?.contentOrNull?.toBooleanStrictOrNull(),
            )
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "MangaBaka fetchLibraryEntry $seriesId: ${e.message}" }
            null
        }
    }

    /** True when the stored token is a Personal Access Token (starts with `mb-`). */
    private fun hasPatToken(): Boolean {
        val pw = getPassword().trim()
        return pw.startsWith("mb-")
    }

    /**
     * Writes go through one of two endpoints depending on the credential the user pasted:
     *  - PAT (`mb-...`) → `PATCH https://api.mangabaka.org/v1/my/library/{id}` with JSON.
     *  - Better-Auth session cookies → `POST https://mangabaka.org/my/library/{id}?...`
     *    with a Svelte SuperForms form body (the API rejects session cookies with
     *    `401 No session found`).
     */
    private fun submitLibraryForm(
        seriesId: Long,
        state: String,
        progressChapter: Double,
        progressVolume: Double,
        rating: Int?,
        startDateIso: String?,
        finishDateIso: String?,
        isPrivate: Boolean,
        note: String,
    ) {
        if (getPassword().isBlank()) {
            logcat(LogPriority.WARN) { "MangaBaka write $seriesId: no auth, skipping" }
            return
        }
        if (hasPatToken()) {
            submitLibraryApi(
                seriesId = seriesId,
                state = state,
                progressChapter = progressChapter,
                progressVolume = progressVolume,
                rating = rating,
                startDateIso = startDateIso,
                finishDateIso = finishDateIso,
                isPrivate = isPrivate,
                note = note,
            )
            return
        }
        try {
            val payload = MangaBakaSuperForm.encode(
                note = note,
                state = state,
                isPrivate = isPrivate,
                progressChapter = progressChapter,
                progressVolume = progressVolume,
                rating = rating,
                startDateIso = startDateIso,
                finishDateIso = finishDateIso,
            )
            val body = FormBody.Builder()
                .add("content_rating", "safe,suggestive,erotica,pornographic")
                .add("number_of_rereads", "")
                .add("read_link", "")
                .add("__superform_json", payload)
                .add("__superform_id", "my-library-$seriesId-c57-1")
                .build()
            val url = "$webUrl/my/library/$seriesId?no_redirect=true&added_source=manual"
            logcat(LogPriority.INFO) {
                "MangaBaka POST $url body=$payload"
            }
            val request = Request.Builder()
                .url(url)
                .headers(authHeaders().newBuilder().add("Content-Type", "application/x-www-form-urlencoded").build())
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                val respBody = runCatching { resp.body.string() }.getOrNull().orEmpty()
                logcat(if (resp.isSuccessful) LogPriority.INFO else LogPriority.WARN) {
                    "MangaBaka POST $seriesId -> ${resp.code} body=$respBody"
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "MangaBaka submitLibraryForm $seriesId threw" }
        }
    }

    /**
     * PAT-authenticated write: PATCH the library entry as JSON. If the entry doesn't
     * exist yet (404), upsert via POST.
     */
    private fun submitLibraryApi(
        seriesId: Long,
        state: String,
        progressChapter: Double,
        progressVolume: Double,
        rating: Int?,
        startDateIso: String?,
        finishDateIso: String?,
        isPrivate: Boolean,
        note: String,
    ) {
        val payload = buildJsonObject {
            put("state", state)
            put("progress_chapter", progressChapter)
            put("progress_volume", progressVolume)
            if (rating != null && rating > 0) put("rating", rating) else put("rating", JsonNull)
            put("is_private", isPrivate)
            put("note", note)
            if (startDateIso != null) put("start_date", startDateIso) else put("start_date", JsonNull)
            if (finishDateIso != null) put("finish_date", finishDateIso) else put("finish_date", JsonNull)
        }
        val payloadStr = payload.toString()
        val body = payloadStr.toRequestBody(JSON_MEDIA)
        val attempt = { method: String ->
            val builder = Request.Builder()
                .url("$apiUrl/v1/my/library/$seriesId")
                .headers(authHeaders())
            when (method) {
                "POST" -> builder.post(body)
                "PATCH" -> builder.patch(body)
                else -> error("Unsupported method $method")
            }
            logcat(LogPriority.INFO) { "MangaBaka API $method /v1/my/library/$seriesId body=$payloadStr" }
            client.newCall(builder.build()).execute().use { resp ->
                val respBody = runCatching { resp.body.string() }.getOrNull().orEmpty()
                logcat(if (resp.isSuccessful) LogPriority.INFO else LogPriority.WARN) {
                    "MangaBaka API $method $seriesId -> ${resp.code} body=$respBody"
                }
                resp.code
            }
        }
        try {
            val patchCode = attempt("PATCH")
            if (patchCode == 404) {
                attempt("POST")
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "MangaBaka API write $seriesId threw" }
        }
    }

    private fun extractSeriesId(url: String): Long? {
        Regex("mangabaka\\.org/(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        Regex("/series/(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        return null
    }

    private fun statusToApi(status: Long): String = when (status) {
        READING -> "reading"
        COMPLETED -> "completed"
        ON_HOLD -> "paused"
        DROPPED -> "dropped"
        PLAN_TO_READ -> "plan_to_read"
        else -> "reading"
    }

    private fun statusFromApi(state: String): Long = when (state) {
        "reading", "rereading" -> READING
        "completed" -> COMPLETED
        "paused" -> ON_HOLD
        "dropped" -> DROPPED
        "plan_to_read", "considering" -> PLAN_TO_READ
        else -> READING
    }

    private fun Long.toIsoDateTime(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(this))
    }

    private fun String.parseIsoDateTime(): Long? {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            val clean = substringBefore('.').removeSuffix("Z")
            fmt.parse(clean)?.time
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val DROPPED = 4L
        const val PLAN_TO_READ = 5L

        private val JSON_MEDIA = "application/json".toMediaType()
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"
    }
}

/**
 * Encodes the Svelte SuperForms payload accepted by
 * `POST https://mangabaka.org/my/library/{id}?no_redirect=true&added_source=manual`.
 *
 *  Slot 0: schema {note,state,priority,is_private,lists,progress_chapter,progress_volume,
 *                  finish_date,start_date,rating}
 *  Slot 1: note (string)
 *  Slot 2: state (string)
 *  Slot 3: priority (number) — site default = 20
 *  Slot 4: is_private (bool)
 *  Slot 5: lists (array) — empty unless caller manages multi-list memberships
 *  Slot 6: progress (number) — shared by progress_chapter and progress_volume
 *  Slot 7: finish_date — ["Date", "yyyy-MM-ddTHH:mm:ss.SSSZ"]
 *  Slot 8: start_date — same shape
 *  Slot 9: rating (0..100 number, or omit for unscored)
 */
internal object MangaBakaSuperForm {
    fun encode(
        note: String,
        state: String,
        isPrivate: Boolean,
        progressChapter: Double,
        progressVolume: Double,
        rating: Int?,
        startDateIso: String?,
        finishDateIso: String?,
    ): String {
        val progress = maxOf(progressChapter, progressVolume).coerceAtLeast(0.0)
        val arr = buildJsonArray {
            add(
                buildJsonObject {
                    put("note", 1)
                    put("state", 2)
                    put("priority", 3)
                    put("is_private", 4)
                    put("lists", 5)
                    put("progress_chapter", 6)
                    put("progress_volume", 6)
                    put("finish_date", 7)
                    put("start_date", 8)
                    put("rating", 9)
                },
            )
            add(JsonPrimitive(note))
            add(JsonPrimitive(state))
            add(JsonPrimitive(20))
            add(JsonPrimitive(isPrivate))
            add(buildJsonArray { })
            if (progress == progress.toLong().toDouble()) {
                add(JsonPrimitive(progress.toLong()))
            } else {
                add(JsonPrimitive(progress))
            }
            add(dateValue(finishDateIso))
            add(dateValue(startDateIso))
            if (rating != null && rating > 0) {
                add(JsonPrimitive(rating))
            } else {
                add(JsonNull)
            }
        }
        return Json.encodeToString(JsonArray.serializer(), arr)
    }

    /**
     * Svelte SuperForms encodes Date values as `["Date", "<ISO string>"]`. Returns the
     * tagged array, or `JsonNull` when the input is null/blank.
     */
    private fun dateValue(iso: String?): JsonElement {
        if (iso.isNullOrBlank()) return JsonNull
        return buildJsonArray {
            add(JsonPrimitive("Date"))
            add(JsonPrimitive(iso))
        }
    }
}
