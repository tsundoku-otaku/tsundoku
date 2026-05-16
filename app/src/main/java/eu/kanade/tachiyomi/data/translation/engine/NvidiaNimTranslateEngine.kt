package eu.kanade.tachiyomi.data.translation.engine

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
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

/**
 * NVIDIA NIM translation engine.
 * Uses the OpenAI-compatible chat completions API exposed by NIM.
 */
class NvidiaNimTranslateEngine(
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val preferences: TranslationPreferences = Injekt.get(),
) : TranslationEngine {

    private val client: OkHttpClient get() = networkHelper.client

    override val id: Long = ENGINE_ID
    override val name: String = "NVIDIA NIM"
    override val requiresApiKey: Boolean = false
    override val isRateLimited: Boolean = true
    override val isOffline: Boolean = false

    override val supportedLanguages: List<Pair<String, String>> = LanguageCodes.COMMON_LANGUAGES

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Double = 0.3,
        @SerialName("max_tokens")
        val maxTokens: Int = 4096,
    )

    @Serializable
    private data class Message(
        val role: String,
        val content: String,
    )

    @Serializable
    private data class ChatResponse(
        val choices: List<Choice> = emptyList(),
        val error: ErrorInfo? = null,
    )

    @Serializable
    private data class Choice(
        val message: Message,
    )

    @Serializable
    private data class ErrorInfo(
        val message: String,
        val type: String? = null,
        val code: String? = null,
    )

    override fun isConfigured(): Boolean {
        return preferences.nvidiaNimBaseUrl().get().isNotBlank() &&
            preferences.nvidiaNimModel().get().isNotBlank()
    }

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationResult = withContext(Dispatchers.IO) {
        val baseUrl = preferences.nvidiaNimBaseUrl().get().trimEnd('/')
        val model = preferences.nvidiaNimModel().get().trim()
        val apiKey = preferences.nvidiaNimApiKey().get().trim()

        if (baseUrl.isBlank()) {
            return@withContext TranslationResult.Error(
                "NVIDIA NIM base URL not configured",
                TranslationResult.ErrorCode.API_KEY_MISSING,
            )
        }

        if (model.isBlank()) {
            return@withContext TranslationResult.Error(
                "NVIDIA NIM model not configured",
                TranslationResult.ErrorCode.API_KEY_MISSING,
            )
        }

        try {
            val translatedTexts = texts.map { text ->
                translateSingleText(baseUrl, model, apiKey, text, sourceLanguage, targetLanguage)
            }

            TranslationResult.Success(translatedTexts)
        } catch (e: TranslationException) {
            TranslationResult.Error(e.message ?: "Translation failed", e.errorCode)
        } catch (e: Exception) {
            TranslationResult.Error(
                e.message ?: "Unknown error",
                TranslationResult.ErrorCode.UNKNOWN,
            )
        }
    }

    private suspend fun translateSingleText(
        baseUrl: String,
        model: String,
        apiKey: String,
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

        val systemPrompt = """You are a professional translator specializing in novel/fiction translation. $fromClause
Rules:
- Only output the translation, nothing else
- Preserve paragraph structure
- Maintain the author's writing style and tone
- Keep character names consistent
- Do not add explanations or notes"""

        val request = ChatRequest(
            model = model,
            messages = listOf(
                Message(role = "system", content = systemPrompt),
                Message(role = "user", content = text),
            ),
        )

        val requestBody = json.encodeToString(ChatRequest.serializer(), request)
        val apiUrl = buildApiUrl(baseUrl)

        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        val responseBody = response.use { it.body.string() }

        if (!response.isSuccessful) {
            val errorCode = when (response.code) {
                401, 403 -> TranslationResult.ErrorCode.API_KEY_INVALID
                429 -> TranslationResult.ErrorCode.RATE_LIMITED
                503 -> TranslationResult.ErrorCode.SERVICE_UNAVAILABLE
                402 -> TranslationResult.ErrorCode.QUOTA_EXCEEDED
                else -> TranslationResult.ErrorCode.UNKNOWN
            }

            val errorMessage = try {
                val errorResponse = json.decodeFromString(ChatResponse.serializer(), responseBody)
                errorResponse.error?.message ?: "HTTP ${response.code}"
            } catch (_: Exception) {
                "HTTP ${response.code}: $responseBody"
            }

            throw TranslationException(errorMessage, errorCode)
        }

        val chatResponse = json.decodeFromString(ChatResponse.serializer(), responseBody)
        return chatResponse.choices.firstOrNull()?.message?.content?.trim()
            ?: throw TranslationException("Empty response from NVIDIA NIM", TranslationResult.ErrorCode.UNKNOWN)
    }

    private fun buildApiUrl(baseUrl: String): String {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        return if (normalizedBaseUrl.endsWith("/v1")) {
            "$normalizedBaseUrl/chat/completions"
        } else {
            "$normalizedBaseUrl/v1/chat/completions"
        }
    }

    private class TranslationException(
        message: String,
        val errorCode: TranslationResult.ErrorCode,
    ) : Exception(message)

    companion object {
        const val ENGINE_ID = 11L
    }
}