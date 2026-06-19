package mihon.core.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubReaderExportImageIdTest {

    private fun id(path: String): String = EpubReader.computeExportImageId(path)

    @Test
    fun `id preserves the file extension for MIME detection downstream`() {
        assertTrue(id("OEBPS/Images/insert1.jpg").endsWith(".jpg"))
        assertTrue(id("OEBPS/Images/insert1.png").endsWith(".png"))
        assertTrue(id("OEBPS/Images/insert1.webp").endsWith(".webp"))
        assertTrue(id("OEBPS/Images/insert1.svg").endsWith(".svg"))
    }

    @Test
    fun `id squashes path separators to underscores so it is safe as a filename`() {
        val out = id("OEBPS/Images/insert1.jpg")
        assertFalse(out.contains("/"), "id must contain no slashes")
        assertFalse(out.contains("\\"), "id must contain no backslashes")
        assertTrue(out.contains("OEBPS_Images_insert1"), "id must encode the full path")
    }

    @Test
    fun `id normalizes windows-style separators`() {
        val unix = id("OEBPS/Images/foo.png")
        val win = id("OEBPS\\Images\\foo.png")
        assertEquals(unix, win, "Backslashes and forward slashes must produce identical ids")
    }

    @Test
    fun `equal source paths produce equal ids for in-chapter deduplication`() {
        assertEquals(id("a/b/c.jpg"), id("a/b/c.jpg"))
    }

    @Test
    fun `different source paths produce different ids`() {
        assertNotEquals(id("vol1/Images/cover.jpg"), id("vol2/Images/cover.jpg"))
    }

    @Test
    fun `id drops disallowed characters so writer filename rules hold`() {
        val out = id("OEBPS/Some Folder/img (1).jpg")
        val stem = out.substringBeforeLast('.')
        assertTrue(
            stem.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' },
            "id must contain only filename-safe characters, was: $out",
        )
    }

    @Test
    fun `id truncates extremely long paths so filenames stay reasonable`() {
        val longPath = "OEBPS/" + "deep/".repeat(50) + "file.jpg"
        val out = id(longPath)
        val stem = out.substringBeforeLast('.')
        assertTrue(stem.length <= 80, "stem should be capped at 80 chars, was ${stem.length}")
        assertTrue(out.endsWith(".jpg"), "extension must survive truncation")
    }

    @Test
    fun `id falls back gracefully for paths with no recognizable extension`() {
        val out = id("OEBPS/data/weird.NOTANEXTENSION")
        assertFalse(out.endsWith(".NOTANEXTENSION"), "unrealistic extensions must be folded into the stem")
    }

    @Test
    fun `id handles empty input without throwing`() {
        val out = id("")
        assertTrue(out.isNotBlank(), "empty path must still produce a usable id")
    }
}
