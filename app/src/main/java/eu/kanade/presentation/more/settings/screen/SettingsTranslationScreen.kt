package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.translation.TranslationEngineManager
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsTranslationScreen : SearchableSettings {

    override val supportsReset: Boolean get() = true

    @Composable
    override fun getAdditionalResetPreferences(): List<tachiyomi.core.common.preference.Preference<*>> {
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        return listOf(
            prefs.rateLimitDelayMs(),
            prefs.translationTimeoutMs(),
            prefs.maxParallelTranslations(),
        )
    }

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = TDMR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val translationPreferences = remember { Injekt.get<TranslationPreferences>() }
        val engineManager = remember { Injekt.get<TranslationEngineManager>() }
        val translationService = remember { Injekt.get<eu.kanade.tachiyomi.data.translation.TranslationService>() }
        val navigator = LocalNavigator.currentOrThrow

        val progress by translationService.progressState.collectAsState()
        val isPaused by translationService.isPaused.collectAsState()
        val queueStatusText = when {
            progress.isCancelling -> stringResource(MR.strings.pref_translation_status_cancelling)
            progress.isRunning && isPaused -> stringResource(MR.strings.pref_translation_status_paused, progress.completedChapters, progress.totalChapters)
            progress.isRunning -> stringResource(MR.strings.pref_translation_status_translating, progress.currentChapterName ?: "...", "", progress.completedChapters, progress.totalChapters)
            else -> stringResource(MR.strings.pref_translation_status_idle)
        }

        return listOf(
            getGeneralGroup(translationPreferences, engineManager),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_queue),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_queue),
                        subtitle = queueStatusText,
                        onClick = { navigator.push(eu.kanade.tachiyomi.ui.setting.TranslationQueueScreen) },
                    ),
                ),
            ),
            getRateLimitGroup(translationPreferences),
        ) + getEngineConfigGroups(translationPreferences, engineManager)
    }

    @Composable
    private fun getGeneralGroup(
        prefs: TranslationPreferences,
        engineManager: TranslationEngineManager,
    ): Preference.PreferenceGroup {
        val enabled by prefs.translationEnabled().collectAsState()
        val selectedEngineId by prefs.selectedEngineId().collectAsState()
        val sourceLanguage by prefs.sourceLanguage().collectAsState()
        val targetLanguage by prefs.targetLanguage().collectAsState()
        val chunkSize by prefs.translationChunkSize().collectAsState()
        val anchoringEnabled by prefs.contextualAnchoringEnabled().collectAsState()
        val anchoringParagraphs by prefs.contextualAnchoringParagraphs().collectAsState()

        val engines = engineManager.engines
        val engineEntries = engines.associate { it.id.toString() to it.name }.toImmutableMap()

        val selectedEngine = engines.find { it.id == selectedEngineId } ?: engines.first()
        val languageEntries = selectedEngine.supportedLanguages.associate { it.first to it.second }.toImmutableMap()

        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_translation_general),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.translationEnabled(),
                    title = stringResource(TDMR.strings.pref_translation_enabled),
                    subtitle = stringResource(TDMR.strings.pref_translation_enabled_summary),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.selectedEngineId(),
                    title = stringResource(TDMR.strings.pref_translation_engine),
                    subtitle = selectedEngine.name + if (selectedEngine.isOffline) stringResource(MR.strings.pref_translation_offline_suffix) else "",
                    entries = engineEntries.mapKeys { it.key.toLong() }.toImmutableMap(),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.BasicListPreference(
                    value = sourceLanguage,
                    title = stringResource(TDMR.strings.pref_translation_source_language),
                    subtitle = languageEntries[sourceLanguage] ?: sourceLanguage,
                    entries = languageEntries,
                    onValueChanged = { newValue ->
                        prefs.sourceLanguage().set(newValue)
                        true
                    },
                    enabled = enabled,
                ),
                Preference.PreferenceItem.BasicListPreference(
                    value = targetLanguage,
                    title = stringResource(TDMR.strings.pref_translation_target_language),
                    subtitle = languageEntries[targetLanguage] ?: targetLanguage,
                    entries = languageEntries.filterKeys { it != "auto" }.toImmutableMap(),
                    onValueChanged = { newValue ->
                        prefs.targetLanguage().set(newValue)
                        true
                    },
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.autoDownloadBeforeTranslate(),
                    title = stringResource(TDMR.strings.pref_translation_auto_download),
                    subtitle = stringResource(TDMR.strings.pref_translation_auto_download_summary),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.autoTranslateDownloads(),
                    title = stringResource(TDMR.strings.pref_translation_auto_translate),
                    subtitle = stringResource(TDMR.strings.pref_translation_auto_translate_summary),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.replaceTitle(),
                    title = stringResource(MR.strings.pref_translation_replace_title),
                    subtitle = stringResource(MR.strings.pref_translation_replace_title_desc),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.saveTranslatedTitleAsAlternative(),
                    title = stringResource(MR.strings.pref_translation_save_alt_titles),
                    subtitle = stringResource(MR.strings.pref_translation_save_alt_titles_desc),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.translateTags(),
                    title = stringResource(MR.strings.pref_translation_translate_tags),
                    subtitle = stringResource(MR.strings.pref_translation_translate_tags_desc),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.smartAutoTranslate(),
                    title = stringResource(MR.strings.pref_translation_smart_auto),
                    subtitle = stringResource(MR.strings.pref_translation_smart_auto_desc),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = chunkSize,
                    valueRange = 1..500,
                    title = stringResource(MR.strings.pref_translation_chunk_size),
                    subtitle = stringResource(MR.strings.pref_translation_chunk_paragraphs_rec),
                    valueString = "$chunkSize ${stringResource(MR.strings.pref_translation_chunk_paragraphs)}",
                    onValueChanged = { prefs.translationChunkSize().set(it) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.contextualAnchoringEnabled(),
                    title = stringResource(MR.strings.pref_translation_contextual_anchoring),
                    subtitle = stringResource(MR.strings.pref_translation_contextual_anchoring_desc),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = anchoringParagraphs,
                    valueRange = 1..10,
                    title = stringResource(MR.strings.pref_translation_contextual_anchoring_paragraphs),
                    subtitle = stringResource(MR.strings.pref_translation_contextual_anchoring_paragraphs_desc),
                    valueString = "$anchoringParagraphs",
                    onValueChanged = { prefs.contextualAnchoringParagraphs().set(it) },
                    enabled = anchoringEnabled && enabled,
                ),
            ),
        )
    }

    @Composable
    private fun getRateLimitGroup(
        prefs: TranslationPreferences,
    ): Preference.PreferenceGroup {
        val rateLimitDelay by prefs.rateLimitDelayMs().collectAsState()
        val maxParallel by prefs.maxParallelTranslations().collectAsState()
        val timeoutMs by prefs.translationTimeoutMs().collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(TDMR.strings.pref_translation_rate_limit),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SliderPreference(
                    value = rateLimitDelay,
                    valueRange = 500..10000,
                    title = stringResource(TDMR.strings.pref_translation_rate_limit_delay),
                    subtitle = stringResource(TDMR.strings.pref_translation_rate_limit_delay_summary),
                    valueString = "${rateLimitDelay}ms",
                    onValueChanged = { prefs.rateLimitDelayMs().set(it) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = (timeoutMs / 1000).toInt(),
                    valueRange = 30..300,
                    title = stringResource(TDMR.strings.pref_translation_timeout),
                    subtitle = stringResource(TDMR.strings.pref_translation_timeout_summary),
                    valueString = "${timeoutMs / 1000}s",
                    onValueChanged = { prefs.translationTimeoutMs().set(it.toLong() * 1000) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = maxParallel,
                    valueRange = 1..10,
                    title = stringResource(TDMR.strings.pref_translation_max_parallel),
                    subtitle = stringResource(TDMR.strings.pref_translation_max_parallel_summary),
                    valueString = "$maxParallel",
                    onValueChanged = { prefs.maxParallelTranslations().set(it) },
                ),
            ),
        )
    }

    @Composable
    private fun getEngineConfigGroups(
        prefs: TranslationPreferences,
        engineManager: TranslationEngineManager,
    ): List<Preference.PreferenceGroup> {
        val sourceLanguage by prefs.sourceLanguage().collectAsState()
        val targetLanguage by prefs.targetLanguage().collectAsState()
        val scope = rememberCoroutineScope()

        // Per-engine test state
        val testResults = remember { mutableMapOf<Long, String>() }
        var testingEngineId by remember { mutableStateOf<Long?>(null) }

        // Collect all preference states
        val openAiKey by prefs.openAiApiKey().collectAsState()
        val deepSeekKey by prefs.deepSeekApiKey().collectAsState()
        val systranKey by prefs.systranApiKey().collectAsState()
        val deepLKey by prefs.deepLApiKey().collectAsState()
        val googleKey by prefs.googleApiKey().collectAsState()
        val libreTranslateUrl by prefs.libreTranslateUrl().collectAsState()
        val libreTranslateKey by prefs.libreTranslateApiKey().collectAsState()
        val ollamaUrl by prefs.ollamaUrl().collectAsState()
        val ollamaModel by prefs.ollamaModel().collectAsState()
        val customHttpUrl by prefs.customHttpUrl().collectAsState()
        val customHttpApiKey by prefs.customHttpApiKey().collectAsState()
        val customHttpResponsePath by prefs.customHttpResponsePath().collectAsState()

        // Pre-resolve strings for non-composable testButton function
        val testEngineFormat = stringResource(MR.strings.pref_translation_test_engine)
        val testingStr = stringResource(MR.strings.pref_translation_testing)
        val testSendStr = stringResource(MR.strings.pref_translation_test_send)
        val testTextStr = stringResource(MR.strings.pref_translation_test_text)

        fun testButton(engine: tachiyomi.domain.translation.model.TranslationEngine): Preference.PreferenceItem.TextPreference {
            val engineId = engine.id
            return Preference.PreferenceItem.TextPreference(
                title = testEngineFormat.format(engine.name),
                subtitle = when {
                    testingEngineId == engineId -> testingStr
                    testResults.containsKey(engineId) -> testResults[engineId]!!
                    else -> testSendStr
                },
                enabled = testingEngineId == null,
                onClick = {
                    testingEngineId = engineId
                    testResults.remove(engineId)
                    scope.launch {
                        try {
                            val result = engine.translate(
                                listOf(testTextStr),
                                sourceLanguage,
                                targetLanguage,
                            )
                            testResults[engineId] = when (result) {
                                is TranslationResult.Success ->
                                    "✓ ${result.translatedTexts.joinToString(" | ")}"
                                is TranslationResult.Error ->
                                    "✗ ${result.message}"
                            }
                        } catch (e: Exception) {
                            testResults[engineId] = "✗ ${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            testingEngineId = null
                        }
                    }
                },
            )
        }

        return listOf(
            // OpenAI
            Preference.PreferenceGroup(
                title = "OpenAI",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.openAiApiKey(),
                        title = stringResource(TDMR.strings.pref_translation_openai_key),
                        subtitle = if (openAiKey.isNotBlank()) "••••••••" else stringResource(TDMR.strings.not_set),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.openAiSystemPrompt(),
                        title = stringResource(MR.strings.pref_translation_system_prompt),
                        subtitle = stringResource(MR.strings.pref_translation_system_prompt_desc),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.openAiUserPrompt(),
                        title = stringResource(MR.strings.pref_translation_user_prompt),
                        subtitle = stringResource(MR.strings.pref_translation_user_prompt_desc),
                    ),
                    testButton(engineManager.engines.first { it.name.contains("OpenAI") }),
                ),
            ),
            // DeepSeek
            Preference.PreferenceGroup(
                title = "DeepSeek",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.deepSeekApiKey(),
                        title = stringResource(TDMR.strings.pref_translation_deepseek_key),
                        subtitle = if (deepSeekKey.isNotBlank()) "••••••••" else stringResource(TDMR.strings.not_set),
                    ),
                    testButton(engineManager.engines.first { it.name.contains("DeepSeek") }),
                ),
            ),
            // DeepL
            Preference.PreferenceGroup(
                title = "DeepL",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.deepLApiKey(),
                        title = stringResource(MR.strings.pref_translation_api_key),
                        subtitle = if (deepLKey.isNotBlank()) "••••••••" else stringResource(TDMR.strings.not_set),
                    ),
                    testButton(engineManager.engines.first { it.name.contains("DeepL") }),
                ),
            ),
            // SYSTRAN
            Preference.PreferenceGroup(
                title = "SYSTRAN",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.systranApiKey(),
                        title = stringResource(MR.strings.pref_translation_api_key),
                        subtitle = if (systranKey.isNotBlank()) "••••••••" else stringResource(TDMR.strings.not_set),
                    ),
                    testButton(engineManager.engines.first { it.name.contains("SYSTRAN") }),
                ),
            ),
            // Google Translate (Paid)
            Preference.PreferenceGroup(
                title = "Google Translate",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.googleApiKey(),
                        title = stringResource(MR.strings.pref_translation_api_key),
                        subtitle = if (googleKey.isNotBlank()) "••••••••" else stringResource(TDMR.strings.not_set),
                    ),
                    testButton(engineManager.engines.first { it.name == "Google Translate" }),
                ),
            ),
            // Google Translate Free (Scraper)
            Preference.PreferenceGroup(
                title = "Google Translate (Free)",
                preferenceItems = persistentListOf(
                    testButton(engineManager.engines.first { it.name.contains("Scraper") }),
                ),
            ),
            // LibreTranslate
            Preference.PreferenceGroup(
                title = "LibreTranslate",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.libreTranslateUrl(),
                        title = stringResource(TDMR.strings.pref_translation_libretranslate_url),
                        subtitle = libreTranslateUrl,
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.libreTranslateApiKey(),
                        title = stringResource(MR.strings.pref_translation_api_key),
                        subtitle = if (libreTranslateKey.isNotBlank()) "••••••••" else stringResource(TDMR.strings.not_set),
                    ),
                    testButton(engineManager.engines.first { it.name.contains("Libre") }),
                ),
            ),
            // Ollama
            Preference.PreferenceGroup(
                title = "Ollama",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.ollamaUrl(),
                        title = stringResource(TDMR.strings.pref_translation_ollama_url),
                        subtitle = ollamaUrl,
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.ollamaModel(),
                        title = stringResource(TDMR.strings.pref_translation_ollama_model),
                        subtitle = ollamaModel,
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.ollamaPrompt(),
                        title = stringResource(MR.strings.pref_translation_custom_prompt),
                        subtitle = stringResource(MR.strings.pref_translation_user_prompt_desc),
                    ),
                    testButton(engineManager.engines.first { it.name.contains("Ollama") }),
                ),
            ),
            // HuggingFace
            Preference.PreferenceGroup(
                title = "HuggingFace",
                preferenceItems = persistentListOf(
                    testButton(engineManager.engines.first { it.name.contains("Hugging") }),
                ),
            ),
            // Custom HTTP
            Preference.PreferenceGroup(
                title = "Custom HTTP",
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.customHttpUrl(),
                        title = stringResource(MR.strings.pref_translation_api_url),
                        subtitle = if (customHttpUrl.isNotBlank()) customHttpUrl else stringResource(TDMR.strings.not_set),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.customHttpApiKey(),
                        title = stringResource(MR.strings.pref_translation_api_key),
                        subtitle = if (customHttpApiKey.isNotBlank()) "••••••••" else stringResource(TDMR.strings.not_set),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.customHttpRequestTemplate(),
                        title = stringResource(MR.strings.pref_translation_request_template),
                        subtitle = stringResource(MR.strings.pref_translation_request_template_desc),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.customHttpResponsePath(),
                        title = stringResource(MR.strings.pref_translation_response_path),
                        subtitle = customHttpResponsePath.ifBlank { "translatedText" },
                    ),
                    testButton(engineManager.engines.first { it.name.contains("Custom") }),
                ),
            ),
        )
    }
}
