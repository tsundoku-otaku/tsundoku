package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Path

class NovelWebViewImageCacheTest {

    private fun makeCache(
        tempDir: File,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ) = NovelWebViewImageCache(tempDir, scope)

    private fun fakeLoader(vararg files: Pair<String, ByteArray>): PageLoader {
        val fileMap = mapOf(*files)
        return object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages(): List<ReaderPage> = emptyList()
            override suspend fun getPageDataStream(url: String): java.io.InputStream? =
                fileMap[url]?.let { ByteArrayInputStream(it.copyOf()) }
        }
    }

    @Test
    fun `intercept returns null for https URL`(@TempDir tempDir: Path) {
        val cache = makeCache(tempDir.toFile())
        assertNull(cache.intercept("https://example.com/image.jpg", null, null))
    }

    @Test
    fun `intercept returns null for data URI`(@TempDir tempDir: Path) {
        val cache = makeCache(tempDir.toFile())
        assertNull(cache.intercept("data:image/png;base64,abc", null, null))
    }

    @Test
    fun `intercept loads JPEG synchronously when not in cache`(@TempDir tempDir: Path) {
        val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val loader = fakeLoader("cover.jpg" to imageBytes)
        val cache = makeCache(tempDir.toFile())

        val response = cache.intercept(
            url = "tsundoku-novel-image://cover.jpg",
            fallbackChapterId = 1L,
            fallbackLoader = loader,
        )

        assertNotNull(response)
        val cachedFiles = tempDir.toFile().listFiles { f -> f.extension == "jpg" }
        assertNotNull(cachedFiles)
        assertArrayEquals(imageBytes, cachedFiles!!.first().readBytes())
    }

    @Test
    fun `intercept loads PNG synchronously when not in cache`(@TempDir tempDir: Path) {
        val imageBytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        val loader = fakeLoader("figure.png" to imageBytes)
        val cache = makeCache(tempDir.toFile())

        val response = cache.intercept(
            url = "tsundoku-novel-image://figure.png",
            fallbackChapterId = 2L,
            fallbackLoader = loader,
        )

        assertNotNull(response)
        val cachedFiles = tempDir.toFile().listFiles { f -> f.extension == "png" }
        assertNotNull(cachedFiles)
        assertArrayEquals(imageBytes, cachedFiles!!.first().readBytes())
    }

    @Test
    fun `intercept serves from cache on second request without calling loader again`(@TempDir tempDir: Path) {
        val imageBytes = byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte())
        var loadCount = 0
        val loader = object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages(): List<ReaderPage> = emptyList()
            override suspend fun getPageDataStream(url: String): java.io.InputStream? {
                loadCount++
                return ByteArrayInputStream(imageBytes.copyOf())
            }
        }
        val cache = makeCache(tempDir.toFile())

        cache.intercept("tsundoku-novel-image://anim.gif", 1L, loader)
        cache.intercept("tsundoku-novel-image://anim.gif", 1L, loader)

        assert(loadCount == 1) { "Loader should only be called once; second hit should come from cache" }
    }

    @Test
    fun `intercept returns null when no loader is available`(@TempDir tempDir: Path) {
        val cache = makeCache(tempDir.toFile())
        assertNull(cache.intercept("tsundoku-novel-image://cover.jpg", null, null))
    }

    @Test
    fun `intercept returns null when loader has no image for path`(@TempDir tempDir: Path) {
        val loader = fakeLoader()
        val cache = makeCache(tempDir.toFile())
        assertNull(cache.intercept("tsundoku-novel-image://missing.jpg", 1L, loader))
    }

    @Test
    fun `intercept resolves chapterId-prefixed URL and uses matching chapter loader`(@TempDir tempDir: Path) {
        val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val loader = fakeLoader("img.jpg" to imageBytes)
        val cache = makeCache(tempDir.toFile())

        val response = cache.intercept(
            url = "tsundoku-novel-image://42/img.jpg",
            fallbackChapterId = null,
            fallbackLoader = loader,
        )

        assertNotNull(response)
        val cachedFiles = tempDir.toFile().listFiles { f -> f.extension == "jpg" }
        assertNotNull(cachedFiles)
        assertArrayEquals(imageBytes, cachedFiles!!.first().readBytes())
    }

    @Test
    fun `different chapters with same filename load independently`(@TempDir tempDir: Path) {
        val ch3Bytes = byteArrayOf(0x01.toByte())
        val ch4Bytes = byteArrayOf(0x02.toByte())
        val loader3 = fakeLoader("image_0.jpg" to ch3Bytes)
        val loader4 = fakeLoader("image_0.jpg" to ch4Bytes)
        val cache = makeCache(tempDir.toFile())

        val r3 = cache.intercept("tsundoku-novel-image://3/image_0.jpg", null, loader3)
        val r4 = cache.intercept("tsundoku-novel-image://4/image_0.jpg", null, loader4)

        assertNotNull(r3)
        assertNotNull(r4)
        val cachedFiles = tempDir.toFile().listFiles { f -> f.extension == "jpg" }
        assertNotNull(cachedFiles)
        assert(cachedFiles!!.size == 2) { "Expected 2 distinct cache files, got ${cachedFiles.size}" }
        val allBytes = cachedFiles.map { it.readBytes() }.sortedWith(compareBy { it[0] })
        assertArrayEquals(ch3Bytes, allBytes[0])
        assertArrayEquals(ch4Bytes, allBytes[1])
    }

    @Test
    fun `schedulePrefetch populates cache so intercept is a hit`(@TempDir tempDir: Path) = runBlocking {
        val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val loader = fakeLoader("hero.jpg" to imageBytes)
        val scope = CoroutineScope(Dispatchers.IO)
        val cache = makeCache(tempDir.toFile(), scope)

        cache.schedulePrefetch(
            content = """<img src="tsundoku-novel-image://hero.jpg"/>""",
            chapterId = 5L,
            loader = loader,
        )

        delay(300)

        var loadCount = 0
        val countingLoader = object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages(): List<ReaderPage> = emptyList()
            override suspend fun getPageDataStream(url: String): java.io.InputStream? {
                loadCount++
                return null
            }
        }

        val response = cache.intercept(
            url = "tsundoku-novel-image://5/hero.jpg",
            fallbackChapterId = 5L,
            fallbackLoader = countingLoader,
        )

        assertNotNull(response, "Prefetched image must be served from cache")
        assert(loadCount == 0) { "Counting loader must not be called when cache is warm" }
    }
}
