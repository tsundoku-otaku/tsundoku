package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TtsHandoffStateTest {

    @Test
    fun `Idle classification flags`() {
        val s: TtsHandoffState<String> = TtsHandoffState.Idle
        assertTrue(s.isIdle)
        assertFalse(s.isPreFetching)
        assertNull(s.cachedOrNull)
    }

    @Test
    fun `PreFetching classification flags`() {
        val s: TtsHandoffState<String> = TtsHandoffState.PreFetching(anchorChapterId = 7L)
        assertFalse(s.isIdle)
        assertTrue(s.isPreFetching)
        assertNull(s.cachedOrNull)
    }

    @Test
    fun `Cached exposes payload and clears other flags`() {
        val payload = "ready"
        val s: TtsHandoffState<String> = TtsHandoffState.Cached(payload)
        assertFalse(s.isIdle)
        assertFalse(s.isPreFetching)
        assertEquals(payload, s.cachedOrNull)
    }

    @Test
    fun `PreFetching can carry null anchor`() {
        val s: TtsHandoffState<String> = TtsHandoffState.PreFetching(anchorChapterId = null)
        assertTrue(s.isPreFetching)
    }

    @Test
    fun `Cached payload type matches generic`() {
        val s: TtsHandoffState<Int> = TtsHandoffState.Cached(payload = 42)
        // Generic resolution returns the declared payload type, not Any.
        val payload: Int? = s.cachedOrNull
        assertEquals(42, payload)
    }
}
