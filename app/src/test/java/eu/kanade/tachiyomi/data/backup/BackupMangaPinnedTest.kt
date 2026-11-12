package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupMangaPinnedTest {

    @Serializable
    private class BackupMangaBeforePinning(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(3) val title: String = "",
        @ProtoNumber(8000) val isNovel: Boolean = false,
        @ProtoNumber(8001) val alternativeTitles: List<String> = emptyList(),
    )

    @Test
    fun `pinned flag survives a backup round trip`() {
        val backup = BackupManga(source = 1, url = "/a", title = "A", isPinned = true)

        val decoded = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupManga.serializer(), backup),
        )

        assertTrue(decoded.isPinned)
        assertTrue(decoded.getMangaImpl().isPinned)
    }

    @Test
    fun `backups made before pinning existed restore as unpinned`() {
        val old = BackupMangaBeforePinning(source = 1, url = "/a", title = "A", isNovel = true)

        val decoded = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupMangaBeforePinning.serializer(), old),
        )

        assertFalse(decoded.isPinned)
        assertEquals("A", decoded.title)
        assertTrue(decoded.isNovel)
    }

    @Test
    fun `unpinned entries do not write the pin field`() {
        val unpinned = ProtoBuf.encodeToByteArray(BackupManga.serializer(), BackupManga(source = 1, url = "/a"))
        val pinned = ProtoBuf.encodeToByteArray(
            BackupManga.serializer(),
            BackupManga(source = 1, url = "/a", isPinned = true),
        )

        assertTrue(pinned.size > unpinned.size)
        assertFalse(ProtoBuf.decodeFromByteArray(BackupManga.serializer(), unpinned).isPinned)
    }
}
