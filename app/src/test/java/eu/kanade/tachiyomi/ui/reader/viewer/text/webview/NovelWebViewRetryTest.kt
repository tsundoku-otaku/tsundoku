package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import eu.kanade.tachiyomi.source.model.Page
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelWebViewRetryTest {

    private fun page(status: Page.State, text: String?) =
        Page(0, "https://example.com/chapter/1").apply {
            this.status = status
            this.text = text
        }

    @Test
    fun `ready page with no text retries from source`() {
        assertTrue(shouldRetryFromSource(page(Page.State.Ready, null)))
        assertTrue(shouldRetryFromSource(page(Page.State.Ready, "  ")))
    }

    @Test
    fun `ready page with text emptied by processing retries the local copy`() {
        assertFalse(shouldRetryFromSource(page(Page.State.Ready, "<p>Hello</p>")))
    }

    @Test
    fun `failed or missing page retries the local copy`() {
        assertFalse(shouldRetryFromSource(page(Page.State.Error(Exception("boom")), null)))
        assertFalse(shouldRetryFromSource(null))
    }
}
