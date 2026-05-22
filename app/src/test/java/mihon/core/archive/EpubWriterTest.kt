package mihon.core.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class EpubWriterTest {

    // ── Helpers ─────────────────────────────────────────────────────

    private fun buildEpub(
        chapters: List<EpubWriter.Chapter> = listOf(
            EpubWriter.Chapter(title = "Chapter 1", content = "<p>Hello</p>"),
        ),
        coverImage: ByteArray? = null,
        metadata: EpubWriter.Metadata = EpubWriter.Metadata(title = "Test Book"),
    ): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        EpubWriter().write(out, metadata, chapters, coverImage)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun Map<String, ByteArray>.text(key: String): String =
        this[key]?.decodeToString() ?: error("Entry not found: $key")

    // ── detectImageType ─────────────────────────────────────────────

    @Test
    fun `detectImageType returns jpeg for JPEG magic bytes`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals("image/jpeg" to "jpg", EpubWriter.detectImageType(bytes))
    }

    @Test
    fun `detectImageType returns png for PNG magic bytes`() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        assertEquals("image/png" to "png", EpubWriter.detectImageType(bytes))
    }

    @Test
    fun `detectImageType returns webp for WEBP magic bytes`() {
        val bytes = ByteArray(12).also { b ->
            // RIFF
            b[0] = 0x52; b[1] = 0x49; b[2] = 0x46; b[3] = 0x46
            // WEBP at bytes 8-11
            b[8] = 0x57; b[9] = 0x45; b[10] = 0x42; b[11] = 0x50
        }
        assertEquals("image/webp" to "webp", EpubWriter.detectImageType(bytes))
    }

    @Test
    fun `detectImageType returns gif for GIF magic bytes`() {
        val bytes = byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte())
        assertEquals("image/gif" to "gif", EpubWriter.detectImageType(bytes))
    }

    @Test
    fun `detectImageType falls back to jpeg for unknown bytes`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertEquals("image/jpeg" to "jpg", EpubWriter.detectImageType(bytes))
    }

    @Test
    fun `detectImageType falls back to jpeg for too-small byte array`() {
        val bytes = byteArrayOf(0xFF.toByte())
        assertEquals("image/jpeg" to "jpg", EpubWriter.detectImageType(bytes))
    }

    // ── Cover MIME detection ────────────────────────────────────────

    @Test
    fun `PNG cover uses png extension and image mime in manifest`() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0, 0, 0, 0)
        val entries = buildEpub(coverImage = pngBytes)
        assertNotNull(entries["OEBPS/images/cover.png"], "cover.png must be written")
        assertNull(entries["OEBPS/images/cover.jpg"], "cover.jpg must NOT be written for PNG cover")
        val opf = entries.text("OEBPS/content.opf")
        assertTrue(opf.contains("cover.png"), "OPF must reference cover.png")
        assertTrue(opf.contains("image/png"), "OPF must use image/png mime type")
    }

    @Test
    fun `JPEG cover uses jpg extension`() {
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
        val entries = buildEpub(coverImage = jpegBytes)
        assertNotNull(entries["OEBPS/images/cover.jpg"])
        val opf = entries.text("OEBPS/content.opf")
        assertTrue(opf.contains("cover.jpg"))
        assertTrue(opf.contains("image/jpeg"))
    }

    // ── EPUB 2 compatibility ────────────────────────────────────────

    @Test
    fun `EPUB 2 cover meta is present when cover provided`() {
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
        val opf = buildEpub(coverImage = jpegBytes).text("OEBPS/content.opf")
        assertTrue(
            opf.contains("""meta name="cover" content="cover-image""""),
            "OPF must contain EPUB 2 cover meta",
        )
    }

    @Test
    fun `EPUB 2 cover meta is absent when no cover provided`() {
        val opf = buildEpub(coverImage = null).text("OEBPS/content.opf")
        assertFalse(opf.contains("""meta name="cover""""), "OPF must NOT contain cover meta when no cover")
    }

    // ── EPUB 3 cover-image property ─────────────────────────────────

    @Test
    fun `EPUB 3 cover-image property is set`() {
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
        val opf = buildEpub(coverImage = jpegBytes).text("OEBPS/content.opf")
        assertTrue(
            opf.contains("""properties="cover-image""""),
            "OPF manifest item must have properties=\"cover-image\"",
        )
    }

    // ── Chapter image embedding ─────────────────────────────────────

    @Test
    fun `chapter image is written to correct EPUB path`() {
        val imgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
        val img = EpubWriter.EmbeddedImage(id = "image_0.jpg", bytes = imgBytes, mimeType = "image/jpeg", extension = "jpg")
        val chapter = EpubWriter.Chapter(title = "Ch 1", content = """<img src="tsundoku-novel-image://image_0.jpg"/>""", images = listOf(img))
        val entries = buildEpub(chapters = listOf(chapter))
        assertNotNull(entries["OEBPS/images/chapter0000_image_0.jpg"], "Image must be stored at chapter-scoped path")
    }

    @Test
    fun `chapter image is listed in OPF manifest`() {
        val imgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
        val img = EpubWriter.EmbeddedImage(id = "image_0.jpg", bytes = imgBytes, mimeType = "image/jpeg", extension = "jpg")
        val chapter = EpubWriter.Chapter(title = "Ch 1", content = """<img src="tsundoku-novel-image://image_0.jpg"/>""", images = listOf(img))
        val opf = buildEpub(chapters = listOf(chapter)).text("OEBPS/content.opf")
        assertTrue(opf.contains("chapter0000_image_0.jpg"), "Manifest must reference the image file")
        assertTrue(opf.contains("image/jpeg"), "Manifest must include correct MIME type")
    }

    // ── HTML rewriting ──────────────────────────────────────────────

    @Test
    fun `tsundoku-novel-image URLs are rewritten to EPUB-relative paths`() {
        val imgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
        val img = EpubWriter.EmbeddedImage(id = "image_0.jpg", bytes = imgBytes, mimeType = "image/jpeg", extension = "jpg")
        val chapter = EpubWriter.Chapter(title = "Ch 1", content = """<img src="tsundoku-novel-image://image_0.jpg"/>""", images = listOf(img))
        val chapterXhtml = buildEpub(chapters = listOf(chapter)).text("OEBPS/chapter0000.xhtml")
        assertFalse(chapterXhtml.contains("tsundoku-novel-image://"), "tsundoku-novel-image:// must be rewritten")
        assertTrue(chapterXhtml.contains("images/chapter0000_image_0.jpg"), "Rewritten path must point to EPUB image")
    }

    @Test
    fun `orphan tsundoku-novel-image img tags are removed`() {
        // Image referenced in HTML but NOT provided in images list
        val chapter = EpubWriter.Chapter(
            title = "Ch 1",
            content = """<img src="tsundoku-novel-image://orphan.jpg"/>""",
            images = emptyList(),
        )
        val chapterXhtml = buildEpub(chapters = listOf(chapter)).text("OEBPS/chapter0000.xhtml")
        assertFalse(chapterXhtml.contains("orphan.jpg"), "Orphan img must be removed")
    }

    @Test
    fun `picture element sources are stripped and img is kept`() {
        val imgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
        val img = EpubWriter.EmbeddedImage(id = "image_0.jpg", bytes = imgBytes, mimeType = "image/jpeg", extension = "jpg")
        val chapter = EpubWriter.Chapter(
            title = "Ch 1",
            content = """<picture><source srcset="https://cdn.example.com/big.webp" type="image/webp"/><img src="tsundoku-novel-image://image_0.jpg"/></picture>""",
            images = listOf(img),
        )
        val xhtml = buildEpub(chapters = listOf(chapter)).text("OEBPS/chapter0000.xhtml")
        assertFalse(xhtml.contains("<source"), "picture <source> must be stripped")
        assertFalse(xhtml.contains("<picture"), "picture element must be unwrapped")
        assertTrue(xhtml.contains("images/chapter0000_image_0.jpg"), "img src must be rewritten")
    }

    @Test
    fun `picture element with no img fallback is removed`() {
        val chapter = EpubWriter.Chapter(
            title = "Ch 1",
            content = """<picture><source srcset=""/></picture>""",
            images = emptyList(),
        )
        val xhtml = buildEpub(chapters = listOf(chapter)).text("OEBPS/chapter0000.xhtml")
        assertFalse(xhtml.contains("<picture"), "picture with no usable img must be removed")
    }

    @Test
    fun `images from different chapters use chapter-scoped paths`() {
        val imgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
        val img = EpubWriter.EmbeddedImage(id = "image_0.jpg", bytes = imgBytes, mimeType = "image/jpeg", extension = "jpg")
        val ch0 = EpubWriter.Chapter(title = "Ch 0", content = """<img src="tsundoku-novel-image://image_0.jpg"/>""", images = listOf(img))
        val ch1 = EpubWriter.Chapter(title = "Ch 1", content = """<img src="tsundoku-novel-image://image_0.jpg"/>""", images = listOf(img))
        val entries = buildEpub(chapters = listOf(ch0, ch1))

        assertNotNull(entries["OEBPS/images/chapter0000_image_0.jpg"], "Chapter 0 image must be stored")
        assertNotNull(entries["OEBPS/images/chapter0001_image_0.jpg"], "Chapter 1 image must be stored separately")

        val xhtml0 = entries.text("OEBPS/chapter0000.xhtml")
        val xhtml1 = entries.text("OEBPS/chapter0001.xhtml")
        assertTrue(xhtml0.contains("chapter0000_image_0.jpg"))
        assertTrue(xhtml1.contains("chapter0001_image_0.jpg"))
        assertFalse(xhtml0.contains("chapter0001"), "Chapter 0 must not reference chapter 1 images")
    }

    @Test
    fun `plain HTML without images is preserved unchanged`() {
        val chapter = EpubWriter.Chapter(title = "Ch 1", content = "<p>Plain <strong>text</strong> content.</p>")
        val xhtml = buildEpub(chapters = listOf(chapter)).text("OEBPS/chapter0000.xhtml")
        assertTrue(xhtml.contains("Plain"), "Plain text content must be preserved")
        assertTrue(xhtml.contains("<strong>"), "Inline HTML must be preserved")
    }

    // ── Required EPUB structure ─────────────────────────────────────

    @Test
    fun `required EPUB structure entries are present`() {
        val entries = buildEpub()
        assertNotNull(entries["mimetype"], "mimetype must be present")
        assertNotNull(entries["META-INF/container.xml"], "container.xml must be present")
        assertNotNull(entries["OEBPS/content.opf"], "content.opf must be present")
        assertNotNull(entries["OEBPS/nav.xhtml"], "nav.xhtml must be present")
        assertNotNull(entries["OEBPS/chapter0000.xhtml"], "chapter0000.xhtml must be present")
        assertEquals("application/epub+zip", entries.text("mimetype"))
    }
}
