package eu.kanade.tachiyomi.data.translation.engine

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.translation.model.LanguageCodes
import tachiyomi.domain.translation.model.TranslationEngine
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GeminiTranslateEngine(
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val preferences: TranslationPreferences = Injekt.get(),
) : TranslationEngine {

    private val client: OkHttpClient get() = networkHelper.client

    override val id: Long = ENGINE_ID
    override val name: String = "Gemini (Google AI)"
    override val requiresApiKey: Boolean = true
    override val isRateLimited: Boolean = true
    override val isOffline: Boolean = false

    override val supportedLanguages: List<Pair<String, String>> =
        LanguageCodes.COMMON_LANGUAGES

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    private data class Part(val text: String)

    @Serializable
    private data class Content(val parts: List<Part>)

    @Serializable
    private data class GenerateRequest(val contents: List<Content>)

    @Serializable
    private data class Candidate(val content: Content? = null)

    @Serializable
    private data class GenerateResponse(val candidates: List<Candidate>? = null)

    override fun isConfigured(): Boolean {
        return preferences.geminiApiKey().get().isNotBlank()
    }

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationResult = withContext(Dispatchers.IO) {
        val apiKey = preferences.geminiApiKey().get()
        val model = preferences.geminiModel().get()

        if (apiKey.isBlank()) {
            return@withContext TranslationResult.Error(
                "Gemini API key not configured",
                TranslationResult.ErrorCode.API_KEY_MISSING,
            )
        }

        try {
            val translatedTexts = texts.map {
                translateSingleText(apiKey, model, it, sourceLanguage, targetLanguage)
            }
            TranslationResult.Success(translatedTexts)
        } catch (e: TranslationException) {
            TranslationResult.Error(
                e.message ?: "Translation failed",
                e.errorCode,
            )
        } catch (e: Exception) {
            TranslationResult.Error(
                e.message ?: "Unknown error",
                TranslationResult.ErrorCode.UNKNOWN,
            )
        }
    }

    private fun translateSingleText(
        apiKey: String,
        model: String,
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): String {
        val sourceLangName = LanguageCodes.getDisplayName(sourceLanguage)
        val targetLangName = LanguageCodes.getDisplayName(targetLanguage)

        val fromClause = if (sourceLanguage == "auto") {
            "Detect the source language and translate the following text to $targetLangName."
        } else {
            "Translate the following text from $sourceLangName to $targetLangName."
        }

        val prompt = """
You are a professional translator specializing in novel/fiction translation.

$fromClause

Rules:
- Only output the translation, nothing else
- Preserve paragraph structure
- Maintain style and tone
- Keep character names consistent

Text:
$text

Translation:
        """.trimIndent()

        val request = GenerateRequest(
            contents = listOf(Content(parts = listOf(Part(prompt)))),
        )

        val requestBody = json.encodeToString(GenerateRequest.serializer(), request)

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .build()

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.use { it.body.string() }

        if (!response.isSuccessful) {
            val errorCode = when (response.code) {
                401, 403 -> TranslationResult.ErrorCode.API_KEY_INVALID
                429 -> TranslationResult.ErrorCode.RATE_LIMITED
                else -> TranslationResult.ErrorCode.UNKNOWN
            }
            throw TranslationException(
                "Gemini error: HTTP ${response.code}",
                errorCode,
            )
        }

        val parsed = json.decodeFromString(GenerateResponse.serializer(), responseBody)

        val translation = parsed.candidates
            ?.firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text

        return translation?.trim()?.ifEmpty {
            throw TranslationException(
                "Empty response from Gemini",
                TranslationResult.ErrorCode.UNKNOWN,
            )
        } ?: throw TranslationException(
            "No candidate response",
            TranslationResult.ErrorCode.UNKNOWN,
        )
    }

    private class TranslationException(
        message: String,
        val errorCode: TranslationResult.ErrorCode,
    ) : Exception(message)

    companion object {
        const val ENGINE_ID = 8L
    }
}
