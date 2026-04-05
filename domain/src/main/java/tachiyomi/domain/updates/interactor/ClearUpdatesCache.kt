package tachiyomi.domain.updates.interactor

import tachiyomi.domain.library.service.LibraryPreferences
import java.time.Instant

class ClearUpdatesCache(
    private val libraryPreferences: LibraryPreferences,
) {
    suspend fun clearAll() {
        libraryPreferences.lastUpdatesClearedTimestamp.set(Instant.now().toEpochMilli())
    }
}
