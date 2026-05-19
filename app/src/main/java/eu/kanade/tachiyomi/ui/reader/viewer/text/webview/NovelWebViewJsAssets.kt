package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads JS scripts shipped under `assets/novel-reader/` into memory so the
 * WebView reader can `evaluateJavascript` them.
 *
 * Storing scripts as `.js` files (instead of inline Kotlin triple-quoted
 * strings) gives the IDE proper JS syntax highlighting, lint, and
 * find-usages on identifiers. The runtime cost is one mmap'd read per
 * script the first time it's needed — subsequent calls hit the in-memory
 * cache. Compared to inline Kotlin strings, the only added cost is the
 * initial asset read (microseconds) plus the token-replacement pass
 * (linear in script length). WebView's `evaluateJavascript` cost is
 * dominated by JS parse + execute and is unchanged.
 *
 * Conventions:
 * - Files live in `app/src/main/assets/novel-reader/<name>.js`.
 * - Placeholders are `__TOKEN_NAME__` (double underscores, JS-safe
 *   identifier shape, visually distinct from real JS identifiers).
 * - String tokens that need quoting must include their own quotes in the
 *   replacement value (e.g. `"foo bar"` not `foo bar`).
 *
 * Cache invalidation: the in-memory cache is intentionally unbounded and has
 * no TTL. Assets are compiled into the APK and cannot change at runtime, so
 * staleness is impossible in production. During development (hot-swap /
 * instant-run) the cache will serve the pre-swap version — restart the app
 * to pick up changed assets.
 */
internal object NovelWebViewJsAssets {

    private val cache = ConcurrentHashMap<String, String>()

    /** Load the script verbatim (no token substitution). */
    fun load(context: Context, name: String): String {
        return cache.getOrPut(name) {
            context.assets.open("novel-reader/$name").bufferedReader().use { it.readText() }
        }
    }

    /**
     * Load the script and substitute each `__KEY__` placeholder with the
     * matching value. Both the file content and the token map are cached
     * via [load]'s memoization — only the per-call string replace runs
     * each time.
     */
    fun loadWith(context: Context, name: String, tokens: Map<String, String>): String {
        var content = load(context, name)
        tokens.forEach { (key, value) -> content = content.replace("__${key}__", value) }
        return content
    }
}
