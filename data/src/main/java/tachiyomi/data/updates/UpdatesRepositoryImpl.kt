package tachiyomi.data.updates

import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository

class UpdatesRepositoryImpl(
    private val databaseHandler: DatabaseHandler,
) : UpdatesRepository {

    override suspend fun awaitWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
    ): List<UpdatesWithRelations> {
        return databaseHandler.awaitList {
            // Use updates_cache table for faster queries
            updates_cacheQueries.getUpdatesByReadStatus(
                read = read,
                after = after,
                limit = limit,
                mapper = ::mapUpdatesWithRelations,
            )
        }
    }

    override fun subscribeAll(after: Long, limit: Long): Flow<List<UpdatesWithRelations>> {
        return databaseHandler.subscribeToList {
            // Use updates_cache table for faster queries
            updates_cacheQueries.getRecentUpdates(after, limit, ::mapUpdatesWithRelations)
        }
    }

    override fun subscribeWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
    ): Flow<List<UpdatesWithRelations>> {
        return databaseHandler.subscribeToList {
            // Use updates_cache table for faster queries
            updates_cacheQueries.getUpdatesByReadStatus(
                read = read,
                after = after,
                limit = limit,
                mapper = ::mapUpdatesWithRelations,
            )
        }
    }

    /**
     * Clear all updates cache using batch deletion approach.
     * Deletes in batches of 100,000 rows to avoid blocking the database.
     * Runs VACUUM afterwards to reclaim space.
     */
    override suspend fun clearAllUpdates() {
        val batchSize = 100_000L
        var totalDeleted = 0L

        // Keep deleting batches until no more rows
        do {
            val deletedInBatch = databaseHandler.await {
                updates_cacheQueries.deleteBatch(batchSize)
                // SQLDelight doesn't return affected rows directly, so we track progress via count
                batchSize // Assume we deleted up to batchSize
            }

            // Check if there are more rows to delete
            val remaining = databaseHandler.awaitOneOrNull {
                updates_cacheQueries.countAll()
            } ?: 0L

            totalDeleted += if (remaining == 0L) batchSize else deletedInBatch

            // Small delay between batches to allow other operations
            if (remaining > 0) {
                kotlinx.coroutines.delay(10)
            }
        } while (databaseHandler.awaitOneOrNull { updates_cacheQueries.countAll() } ?: 0L > 0)

        // Run VACUUM to reclaim space
        databaseHandler.vacuum()
    }

    override suspend fun clearUpdatesOlderThan(timestamp: Long) {
        databaseHandler.await { updates_cacheQueries.deleteOlderThan(timestamp) }
    }

    override suspend fun clearUpdatesKeepLatest(keep: Long) {
        // Delete all entries except the newest `keep` rows by date_fetch.
        // This is useful to quickly trim the cache to a fixed size (e.g., 100 latest entries).
        databaseHandler.await { updates_cacheQueries.deleteKeepLatest(keep) }
        // Optionally reclaim space.
        databaseHandler.vacuum()
    }

    private fun mapUpdatesWithRelations(
        mangaId: Long,
        mangaTitle: String,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
        sourceId: Long,
        favorite: Boolean,
        thumbnailUrl: String?,
        coverLastModified: Long,
        dateUpload: Long,
        dateFetch: Long,
    ): UpdatesWithRelations = UpdatesWithRelations(
        mangaId = mangaId,
        mangaTitle = mangaTitle,
        chapterId = chapterId,
        chapterName = chapterName,
        scanlator = scanlator,
        chapterUrl = chapterUrl,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        sourceId = sourceId,
        dateFetch = dateFetch,
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = sourceId,
            isMangaFavorite = favorite,
            url = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )

    override suspend fun refreshUpdatesCache() {
        logcat(LogPriority.INFO) { "UpdatesRepositoryImpl.refreshUpdatesCache: Refreshing entire updates cache" }
        val queryStart = System.currentTimeMillis()
        databaseHandler.await(inTransaction = true) {
            updates_cacheQueries.refreshAll()
        }
        val queryDuration = System.currentTimeMillis() - queryStart
        logcat(LogPriority.INFO) { "UpdatesRepositoryImpl.refreshUpdatesCache: Cache refresh completed in ${queryDuration}ms" }
    }

    override suspend fun checkUpdatesCacheIntegrity(): Pair<Long, Long> {
        val chaptersCount = databaseHandler.awaitOne { chaptersQueries.countFavoriteChapters() }
        val cacheCount = databaseHandler.awaitOne { updates_cacheQueries.countAll() }
        return Pair(chaptersCount, cacheCount)
    }
}
