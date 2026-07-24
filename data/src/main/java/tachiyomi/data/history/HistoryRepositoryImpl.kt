package tachiyomi.data.history

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

class HistoryRepositoryImpl(
    private val database: Database,
) : HistoryRepository {

    /**
     * Maps direct JOIN query columns to HistoryWithRelations.
     */
    private fun mapHistoryWithRelations(
        id: Long,
        mangaId: Long,
        chapterId: Long,
        title: String,
        thumbnailUrl: String?,
        source: Long,
        favorite: Boolean,
        coverLastModified: Long,
        chapterNumber: Double,
        readAt: Long,
        readDuration: Long,
        chapterRead: Boolean,
        lastPageRead: Long,
        isNovel: Boolean,
    ): HistoryWithRelations = HistoryWithRelations(
        id = id,
        chapterId = chapterId,
        mangaId = mangaId,
        title = title,
        chapterNumber = chapterNumber,
        readAt = if (readAt > 0) Date(readAt) else null,
        readDuration = readDuration,
        chapterRead = chapterRead,
        lastPageRead = lastPageRead,
        isNovel = isNovel,
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = source,
            isMangaFavorite = favorite,
            url = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )

    override fun getHistory(query: String, limit: Long): Flow<List<HistoryWithRelations>> {
        return database.historyQueries.getHistoryWithRelations(
            query,
            limit,
            ::mapHistoryWithRelations,
        ).subscribeToList()
    }

    override fun getHistoryGrouped(query: String, limit: Long): Flow<List<HistoryWithRelations>> {
        return database.historyQueries.getHistoryWithRelationsGrouped(
            query,
            limit,
            ::mapHistoryWithRelations,
        ).subscribeToList()
    }

    override suspend fun getLastHistory(): HistoryWithRelations? {
        return database.historyQueries.getLatestHistory(::mapHistoryWithRelations).awaitAsOneOrNull()
    }

    override suspend fun getTotalReadDuration(): Long {
        return database.historyQueries.getReadDuration().awaitAsOne()
    }

    override suspend fun getHistoryByMangaId(mangaId: Long): List<History> {
        return database.historyQueries.getHistoryByMangaId(mangaId, HistoryMapper::mapHistory).awaitAsList()
    }

    override suspend fun resetHistory(historyId: Long) {
        try {
            database.historyQueries.resetHistoryById(historyId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun resetHistoryByMangaId(mangaId: Long) {
        try {
            database.historyQueries.resetHistoryByMangaId(mangaId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun deleteAllHistory(): Boolean {
        return try {
            database.historyQueries.removeAllHistory()
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            false
        }
    }

    override suspend fun upsertHistory(historyUpdate: HistoryUpdate) {
        try {
            database.historyQueries.upsert(
                historyUpdate.chapterId,
                historyUpdate.readAt,
                historyUpdate.sessionReadDuration,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun upsertHistoryTimeRead(historyUpdate: HistoryUpdate) {
        try {
            database.historyQueries.upsertTimeRead(
                historyUpdate.chapterId,
                historyUpdate.readAt,
                historyUpdate.sessionReadDuration,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }
}
