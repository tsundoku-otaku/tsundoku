package tachiyomi.domain.history.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations

interface HistoryRepository {

    fun getHistory(query: String, limit: Long = Long.MAX_VALUE): Flow<List<HistoryWithRelations>>

    fun getHistoryGrouped(query: String, limit: Long = Long.MAX_VALUE): Flow<List<HistoryWithRelations>>

    suspend fun getLastHistory(): HistoryWithRelations?

    suspend fun getTotalReadDuration(): Long

    suspend fun getHistoryByMangaId(mangaId: Long): List<History>

    suspend fun resetHistory(historyId: Long)

    suspend fun resetHistoryByMangaId(mangaId: Long)

    suspend fun deleteAllHistory(): Boolean

    suspend fun upsertHistory(historyUpdate: HistoryUpdate)

    /** Adds read duration without moving last_read on an existing row (novel readers). */
    suspend fun upsertHistoryTimeRead(historyUpdate: HistoryUpdate)
}
