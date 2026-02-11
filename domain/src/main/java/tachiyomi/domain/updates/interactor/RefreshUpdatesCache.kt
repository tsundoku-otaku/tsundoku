package tachiyomi.domain.updates.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.updates.repository.UpdatesRepository

/**
 * Interactor for refreshing the updates cache table.
 * 
 * The updates_cache table stores pre-computed chapter updates for favorite manga
 * that would otherwise require expensive JOINs on every query.
 * 
 * Triggers automatically update the cache on most operations, but this interactor
 * can be used to:
 * - Force a full cache refresh on app startup
 * - Refresh cache after bulk operations
 * - Verify cache integrity
 */
class RefreshUpdatesCache(
    private val updatesRepository: UpdatesRepository,
) {
    /**
     * Refresh the entire updates cache.
     * This is a relatively expensive operation and should be called sparingly.
     */
    suspend fun await() {
        logcat(LogPriority.INFO) { "RefreshUpdatesCache: Starting full cache refresh" }
        updatesRepository.refreshUpdatesCache()
        logcat(LogPriority.INFO) { "RefreshUpdatesCache: Cache refresh complete" }
    }

    /**
     * Check if the cache is valid (row counts match).
     * @return true if cache is valid, false if it needs refresh
     */
    suspend fun checkIntegrity(): Boolean {
        val (chaptersCount, cacheCount) = updatesRepository.checkUpdatesCacheIntegrity()
        val isValid = chaptersCount == cacheCount
        if (!isValid) {
            logcat(LogPriority.WARN) { 
                "RefreshUpdatesCache: Cache integrity check failed! " +
                "Favorite chapters: $chaptersCount, Cache: $cacheCount" 
            }
        }
        return isValid
    }

    /**
     * Check integrity and refresh if needed.
     * Useful for startup checks.
     */
    suspend fun ensureIntegrity() {
        if (!checkIntegrity()) {
            logcat(LogPriority.INFO) { "RefreshUpdatesCache: Cache invalid, triggering refresh" }
            await()
        } else {
            logcat(LogPriority.DEBUG) { "RefreshUpdatesCache: Cache integrity verified" }
        }
    }
}
