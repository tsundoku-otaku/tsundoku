package mihon.core.archive

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile
import mihon.core.archive.ArchiveEntry as MihonArchiveEntry

/**
 * @param streamableZip read [buffer] as a stream of ZIP entries starting at a local entry header, as
 *   when opening a ZIP archive directly at one entry, instead of detecting the format of a whole archive.
 */
internal class ArchiveInputStream(buffer: Long, size: Long, streamableZip: Boolean = false) : InputStream() {
    private val lock = Any()

    @Volatile
    private var isClosed = false

    private val archive = Archive.readNew()

    init {
        try {
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            if (streamableZip) {
                Archive.readSupportFormatZipStreamable(archive)
            } else {
                Archive.readSupportFilterAll(archive)
                Archive.readSupportFormatAll(archive)
            }
            Archive.readOpenMemoryUnsafe(archive, buffer, size)
        } catch (e: ArchiveException) {
            close()
            throw e
        }
    }

    private val oneByteBuffer = ByteBuffer.allocateDirect(1)

    override fun read(): Int {
        read(oneByteBuffer)
        return if (oneByteBuffer.hasRemaining()) oneByteBuffer.get().toUByte().toInt() else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val buffer = ByteBuffer.wrap(b, off, len)
        read(buffer)
        return if (buffer.hasRemaining()) buffer.remaining() else -1
    }

    private fun read(buffer: ByteBuffer) {
        buffer.clear()
        Archive.readData(archive, buffer)
        buffer.flip()
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
        }

        Archive.readFree(archive)
    }

    /** Offset of the most recently read entry header within the archive. */
    fun headerPosition(): Long = Archive.readHeaderPosition(archive)

    /** Whether the most recently read entry header states the entry's size. */
    var entrySizeKnown = false
        private set

    fun getNextEntry(): MihonArchiveEntry? {
        return Archive.readNextHeader(archive).takeUnless { it == 0L }?.let { entry ->
            entrySizeKnown = ArchiveEntry.sizeIsSet(entry)
            val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.decodeToString() ?: return null
            val isFile = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG
            MihonArchiveEntry(name, isFile)
        }
    }
}
