package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class RegexReplacementsProcessorTest {

    private fun prefs(rulesJson: String): ReaderPreferences {
        val pref: Preference<String> = mockk { every { get() } returns rulesJson }
        return mockk<ReaderPreferences>().apply {
            every { novelRegexReplacements } returns pref
        }
    }

    private fun rule(
        pattern: String,
        replacement: String,
        isRegex: Boolean = false,
        caseSensitive: Boolean = false,
        matchWholeWord: Boolean = false,
        enabled: Boolean = true,
        title: String = "r",
    ): String {
        // JSON strings only support a fixed escape set; backslashes in the pattern
        // (e.g. `\w`, `\d`) must be doubled so the JSON decoder gives the regex
        // engine back a single backslash.
        val p = pattern.replace("\\", "\\\\").replace("\"", "\\\"")
        val r = replacement.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"title":"$title","pattern":"$p","replacement":"$r","isRegex":$isRegex,"enabled":$enabled,"caseSensitive":$caseSensitive,"matchWholeWord":$matchWholeWord}"""
    }

    private fun rulesJson(vararg rules: String): String = rules.joinToString(prefix = "[", postfix = "]")

    @Test
    fun `empty rules list returns input unchanged`() {
        val result = RegexReplacementsProcessor.apply("hello world", prefs("[]"))
        assertEquals("hello world", result)
    }

    @Test
    fun `blank rules string returns input unchanged`() {
        val result = RegexReplacementsProcessor.apply("hello world", prefs(""))
        assertEquals("hello world", result)
    }

    @Test
    fun `plain literal rule replaces all matches`() {
        val rules = rulesJson(rule("foo", "bar"))
        val result = RegexReplacementsProcessor.apply("foo foo baz", prefs(rules))
        assertEquals("bar bar baz", result)
    }

    @Test
    fun `disabled rule is skipped`() {
        val rules = rulesJson(rule("foo", "bar", enabled = false))
        val result = RegexReplacementsProcessor.apply("foo baz", prefs(rules))
        assertEquals("foo baz", result)
    }

    @Test
    fun `blank pattern is skipped`() {
        val rules = rulesJson(rule("", "bar"))
        val result = RegexReplacementsProcessor.apply("foo baz", prefs(rules))
        assertEquals("foo baz", result)
    }

    @Test
    fun `case-insensitive literal matches different cases`() {
        val rules = rulesJson(rule("foo", "bar", caseSensitive = false))
        val result = RegexReplacementsProcessor.apply("Foo FOO foo", prefs(rules))
        assertEquals("bar bar bar", result)
    }

    @Test
    fun `case-sensitive literal matches exact case only`() {
        val rules = rulesJson(rule("foo", "bar", caseSensitive = true))
        val result = RegexReplacementsProcessor.apply("Foo FOO foo", prefs(rules))
        assertEquals("Foo FOO bar", result)
    }

    @Test
    fun `match whole word does not match substring`() {
        val rules = rulesJson(rule("cat", "dog", matchWholeWord = true))
        val result = RegexReplacementsProcessor.apply("cats are like a cat", prefs(rules))
        assertEquals("cats are like a dog", result)
    }

    @Test
    fun `regex pattern with capture groups`() {
        val rules = rulesJson(rule("(\\w+)@(\\w+)", "$2@$1", isRegex = true))
        val result = RegexReplacementsProcessor.apply("alice@example", prefs(rules))
        assertEquals("example@alice", result)
    }

    @Test
    fun `regex with character class`() {
        val rules = rulesJson(rule("\\d+", "N", isRegex = true))
        val result = RegexReplacementsProcessor.apply("chapter 12 of 345", prefs(rules))
        assertEquals("chapter N of N", result)
    }

    @Test
    fun `multiple rules apply in declaration order`() {
        val rules = rulesJson(
            rule("foo", "bar"),
            rule("bar", "baz"),
        )
        val result = RegexReplacementsProcessor.apply("foo", prefs(rules))
        // foo → bar (rule 1), bar → baz (rule 2). Both rules see the result of the previous.
        assertEquals("baz", result)
    }

    @Test
    fun `invalid regex pattern is skipped, other rules still apply`() {
        val rules = rulesJson(
            rule("[unclosed", "x", isRegex = true),
            rule("foo", "bar"),
        )
        val result = RegexReplacementsProcessor.apply("foo", prefs(rules))
        assertEquals("bar", result)
    }

    @Test
    fun `whole-word pattern with special chars escaped`() {
        val rules = rulesJson(rule("a.b", "X", matchWholeWord = true))
        // Should match the literal "a.b", not any character between a and b
        val result = RegexReplacementsProcessor.apply("a.b azb a.b.c", prefs(rules))
        assertEquals("X azb X.c", result)
    }

    @Test
    fun `replacement with dollar sign in regex mode is treated as group reference`() {
        val rules = rulesJson(rule("(foo)", "[$1]", isRegex = true))
        val result = RegexReplacementsProcessor.apply("foo", prefs(rules))
        assertEquals("[foo]", result)
    }
}
