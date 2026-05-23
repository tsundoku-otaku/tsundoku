package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class NovelWebViewImageCache(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val cache = ConcurrentHashMap<String, File>()
    private val prefetchJobs = ConcurrentHashMap<String, Job>()
    private val chapterLoaderMap = ConcurrentHashMap<Long, PageLoader>()

    fun intercept(url: String, fallbackChapterId: Long?, fallbackLoader: PageLoader?): WebResourceResponse? {
        if (!url.startsWith(URL_SCHEME_NOVEL_IMAGE)) return null
        val rawPath = Uri.decode(url.removePrefix(URL_SCHEME_NOVEL_IMAGE))

        val slashIdx = rawPath.indexOf('/')
        val (chapterId, imagePath) = if (slashIdx > 0) {
            val possibleId = rawPath.substring(0, slashIdx).toLongOrNull()
            if (possibleId != null) {
                possibleId to rawPath.substring(slashIdx + 1)
            } else {
                fallbackChapterId to rawPath
            }
        } else {
            fallbackChapterId to rawPath
        }

        val loader = chapterLoaderMap[chapterId] ?: fallbackLoader
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

    fun schedulePrefetch(content: String, chapterId: Long?, loader: PageLoader?) {
        if (chapterId == null || loader == null) return
        chapterLoaderMap[chapterId] = loader
        IMAGE_URL_PATTERN.findAll(content)
            .mapNotNull { match -> runCatching { Uri.decode(match.groupValues[1]) }.getOrNull() }
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
                val fileSuffix = imagePath.substringAfterLast('.', "bin").ifBlank { "bin" }
                val keyHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(cacheKey.toByteArray())
                    .take(16)
                    .joinToString("") { "%02x".format(it) }
                val cachedFile = File(context.cacheDir, "novel-image-$keyHash.$fileSuffix")
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
