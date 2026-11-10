package mihon.core.archive

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.InputStream
import kotlin.concurrent.Volatile

class ArchiveReader(pfd: ParcelFileDescriptor) : Closeable {
    private val size = pfd.statSize
    private val address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)

    fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T = ArchiveInputStream(address, size).use {
        block(generateSequence { it.getNextEntry() })
    }

    /** Entry name to the offset of its header, once [indexEntries] has indexed a ZIP archive. */
    @Volatile
    private var entryOffsets: Map<String, Long>? = null

    /**
     * Records where every entry starts, so later [getInputStream] calls open an entry directly instead
     * of reading past every entry before it. Only ZIP-based archives (EPUB, CBZ) are indexed; for any
     * other format, or when the index cannot be built, lookups keep scanning from the start. Worth it
     * when many entries will be read from one open archive.
     */
    fun indexEntries() {
        if (entryOffsets != null) return
        entryOffsets = try {
            val names = useEntries { entries -> entries.map { it.name }.toList() }
            // Header positions are only file offsets when the entries are read as a stream of local
            // headers, so index with the streamable reader and trust it only if it lists the same entries.
            val offsets = ArchiveInputStream(address, size, streamableZip = true).use { archive ->
                buildMap {
                    while (true) {
                        val entry = archive.getNextEntry() ?: break
                        putIfAbsent(entry.name, archive.headerPosition())
                    }
                }
            }
            offsets.takeIf { it.keys == names.toSet() }
        } catch (e: ArchiveException) {
            null
        }
    }

    fun getInputStream(entryName: String): InputStream? {
        entryOffsets?.let { offsets ->
            // The index lists every entry, so a name it lacks is not in the archive.
            val offset = offsets[entryName] ?: return null
            openEntryAt(offset, entryName)?.let { return it }
        }
        val archive = ArchiveInputStream(address, size)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name == entryName) {
                    return archive
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
        return null
    }

    /** Opens the archive at the header of [entryName], or returns null if it is not found there. */
    private fun openEntryAt(offset: Long, entryName: String): InputStream? {
        if (offset !in 0..<size) return null
        val archive = try {
            ArchiveInputStream(address + offset, size - offset, streamableZip = true)
        } catch (e: ArchiveException) {
            return null
        }
        val found = try {
            // An entry whose size only follows its data is left to the regular lookup.
            archive.getNextEntry()?.name == entryName && archive.entrySizeKnown
        } catch (e: ArchiveException) {
            false
        }
        if (found) return archive
        archive.close()
        return null
    }

    override fun close() {
        Os.munmap(address, size)
    }
}
