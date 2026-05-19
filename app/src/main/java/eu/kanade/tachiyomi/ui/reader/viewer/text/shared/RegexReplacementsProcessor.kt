package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.presentation.reader.settings.RegexReplacement
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import logcat.logcat

/**
 * Applies the user-defined Find / Replace rules stored as JSON in
 * [ReaderPreferences.novelRegexReplacements]. Compiled patterns are cached.
 */
object RegexReplacementsProcessor {

    // LRU cache capped at 16 entries. A user rarely has more than a handful of
    // distinct rule-set JSONs; an unbounded ConcurrentHashMap would grow forever
    // if rules are edited repeatedly.
    private val cache: MutableMap<String, List<Pair<Regex, String>>> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, List<Pair<Regex, String>>>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, List<Pair<Regex, String>>>,
                ) = size > 16
            },
        )

    /**
     * Applies all enabled rules to [content] in declaration order.
     */
    fun apply(content: String, preferences: ReaderPreferences): String {
        val rulesJson = preferences.novelRegexReplacements.get()
        if (rulesJson.isBlank() || rulesJson == "[]") return content

        val compiled = synchronized(cache) {
            cache.computeIfAbsent(rulesJson) { json ->
                try {
                    val rules: List<RegexReplacement> = kotlinx.serialization.json.Json.decodeFromString(json)
                    rules.mapNotNull { rule ->
                        if (!rule.enabled || rule.pattern.isBlank()) return@mapNotNull null
                        try {
                            val options = if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                            if (rule.isRegex) {
                                Regex(rule.pattern, options) to rule.replacement
                            } else {
                                val escapedPattern = Regex.escape(rule.pattern)
                                val boundedPattern = if (rule.matchWholeWord) {
                                    "(?<![\\p{L}\\p{N}_])(?:$escapedPattern)(?![\\p{L}\\p{N}_])"
                                } else {
                                    escapedPattern
                                }
                                Regex(boundedPattern, options) to rule.replacement
                            }
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

        var result = content
        for ((regex, replacement) in compiled) {
            try {
                result = regex.replace(result, replacement)
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Regex replacement failed: ${e.message}" }
            }
        }
        return result
    }
}
