package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.BackupProtoReader
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.protobuf.ProtoBuf
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,

    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(),
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context),
    private val extensionStoreRestorer: ExtensionStoreRestorer = ExtensionStoreRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(),
    private val parser: ProtoBuf = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
) {

    private var restoreAmount = 0
    private var restoreProgress = 0
    private val errors = mutableListOf<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        restoreFromFile(uri, options)

        if (options.libraryEntries || options.categories) {
            try {
                getLibraryManga.refreshForced()
            } catch (_: Exception) {
            }
        }

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val summary = readBackupSummary(uri)

        // Store source mapping for error messages
        sourceMapping = summary.backupSources.associate { it.sourceId to it.name }

        if (options.libraryEntries) {
            restoreAmount += summary.mangaCount
        }
        if (options.categories) {
            restoreAmount += 1
        }
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.extensionStores) {
            restoreAmount += summary.backupExtensionStores.size
        }
        if (options.sourceSettings) {
            restoreAmount += 1
        }

        coroutineScope {
            // Categories MUST be restored before preferences because preference restoration
            // maps backup category IDs → current DB category IDs by name.  Running both
            // concurrently causes restoreAppPreferences to see an empty categories table.
            val categoriesJob = if (options.categories) {
                restoreCategories(summary.backupCategories)
            } else {
                null
            }
            categoriesJob?.join()

            if (options.appSettings) {
                restoreAppPreferences(summary.backupPreferences, summary.backupCategories.takeIf { options.categories })
            }
            if (options.sourceSettings) {
                restoreSourcePreferences(summary.backupSourcePreferences)
            }
            if (options.libraryEntries) {
                restoreMangaStream(uri, if (options.categories) summary.backupCategories else emptyList(), options)
            }
            if (options.extensionStores) {
                restoreExtensionStores(summary.backupExtensionStores)
            }

            // TODO: optionally trigger online library + tracker update
        }
    }

    private suspend fun readBackupSummary(uri: Uri): BackupSummary {
        val backupCategories = mutableListOf<BackupCategory>()
        val backupSources = mutableListOf<BackupSource>()
        val backupPreferences = mutableListOf<BackupPreference>()
        val backupSourcePreferences = mutableListOf<BackupSourcePreferences>()
        val backupExtensionStores = mutableListOf<BackupExtensionStore>()
        var mangaCount = 0

        val reader = BackupProtoReader(context)
        reader.read(uri) { fieldNumber, data ->
            when (fieldNumber) {
                1 -> mangaCount++
                2 -> backupCategories.add(parser.decodeFromByteArray(BackupCategory.serializer(), data))
                101 -> backupSources.add(parser.decodeFromByteArray(BackupSource.serializer(), data))
                104 -> backupPreferences.add(parser.decodeFromByteArray(BackupPreference.serializer(), data))
                105 -> backupSourcePreferences.add(
                    parser.decodeFromByteArray(BackupSourcePreferences.serializer(), data),
                )
                106 -> backupExtensionStores.add(parser.decodeFromByteArray(BackupExtensionStore.serializer(), data))
            }
        }

        return BackupSummary(
            mangaCount = mangaCount,
            backupCategories = backupCategories,
            backupSources = backupSources,
            backupPreferences = backupPreferences,
            backupSourcePreferences = backupSourcePreferences,
            backupExtensionStores = backupExtensionStores,
        )
    }

    private fun CoroutineScope.restoreMangaStream(
        uri: Uri,
        backupCategories: List<BackupCategory>,
        options: RestoreOptions,
    ) = launch {
        val reader = BackupProtoReader(context)
        reader.read(uri) { fieldNumber, data ->
            if (fieldNumber != 1) return@read
            ensureActive()

            val backupManga = parser.decodeFromByteArray(BackupManga.serializer(), data)
            if ((!backupManga.isNovel && options.includeManga) || (backupManga.isNovel && options.includeNovels)) {
                try {
                    mangaRestorer.restore(backupManga, backupCategories)
                } catch (e: Exception) {
                    val sourceName = sourceMapping[backupManga.source] ?: backupManga.source.toString()
                    errors.add(Date() to "${backupManga.title} [$sourceName]: ${e.message}")
                }
            }

            restoreProgress += 1
            notifier.showRestoreProgress(backupManga.title, restoreProgress, restoreAmount, isSync)
        }
    }

    private data class BackupSummary(
        val mangaCount: Int,
        val backupCategories: List<BackupCategory>,
        val backupSources: List<BackupSource>,
        val backupPreferences: List<BackupPreference>,
        val backupSourcePreferences: List<BackupSourcePreferences>,
        val backupExtensionStores: List<BackupExtensionStore>,
    )

    private fun CoroutineScope.restoreCategories(backupCategories: List<BackupCategory>) = launch {
        ensureActive()
        categoriesRestorer(backupCategories)

        restoreProgress += 1
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.categories),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        restoreProgress += 1
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.app_settings),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences)

        restoreProgress += 1
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.source_settings),
            restoreProgress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreExtensionStores(
        backupExtensionStores: List<BackupExtensionStore>,
    ) = launch {
        backupExtensionStores
            .forEach {
                ensureActive()

                try {
                    extensionStoreRestorer(it)
                } catch (e: Exception) {
                    errors.add(Date() to "Error Adding Repo: ${it.name} : ${e.message}")
                }

                restoreProgress += 1
                notifier.showRestoreProgress(
                    context.stringResource(MR.strings.extensionStores),
                    restoreProgress,
                    restoreAmount,
                    isSync,
                )
            }
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("tsundoku_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (e: Exception) {
            // Empty
        }
        return File("")
    }
}
