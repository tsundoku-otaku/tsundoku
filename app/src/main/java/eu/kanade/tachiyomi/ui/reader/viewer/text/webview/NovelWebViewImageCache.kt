package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.webkit.WebResourceResponse
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.logcat
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

internal class NovelWebViewImageCache(
    private val cacheDir: File,
    private val scope: CoroutineScope,
) {

    private val cache = ConcurrentHashMap<String, File>()
    private val prefetchJobs = ConcurrentHashMap<String, Job>()
    private val chapterLoaderMap = ConcurrentHashMap<Long, PageLoader>()

    fun intercept(url: String, fallbackChapterId: Long?, fallbackLoader: PageLoader?): WebResourceResponse? {
        if (!url.startsWith(URL_SCHEME_NOVEL_IMAGE)) return null
        val rawPath = URLDecoder.decode(url.removePrefix(URL_SCHEME_NOVEL_IMAGE), "UTF-8")

        // Only the infinite-scroll append path prefixes the URL with "<chapterId>/". Treat the first
        // segment as a chapter id only when it is a registered loader, so a relative asset folder that
        // happens to be all-digits (e.g. "2020/cover.jpg") on the unprefixed initial load isn't
        // mistaken for a chapter id and dropped.
        val slashIdx = rawPath.indexOf('/')
        val (chapterId, imagePath) = if (slashIdx > 0) {
            val possibleId = rawPath.substring(0, slashIdx).toLongOrNull()
            if (possibleId != null && chapterLoaderMap.containsKey(possibleId)) {
                possibleId to rawPath.substring(slashIdx + 1)
            } else {
                fallbackChapterId to rawPath
            }
        } else {
            fallbackChapterId to rawPath
        }

        val loader = chapterId?.let { chapterLoaderMap[it] } ?: fallbackLoader
        val cacheKey = buildCacheKey(chapterId, imagePath)
        cache[cacheKey]?.takeIf { it.exists() }?.let { cachedFile ->
            return WebResourceResponse(
                guessMimeType(imagePath),
                "UTF-8",
                cachedFile.inputStream(),
            )
        }

        if (loader == null) return null
        prefetchJobs.remove(cacheKey)?.cancel()
        return try {
            val cachedFile = makeCachedFile(cacheKey, imagePath)
            // Stream source → file (no full-image byte[] held in memory), then serve from the file.
            val wrote = runBlocking {
                loader.getPageDataStream(imagePath)?.use { input ->
                    cachedFile.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
            }
            if (!wrote) return null
            cache[cacheKey] = cachedFile
            WebResourceResponse(guessMimeType(imagePath), "UTF-8", cachedFile.inputStream())
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "NovelWebViewImageCache: sync load $imagePath failed: ${e.message}" }
            null
        }
    }

    fun schedulePrefetch(content: String, chapterId: Long?, loader: PageLoader?) {
        if (chapterId == null || loader == null) return
        chapterLoaderMap[chapterId] = loader
        IMAGE_URL_PATTERN.findAll(content)
            .mapNotNull { match -> runCatching { URLDecoder.decode(match.groupValues[1], "UTF-8") }.getOrNull() }
            .distinct()
            .forEach { rawPath ->
                val imagePath = if (rawPath.startsWith("$chapterId/")) {
                    rawPath.removePrefix("$chapterId/")
                } else {
                    rawPath
                }
                prefetch(chapterId, imagePath, loader)
            }
    }

    fun clear() {
        prefetchJobs.values.forEach { it.cancel() }
        prefetchJobs.clear()
        cache.values.forEach { runCatching { it.delete() } }
        cache.clear()
        chapterLoaderMap.clear()
    }

    private fun prefetch(chapterId: Long, imagePath: String, loader: PageLoader) {
        val cacheKey = buildCacheKey(chapterId, imagePath)
        if (cache[cacheKey]?.exists() == true) return

        val job = scope.launch(start = CoroutineStart.LAZY, context = Dispatchers.IO) {
            try {
                val stream = loader.getPageDataStream(imagePath) ?: return@launch
                val cachedFile = makeCachedFile(cacheKey, imagePath)
                stream.use { input ->
                    cachedFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (isActive) cache[cacheKey] = cachedFile else runCatching { cachedFile.delete() }
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG) { "NovelWebViewImageCache: prefetch $imagePath failed: ${e.message}" }
            } finally {
                prefetchJobs.remove(cacheKey)
            }
        }

        if (prefetchJobs.putIfAbsent(cacheKey, job) != null) {
            job.cancel()
            return
        }
        job.start()
    }

    private fun buildCacheKey(chapterId: Long?, imagePath: String): String =
        "${chapterId ?: -1L}:$imagePath"

    private fun makeCachedFile(cacheKey: String, imagePath: String): File {
        val fileSuffix = imagePath.substringAfterLast('.', "bin").ifBlank { "bin" }
        val keyHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "novel-image-$keyHash.$fileSuffix")
    }

    private fun guessMimeType(imagePath: String): String =
        when (imagePath.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "ico" -> "image/x-icon"
            "css" -> "text/css"
            "js", "mjs" -> "text/javascript"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "ogg", "ogv" -> "video/ogg"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "vtt" -> "text/vtt"
            else -> "image/jpeg"
        }

    companion object {
        const val URL_SCHEME_NOVEL_IMAGE = "tsundoku-novel-image://"
        private val IMAGE_URL_PATTERN = Regex("${Regex.escape(URL_SCHEME_NOVEL_IMAGE)}([^\"'<>\\s]+)")
    }
}
