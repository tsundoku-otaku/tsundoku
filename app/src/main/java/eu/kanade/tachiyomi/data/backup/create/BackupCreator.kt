package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.source.isNovelSource
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

class BackupCreator(
    private val context: Context,
    private val isAutoBackup: Boolean,

    private val parser: ProtoBuf = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val backupPreferences: BackupPreferences = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val sourceManager: tachiyomi.domain.source.service.SourceManager = Injekt.get(),

    private val categoriesBackupCreator: CategoriesBackupCreator = CategoriesBackupCreator(),
    private val mangaBackupCreator: MangaBackupCreator = MangaBackupCreator(),
    private val preferenceBackupCreator: PreferenceBackupCreator = PreferenceBackupCreator(),
    private val extensionStoresBackupCreator: ExtensionStoresBackupCreator = ExtensionStoresBackupCreator(),
    private val sourcesBackupCreator: SourcesBackupCreator = SourcesBackupCreator(),
) {

    /**
     * Batch size for processing manga during backup.
     * Slightly larger batches improve throughput without large memory spikes.
     */
    private val MANGA_BATCH_SIZE = 20

    /**
     * How many lightweight favorite rows to pull from the DB per keyset page. Kept well above
     * MANGA_BATCH_SIZE but small enough that a 200k library is never fully resident at once.
     */
    private val MANGA_PAGE_SIZE = 500L

    /**
     * Maximum number of manga to hold in memory at once before flushing.
     * For very large libraries, we process in memory-bounded segments.
     */
    private val MAX_MANGA_IN_MEMORY = 200

    suspend fun backup(uri: Uri, options: BackupOptions, onProgress: ((Int, Int) -> Unit)? = null): String {
        var file: UniFile? = null
        try {
            file = if (isAutoBackup) {
                // Get dir of file and create
                val dir = UniFile.fromUri(context, uri)

                // Delete older backups
                dir?.listFiles { _, filename -> FILENAME_REGEX.matches(filename) }
                    .orEmpty()
                    .sortedByDescending { it.name }
                    .drop(MAX_AUTO_BACKUPS - 1)
                    .forEach { it.delete() }

                // Create new file to place backup
                dir?.createFile(getFilename())
            } else {
                UniFile.fromUri(context, uri)
            }

            if (file == null || !file.isFile) {
                throw IllegalStateException(context.stringResource(MR.strings.create_backup_file_error))
            }

            val backupCategories = backupCategories(options)
            val backupAppPrefs = backupAppPreferences(options)
            val backupExtensionStores = backupExtensionStores(options)
            val backupSourcePrefs = backupSourcePreferences(options)

            val outputStream = file.openOutputStream()
            (outputStream as? FileOutputStream)?.channel?.truncate(0)
            val gzipOut = outputStream.sink().gzip().buffer()

            // Track source IDs as we process manga for backupSources field
            val sourceIds = mutableSetOf<Long>()
            var written = 0
            val approxTotal = if (options.libraryEntries) mangaRepository.getFavoritesCount().toInt() else 0

            // Filter a batch by content-type options, fetch full details for the survivors, then
            // stream each BackupManga straight to the output so only one is resident at a time.
            suspend fun processBatch(batch: List<Manga>) {
                val filtered = batch.filter { manga ->
                    // manga.isNovel is reliable for stub/uninstalled sources; fall back to the live
                    // source check for entries that haven't been migrated.
                    val isNovel = manga.isNovel || sourceManager.getOrStub(manga.source).isNovelSource()
                    when {
                        options.includeManga && options.includeNovels -> true
                        options.includeManga && !isNovel -> true
                        options.includeNovels && isNovel -> true
                        else -> false
                    }
                }
                if (filtered.isEmpty()) return
                val fullBatch = filtered.map { manga ->
                    if (manga.description == null) mangaRepository.getMangaById(manga.id) else manga
                }
                mangaBackupCreator.backupMangaStream(fullBatch, options).collect { m ->
                    sourceIds.add(m.source)
                    val bytes = parser.encodeToByteArray(BackupManga.serializer(), m)
                    writeProtoField(gzipOut.outputStream(), 1, bytes)
                }
                written += filtered.size
                onProgress?.invoke(written, approxTotal.coerceAtLeast(written))
                kotlinx.coroutines.yield()
            }

            try {
                // Field 1: backupManga (repeated). Favorites are keyset-paged so a very large
                // library is never fully materialised in memory at once.
                if (options.libraryEntries) {
                    var afterId = 0L
                    while (true) {
                        val page = mangaRepository.getFavoritesEntryPaged(afterId, MANGA_PAGE_SIZE)
                        if (page.isEmpty()) break
                        afterId = page.last().id
                        page.chunked(MANGA_BATCH_SIZE).forEach { processBatch(it) }
                    }
                }
                if (options.readEntries) {
                    mangaRepository.getReadMangaNotInLibrary()
                        .chunked(MANGA_BATCH_SIZE)
                        .forEach { processBatch(it) }
                }

                logcat(LogPriority.INFO) { "Backup: All manga streamed ($written entries), writing metadata..." }

                val backupSources = sourcesBackupCreator.forSourceIds(sourceIds)

                // Field 2: backupCategories (repeated)
                backupCategories.forEach { c ->
                    val bytes = parser.encodeToByteArray(BackupCategory.serializer(), c)
                    writeProtoField(gzipOut.outputStream(), 2, bytes)
                }
                // Field 101: backupSources (repeated)
                backupSources.forEach { s ->
                    val bytes = parser.encodeToByteArray(BackupSource.serializer(), s)
                    writeProtoField(gzipOut.outputStream(), 101, bytes)
                }
                // Field 104: backupPreferences (repeated)
                backupAppPrefs.forEach { p ->
                    val bytes = parser.encodeToByteArray(BackupPreference.serializer(), p)
                    writeProtoField(gzipOut.outputStream(), 104, bytes)
                }
                // Field 105: backupSourcePreferences (repeated)
                backupSourcePrefs.forEach { sp ->
                    val bytes = parser.encodeToByteArray(BackupSourcePreferences.serializer(), sp)
                    writeProtoField(gzipOut.outputStream(), 105, bytes)
                }
                // Field 106: backupExtensionStores (repeated)
                backupExtensionStores.forEach { er ->
                    val bytes = parser.encodeToByteArray(BackupExtensionStore.serializer(), er)
                    writeProtoField(gzipOut.outputStream(), 106, bytes)
                }

                gzipOut.flush()
            } finally {
                gzipOut.close()
            }

            val fileUri = file.uri
            logcat(LogPriority.INFO) { "Backup: Write complete, validating..." }

            // Make sure it's a valid backup file
            BackupFileValidator(context).validate(fileUri)

            if (isAutoBackup) {
                backupPreferences.lastAutoBackupTimestamp.set(Instant.now().toEpochMilli())
            }

            return fileUri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            try {
                file?.delete()
            } catch (deleteError: Exception) {
                logcat(LogPriority.WARN, deleteError) { "Failed to delete partial backup file" }
            }
            throw e
        }
    }

    /** Write a single protobuf length-delimited field (wire type 2) to the stream. */
    private fun writeProtoField(out: OutputStream, fieldNumber: Int, data: ByteArray) {
        // Tag = (fieldNumber << 3) | 2 (length-delimited)
        writeVarint(out, (fieldNumber.toLong() shl 3) or 2L)
        writeVarint(out, data.size.toLong())
        out.write(data)
    }

    /** Write a varint (variable-length integer) in protobuf encoding. */
    private fun writeVarint(out: OutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    private suspend fun backupCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return categoriesBackupCreator()
    }

    private fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()

        return preferenceBackupCreator.createApp(includePrivatePreferences = options.privateSettings)
    }

    private suspend fun backupExtensionStores(options: BackupOptions): List<BackupExtensionStore> {
        if (!options.extensionStores) return emptyList()

        return extensionStoresBackupCreator()
    }

    private fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
        if (!options.sourceSettings) return emptyList()

        return preferenceBackupCreator.createSource(includePrivatePreferences = options.privateSettings)
    }

    companion object {
        private const val MAX_AUTO_BACKUPS: Int = 4
        private val FILENAME_REGEX = """${BuildConfig.APPLICATION_ID}_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}.tachibk""".toRegex()

        fun getFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())
            return "${BuildConfig.APPLICATION_ID}_$date.tachibk"
        }
    }
}
