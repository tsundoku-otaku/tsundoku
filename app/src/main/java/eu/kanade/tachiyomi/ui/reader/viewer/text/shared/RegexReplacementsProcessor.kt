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
        val key: String get() = "${regex.options.sorted()}\u0000${regex.pattern}\u0000$replacement"
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
            cache[rulesJson] ?: compile(rulesJson).also { rules ->
                cache[rulesJson] = rules
                // A rule that was edited, or turned off and on again, gets another chance.
                timedOutRules.retainAll(rules.filter { it.isRegex }.mapTo(HashSet()) { it.key })
            }
        }

        var result = content
        for (rule in compiled) {
            if (rule.isRegex && rule.key in timedOutRules) continue
            try {
                result = if (rule.isRegex) {
                    rule.regex.replaceWithTimeout(result, rule.replacement, ruleTimeoutMs)
                } else {
                    rule.regex.replace(result, rule.replacement)
                }
            } catch (e: RegexTimeoutException) {
                timedOutRules.add(rule.key)
                logcat(LogPriority.WARN) { "Regex '${rule.regex.pattern}' timed out, disabled until the rules change" }
            } catch (e: StackOverflowError) {
                timedOutRules.add(rule.key)
                logcat(LogPriority.WARN) {
                    "Regex '${rule.regex.pattern}' overflowed the stack, disabled until the rules change"
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

    private fun compile(json: String): List<CompiledRule> {
        return try {
            val rules: List<RegexReplacement> = kotlinx.serialization.json.Json.decodeFromString(json)
            rules.mapNotNull { rule ->
                if (!rule.enabled || rule.pattern.isBlank()) return@mapNotNull null
                try {
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
                    val replacement = if (rule.isRegex) {
                        rule.replacement
                    } else {
                        Regex.escapeReplacement(rule.replacement)
                    }
                    CompiledRule(compiledRegex, replacement, rule.isRegex)
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
