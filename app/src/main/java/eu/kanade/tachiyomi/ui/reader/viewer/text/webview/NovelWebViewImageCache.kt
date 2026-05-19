package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk-backed cache for chapter-archive images served to the WebView via the
 * `tsundoku-novel-image://` scheme.
 *
 * The WebView's WebViewClient calls [intercept] for every resource request;
 * matches against [URL_SCHEME_NOVEL_IMAGE] are answered from the on-disk cache
 * if available, otherwise a background prefetch is queued so the image is
 * ready on the next request. [schedulePrefetch] walks freshly-loaded chapter
 * HTML for the same scheme and queues every image it finds.
 *
 * Lifecycle: call [clear] when the viewer is destroyed or the chapter list
 * changes — cancels in-flight prefetches and deletes the on-disk files.
 */
internal class NovelWebViewImageCache(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val cache = ConcurrentHashMap<String, File>()
    private val prefetchJobs = ConcurrentHashMap<String, Job>()

    /**
     * If [url] matches the novel-image scheme and the cache has a file for it,
     * return a [WebResourceResponse] that the WebView will use directly.
     * Otherwise queue a prefetch (so the next request hits the cache) and
     * return `null` to let the WebView handle the request normally.
     */
    fun intercept(url: String, chapterId: Long?, loader: PageLoader?): WebResourceResponse? {
        if (!url.startsWith(URL_SCHEME_NOVEL_IMAGE)) return null
        val imagePath = Uri.decode(url.removePrefix(URL_SCHEME_NOVEL_IMAGE))
        val cacheKey = buildCacheKey(chapterId, imagePath)
        cache[cacheKey]?.takeIf { it.exists() }?.let { cachedFile ->
            return WebResourceResponse(
                guessMimeType(imagePath),
                "UTF-8",
                cachedFile.inputStream(),
            )
        }
        if (loader != null && chapterId != null) prefetch(chapterId, imagePath, loader)
        return null
    }

    /**
     * Scan [content] for `tsundoku-novel-image://` references and queue a
     * prefetch for each distinct one.
     */
    fun schedulePrefetch(content: String, chapterId: Long?, loader: PageLoader?) {
        if (chapterId == null || loader == null) return
        IMAGE_URL_PATTERN.findAll(content)
            .mapNotNull { match -> runCatching { Uri.decode(match.groupValues[1]) }.getOrNull() }
            .distinct()
            .forEach { imagePath -> prefetch(chapterId, imagePath, loader) }
    }

    /**
     * Cancel all in-flight prefetches and delete every cached file. Idempotent.
     */
    fun clear() {
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
        cache.values.forEach { runCatching { it.delete() } }
        cache.clear()
    }

    private fun prefetch(chapterId: Long, imagePath: String, loader: PageLoader) {
        val cacheKey = buildCacheKey(chapterId, imagePath)
        if (cache[cacheKey]?.exists() == true) return

        // Create lazily so no coroutine starts before the atomic slot claim.
        val job = scope.launch(start = CoroutineStart.LAZY, context = Dispatchers.IO) {
            try {
                val stream = loader.getPageDataStream(imagePath) ?: return@launch
                val fileSuffix = imagePath.substringAfterLast('.', "bin").ifBlank { "bin" }
                val keyHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(cacheKey.toByteArray())
                    .take(16)
                    .joinToString("") { "%02x".format(it) }
                val cachedFile = File(context.cacheDir, "novel-image-$keyHash.$fileSuffix")
                stream.use { input ->
                    cachedFile.outputStream().use { output -> input.copyTo(output) }
                }
                cache[cacheKey] = cachedFile
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG) { "NovelWebViewImageCache: prefetch $imagePath failed: ${e.message}" }
            } finally {
                prefetchJobs.remove(cacheKey)
            }
        }

        // Only the winner of the atomic slot claim starts the job; losers cancel
        // without having executed any I/O.
        if (prefetchJobs.putIfAbsent(cacheKey, job) != null) {
            job.cancel()
            return
        }
        job.start()
    }

    private fun buildCacheKey(chapterId: Long?, imagePath: String): String =
        "${chapterId ?: -1L}:$imagePath"

    private fun guessMimeType(imagePath: String): String =
        when (imagePath.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            else -> "image/jpeg"
        }

    companion object {
        const val URL_SCHEME_NOVEL_IMAGE = "tsundoku-novel-image://"
        private val IMAGE_URL_PATTERN = Regex("${Regex.escape(URL_SCHEME_NOVEL_IMAGE)}([^\"'<>\\s]+)")
    }
}
