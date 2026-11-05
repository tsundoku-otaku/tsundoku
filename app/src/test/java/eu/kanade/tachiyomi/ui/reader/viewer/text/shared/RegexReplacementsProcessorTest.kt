@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.presentation.reader.settings.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

private fun CountDownLatch.awaitUninterruptibly() {
    while (true) {
        try {
            await()
            return
        } catch (_: InterruptedException) {
        }
    }
}

class RegexReplacementsProcessorTest {

    private fun prefs(rulesJson: String): ReaderPreferences {
        val pref: Preference<String> = mockk { every { get() } returns rulesJson }
        return mockk<ReaderPreferences>().apply {
            every { novelRegexReplacements } returns pref
        }
    }

    @Suppress("ktlint:standard:max-line-length")
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

    @Test
    fun `literal rule treats dollar and backslash in replacement literally`() {
        val rules = rulesJson(rule("price", "$1 \\o/"))
        val result = RegexReplacementsProcessor.apply("price tag", prefs(rules))
        assertEquals("$1 \\o/ tag", result)
    }

    @Test
    fun `worker that never finishes times out`() {
        assertThrows(RegexTimeoutException::class.java) {
            runWithRegexTimeout(50) { Thread.sleep(10_000) }
        }
    }

    @Test
    fun `worker exceptions are rethrown unwrapped`() {
        assertThrows(IllegalStateException::class.java) {
            runWithRegexTimeout(1_000) { error("boom") }
        }
    }

    @Test
    fun `catastrophic pattern times out`() {
        assertThrows(RegexTimeoutException::class.java) {
            Regex(CATASTROPHIC_PATTERN).replaceWithTimeout(CATASTROPHIC_INPUT, "x", timeoutMs = 200)
        }
    }

    @Test
    fun `catastrophic rule is skipped and disabled for the session, other rules still apply`() {
        val rules = rulesJson(
            rule(CATASTROPHIC_PATTERN, "x", isRegex = true),
            rule("a", "b"),
        )
        val previous = RegexReplacementsProcessor.ruleTimeoutMs
        try {
            RegexReplacementsProcessor.ruleTimeoutMs = 200
            assertEquals("b".repeat(40), RegexReplacementsProcessor.apply(CATASTROPHIC_INPUT, prefs(rules)))

            RegexReplacementsProcessor.ruleTimeoutMs = 60_000
            val start = System.nanoTime()
            assertEquals("b".repeat(40), RegexReplacementsProcessor.apply(CATASTROPHIC_INPUT, prefs(rules)))
            assertTrue(System.nanoTime() - start < 5_000_000_000L)
        } finally {
            RegexReplacementsProcessor.ruleTimeoutMs = previous
        }
    }

    @Test
    fun `stuck workers block regex rules only until they finish`() {
        val rules = rulesJson(rule("x+", "y", isRegex = true))
        val release = CountDownLatch(1)
        repeat(MAX_STUCK_REGEX_WORKERS) {
            assertThrows(RegexTimeoutException::class.java) {
                runWithRegexTimeout(20) { release.awaitUninterruptibly() }
            }
        }
        try {
            assertThrows(RegexBusyException::class.java) { runWithRegexTimeout(1_000) {} }
            assertEquals("axxb", RegexReplacementsProcessor.apply("axxb", prefs(rules)))
        } finally {
            release.countDown()
        }
        val deadline = System.nanoTime() + 2_000_000_000L
        var result: String
        do {
            result = RegexReplacementsProcessor.apply("axxb", prefs(rules))
        } while (result != "ayb" && System.nanoTime() < deadline)
        assertEquals("ayb", result)
        awaitNoStuckWorkers()
    }

    @Test
    fun `concurrent rules are never rejected`() {
        val release = CountDownLatch(1)
        val holders = List(8) { thread { runWithRegexTimeout(10_000) { release.await() } } }
        try {
            assertEquals("y", runWithRegexTimeout(1_000) { "y" })
        } finally {
            release.countDown()
            holders.forEach { it.join() }
        }
    }

    @Test
    fun `stack overflow is rethrown`() {
        assertThrows(StackOverflowError::class.java) {
            runWithRegexTimeout(1_000) { throw StackOverflowError() }
        }
    }

