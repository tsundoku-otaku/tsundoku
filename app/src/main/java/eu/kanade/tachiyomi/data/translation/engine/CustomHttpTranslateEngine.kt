package eu.kanade.tachiyomi.data.translation.engine

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Custom HTTP translation engine.
 * Allows users to configure their own translation API endpoint.
 *
 * Supports configurable:
 * - API URL (POST endpoint)
 * - API Key (Authorization header)
 * - Request body template with placeholders
 * - Response JSON path for result extraction
 */
class CustomHttpTranslateEngine(
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val preferences: TranslationPreferences = Injekt.get(),
) : TranslationEngine {

    private val client: OkHttpClient
        get() {
            val timeout = preferences.translationTimeoutMs().get()
            return networkHelper.client.newBuilder()
                .connectTimeout(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
        }

    override val id: Long = ENGINE_ID
    override val name: String = "Custom HTTP"
    override val requiresApiKey: Boolean = false // Optional, depends on user's API
    override val isRateLimited: Boolean = true
    override val isOffline: Boolean = false

    // Common language codes - user's API may support different languages
    override val supportedLanguages: List<Pair<String, String>> = listOf(
        "auto" to "Auto-detect",
        "ar" to "Arabic",
        "cs" to "Czech",
        "da" to "Danish",
        "de" to "German",
        "el" to "Greek",
        "en" to "English",
        "es" to "Spanish",
        "fi" to "Finnish",
        "fr" to "French",
        "he" to "Hebrew",
        "hi" to "Hindi",
        "hu" to "Hungarian",
        "id" to "Indonesian",
        "it" to "Italian",
        "ja" to "Japanese",
        "ko" to "Korean",
        "nl" to "Dutch",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "sv" to "Swedish",
        "th" to "Thai",
        "tr" to "Turkish",
        "uk" to "Ukrainian",
        "vi" to "Vietnamese",
        "zh" to "Chinese",
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun isConfigured(): Boolean {
        val url = preferences.customHttpUrl().get()
        return url.isNotBlank()
    }

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationResult = withContext(Dispatchers.IO) {
        try {
            val apiUrl = preferences.customHttpUrl().get()
            if (apiUrl.isBlank()) {
                return@withContext TranslationResult.Error(
                    "Custom HTTP URL not configured",
                    TranslationResult.ErrorCode.API_KEY_MISSING, // Using API_KEY_MISSING as "config missing"
                )
            }

            val translatedTexts = translateBatch(apiUrl, texts, sourceLanguage, targetLanguage)
            TranslationResult.Success(translatedTexts, null)
        } catch (e: Exception) {
            TranslationResult.Error(
                e.message ?: "Translation failed",
                TranslationResult.ErrorCode.UNKNOWN,
            )
        }
    }

    private suspend fun translateBatch(
        apiUrl: String,
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<String> {
        val apiKey = preferences.customHttpApiKey().get().takeIf { it.isNotBlank() }
        val requestTemplate = preferences.customHttpRequestTemplate().get()
        val responsePath = preferences.customHttpResponsePath().get()
        val method = preferences.customHttpMethod().get()

        // Placeholders in the URL are substituted URL-encoded (mainly for GET query params)
        val finalUrl = substituteUrl(apiUrl, texts, sourceLanguage, targetLanguage)

        val requestBuilder = Request.Builder().url(finalUrl)
        if (method.equals("GET", ignoreCase = true)) {
            requestBuilder.get()
        } else {
            // Build request body from template
            val requestBody = buildRequestBody(requestTemplate, texts, sourceLanguage, targetLanguage)
            requestBuilder
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
        }

        // Add API key if configured
        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        // Custom headers as ;- or newline-separated "Name: Value" pairs, override defaults on name match
        preferences.customHttpHeaders().get().split(';', '\n')
            .mapNotNull { entry ->
                val idx = entry.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                entry.substring(0, idx).trim() to entry.substring(idx + 1).trim()
            }
            .filter { (name, _) -> name.isNotEmpty() }
            .forEach { (name, value) ->
                requestBuilder.header(name, value.replace("{apiKey}", apiKey.orEmpty()))
            }

        return client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = readBody(response) ?: "Unknown error"
                throw Exception("HTTP ${response.code}: $errorBody")
            }

            val responseBody = readBody(response) ?: throw Exception("Empty response")
            parseResponse(responseBody, responsePath, texts.size)
        }
    }

    /**
     * Build the request body from the user's template.
     * Replaces placeholders:
     * - {text} - single text as a JSON string literal, quotes included (first one if multiple)
     * - {text_esc} - single text JSON-escaped without surrounding quotes, for embedding inside a larger string
     * - {texts} - JSON array of texts
     * - {source} - source language code
     * - {target} - target language code
     * - {source_name} - source language English name (falls back to the code)
     * - {target_name} - target language English name (falls back to the code)
     */
    private fun buildRequestBody(
        template: String,
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): String {
        val textsJson = json.encodeToString(JsonArray.serializer(), JsonArray(texts.map { JsonPrimitive(it) }))
        val singleText =
            texts.firstOrNull()?.let { json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(it)) } ?: "\"\""

        return template
            .replace("{texts}", textsJson)
            .replace("{text_esc}", singleText.removeSurrounding("\""))
            .replace("{text}", singleText)
            .replace("{source_name}", languageName(sourceLanguage))
            .replace("{target_name}", languageName(targetLanguage))
            .replace("{source}", sourceLanguage)
            .replace("{target}", targetLanguage)
    }

    private fun languageName(code: String): String =
        supportedLanguages.firstOrNull { it.first == code }?.second ?: code

    /**
     * Read the response body, decompressing manually if needed.
     * OkHttp only auto-decompresses gzip when it added Accept-Encoding itself;
     * a user-supplied Accept-Encoding header delivers the body raw.
     */
    private fun readBody(response: okhttp3.Response): String? {
        val bytes = response.body?.bytes()?.takeIf { it.isNotEmpty() } ?: return null
        return when (response.header("Content-Encoding")?.lowercase()) {
            "gzip" -> GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
            "deflate" -> inflate(bytes).toString(Charsets.UTF_8)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    private fun inflate(bytes: ByteArray): ByteArray =
        try {
            // zlib-wrapped deflate
            InflaterInputStream(bytes.inputStream()).use { it.readBytes() }
        } catch (e: Exception) {
            // raw deflate, some servers omit the zlib header
            InflaterInputStream(bytes.inputStream(), Inflater(true)).use { it.readBytes() }
        }

    /**
     * Substitute placeholders into the URL, URL-encoded.
     * {text} here is the raw first text (no JSON quoting); {texts}/{text_esc} are not applicable.
     */
    private fun substituteUrl(
        url: String,
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): String {
        fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
        return url
            .replace("{text}", enc(texts.firstOrNull().orEmpty()))
            .replace("{source_name}", enc(languageName(sourceLanguage)))
            .replace("{target_name}", enc(languageName(targetLanguage)))
            .replace("{source}", enc(sourceLanguage))
            .replace("{target}", enc(targetLanguage))
    }

    /**
     * Parse the response JSON using the user-configured path.
     * Supports dot notation: translatedText, result.translations, etc.
     * Returns a list of translated texts.
     */
    private fun parseResponse(responseBody: String, path: String, expectedCount: Int): List<String> {
        val jsonElement = json.parseToJsonElement(responseBody)
        val result = try {
            navigateJsonPath(jsonElement, path)
        } catch (e: Exception) {
            // Include the body so 200-with-error responses are diagnosable
            throw Exception("${e.message}. Response body: ${responseBody.take(300)}")
        }

        return when (result) {
            is JsonArray -> result.map {
                when (it) {
                    is JsonPrimitive -> it.content
                    is JsonObject -> {
                        // Try common field names for text
                        it["text"]?.jsonPrimitive?.content
                            ?: it["translatedText"]?.jsonPrimitive?.content
                            ?: it["translation"]?.jsonPrimitive?.content
                            ?: it.toString()
                    }
                    else -> it.toString()
                }
            }
            is JsonPrimitive -> {
                // Single result, replicate for all texts if needed
                val text = result.content
                if (expectedCount == 1) listOf(text) else List(expectedCount) { text }
            }
            else -> throw Exception("Unexpected response format at path '$path'")
        }
    }

    /**
     * Navigate a JSON element using dot notation path.
     * Supports array access with [index] notation.
     */
    private fun navigateJsonPath(element: JsonElement, path: String): JsonElement {
        if (path.isBlank()) return element

        val parts = path.trim().split(".").map { it.trim() }
        var current = element

        for (part in parts) {
            // Check for array access: fieldName[0]
            val arrayMatch = Regex("""(.+?)\[(\d+)\]""").matchEntire(part)
            if (arrayMatch != null) {
                val fieldName = arrayMatch.groupValues[1]
                val index = arrayMatch.groupValues[2].toInt()

                current = if (fieldName.isNotBlank()) {
                    current.jsonObject[fieldName] ?: throw Exception("Field '$fieldName' not found")
                } else {
                    current
                }
                current = current.jsonArray.getOrNull(index)
                    ?: throw Exception("Array index $index out of bounds")
            } else {
                current = current.jsonObject[part]
                    ?: throw Exception("Field '$part' not found in response")
            }
        }

        return current
    }

    companion object {
        const val ENGINE_ID = 10L // Unique ID for this engine
    }
}
