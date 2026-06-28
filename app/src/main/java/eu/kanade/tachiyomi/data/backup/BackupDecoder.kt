package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.buffer
import okio.gzip
import okio.source
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class BackupDecoder(
    private val context: Context,
    private val parser: ProtoBuf = Injekt.get(),
) {
    /**
     * Decode a potentially-gzipped backup.
     */
    fun decode(uri: Uri): Backup {
        return context.contentResolver.openInputStream(uri)!!.use { inputStream ->
            val source = inputStream.source().buffer()

            val peeked = source.peek().apply {
                require(2)
            }
            val id1id2 = peeked.readShort().toInt()

            val payloadSource = when (id1id2) {
                0x1f8b -> source.gzip().buffer() // 0x1f8b is gzip magic bytes
                MAGIC_JSON_SIGNATURE1, MAGIC_JSON_SIGNATURE2, MAGIC_JSON_SIGNATURE3 -> {
                    throw IOException(context.stringResource(MR.strings.invalid_backup_file_json))
                }
                else -> source
            }

            val backupBytes = payloadSource.use { readWithSizeLimit(it, MAX_BACKUP_BYTES) }
                .let { BackupProtoMigration.migrateLegacyIsNovel(it) }
            try {
                parser.decodeFromByteArray(Backup.serializer(), backupBytes)
            } catch (_: SerializationException) {
                throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
            }
        }
    }

    private fun readWithSizeLimit(source: okio.BufferedSource, maxBytes: Long): ByteArray {
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, READ_CHUNK_BYTES)
            if (read == -1L) break
            total += read
            if (total > maxBytes) {
                throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
            }
        }
        return buffer.readByteArray()
    }

    companion object {
        private const val READ_CHUNK_BYTES = 8_192L
        private const val MAX_BACKUP_BYTES = 256L * 1024L * 1024L
        private const val MAGIC_JSON_SIGNATURE1 = 0x7b7d // `{}`
        private const val MAGIC_JSON_SIGNATURE2 = 0x7b22 // `{"`
        private const val MAGIC_JSON_SIGNATURE3 = 0x7b0a // `{\n`
    }
}
