package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

internal object NovelWebViewJsAssets {

    private val cache = ConcurrentHashMap<String, String>()

    fun load(context: Context, name: String): String {
        return cache.getOrPut(name) {
            context.assets.open("novel-reader/$name").bufferedReader().use { it.readText() }
        }
    }

    fun loadWith(context: Context, name: String, tokens: Map<String, String>): String {
        var content = load(context, name)
        tokens.forEach { (key, value) -> content = content.replace("__${key}__", value) }
        return content
    }
}
