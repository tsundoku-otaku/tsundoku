package eu.kanade.tachiyomi.util.chapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import coil3.imageLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.HtmlAssetRewriter
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.NovelDownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.net.URL

/**
 * Utility class for extracting image URLs from HTML and embedding them as base64.
 */
class ChapterImageEmbedder(
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val novelDownloadPreferences: NovelDownloadPreferences = Injekt.get(),
) {
    private val client: OkHttpClient get() = networkHelper.client

    /**
     * Process HTML content and embed images as base64 if enabled.
     *
     * @param html The HTML content to process
     * @param baseUrl The base URL of the chapter for resolving relative URLs
     * @return Processed HTML with embedded images
     */
    suspend fun processHtml(
        html: String,
        baseUrl: String?,
        tmpDir: com.hippo.unifile.UniFile? = null,
    ): String = withContext(Dispatchers.IO) {
        if (!novelDownloadPreferences.downloadChapterImages().get()) {
            return@withContext html
        }

        val imageUrls = HtmlAssetRewriter.extractImageUrls(html).filterNot(::isAlreadyLocal)

        logcat { "ChapterImageEmbedder: Found ${imageUrls.size} images to process" }

        var imageCounter = 0
        val replacements = mutableMapOf<String, String>()
        for (imageUrl in imageUrls) {
            try {
                val absoluteUrl = resolveUrl(imageUrl, baseUrl)
                val imageResponse = downloadAndEncodeImage(absoluteUrl)

                if (imageResponse != null) {
                    val (imageBytes, mimeType) = imageResponse
                    val finalUrl = if (tmpDir != null) {
                        // Save image to chapter's zip as a local file
                        val extension = when (mimeType) {
                            "image/png" -> "png"
                            "image/gif" -> "gif"
                            "image/webp" -> "webp"
                            "image/svg+xml" -> "svg"
                            "image/avif" -> "avif"
                            else -> "jpg"
                        }
                        var filename: String
                        do {
                            filename = "image_${imageCounter++}.$extension"
                        } while (tmpDir.findFile(filename) != null)

                        tmpDir.createFile(filename)?.openOutputStream()?.use { it.write(imageBytes) }
                        filename
                    } else {
                        // Fallback to base64 if not actively zipping
                        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                        "data:$mimeType;base64,$base64"
                    }

                    replacements[imageUrl] = finalUrl
                    logcat { "ChapterImageEmbedder: Embedded image $imageUrl" }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "ChapterImageEmbedder: Failed to process image $imageUrl" }
            }
        }

        // Attribute-aware rewrite; a plain string replace would corrupt shared-prefix srcset candidates.
        HtmlAssetRewriter.rewriteImageUrls(html, replacements::get)
    }

    private fun isAlreadyLocal(url: String): Boolean =
        url.startsWith("tsundoku-novel-image://") || url.startsWith("file://") || url.startsWith("data:")

    /**
     * Resolve a potentially relative URL against a base URL.
     */
    internal fun resolveUrl(url: String, baseUrl: String?): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") && baseUrl != null -> {
                try {
                    val base = URL(baseUrl)
                    "${base.protocol}://${base.host}$url"
                } catch (e: Exception) {
                    url
                }
            }
            baseUrl != null -> {
                try {
                    URL(URL(baseUrl), url).toString()
                } catch (e: Exception) {
                    url
                }
            }
            else -> url
        }
    }

    /**
     * Download an image and encode it as base64 data URI.
     */
    private suspend fun downloadAndEncodeImage(url: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        try {
            var imageBytes: ByteArray? = null
            var mimeType = "image/jpeg"

            try {
                val context = Injekt.get<android.app.Application>() as android.content.Context
                val diskCache = context.imageLoader.diskCache
                val snapshot = diskCache?.openSnapshot(url)
                if (snapshot != null) {
                    snapshot.use { snap ->
                        val bytes = okio.FileSystem.SYSTEM.read(snap.data) { readByteArray() }
                        imageBytes = bytes
                        if (bytes.size > 12) {
                            mimeType = when {
                                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
                                bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "image/gif"
                                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[8] == 0x57.toByte() &&
                                    bytes[9] == 0x45.toByte() -> "image/webp"
                                else -> "image/jpeg"
                            }
                        }
                        logcat { "ChapterImageEmbedder: Loaded image from Coil cache: $url" }
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG) {
                    "ChapterImageEmbedder: Not found in Coil cache or error reading, fetching from network..."
                }
            }

            if (imageBytes == null) {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        logcat(LogPriority.WARN) { "ChapterImageEmbedder: Failed to download $url - ${resp.code}" }
                        return@withContext null
                    }

                    val contentType = resp.header("Content-Type") ?: "image/jpeg"
                    mimeType = when {
                        contentType.contains("png") -> "image/png"
                        contentType.contains("gif") -> "image/gif"
                        contentType.contains("webp") -> "image/webp"
                        contentType.contains("svg") -> "image/svg+xml"
                        contentType.contains("avif") -> "image/avif"
                        else -> "image/jpeg"
                    }

                    imageBytes = resp.body.bytes()
                }
            }

            val validImageBytes = imageBytes ?: return@withContext null

            // Check if compression is needed
            val maxSizeKb = novelDownloadPreferences.maxImageSizeKb().get()
            val compressionQuality = novelDownloadPreferences.imageCompressionQuality().get()

            val finalBytes = if (maxSizeKb > 0 && validImageBytes.size > maxSizeKb * 1024 &&
                mimeType != "image/svg+xml"
            ) {
                compressImage(validImageBytes, compressionQuality, maxSizeKb)
            } else {
                validImageBytes
            }

            val finalMimeType = if (finalBytes !== validImageBytes) "image/jpeg" else mimeType

            Pair(finalBytes, finalMimeType)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ChapterImageEmbedder: Error downloading image $url" }
            null
        }
    }

    /**
     * Compress an image to fit within the size limit.
     */
    private fun compressImage(imageBytes: ByteArray, quality: Int, maxSizeKb: Int): ByteArray {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return imageBytes

            var currentQuality = quality
            var outputBytes: ByteArray

            do {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, currentQuality, outputStream)
                outputBytes = outputStream.toByteArray()
                currentQuality -= 10
            } while (outputBytes.size > maxSizeKb * 1024 && currentQuality > 10)

            bitmap.recycle()
            outputBytes
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ChapterImageEmbedder: Error compressing image" }
            imageBytes
        }
    }
}
