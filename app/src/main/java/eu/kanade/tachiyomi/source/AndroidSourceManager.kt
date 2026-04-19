package eu.kanade.tachiyomi.source

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.source.custom.CustomSourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalNovelSource
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap

class AndroidSourceManager(
    private val context: Context,
    private val extensionManager: ExtensionManager,
    private val sourceRepository: StubSourceRepository,
) : SourceManager {

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val downloadManager: DownloadManager by injectLazy()
    private val customSourceManager: CustomSourceManager by injectLazy()
    private val jsPluginManager: JsPluginManager by injectLazy()

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    private val sourcesMapFlow = MutableStateFlow(ConcurrentHashMap<Long, Source>())

    private val stubSourcesMap = ConcurrentHashMap<Long, StubSource>()

    override val catalogueSources: Flow<List<CatalogueSource>> = sourcesMapFlow.map {
        it.values.filterIsInstance<CatalogueSource>()
    }

    init {
        scope.launch {
            combine(
                extensionManager.installedExtensionsFlow,
                customSourceManager.customSources,
                jsPluginManager.jsSources,
            ) { extensions, customSources, jsSources ->
                val mutableMap = ConcurrentHashMap<Long, Source>(
                    mapOf(
                        LocalSource.ID to LocalSource(
                            context,
                            Injekt.get(),
                            Injekt.get(),
                        ),
                        LocalNovelSource.ID to LocalNovelSource(
                            context,
                            Injekt.get(),
                            Injekt.get(),
                        ),
                    ),
                )

                extensions.forEach { extension ->
                    extension.sources.forEach {
                        mutableMap[it.id] = it
                        registerStubSource(StubSource.from(it))
                    }
                }

                customSources.forEach { customSource ->
                    mutableMap[customSource.id] = customSource
                    registerStubSource(StubSource.from(customSource))
                }

                jsSources.forEach { jsSource ->
                    mutableMap[jsSource.id] = jsSource
                    registerStubSource(StubSource.from(jsSource))
                }

                mutableMap
            }.collectLatest { sources ->
                sourcesMapFlow.value = sources
                if (!_isInitialized.value) {
                    extensionManager.isInitialized.first { it }
                    jsPluginManager.isInitialized.first { it }
                }
                _isInitialized.value = true
            }
        }

        scope.launch {
            sourceRepository.subscribeAll()
                .collectLatest { sources ->
                    stubSourcesMap.clear()
                    sources.forEach {
                        stubSourcesMap[it.id] = it
                    }
                }
        }
    }

    override fun get(sourceKey: Long): Source? {
        return sourcesMapFlow.value[sourceKey]
    }

    override fun getOrStub(sourceKey: Long): Source {
        return sourcesMapFlow.value[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            runBlocking { createStubSource(sourceKey) }
        }
    }

    override fun getOnlineSources() = sourcesMapFlow.value.values.filterIsInstance<HttpSource>()

    override fun getCatalogueSources() = sourcesMapFlow.value.values.filterIsInstance<CatalogueSource>()

    override fun getStubSources(): List<StubSource> {
        val onlineSourceIds = getOnlineSources().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    private fun registerStubSource(source: StubSource) {
        scope.launch {
            val dbSource = sourceRepository.getStubSource(source.id)
            if (dbSource == source) return@launch
            sourceRepository.upsertStubSource(source.id, source.lang, source.name, source.isNovelSource)
            if (dbSource != null) {
                downloadManager.renameSource(dbSource, source)
            }
        }
    }

    private suspend fun createStubSource(id: Long): StubSource {
        sourceRepository.getStubSource(id)?.let {
            return it
        }
        extensionManager.getSourceData(id)?.let {
            registerStubSource(it)
            return it
        }
        return StubSource(id = id, lang = "", name = "")
    }
}
