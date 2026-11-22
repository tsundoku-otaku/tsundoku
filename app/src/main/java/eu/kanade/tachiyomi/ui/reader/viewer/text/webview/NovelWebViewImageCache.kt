package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.webkit.WebResourceResponse
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.logcat
import mihon.core.archive.NOVEL_IMAGE_SCHEME
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class NovelWebViewImageCache(
    private val cacheDir: File,
    private val scope: CoroutineScope,
) {

    private val cache = ConcurrentHashMap<String, File>()
    private val prefetchJobs = ConcurrentHashMap<String, Job>()
    private val chapterLoaderMap = ConcurrentHashMap<Long, PageLoader>()

    // Bumped by clear(); a write that finishes after it drops its file instead of caching it.
    private var generation = 0L
    private val lock = Any()

    // Prefetch slots go to the most recently scheduled chapter first, so chapters already scrolled
    // past don't hold up the one being read.
    private val chapterPriority = ConcurrentHashMap<Long, Long>()
    private var nextPriority = 0L
    private val slotWaiters = mutableListOf<SlotWaiter>()
    private var runningPrefetches = 0

    private class SlotWaiter(val chapterId: Long, val order: Long, val gate: CompletableDeferred<Unit>)

    // Every file this instance writes carries its session and its own writer number, so the startup
    // sweep never deletes one of them and concurrent writers of one image never share a path.
    private val session = UUID.randomUUID().toString().take(8)
    private val writerSeq = AtomicLong()

    init {
        // Entries live only in this session's map, so files left by an earlier session are never served.
        scope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MS
            val ownPrefix = "$FILE_PREFIX$session-"
            cacheDir.listFiles { f ->
                f.name.startsWith(FILE_PREFIX) && !f.name.startsWith(ownPrefix) && f.lastModified() < cutoff
            }?.forEach { it.delete() }
        }
    }

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
        val imagePath = runCatching { URLDecoder.decode(encodedImagePath, "UTF-8") }.getOrNull() ?: return null

        val loader = chapterId?.let { chapterLoaderMap[it] } ?: fallbackLoader
        val cacheKey = buildCacheKey(chapterId, imagePath)
        // Opened under the lock publish and clear retire files under, so the hit can't be deleted in between;
        // once open, the stream outlives a later delete.
        val hit = synchronized(lock) {
            cache[cacheKey]?.takeIf { it.exists() }?.let { runCatching { it.inputStream() }.getOrNull() }
        }
        if (hit != null) return WebResourceResponse(guessMimeType(imagePath), "UTF-8", hit)

        if (loader == null) return null
        prefetchJobs.remove(cacheKey)?.cancel()
        val gen = synchronized(lock) { generation }
        return try {
            val cachedFile = makeCachedFile(cacheKey, imagePath)
            // Stream source → file (no full-image byte[] held in memory), then serve from the file.
            val wrote = runBlocking {
                loader.getPageDataStream(imagePath)?.use { input ->
                    writeAtomically(cachedFile, input)
                    true
                } ?: false
            }
            if (!wrote) return null
            val stream = cachedFile.inputStream()
            if (!publish(cacheKey, cachedFile, gen)) cachedFile.delete()
            WebResourceResponse(guessMimeType(imagePath), "UTF-8", stream)
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "NovelWebViewImageCache: sync load $imagePath failed: ${e.message}" }
            null
        }
    }

    fun schedulePrefetch(content: String, chapterId: Long?, loader: PageLoader?) {
        if (chapterId == null || loader == null) return
        chapterLoaderMap[chapterId] = loader
        synchronized(lock) { chapterPriority[chapterId] = nextPriority++ }
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
        synchronized(lock) {
            generation++
            cache.values.forEach { runCatching { it.delete() } }
            cache.clear()
            chapterPriority.clear()
        }
        chapterLoaderMap.clear()
    }

    // False when clear() ran since [gen] was read; the caller then owns (and deletes) the file.
    private fun publish(cacheKey: String, file: File, gen: Long): Boolean = synchronized(lock) {
        if (generation != gen) return false
        // A reader still streaming the replaced file keeps its open handle.
        cache.put(cacheKey, file)?.takeIf { it != file }?.delete()
        true
    }

    private suspend fun <T> withPrefetchSlot(chapterId: Long, block: suspend () -> T): T {
        val waiter = synchronized(lock) {
            if (runningPrefetches < MAX_CONCURRENT_PREFETCHES) {
                runningPrefetches++
                null
            } else {
                SlotWaiter(chapterId, nextPriority++, CompletableDeferred()).also { slotWaiters += it }
            }
        }
        if (waiter != null) {
            try {
                waiter.gate.await()
            } catch (e: CancellationException) {
                // Not in the list any more means the slot was already handed over.
                synchronized(lock) { if (!slotWaiters.remove(waiter)) releaseSlotLocked() }
                throw e
            }
        }
        try {
            return block()
        } finally {
            synchronized(lock) { releaseSlotLocked() }
        }
    }

    private fun releaseSlotLocked() {
        val next = slotWaiters.maxWithOrNull(
            compareBy<SlotWaiter> { chapterPriority[it.chapterId] ?: -1L }.thenByDescending { it.order },
        )
        if (next == null) {
            runningPrefetches--
        } else {
            slotWaiters.remove(next)
            next.gate.complete(Unit)
        }
    }

    private fun prefetch(chapterId: Long, imagePath: String, loader: PageLoader) {
        val cacheKey = buildCacheKey(chapterId, imagePath)
        if (cache[cacheKey]?.exists() == true) return

        val gen = synchronized(lock) { generation }
        val job = scope.launch(start = CoroutineStart.LAZY, context = Dispatchers.IO) {
            val self = coroutineContext[Job]
            try {
                withPrefetchSlot(chapterId) {
                    if (cache[cacheKey]?.exists() == true) return@withPrefetchSlot
                    val stream = loader.getPageDataStream(imagePath) ?: return@withPrefetchSlot
                    val cachedFile = makeCachedFile(cacheKey, imagePath)
                    val committed = stream.use { input ->
                        writeAtomically(cachedFile, input) { self?.isActive != false }
                    }
                    if (committed && !publish(cacheKey, cachedFile, gen)) cachedFile.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG) { "NovelWebViewImageCache: prefetch $imagePath failed: ${e.message}" }
            } finally {
                // Only its own entry: intercept may have replaced it with a newer prefetch.
                if (self != null) prefetchJobs.remove(cacheKey, self)
            }
        }

        if (prefetchJobs.putIfAbsent(cacheKey, job) != null) {
            job.cancel()
            return
        }
        job.start()
    }

    // Each writer fills its own temp file and renames it into place, so a reader never sees a partial
    // or missing target. A writer that should no longer commit stops copying at the next buffer
    // (releasing its prefetch permit) and only drops its temp file.
    private fun writeAtomically(target: File, input: InputStream, commit: () -> Boolean = { true }): Boolean {
        val tmp = File.createTempFile(target.name, ".tmp", target.parentFile)
        try {
            tmp.outputStream().use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (!commit()) return false
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
            if (!commit()) return false
            // A failed rename onto a target another writer already published still leaves a whole file.
            check(tmp.renameTo(target) || target.exists()) { "rename failed for ${target.name}" }
            return true
        } finally {
            tmp.delete()
        }
    }

    private fun buildCacheKey(chapterId: Long?, imagePath: String): String =
        "${chapterId ?: -1L}:$imagePath"

    private fun makeCachedFile(cacheKey: String, imagePath: String): File {
        // substringAfterLast('.') alone would scan the whole path, not just the filename -- a '.' in
        // a directory segment (e.g. "v1.2/cover.png") would put a "/" into the suffix and point the
        // cache file at a nonexistent subdirectory.
        val fileName = imagePath.substringAfterLast('/')
        val fileSuffix = fileName.substringAfterLast('.', "bin").ifBlank { "bin" }
        val keyHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
        cacheDir.mkdirs()
        return File(cacheDir, "$FILE_PREFIX$session-${writerSeq.incrementAndGet()}-$keyHash.$fileSuffix")
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
        private const val MAX_CONCURRENT_PREFETCHES = 3
        private const val STALE_FILE_AGE_MS = 60 * 60 * 1000L
        private const val FILE_PREFIX = "novel-image-"
        const val URL_SCHEME_NOVEL_IMAGE = NOVEL_IMAGE_SCHEME
        private val IMAGE_URL_PATTERN = Regex("${Regex.escape(URL_SCHEME_NOVEL_IMAGE)}([^\"'<>\\s]+)")
    }
}
