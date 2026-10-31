package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.presentation.reader.settings.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import logcat.logcat
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

object RegexReplacementsProcessor {

    private class CompiledRule(
        val regex: Regex,
        val replacement: String,
        val isRegex: Boolean,
    ) {
        val key: String get() = "${regex.options.sorted()}\u0000${regex.pattern}"
    }

    @Volatile
    internal var ruleTimeoutMs: Long = DEFAULT_REGEX_TIMEOUT_MS

    private val timedOutRules: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val cache: MutableMap<String, List<CompiledRule>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, List<CompiledRule>>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, List<CompiledRule>>,
                ) = size > 16
            },
        )

    fun apply(content: String, preferences: ReaderPreferences): String {
        val rulesJson = preferences.novelRegexReplacements.get()
        if (rulesJson.isBlank() || rulesJson == "[]") return content

        val compiled = synchronized(cache) {
            cache[rulesJson] ?: compile(rulesJson).also { cache[rulesJson] = it }
        }

        var result = content
        for (rule in compiled) {
            if (rule.isRegex && rule.key in timedOutRules) continue
            try {
                result = run(rule, result)
            } catch (e: RegexTimeoutException) {
                logcat(LogPriority.WARN) {
                    "Regex '${rule.regex.pattern}' timed out, disabled until its pattern changes"
                }
            } catch (e: StackOverflowError) {
                logcat(LogPriority.WARN) {
                    "Regex '${rule.regex.pattern}' overflowed the stack, disabled until its pattern changes"
                }
            } catch (e: RegexBusyException) {
                logcat(LogPriority.WARN) { "Regex '${rule.regex.pattern}' skipped, timed out rules still running" }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return result
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Regex replacement failed: ${e.message}" }
            }
        }
        return result
    }

    /**
     * Runs [rule] on [input] the way [apply] does, for the rule editor. Blocks for up to the rule timeout, so call
     * it off the main thread. Throws [RegexTimeoutException] for a pattern that timed out now or earlier.
     */
    fun test(rule: RegexReplacement, input: String): String {
        val compiled = compileRule(rule)
        if (compiled.isRegex && compiled.key in timedOutRules) throw RegexTimeoutException()
        return run(compiled, input)
    }

    private fun run(rule: CompiledRule, input: String): String {
        if (!rule.isRegex) return rule.regex.replace(input, rule.replacement)
        try {
            return rule.regex.replaceWithTimeout(input, rule.replacement, ruleTimeoutMs)
        } catch (e: RegexTimeoutException) {
            timedOutRules.add(rule.key)
            throw e
        } catch (e: StackOverflowError) {
            timedOutRules.add(rule.key)
            throw e
        }
    }

    private fun compileRule(rule: RegexReplacement): CompiledRule {
        val options = if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val compiledRegex = if (rule.isRegex) {
            Regex(rule.pattern, options)
        } else {
            val escapedPattern = Regex.escape(rule.pattern)
            val boundedPattern = if (rule.matchWholeWord) {
                "(?<![\\p{L}\\p{N}_])(?:$escapedPattern)(?![\\p{L}\\p{N}_])"
            } else {
                escapedPattern
            }
            Regex(boundedPattern, options)
        }
        val replacement = if (rule.isRegex) rule.replacement else Regex.escapeReplacement(rule.replacement)
        return CompiledRule(compiledRegex, replacement, rule.isRegex)
    }

    private fun compile(json: String): List<CompiledRule> {
        return try {
            val rules: List<RegexReplacement> = kotlinx.serialization.json.Json.decodeFromString(json)
            rules.mapNotNull { rule ->
                if (!rule.enabled || rule.pattern.isBlank()) return@mapNotNull null
                try {
                    compileRule(rule)
                } catch (e: Exception) {
                    logcat(LogPriority.WARN) { "Failed to compile regex for '${rule.title}': ${e.message}" }
                    null
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Failed to parse regex replacements: ${e.message}" }
            emptyList()
        }
    }
}
