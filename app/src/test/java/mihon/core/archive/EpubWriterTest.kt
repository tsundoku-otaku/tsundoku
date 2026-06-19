@file:Suppress("ktlint:standard:max-line-length")

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
        customCss: String? = null,
        customJs: String? = null,
    ): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        EpubWriter().write(
            outputStream = out,
            metadata = metadata,
            chapters = chapters,
            coverImage = coverImage,
            customCss = customCss,
            customJs = customJs,
        )
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
            b[0] = 0x52
            b[1] = 0x49
            b[2] = 0x46
            b[3] = 0x46
            // WEBP at bytes 8-11
            b[8] = 0x57
            b[9] = 0x45
            b[10] = 0x42
            b[11] = 0x50
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
        val img = EpubWriter.EmbeddedImage(
            id = "image_0.jpg",
            bytes = imgBytes,
            mimeType = "image/jpeg",
            extension = "jpg",
        )
        val chapter = EpubWriter.Chapter(
            title = "Ch 1",
            content = """<img src="tsundoku-novel-image://image_0.jpg"/>""",
            images = listOf(img),
        )
        val entries = buildEpub(chapters = listOf(chapter))
        assertNotNull(entries["OEBPS/images/chapter0000_image_0.jpg"], "Image must be stored at chapter-scoped path")
    }

    @Test
    fun `chapter image is listed in OPF manifest`() {
        val imgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
        val img = EpubWriter.EmbeddedImage(
            id = "image_0.jpg",
            bytes = imgBytes,
            mimeType = "image/jpeg",
            extension = "jpg",
        )
        val chapter = EpubWriter.Chapter(
            title = "Ch 1",
            content = """<img src="tsundoku-novel-image://image_0.jpg"/>""",
            images = listOf(img),
        )
        val opf = buildEpub(chapters = listOf(chapter)).text("OEBPS/content.opf")
        assertTrue(opf.contains("chapter0000_image_0.jpg"), "Manifest must reference the image file")
        assertTrue(opf.contains("image/jpeg"), "Manifest must include correct MIME type")
    }

    // ── HTML rewriting ──────────────────────────────────────────────

    @Test
    fun `tsundoku-novel-image URLs are rewritten to EPUB-relative paths`() {
        val imgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
        val img = EpubWriter.EmbeddedImage(
            id = "image_0.jpg",
            bytes = imgBytes,
            mimeType = "image/jpeg",
            extension = "jpg",
        )
        val chapter = EpubWriter.Chapter(
            title = "Ch 1",
            content = """<img src="tsundoku-novel-image://image_0.jpg"/>""",
            images = listOf(img),
        )
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
        val img = EpubWriter.EmbeddedImage(
            id = "image_0.jpg",
            bytes = imgBytes,
            mimeType = "image/jpeg",
            extension = "jpg",
        )
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
        val img = EpubWriter.EmbeddedImage(
            id = "image_0.jpg",
            bytes = imgBytes,
            mimeType = "image/jpeg",
            extension = "jpg",
        )
        val ch0 = EpubWriter.Chapter(
            title = "Ch 0",
            content = """<img src="tsundoku-novel-image://image_0.jpg"/>""",
            images = listOf(img),
        )
        val ch1 = EpubWriter.Chapter(
            title = "Ch 1",
            content = """<img src="tsundoku-novel-image://image_0.jpg"/>""",
            images = listOf(img),
        )
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
        assertNotNull(entries["OEBPS/toc.ncx"], "toc.ncx must be present (EPUB 2 fallback)")
        assertNotNull(entries["OEBPS/chapter0000.xhtml"], "chapter0000.xhtml must be present")
        assertEquals("application/epub+zip", entries.text("mimetype"))
    }

    @Test
    fun `toc ncx has one navPoint per chapter in order`() {
        val chapters = (1..3).map { idx ->
            EpubWriter.Chapter(title = "Ch $idx", content = "<p>$idx</p>")
        }
        val entries = buildEpub(chapters = chapters)
        val ncx = entries.text("OEBPS/toc.ncx")

        val navPointCount = "<navPoint ".toRegex().findAll(ncx).count()
        assertEquals(3, navPointCount)
        listOf(1, 2, 3).forEach { i ->
            assertTrue(
                ncx.contains("""playOrder="$i""""),
                "playOrder=$i must appear in NCX",
            )
            assertTrue(ncx.contains("Ch $i"), "Chapter $i title must appear in NCX")
            assertTrue(
                ncx.contains("""src="chapter${(i - 1).toString().padStart(4, '0')}.xhtml""""),
                "Chapter $i src must reference the correct XHTML file",
            )
        }
    }

    @Test
    fun `toc ncx uses correct namespace and version`() {
        val ncx = buildEpub().text("OEBPS/toc.ncx")
        assertTrue(
            ncx.contains("""xmlns="http://www.daisy.org/z3986/2005/ncx/""""),
            "NCX must declare the DAISY namespace",
        )
        assertTrue(ncx.contains("""version="2005-1""""), "NCX must declare version 2005-1")
    }

    @Test
    fun `toc ncx dtb uid matches package identifier`() {
        val entries = buildEpub()
        val opf = entries.text("OEBPS/content.opf")
        val ncx = entries.text("OEBPS/toc.ncx")

        val packageUid = Regex("""urn:uuid:[0-9a-f-]{36}""").find(opf)?.value
        assertNotNull(packageUid, "OPF must contain a urn:uuid identifier")
        assertTrue(
            ncx.contains(packageUid!!),
            "NCX dtb:uid must match the OPF identifier so EPUB 2 readers can cross-reference",
        )
    }

    @Test
    fun `OPF references NCX in manifest and spine`() {
        val opf = buildEpub().text("OEBPS/content.opf")
        assertTrue(
            opf.contains("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""),
            "Manifest must include NCX item with correct media-type",
        )
        assertTrue(
            opf.contains("""<spine toc="ncx">"""),
            "Spine must declare toc=\"ncx\" so EPUB 2 readers pick up the NCX",
        )
    }

    @Test
    fun `custom CSS body is written as standalone stylesheet entry`() {
        val css = "body { background: #111; color: #eee; }"
        val entries = buildEpub(customCss = css)

        val stored = entries["OEBPS/${EpubWriter.CUSTOM_CSS_PATH}"]
        assertNotNull(stored, "Custom CSS file must be written to the archive")
        assertEquals(css, stored!!.decodeToString(), "Stored CSS body must match input verbatim")
    }

    @Test
    fun `custom CSS manifest entry uses correct media type`() {
        val opf = buildEpub(customCss = ".a{}").text("OEBPS/content.opf")
        assertTrue(
            opf.contains("""href="${EpubWriter.CUSTOM_CSS_PATH}""""),
            "Manifest must reference stylesheet at the writer's fixed path",
        )
        assertTrue(opf.contains("""media-type="text/css""""), "Manifest entry must declare text/css")
    }

    @Test
    fun `every chapter links to the custom stylesheet when CSS is embedded`() {
        val chapters = listOf(
            EpubWriter.Chapter(title = "A", content = "<p>a</p>"),
            EpubWriter.Chapter(title = "B", content = "<p>b</p>"),
        )
        val entries = buildEpub(chapters = chapters, customCss = ".a{}")
        val expectedHref = EpubWriter.CUSTOM_CSS_PATH
        assertTrue(
            entries.text("OEBPS/chapter0000.xhtml").contains("""href="$expectedHref""""),
            "Chapter 0 must <link> the bundled stylesheet",
        )
        assertTrue(
            entries.text("OEBPS/chapter0001.xhtml").contains("""href="$expectedHref""""),
            "Chapter 1 must <link> the bundled stylesheet",
        )
    }

    @Test
    fun `chapter does not reference custom stylesheet when CSS is absent`() {
        val xhtml = buildEpub(customCss = null).text("OEBPS/chapter0000.xhtml")
        assertFalse(
            xhtml.contains(EpubWriter.CUSTOM_CSS_PATH),
            "Chapter must not link tsundoku-style.css when CSS embedding is disabled",
        )
    }

    @Test
    fun `blank custom CSS is treated as absent`() {
        val entries = buildEpub(customCss = "   \n  ")
        assertNull(
            entries["OEBPS/${EpubWriter.CUSTOM_CSS_PATH}"],
            "Blank CSS input must not produce a stylesheet entry",
        )
        val opf = entries.text("OEBPS/content.opf")
        assertFalse(opf.contains(EpubWriter.CUSTOM_CSS_PATH), "OPF must omit stylesheet manifest entry")
    }

    @Test
    fun `custom JS body is written as standalone script entry`() {
        val js = "console.log('hi');"
        val entries = buildEpub(customJs = js)

        val stored = entries["OEBPS/${EpubWriter.CUSTOM_JS_PATH}"]
        assertNotNull(stored, "Custom JS file must be written to the archive")
        assertEquals(js, stored!!.decodeToString())
    }

    @Test
    fun `custom JS manifest entry uses application javascript media type`() {
        val opf = buildEpub(customJs = "void 0;").text("OEBPS/content.opf")
        assertTrue(opf.contains("""href="${EpubWriter.CUSTOM_JS_PATH}""""))
        assertTrue(opf.contains("""media-type="application/javascript""""))
    }

    @Test
    fun `chapters marked scripted in OPF when JS is embedded`() {
        val opf = buildEpub(customJs = "void 0;").text("OEBPS/content.opf")
        assertTrue(
            Regex("""<item id="chapter0000"[^>]*properties="scripted"""").containsMatchIn(opf),
            "Chapter manifest items must carry properties=\"scripted\" when JS is bundled",
        )
    }

    @Test
    fun `chapters not marked scripted when JS is absent`() {
        val opf = buildEpub(customJs = null).text("OEBPS/content.opf")
        assertFalse(opf.contains("properties=\"scripted\""), "No scripted property when JS not bundled")
    }

    @Test
    fun `every chapter includes script tag when JS is embedded`() {
        val chapters = listOf(
            EpubWriter.Chapter(title = "A", content = "<p>a</p>"),
            EpubWriter.Chapter(title = "B", content = "<p>b</p>"),
        )
        val entries = buildEpub(chapters = chapters, customJs = "void 0;")
        val expectedHref = EpubWriter.CUSTOM_JS_PATH
        assertTrue(
            entries.text("OEBPS/chapter0000.xhtml").contains("""src="$expectedHref""""),
            "Chapter 0 must reference tsundoku-script.js",
        )
        assertTrue(
            entries.text("OEBPS/chapter0001.xhtml").contains("""src="$expectedHref""""),
            "Chapter 1 must reference tsundoku-script.js",
        )
    }

    @Test
    fun `script tag is non-self-closing for XHTML compatibility`() {
        val xhtml = buildEpub(customJs = "void 0;").text("OEBPS/chapter0000.xhtml")
        assertTrue(xhtml.contains("</script>"), "Script tag must use explicit closing tag")
    }

    @Test
    fun `embedding both CSS and JS produces both files and references`() {
        val entries = buildEpub(customCss = ".a{}", customJs = "void 0;")
        assertNotNull(entries["OEBPS/${EpubWriter.CUSTOM_CSS_PATH}"])
        assertNotNull(entries["OEBPS/${EpubWriter.CUSTOM_JS_PATH}"])
        val xhtml = entries.text("OEBPS/chapter0000.xhtml")
        assertTrue(xhtml.contains(EpubWriter.CUSTOM_CSS_PATH))
        assertTrue(xhtml.contains(EpubWriter.CUSTOM_JS_PATH))
    }

    @Test
    fun `bundled snippet paths match conventional EPUB layout`() {
        // Files should land under OEBPS/styles/ and OEBPS/scripts/ — common EPUB convention.
        assertTrue(EpubWriter.CUSTOM_CSS_PATH.startsWith("styles/"))
        assertTrue(EpubWriter.CUSTOM_CSS_PATH.endsWith(".css"))
        assertTrue(EpubWriter.CUSTOM_JS_PATH.startsWith("scripts/"))
        assertTrue(EpubWriter.CUSTOM_JS_PATH.endsWith(".js"))
    }

    @Test
    fun `joined multi-volume EPUB embeds CSS and JS for every chapter`() {
        val chapters = (1..6).map { idx ->
            EpubWriter.Chapter(title = "ch $idx", content = "<p>$idx</p>")
        }
        val entries = buildEpub(chapters = chapters, customCss = ".a{}", customJs = "void 0;")

        assertNotNull(entries["OEBPS/${EpubWriter.CUSTOM_CSS_PATH}"])
        assertNotNull(entries["OEBPS/${EpubWriter.CUSTOM_JS_PATH}"])
        repeat(6) { i ->
            val xhtml = entries.text("OEBPS/chapter${i.toString().padStart(4, '0')}.xhtml")
            assertTrue(xhtml.contains(EpubWriter.CUSTOM_CSS_PATH), "chapter $i missing CSS link")
            assertTrue(xhtml.contains(EpubWriter.CUSTOM_JS_PATH), "chapter $i missing JS reference")
        }

        val opf = entries.text("OEBPS/content.opf")
        repeat(6) { i ->
            val id = "chapter${i.toString().padStart(4, '0')}"
            assertTrue(opf.contains("""<itemref idref="$id""""), "spine must reference $id")
            assertTrue(
                Regex("""<item id="$id"[^>]*properties="scripted"""").containsMatchIn(opf),
                "$id must be marked scripted",
            )
        }
    }

    @Test
    fun `split-volume export writes one self-contained EPUB per volume with CSS and JS`() {
        data class Volume(val title: String, val chapters: List<EpubWriter.Chapter>)
        val volumes = listOf(
            Volume(
                "Vol 1",
                listOf(EpubWriter.Chapter("v1c1", "<p>v1c1</p>"), EpubWriter.Chapter("v1c2", "<p>v1c2</p>")),
            ),
            Volume("Vol 2", listOf(EpubWriter.Chapter("v2c1", "<p>v2c1</p>"))),
            Volume(
                "Vol 3",
                listOf(EpubWriter.Chapter("v3c1", "<p>v3c1</p>"), EpubWriter.Chapter("v3c2", "<p>v3c2</p>")),
            ),
        )

        volumes.forEach { volume ->
            val entries = buildEpub(
                chapters = volume.chapters,
                metadata = EpubWriter.Metadata(title = volume.title),
                customCss = ".a{}",
                customJs = "void 0;",
            )

            assertNotNull(
                entries["OEBPS/${EpubWriter.CUSTOM_CSS_PATH}"],
                "${volume.title} must bundle its own CSS file",
            )
            assertNotNull(
                entries["OEBPS/${EpubWriter.CUSTOM_JS_PATH}"],
                "${volume.title} must bundle its own JS file",
            )

            val chapterEntryCount = entries.keys.count {
                it.startsWith("OEBPS/chapter") && it.endsWith(".xhtml")
            }
            assertEquals(volume.chapters.size, chapterEntryCount, "${volume.title} chapter count mismatch")

            volume.chapters.indices.forEach { i ->
                val xhtml = entries.text("OEBPS/chapter${i.toString().padStart(4, '0')}.xhtml")
                assertTrue(xhtml.contains(EpubWriter.CUSTOM_CSS_PATH))
                assertTrue(xhtml.contains(EpubWriter.CUSTOM_JS_PATH))
            }
        }
    }
}
