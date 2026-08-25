package tachiyomi.domain.translation.service

/**
 * Shared default prompt text for LLM-based translation engines.
 * Placeholders: {SOURCE_LANG}, {TARGET_LANG}, {TEXT}.
 */
object TranslationPromptDefaults {

    const val DEFAULT_SYSTEM_PROMPT = """You are a professional translator specializing in novel/fiction translation. Translate the following text from {SOURCE_LANG} to {TARGET_LANG}.
Rules:
- Only output the translation, nothing else
- Preserve paragraph structure (keep empty lines between paragraphs)
- Do not summarize, merge, or split paragraphs
- Preserve every line break exactly as in the input; do not wrap lines or normalize whitespace
- Maintain the author's writing style and tone
- Keep character names consistent
- Do not add explanations or notes
- Copy tokens like [IMG_PLACEHOLDER_0] verbatim — do not translate or alter them"""

    const val DEFAULT_USER_PROMPT = "{TEXT}"

    /** Combined single-string default for completion-style APIs that send one prompt (e.g. Ollama). */
    val DEFAULT_COMBINED_PROMPT = """$DEFAULT_SYSTEM_PROMPT

Text to translate:
$DEFAULT_USER_PROMPT

Translation:"""

    /** Display text for {SOURCE_LANG} when the source language is auto-detected. */
    fun sourceLangDisplay(sourceLanguage: String, sourceLangName: String): String {
        return if (sourceLanguage == "auto") "the automatically-detected source language" else sourceLangName
    }

    fun apply(template: String, sourceLangDisplay: String, targetLangName: String, text: String? = null): String {
        var result = template
            .replace("{SOURCE_LANG}", sourceLangDisplay)
            .replace("{TARGET_LANG}", targetLangName)
        if (text != null) result = result.replace("{TEXT}", text)
        return result
    }
}
