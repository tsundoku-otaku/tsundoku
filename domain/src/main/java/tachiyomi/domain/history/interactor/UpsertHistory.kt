package tachiyomi.domain.history.interactor

import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository

class UpsertHistory(
    private val historyRepository: HistoryRepository,
) {

    suspend fun await(historyUpdate: HistoryUpdate) {
        historyRepository.upsertHistory(historyUpdate)
    }

    /** Adds read duration without moving last_read on an existing row (novel readers). */
    suspend fun awaitTimeReadOnly(historyUpdate: HistoryUpdate) {
        historyRepository.upsertHistoryTimeRead(historyUpdate)
    }
}
