package eu.kanade.tachiyomi.data.backup.restore

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LNReaderBackupImporterTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes chapter booleans from newer LNReader JSON boolean export`() {
        val chapter = json.decodeFromString<LNReaderBackupImporter.LNChapter>(
            """{"id":1,"novelId":1,"path":"p","name":"n","bookmark":false,"unread":false,"isDownloaded":true}""",
        )

        assertEquals(0, chapter.bookmark)
        assertEquals(0, chapter.unread)
        assertEquals(1, chapter.isDownloaded)
    }

    @Test
    fun `decodes chapter booleans from older LNReader 0-1 integer export`() {
        val chapter = json.decodeFromString<LNReaderBackupImporter.LNChapter>(
            """{"id":1,"novelId":1,"path":"p","name":"n","bookmark":1,"unread":0,"isDownloaded":1}""",
        )

        assertEquals(1, chapter.bookmark)
        assertEquals(0, chapter.unread)
        assertEquals(1, chapter.isDownloaded)
    }

    @Test
    fun `decodes novel booleans from newer LNReader JSON boolean export`() {
        val novel = json.decodeFromString<LNReaderBackupImporter.LNNovel>(
            """{"id":1,"path":"p","pluginId":"x","name":"n","inLibrary":true,"isLocal":false}""",
        )

        assertEquals(1, novel.inLibrary)
        assertEquals(0, novel.isLocal)
    }

    @Test
    fun `decodes novel booleans from older LNReader 0-1 integer export`() {
        val novel = json.decodeFromString<LNReaderBackupImporter.LNNovel>(
            """{"id":1,"path":"p","pluginId":"x","name":"n","inLibrary":0,"isLocal":1}""",
        )

        assertEquals(0, novel.inLibrary)
        assertEquals(1, novel.isLocal)
    }
}
