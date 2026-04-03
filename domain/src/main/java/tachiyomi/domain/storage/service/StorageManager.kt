package tachiyomi.domain.storage.service

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn

class StorageManager(
    private val context: Context,
    storagePreferences: StoragePreferences,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private var baseDir: UniFile? = getBaseDir(storagePreferences.baseStorageDirectory().get())

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        storagePreferences.baseStorageDirectory().changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                baseDir = getBaseDir(uri)
                baseDir?.let { parent ->
                    getOrCreateDirectory(parent, AUTOMATIC_BACKUPS_PATH)
                    getOrCreateDirectory(parent, LOCAL_SOURCE_PATH)
                    getOrCreateDirectory(parent, LOCAL_NOVEL_SOURCE_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                    getOrCreateDirectory(parent, LNREADER_PLUGINS_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                    getOrCreateDirectory(parent, FONTS_PATH)
                    getOrCreateDirectory(parent, TRANSLATIONS_PATH)
                    getOrCreateDirectory(parent, DOWNLOADS_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                }
                _changes.send(Unit)
            }
            .launchIn(scope)
    }

    private fun getBaseDir(uri: String): UniFile? {
        return UniFile.fromUri(context, uri.toUri())
            .takeIf { it?.exists() == true }
    }

    private fun getOrCreateDirectory(parent: UniFile?, name: String): UniFile? {
        parent ?: return null
        val existing = parent.findFile(name)
        if (existing?.isDirectory == true) return existing
        return parent.createDirectory(name)
    }

    fun getAutomaticBackupsDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, AUTOMATIC_BACKUPS_PATH)
    }

    fun getDownloadsDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, DOWNLOADS_PATH)
    }

    fun getLocalSourceDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, LOCAL_SOURCE_PATH)
    }

    fun getLocalNovelSourceDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, LOCAL_NOVEL_SOURCE_PATH)
    }

    fun getLNReaderPluginsDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, LNREADER_PLUGINS_PATH)
    }

    fun getFontsDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, FONTS_PATH)
    }

    fun getTranslationsDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, TRANSLATIONS_PATH)
    }

    fun getMassImportDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, MASS_IMPORT_PATH)
    }

    fun getQuotesDirectory(): UniFile? {
        return getOrCreateDirectory(baseDir, QUOTES_PATH)
    }
}

private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val LOCAL_SOURCE_PATH = "local"
private const val LOCAL_NOVEL_SOURCE_PATH = "localnovels"
private const val LNREADER_PLUGINS_PATH = "lnreader_plugins"
private const val FONTS_PATH = "fonts"
private const val TRANSLATIONS_PATH = "translations"
private const val MASS_IMPORT_PATH = "mass_import"
private const val QUOTES_PATH = "quotes"
