package eu.kanade.tachiyomi.ui.browse.source.custom

import android.app.Application
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.source.custom.CustomNovelSource
import eu.kanade.tachiyomi.source.custom.CustomSourceConfig
import eu.kanade.tachiyomi.source.custom.CustomSourceManager
import eu.kanade.tachiyomi.source.custom.SourceTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * ScreenModel for managing custom sources
 */
class CustomSourcesScreenModel(
    private val customSourceManager: CustomSourceManager = Injekt.get(),
) : ScreenModel {

    private val _customSources = MutableStateFlow<List<CustomNovelSource>>(emptyList())
    val customSources: StateFlow<List<CustomNovelSource>> = _customSources.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadSources()
    }

    private fun loadSources() {
        screenModelScope.launch {
            _isLoading.value = true
            try {
                customSourceManager.customSources.collect { sources ->
                    _customSources.value = sources
                }
            } catch (e: Exception) {
                val context = Injekt.get<Application>()
                _errorMessage.value = context.stringResource(TDMR.strings.custom_source_load_failed, e.message ?: "")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Get a specific source config by ID
     */
    fun getSourceConfig(sourceId: Long): CustomSourceConfig? {
        return _customSources.value.find { it.id == sourceId }?.config
    }

    /**
     * Create a blank source config with name and base URL.
     * Use the WebView wizard to fill in selectors, or edit manually.
     */
    fun createBlankConfig(
        name: String,
        baseUrl: String,
    ): CustomSourceConfig {
        return customSourceManager.createBlankConfig(name, baseUrl)
    }

    /**
     * Create a new custom source
     */
    suspend fun createSource(config: CustomSourceConfig): Result<Long> {
        return withContext(Dispatchers.IO) {
            try {
                customSourceManager.validateConfig(config)
                val result = customSourceManager.createSource(config)
                result.fold(
                    onSuccess = { source -> Result.success(source.id) },
                    onFailure = { Result.failure(it) },
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Update an existing custom source
     */
    suspend fun updateSource(sourceId: Long, config: CustomSourceConfig): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                customSourceManager.validateConfig(config)
                customSourceManager.updateSource(sourceId, config)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Delete a custom source
     */
    fun deleteSource(sourceId: Long) {
        screenModelScope.launch {
            try {
                customSourceManager.deleteSource(sourceId)
            } catch (e: Exception) {
                val context = Injekt.get<Application>()
                _errorMessage.value = context.stringResource(TDMR.strings.custom_source_delete_failed, e.message ?: "")
            }
        }
    }

    /**
     * Export a source to JSON
     */
    fun exportSource(sourceId: Long): String? {
        return try {
            customSourceManager.exportSource(sourceId)
        } catch (e: Exception) {
            val context = Injekt.get<Application>()
            _errorMessage.value = context.stringResource(TDMR.strings.custom_source_export_failed, e.message ?: "")
            null
        }
    }

    /**
     * Import a source from JSON
     */
    suspend fun importSource(json: String): Result<Long> {
        return withContext(Dispatchers.IO) {
            try {
                val result = customSourceManager.importSource(json)
                result.fold(
                    onSuccess = { source -> Result.success(source.id) },
                    onFailure = { Result.failure(it) },
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Export all custom sources as a single JSON array for bulk backup/share.
     */
    fun exportAllSources(): String? {
        return try {
            customSourceManager.exportAllSources()
        } catch (e: Exception) {
            val context = Injekt.get<Application>()
            _errorMessage.value = context.stringResource(TDMR.strings.custom_source_export_failed, e.message ?: "")
            null
        }
    }

    /**
     * Import multiple custom sources from a JSON array.
     */
    suspend fun importSources(json: String): Result<eu.kanade.tachiyomi.source.custom.BulkImportResult> {
        return withContext(Dispatchers.IO) {
            try {
                customSourceManager.importSources(json)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * A hand-editable skeleton config users can copy and fill in.
     */
    fun blankTemplateJson(): String = customSourceManager.blankTemplateJson()

    /**
     * Test a custom source
     */
    suspend fun testSource(sourceId: Long): SourceTestResult? {
        return withContext(Dispatchers.IO) {
            val config = getSourceConfig(sourceId) ?: return@withContext null
            customSourceManager.testSource(config)
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
}
