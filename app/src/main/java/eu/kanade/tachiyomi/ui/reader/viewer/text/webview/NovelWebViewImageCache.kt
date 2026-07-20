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
import mihon.core.archive.NOVEL_IMAGE_SCHEME
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
        val encodedSuffix = url.removePrefix(URL_SCHEME_NOVEL_IMAGE)

        // The infinite-scroll append path is the only producer of a literal "/" here: it prefixes the
        // scheme with "<chapterId>/". Real asset paths are URL-encoded whole, so their own slashes are
        // "%2F" and never appear as a literal slash. A leading numeric segment before a literal slash is
        // therefore always that append prefix, not an all-digits relative folder like "2020/cover.jpg".
        val slashIdx = encodedSuffix.indexOf('/')
        val (chapterId, encodedImagePath) = if (slashIdx > 0) {
            val possibleId = encodedSuffix.substring(0, slashIdx).toLongOrNull()
            if (possibleId != null) {
                possibleId to encodedSuffix.substring(slashIdx + 1)
            } else {
                fallbackChapterId to encodedSuffix
            }
        } else {
            fallbackChapterId to encodedSuffix
        }
        val imagePath = URLDecoder.decode(encodedImagePath, "UTF-8")

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
            .map { it.groupValues[1] }
            .distinct()
            .forEach { encodedRef ->
                val slashIdx = encodedRef.indexOf('/')
                val encodedImagePath =
                    if (slashIdx > 0 && encodedRef.substring(0, slashIdx).toLongOrNull() == chapterId) {
                        encodedRef.substring(slashIdx + 1)
                    } else {
                        encodedRef
                    }
                val imagePath = runCatching { URLDecoder.decode(encodedImagePath, "UTF-8") }.getOrNull()
                    ?: return@forEach
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
        const val URL_SCHEME_NOVEL_IMAGE = NOVEL_IMAGE_SCHEME
        private val IMAGE_URL_PATTERN = Regex("${Regex.escape(URL_SCHEME_NOVEL_IMAGE)}([^\"'<>\\s]+)")
    }
}