    @Test
    fun `replaceWithTimeout matches Regex replace for normal input`() {
        assertEquals("a-b-c", Regex(",").replaceWithTimeout("a,b,c", "-"))
        assertEquals("A,B", Regex("[a-z]").replaceWithTimeout("a,b") { it.value.uppercase() })
    }

    private fun awaitNoStuckWorkers() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (stuckRegexWorkerCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(0, stuckRegexWorkerCount())
    }

    @Test
    fun `timed out catastrophic match frees its worker`() {
        assertThrows(RegexTimeoutException::class.java) {
            Regex("(a+)+\\1d").replaceWithTimeout(CATASTROPHIC_INPUT, "x", timeoutMs = 100)
        }
        awaitNoStuckWorkers()
    }

    @Test
    fun `tasks timed out before they start do not count as stuck`() {
        repeat(200) {
            try {
                runWithRegexTimeout(0) {}
            } catch (_: RegexTimeoutException) {
            } catch (_: RegexBusyException) {
            }
        }
        awaitNoStuckWorkers()
        assertEquals("y", runWithRegexTimeout(1_000) { "y" })
    }

    @Test
    fun `timed out rule stays disabled after being toggled off and on`() {
        val pattern = "(a+)+\\1e"
        val enabled = rulesJson(rule(pattern, "x", isRegex = true), rule("a", "b"))
        val disabled = rulesJson(rule(pattern, "x", isRegex = true, enabled = false), rule("a", "b"))
        val previous = RegexReplacementsProcessor.ruleTimeoutMs
        try {
            RegexReplacementsProcessor.ruleTimeoutMs = 100
            assertEquals("b".repeat(40), RegexReplacementsProcessor.apply(CATASTROPHIC_INPUT, prefs(enabled)))
            assertEquals("b".repeat(40), RegexReplacementsProcessor.apply(CATASTROPHIC_INPUT, prefs(disabled)))

            RegexReplacementsProcessor.ruleTimeoutMs = 60_000
            val start = System.nanoTime()
            assertEquals("b".repeat(40), RegexReplacementsProcessor.apply(CATASTROPHIC_INPUT, prefs(enabled)))
            assertTrue(System.nanoTime() - start < 5_000_000_000L)
        } finally {
            RegexReplacementsProcessor.ruleTimeoutMs = previous
        }
        awaitNoStuckWorkers()
    }

    @Test
    fun `editor test applies the rule like the reader does`() {
        assertEquals("x-x", RegexReplacementsProcessor.test(RegexReplacement("t", "A", "x"), "a-A"))
        assertEquals("$1", RegexReplacementsProcessor.test(RegexReplacement("t", "b", "$1", isRegex = false), "b"))
        assertEquals("[b]", RegexReplacementsProcessor.test(RegexReplacement("t", "(b)", "[$1]"), "b"))
    }

    @Test
    fun `editor test times out and does not rerun a timed out pattern`() {
        val rule = RegexReplacement("t", "(a+)+\\1f", "x")
        val previous = RegexReplacementsProcessor.ruleTimeoutMs
        try {
            RegexReplacementsProcessor.ruleTimeoutMs = 100
            assertThrows(RegexTimeoutException::class.java) {
                RegexReplacementsProcessor.test(rule, CATASTROPHIC_INPUT)
            }

            RegexReplacementsProcessor.ruleTimeoutMs = 60_000
            val start = System.nanoTime()
            assertThrows(RegexDisabledException::class.java) {
                RegexReplacementsProcessor.test(rule, CATASTROPHIC_INPUT)
            }
            assertTrue(System.nanoTime() - start < 1_000_000_000L)
        } finally {
            RegexReplacementsProcessor.ruleTimeoutMs = previous
        }
        awaitNoStuckWorkers()
    }

    private companion object {
        // The backreference defeats the JDK's loop memoization, so this backtracks exponentially on the JVM too.
        const val CATASTROPHIC_PATTERN = "(a+)+\\1b"
        val CATASTROPHIC_INPUT = "a".repeat(40)
    }
}
