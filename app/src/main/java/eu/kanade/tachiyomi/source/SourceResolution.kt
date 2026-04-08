package eu.kanade.tachiyomi.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.source.service.SourceManager

/**
 * Tries to resolve a real source instance after process restore where source registration can lag.
 * Returns null on timeout so callers can safely fall back to getOrStub().
 */
suspend fun SourceManager.awaitSource(
    sourceId: Long,
    timeoutMillis: Long = 5_000L,
    pollIntervalMillis: Long = 100L,
): Source? {
    get(sourceId)?.let { return it }

    return withTimeoutOrNull(timeoutMillis) {
        if (!isInitialized.value) {
            isInitialized.first { it }
        }

        var resolved: Source? = null
        while (resolved == null) {
            resolved = get(sourceId)
            if (resolved == null) {
                delay(pollIntervalMillis)
            }
        }
        resolved
    }
}
