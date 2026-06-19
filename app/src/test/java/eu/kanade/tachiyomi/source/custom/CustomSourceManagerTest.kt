package eu.kanade.tachiyomi.source.custom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class CustomSourceManagerTest {

    @Test
    fun `stable id is derived from name and base url`() {
        val firstId = stableCustomSourceId("Custom Source", "https://example.com")
        val secondId = stableCustomSourceId("Custom Source", "https://example.com")
        val differentId = stableCustomSourceId("Custom Source", "https://other.example")

        assertEquals(firstId, secondId)
        assertTrue(firstId > 0)
        assertTrue(firstId != differentId)
    }

    @Test
    fun `storage helper returns stable and legacy file names`() {
        val tempDir = Files.createTempDirectory("custom-source-manager-test").toFile()

        try {
            val candidates = customSourceStorageFileCandidates(tempDir, 123L, "My Custom Source")

            assertEquals(
                listOf(
                    File(tempDir, "123.json"),
                    File(tempDir, "My_Custom_Source.json"),
                ),
                candidates,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
