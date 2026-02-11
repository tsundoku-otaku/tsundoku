package tachiyomi.domain.history.interactor

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.repository.HistoryRepository

class RefreshHistoryCacheTest {

    private val historyRepository = mockk<HistoryRepository>()
    private val refreshHistoryCache = RefreshHistoryCache(historyRepository)

    @Test
    fun `await calls repository refreshHistoryCache`() = runBlocking {
        // Arrange
        coEvery { historyRepository.refreshHistoryCache() } returns Unit

        // Act
        refreshHistoryCache.await()

        // Assert
        coVerify { historyRepository.refreshHistoryCache() }
    }

    @Test
    fun `checkIntegrity returns true when counts match`() = runBlocking {
        // Arrange
        coEvery { historyRepository.checkHistoryCacheIntegrity() } returns Pair(10L, 10L)

        // Act
        val result = refreshHistoryCache.checkIntegrity()

        // Assert
        assert(result) { "Cache should be valid when counts match" }
    }

    @Test
    fun `checkIntegrity returns false when counts mismatch`() = runBlocking {
        // Arrange
        coEvery { historyRepository.checkHistoryCacheIntegrity() } returns Pair(10L, 5L)

        // Act
        val result = refreshHistoryCache.checkIntegrity()

        // Assert
        assert(!result) { "Cache should be invalid when counts don't match" }
    }

    @Test
    fun `ensureIntegrity calls await when cache is invalid`() = runBlocking {
        // Arrange
        coEvery { historyRepository.checkHistoryCacheIntegrity() } returns Pair(10L, 5L)
        coEvery { historyRepository.refreshHistoryCache() } returns Unit

        // Act
        refreshHistoryCache.ensureIntegrity()

        // Assert
        coVerify { historyRepository.refreshHistoryCache() }
    }

    @Test
    fun `ensureIntegrity does not call await when cache is valid`() = runBlocking {
        // Arrange
        coEvery { historyRepository.checkHistoryCacheIntegrity() } returns Pair(10L, 10L)

        // Act
        refreshHistoryCache.ensureIntegrity()

        // Assert
        coVerify(exactly = 0) { historyRepository.refreshHistoryCache() }
    }
}
