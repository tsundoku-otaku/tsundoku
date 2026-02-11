package tachiyomi.domain.updates.interactor

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.updates.repository.UpdatesRepository

class RefreshUpdatesCacheTest {

    private val updatesRepository = mockk<UpdatesRepository>()
    private val refreshUpdatesCache = RefreshUpdatesCache(updatesRepository)

    @Test
    fun `await calls repository refreshUpdatesCache`() = runBlocking {
        // Arrange
        coEvery { updatesRepository.refreshUpdatesCache() } returns Unit

        // Act
        refreshUpdatesCache.await()

        // Assert
        coVerify { updatesRepository.refreshUpdatesCache() }
    }

    @Test
    fun `checkIntegrity returns true when counts match`() = runBlocking {
        // Arrange
        coEvery { updatesRepository.checkUpdatesCacheIntegrity() } returns Pair(100L, 100L)

        // Act
        val result = refreshUpdatesCache.checkIntegrity()

        // Assert
        assert(result) { "Cache should be valid when counts match" }
    }

    @Test
    fun `checkIntegrity returns false when counts mismatch`() = runBlocking {
        // Arrange
        coEvery { updatesRepository.checkUpdatesCacheIntegrity() } returns Pair(100L, 80L)

        // Act
        val result = refreshUpdatesCache.checkIntegrity()

        // Assert
        assert(!result) { "Cache should be invalid when counts don't match" }
    }

    @Test
    fun `ensureIntegrity calls await when cache is invalid`() = runBlocking {
        // Arrange
        coEvery { updatesRepository.checkUpdatesCacheIntegrity() } returns Pair(100L, 80L)
        coEvery { updatesRepository.refreshUpdatesCache() } returns Unit

        // Act
        refreshUpdatesCache.ensureIntegrity()

        // Assert
        coVerify { updatesRepository.refreshUpdatesCache() }
    }

    @Test
    fun `ensureIntegrity does not call await when cache is valid`() = runBlocking {
        // Arrange
        coEvery { updatesRepository.checkUpdatesCacheIntegrity() } returns Pair(100L, 100L)

        // Act
        refreshUpdatesCache.ensureIntegrity()

        // Assert
        coVerify(exactly = 0) { updatesRepository.refreshUpdatesCache() }
    }
}
