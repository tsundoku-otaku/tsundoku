package eu.kanade.tachiyomi.data.backup

import java.io.ByteArrayOutputStream

/**
This is a temporary migration fix to keep compatibility with old backup files that use isNovel proto number 112 before it was moved to 8000
 TODO: Remove this class after a few releases.
 */
internal object BackupProtoMigration {
    private const val BACKUP_MANGA_FIELD = 1
    private const val LEGACY_IS_NOVEL_FIELD = 112
    private const val IS_NOVEL_FIELD = 8000

    private const val WIRE_VARINT = 0
    private const val WIRE_I64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_I32 = 5

    fun migrateLegacyIsNovel(input: ByteArray): ByteArray {
        return try {
            if (!hasLegacyIsNovel(input)) return input
            val reader = Reader(input)
            val out = ByteArrayOutputStream(input.size + 16)
            var changed = false
            while (reader.hasRemaining()) {
                val tag = reader.readVarint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x7).toInt()
                if (field == BACKUP_MANGA_FIELD && wire == WIRE_LEN) {
                    val body = reader.readLengthDelimited()
                    val migrated = migrateManga(body)
                    if (migrated !== body) changed = true
                    writeTag(out, BACKUP_MANGA_FIELD, WIRE_LEN)
                    writeVarint(out, migrated.size.toLong())
                    out.write(migrated)
                } else {
                    writeVarint(out, tag)
                    copyPayload(reader, out, wire)
                }
            }
            if (changed) out.toByteArray() else input
        } catch (_: Exception) {
            input
        }
    }

    private fun hasLegacyIsNovel(buf: ByteArray): Boolean {
        val reader = Reader(buf)
        while (reader.hasRemaining()) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            if (field == BACKUP_MANGA_FIELD && wire == WIRE_LEN) {
                val len = reader.readVarint().toInt()
                val end = reader.pos + len
                if (mangaHasLegacyIsNovel(buf, reader.pos, end)) return true
                reader.pos = end
            } else {
                skipPayload(reader, wire)
            }
        }
        return false
    }

    private fun mangaHasLegacyIsNovel(buf: ByteArray, start: Int, end: Int): Boolean {
        val reader = Reader(buf)
        reader.pos = start
        while (reader.pos < end) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            if (field == LEGACY_IS_NOVEL_FIELD && wire == WIRE_VARINT) return true
            skipPayload(reader, wire)
        }
        return false
    }

    private fun skipPayload(reader: Reader, wire: Int) {
        when (wire) {
            WIRE_VARINT -> reader.readVarint()
            WIRE_I64 -> reader.skip(8)
            WIRE_LEN -> reader.skip(reader.readVarint().toInt())
            WIRE_I32 -> reader.skip(4)
            else -> throw IllegalStateException("Unsupported wire type $wire")
        }
    }

    private fun migrateManga(body: ByteArray): ByteArray {
        val reader = Reader(body)
        val out = ByteArrayOutputStream(body.size + 2)
        var changed = false
        while (reader.hasRemaining()) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            if (field == LEGACY_IS_NOVEL_FIELD && wire == WIRE_VARINT) {
                writeTag(out, IS_NOVEL_FIELD, WIRE_VARINT)
                writeVarint(out, reader.readVarint())
                changed = true
            } else {
                writeVarint(out, tag)
                copyPayload(reader, out, wire)
            }
        }
        return if (changed) out.toByteArray() else body
    }

    private fun copyPayload(reader: Reader, out: ByteArrayOutputStream, wire: Int) {
        when (wire) {
            WIRE_VARINT -> writeVarint(out, reader.readVarint())
            WIRE_I64 -> out.write(reader.readBytes(8))
            WIRE_LEN -> {
                val body = reader.readLengthDelimited()
                writeVarint(out, body.size.toLong())
                out.write(body)
            }
            WIRE_I32 -> out.write(reader.readBytes(4))
            else -> throw IllegalStateException("Unsupported wire type $wire")
        }
    }

    private fun writeTag(out: ByteArrayOutputStream, field: Int, wire: Int) =
        writeVarint(out, (field.toLong() shl 3) or wire.toLong())

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) {
                out.write(b or 0x80)
            } else {
                out.write(b)
                return
            }
        }
    }

    private class Reader(private val buf: ByteArray) {
        var pos = 0

        fun hasRemaining() = pos < buf.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun readBytes(n: Int): ByteArray {
            val slice = buf.copyOfRange(pos, pos + n)
            pos += n
            return slice
        }

        fun readLengthDelimited(): ByteArray = readBytes(readVarint().toInt())

        fun skip(n: Int) {
            pos += n
        }
    }
}
