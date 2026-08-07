package mihon.core.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLEncoder

class RelativeAssetSchemeTest {

    private fun scheme(path: String) = NOVEL_IMAGE_SCHEME + URLEncoder.encode(path, "UTF-8")

    @Test
    fun `isResolvableAssetRef classification`() {
        assertTrue(isResolvableAssetRef("image_0.jpg"))
        assertTrue(isResolvableAssetRef("./image_0.jpg"))
        assertTrue(isResolvableAssetRef("/image_0.jpg"))
        assertFalse(isResolvableAssetRef("http://x/y.jpg"))
        assertFalse(isResolvableAssetRef("https://x/y.jpg"))
        assertFalse(isResolvableAssetRef("//cdn/x.jpg"))
        assertFalse(isResolvableAssetRef("data:image/png;base64,AAAA"))
        assertFalse(isResolvableAssetRef("tsundoku-novel-image://image_0.jpg"))
        assertFalse(isResolvableAssetRef("#frag"))
        assertFalse(isResolvableAssetRef(""))
    }

    @Test
    fun `maps a plain relative filename to the novel image scheme`() {
        assertEquals(scheme("image_0.jpg"), relativeAssetScheme("image_0.jpg"))
    }

    @Test
    fun `strips a leading dot-slash or slash since there is no real root here`() {
        assertEquals(scheme("image_0.jpg"), relativeAssetScheme("./image_0.jpg"))
        assertEquals(scheme("image_0.jpg"), relativeAssetScheme("/image_0.jpg"))
    }

    @Test
    fun `strips the query string before resolving`() {
        assertEquals(scheme("image_0.jpg"), relativeAssetScheme("image_0.jpg?w=106&h=150"))
    }

    @Test
    fun `returns null for anything already absolute so callers leave it untouched`() {
        assertNull(relativeAssetScheme("https://cdn.com/a.jpg"))
        assertNull(relativeAssetScheme("//cdn.com/a.jpg"))
        assertNull(relativeAssetScheme("data:image/png;base64,AAAA"))
    }

    @Test
    fun `is idempotent for content already carrying the scheme, so old and new downloads mix safely`() {
        val already = scheme("image_0.jpg")
        assertNull(relativeAssetScheme(already))
    }

    @Test
    fun `pre-encoded refs are decoded before re-encoding`() {
        val out = relativeAssetScheme("image%20one.jpg")!!
        val encoded = out.removePrefix(NOVEL_IMAGE_SCHEME)
        assertEquals("image one.jpg", java.net.URLDecoder.decode(encoded, "UTF-8"))
    }
}
