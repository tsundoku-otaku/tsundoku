package tachiyomi.domain.history.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.history.repository.HistoryRepository

/**
 * Interactor for refreshing the history cache table.
 * 
 * The history_cache table stores pre-computed aggregates (latest read chapter per manga)
 * that would otherwise require expensive JOINs on every query.
 * 
 * Triggers automatically update the cache on most operations, but this interactor
 * can be used to:
 * - Force a full cache refresh on app startup
 * - Refresh cache after bulk operations
 * - Verify cache integrity
 */
class RefreshHistoryCache(
    private val historyRepository: HistoryRepository,
) {
    /**
     * Refresh the entire history cache.
     * This is a relatively expensive operation and should be called sparingly.
     */
    suspend fun await() {
        logcat(LogPriority.INFO) { "RefreshHistoryCache: Starting full cache refresh" }
        historyRepository.refreshHistoryCache()
        logcat(LogPriority.INFO) { "RefreshHistoryCache: Cache refresh complete" }
    }

    /**
     * Check if the cache is valid (row counts match).
     * @return true if cache is valid, false if it needs refresh
     */
    suspend fun checkIntegrity(): Boolean {
        val (historyCount, cacheCount) = historyRepository.checkHistoryCacheIntegrity()
        val isValid = historyCount == cacheCount
        if (!isValid) {
            logcat(LogPriority.WARN) { 
                "RefreshHistoryCache: Cache integrity check failed! " +
                "History manga: $historyCount, Cache: $cacheCount" 
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
            logcat(LogPriority.INFO) { "RefreshHistoryCache: Cache invalid, triggering refresh" }
            await()
        } else {
            logcat(LogPriority.DEBUG) { "RefreshHistoryCache: Cache integrity verified" }
        }
    }
}
