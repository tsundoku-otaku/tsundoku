package eu.kanade.tachiyomi.data.backup.create.creators

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.service.TranslationPreferences

class PreferenceBackupCreatorTest {

    private val store = InMemoryPreferenceStore()
    private val translation = TranslationPreferences(store)
    private val creator = PreferenceBackupCreator(sourceManager = mockk(relaxed = true), preferenceStore = store)

    init {
        translation.openAiApiKey().set("sk-test")
        translation.ollamaUrl().set("http://user:pass@example.com")
        translation.targetLanguage().set("fr")
    }

    @Test
    fun `translation secrets are left out without sensitive settings`() {
        val keys = creator.createApp(includePrivatePreferences = false).map { it.key }.toSet()

        assertTrue("translation_target_language" in keys)
        assertTrue(keys.none { it in TranslationPreferences.SECRET_KEYS })
    }

    @Test
    fun `translation secrets keep their plain keys with sensitive settings`() {
        val keys = creator.createApp(includePrivatePreferences = true).map { it.key }.toSet()

        assertTrue("translation_openai_api_key" in keys)
        assertTrue("translation_ollama_url" in keys)
    }

    @Test
    fun `every secret key belongs to a translation preference`() {
        assertEquals(emptySet<String>(), TranslationPreferences.SECRET_KEYS - translationKeys())
    }

    @Test
    fun `every credential looking translation key is a secret`() {
        val sensitive = Regex("(^|_)(key|url|headers?|token|secret|password|auth|template)(_|$)")
        val leaked = translationKeys().filter { sensitive.containsMatchIn(it) }.toSet() - TranslationPreferences.SECRET_KEYS

        assertEquals(emptySet<String>(), leaked)
    }

    private fun translationKeys(): Set<String> {
        return TranslationPreferences::class.java.methods
            .filter { it.parameterCount == 0 && Preference::class.java.isAssignableFrom(it.returnType) }
            .map { (it.invoke(translation) as Preference<*>).key() }
            .toSet()
    }
}
