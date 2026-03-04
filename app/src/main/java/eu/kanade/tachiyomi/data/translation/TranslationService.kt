package eu.kanade.tachiyomi.data.translation

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.translation.engine.LibreTranslateEngine
import eu.kanade.tachiyomi.source.fetchNovelPageText
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.model.TranslatedChapter
import tachiyomi.domain.translation.model.TranslationProgress
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.model.TranslationStatus
import tachiyomi.domain.translation.model.TranslationTask
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Service for managing translation queue and executing translations.
 */
class TranslationService(
    private val context: Context,
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val translationEngineManager: TranslationEngineManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val translatedChapterRepository: TranslatedChapterRepository = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val updateChapter: tachiyomi.domain.chapter.interactor.UpdateChapter = Injekt.get(),
) {
    // Lazy-loaded to avoid circular dependency during initialization
    private val downloadManager: DownloadManager by lazy { Injekt.get() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val queue = ConcurrentLinkedQueue<TranslationTask>()

    private val _queueState = MutableStateFlow<List<TranslationTask>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private var translationJob: Job? = null

    private val _progressState = MutableStateFlow(
        TranslationProgress(
            totalChapters = 0,
            completedChapters = 0,
            currentChapterName = null,
            currentChapterProgress = 0f,
            isRunning = false,
            isPaused = false,
        ),
    )
    val progressState = _progressState.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    /**
     * Add a chapter to the translation queue.
     * @param forceRetranslate if true, skip the "already translated" check.
     */
    fun enqueue(
        manga: Manga,
        chapter: Chapter,
        priority: Int = 0,
        forceRetranslate: Boolean = false,
    ) {
        if (!translationPreferences.translationEnabled().get()) return

        val task = TranslationTask(
            chapterId = chapter.id,
            mangaId = manga.id,
            sourceLanguage = translationPreferences.sourceLanguage().get(),
            targetLanguage = translationPreferences.targetLanguage().get(),
            engineId = 0, // Will be determined at translation time
            priority = priority,
            status = TranslationStatus.QUEUED,
            retryCount = 0,
            forceRetranslate = forceRetranslate,
        )

        // Add to queue if not already present
        if (queue.none { it.chapterId == chapter.id }) {
            queue.add(task)
            updateProgress()
            // Auto-start queue processing if not already running
            start()
        }
    }

    /**
     * Add multiple chapters to the translation queue.
     * @param forceRetranslate if true, retranslate even if already translated.
     */
    fun enqueueAll(
        manga: Manga,
        chapters: List<Chapter>,
        priority: Int = 0,
        forceRetranslate: Boolean = false,
    ) {
        if (!translationPreferences.translationEnabled().get()) return

        chapters.forEach { chapter ->
            enqueue(manga, chapter, priority, forceRetranslate)
        }
    }

    /**
     * Remove a chapter from the queue.
     */
    fun dequeue(chapterId: Long) {
        queue.removeIf { it.chapterId == chapterId }
        updateProgress()
    }

    /**
     * Clear all items from the queue.
     */
    fun clearQueue() {
        queue.clear()
        updateProgress()
    }

    /**
     * Start processing the translation queue.
     */
    fun start() {
        if (translationJob?.isActive == true) return

        _isPaused.value = false

        translationJob = scope.launch {
            while (isActive && queue.isNotEmpty()) {
                if (_isPaused.value) {
                    delay(500)
                    continue
                }

                // Get highest priority task
                val task = queue.poll() ?: break

                try {
                    // Resolve chapter name for progress display
                    val chapterNameForProgress = try {
                        getChapter.await(task.chapterId)?.name ?: "Chapter ${task.chapterId}"
                    } catch (_: Exception) {
                        "Chapter ${task.chapterId}"
                    }

                    _progressState.update { current ->
                        current.copy(
                            isRunning = true,
                            currentChapterName = chapterNameForProgress,
                            currentChapterProgress = 0f,
                        )
                    }

                    translateChapter(task)

                    _progressState.update { current ->
                        current.copy(
                            completedChapters = current.completedChapters + 1,
                            currentChapterProgress = 1f,
                        )
                    }

                    // Rate limiting delay
                    val delayMs = translationPreferences.rateLimitDelayMs().get()
                    if (delayMs > 0) {
                        delay(delayMs.toLong())
                    }
                } catch (e: CancellationException) {
                    // Translation was cancelled (e.g., user navigated away), don't log as error
                    logcat(LogPriority.DEBUG) { "Translation cancelled for chapter ${task.chapterId}" }
                    throw e // Re-throw to stop processing
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Translation failed for chapter ${task.chapterId}" }
                    // Re-queue failed task with lower priority if retry count is low
                    val retryCount = task.retryCount + 1
                    if (retryCount < 2) {
                        queue.add(
                            task.copy(
                                status = TranslationStatus.FAILED,
                                errorMessage = e.message,
                                retryCount = retryCount,
                            ),
                        )
                    } else {
                        logcat(LogPriority.ERROR) { "Max retries reached for chapter ${task.chapterId}, skipping" }
                    }
                }
            }

            _progressState.update { current ->
                current.copy(
                    isRunning = false,
                    currentChapterName = null,
                    currentChapterProgress = 0f,
                )
            }
        }
    }

    /**
     * Pause translation processing.
     */
    fun pause() {
        _isPaused.value = true
        _progressState.update { it.copy(isPaused = true) }
    }

    /**
     * Resume translation processing.
     */
    fun resume() {
        _isPaused.value = false
        _progressState.update { it.copy(isPaused = false) }
        if (translationJob?.isActive != true && queue.isNotEmpty()) {
            start()
        }
    }

    /**
     * Stop translation processing.
     */
    fun stop() {
        translationJob?.cancel()
        translationJob = null
        _progressState.update {
            it.copy(
                isRunning = false,
                isPaused = false,
                isCancelling = false,
                currentChapterName = null,
                currentChapterProgress = 0f,
                currentChunkIndex = 0,
                totalChunks = 0,
            )
        }
    }

    /**
     * Request cancellation of the current translation.
     * The current chunk will finish, then partial progress will be saved.
     */
    fun cancel() {
        _progressState.update { it.copy(isCancelling = true) }
    }

    /**
     * Check if translation service is running.
     */
    fun isRunning(): Boolean = translationJob?.isActive == true

    /**
     * Get the current queue size.
     */
    fun queueSize(): Int = queue.size

    /**
     * Translate a single chapter.
     * Supports partial translation: if some chunks fail, the partial result is saved
     * with a .tmp extension and can be resumed on the next attempt.
     */
    private suspend fun translateChapter(task: TranslationTask) = withContext(Dispatchers.IO) {
        val engine = translationEngineManager.getEngine()
            ?: throw IllegalStateException("No translation engine available")

        // Get chapter and manga from database
        val chapter = getChapter.await(task.chapterId)
            ?: throw IllegalStateException("Chapter ${task.chapterId} not found")
        val manga = getManga.await(task.mangaId)
            ?: throw IllegalStateException("Manga ${task.mangaId} not found")
        val source = sourceManager.get(manga.source)
            ?: throw IllegalStateException("Source ${manga.source} not found")

        logcat(LogPriority.DEBUG) { "Starting translation for chapter: ${chapter.name}" }

        // Check if already translated (skip unless forceRetranslate)
        if (!task.forceRetranslate) {
            val existingTranslation = translatedChapterRepository.getTranslatedChapter(task.chapterId, task.targetLanguage)
            if (existingTranslation != null) {
                logcat(LogPriority.DEBUG) { "Chapter ${chapter.name} already translated, skipping" }
                return@withContext
            }
        }

        if (translationPreferences.smartAutoTranslate().get()) {
            val sourceLang = source.lang
            if (sourceLang.isNotBlank() && sourceLang != "all" && sourceLang != "other") {
                if (sourceLang == task.targetLanguage) {
                    logcat(LogPriority.DEBUG) { "Source language ($sourceLang) matches target language, skipping translation" }
                    return@withContext
                }
            }
        }

        // Try to get content from downloaded chapter first, fall back to fetching from source
        val allContent = getChapterContent(chapter, manga, source)

        if (allContent.isBlank()) {
            logcat(LogPriority.WARN) { "No text content found in chapter" }
            return@withContext
        }

        if (task.sourceLanguage == "auto" && translationPreferences.smartAutoTranslate().get()) {
            val detected = detectLanguage(allContent, task.mangaId)
            if (detected != null && detected == task.targetLanguage) {
                logcat(LogPriority.DEBUG) { "Detected language ($detected) matches target language, skipping translation" }
                return@withContext
            }
        }

        // Extract and preserve image tags (including base64 embedded images) before text extraction
        val (contentWithoutImages, _) = extractImages(allContent)

        // Extract text from HTML
        val plainText = extractTextFromHtml(contentWithoutImages)
        val paragraphs = plainText.split("\n\n").filter { it.isNotBlank() }

        logcat(LogPriority.DEBUG) { "Translating ${paragraphs.size} paragraphs for chapter ${chapter.name}" }

        // Group paragraphs into chunks to improve translation quality
        val chunkMode = translationPreferences.translationChunkMode().get()
        val chunkSize = translationPreferences.translationChunkSize().get()
        val chunks = buildChunks(paragraphs, chunkMode, chunkSize)

        logcat(LogPriority.DEBUG) { "Grouped into ${chunks.size} chunks (mode=$chunkMode, size=$chunkSize)" }

        // Update progress with chunk info
        _progressState.update { current ->
            current.copy(totalChunks = chunks.size, currentChunkIndex = 0)
        }

        // Check for existing partial translation to resume from
        val repo = translatedChapterRepository
        val existingTmpParagraphs = if (repo is tachiyomi.data.translation.TranslatedChapterRepositoryImpl) {
            val tmp = repo.getTmpTranslation(task.chapterId, task.targetLanguage)
            if (tmp != null) {
                val tmpText = extractTextFromHtml(tmp.translatedContent)
                tmpText.split("\n\n").filter { it.isNotBlank() }
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        // Determine which chunks were already translated (for resume)
        // We count how many source paragraphs the existing tmp translation covers
        var resumeFromChunk = 0
        if (existingTmpParagraphs.isNotEmpty() && !task.forceRetranslate) {
            var coveredParagraphs = 0
            for ((i, chunk) in chunks.withIndex()) {
                val chunkParagraphCount = chunk.split("\n\n").filter { it.isNotBlank() }.size
                coveredParagraphs += chunkParagraphCount
                if (coveredParagraphs <= existingTmpParagraphs.size) {
                    resumeFromChunk = i + 1
                } else {
                    break
                }
            }
            if (resumeFromChunk > 0) {
                logcat(LogPriority.INFO) { "Resuming from chunk $resumeFromChunk/${chunks.size} (${existingTmpParagraphs.size} paragraphs already translated)" }
            }
        }

        // Translate chapter title
        var translatedTitle: String? = null
        try {
            val titleResult = engine.translate(listOf(chapter.name), task.sourceLanguage, task.targetLanguage)
            if (titleResult is TranslationResult.Success) {
                translatedTitle = titleResult.translatedTexts.firstOrNull()?.trim()
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to translate chapter title: ${chapter.name}" }
        }

        // Translate chunks and split responses back into paragraphs
        val allTranslated = mutableListOf<String>()
        // Add previously translated paragraphs
        if (resumeFromChunk > 0) {
            allTranslated.addAll(existingTmpParagraphs)
        }

        var failedChunkIndex = -1
        for ((chunkIndex, chunk) in chunks.withIndex()) {
            // Skip already-translated chunks
            if (chunkIndex < resumeFromChunk) {
                logcat(LogPriority.DEBUG) { "Skipping chunk ${chunkIndex + 1}/${chunks.size} (already translated)" }
                _progressState.update { current ->
                    current.copy(
                        currentChapterProgress = 0.5f + 0.5f * (chunkIndex + 1f) / chunks.size,
                        currentChunkIndex = chunkIndex + 1,
                    )
                }
                continue
            }

            // Check for cancellation
            if (_progressState.value.isCancelling) {
                savePartialTranslation(task, engine.id.toString(), allTranslated, translatedTitle)
                throw CancellationException("Translation cancelled by user")
            }

            logcat(LogPriority.DEBUG) { "Sending chunk ${chunkIndex + 1}/${chunks.size} for translation (${chunk.length} chars)" }

            var chunkSuccess = false
            for (attempt in 1..2) {
                try {
                    val result = engine.translate(listOf(chunk), task.sourceLanguage, task.targetLanguage)
                    when (result) {
                        is TranslationResult.Success -> {
                            val translated = result.translatedTexts.firstOrNull() ?: ""
                            val translatedParagraphs = translated.split("\n\n").filter { it.isNotBlank() }
                            allTranslated.addAll(translatedParagraphs.ifEmpty { listOf(translated) })
                            chunkSuccess = true
                        }
                        is TranslationResult.Error -> {
                            logcat(LogPriority.ERROR) { "Translation error for chunk ${chunkIndex + 1}/${chunks.size} (attempt $attempt): ${result.message}" }
                            if (attempt < 2) {
                                delay(2000L)
                                continue
                            }
                        }
                    }
                    break
                } catch (e: CancellationException) {
                    savePartialTranslation(task, engine.id.toString(), allTranslated, translatedTitle)
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Translation exception for chunk ${chunkIndex + 1}/${chunks.size} (attempt $attempt)" }
                    if (attempt < 2) {
                        delay(2000L)
                        continue
                    }
                    break
                }
            }

            if (!chunkSuccess) {
                failedChunkIndex = chunkIndex
                logcat(LogPriority.ERROR) { "Chunk ${chunkIndex + 1}/${chunks.size} failed after retries, stopping" }
                break
            }

            // Update sub-progress
            _progressState.update { current ->
                current.copy(
                    currentChapterProgress = 0.5f + 0.5f * (chunkIndex + 1f) / chunks.size,
                    currentChunkIndex = chunkIndex + 1,
                )
            }
            // Rate limiting between chunks
            val delayMs = translationPreferences.rateLimitDelayMs().get()
            if (delayMs > 0 && chunkIndex < chunks.size - 1) {
                delay(delayMs.toLong())
            }
        }

        // Check if all chunks were translated successfully
        if (failedChunkIndex >= 0) {
            // Save partial translation as .tmp for resume
            if (allTranslated.isNotEmpty()) {
                savePartialTranslation(task, engine.id.toString(), allTranslated, translatedTitle)
                logcat(LogPriority.WARN) { "Saved partial translation (${allTranslated.size} paragraphs) for chapter ${chapter.name}" }
            }
            throw Exception(
                "Translation incomplete: failed at chunk ${failedChunkIndex + 1}/${chunks.size}. " +
                    "${allTranslated.size} paragraphs translated, will resume on next attempt.",
            )
        }

        if (allTranslated.isEmpty()) throw IllegalStateException("No translation returned")

        // Build the final translated HTML with optional title at start
        val translatedHtml = buildString {
            if (!translatedTitle.isNullOrBlank()) {
                append("<h1>${translatedTitle.trim()}</h1>")
            }
            allTranslated.forEach { paragraph ->
                append("<p>${paragraph.trim().replace("\n", "<br/>")}</p>")
            }
        }

        // Save the complete translation
        val translatedChapter = TranslatedChapter(
            chapterId = task.chapterId,
            mangaId = task.mangaId,
            targetLanguage = task.targetLanguage,
            engineId = engine.id.toString(),
            translatedContent = translatedHtml,
            dateTranslated = System.currentTimeMillis(),
        )
        translatedChapterRepository.upsertTranslation(translatedChapter)

        logcat(LogPriority.DEBUG) { "Successfully translated and saved chapter ${chapter.name}" }

        _progressState.update { current ->
            current.copy(currentChapterProgress = 1f)
        }
    }

    /**
     * Save a partial translation as a .tmp file for later resume.
     */
    private suspend fun savePartialTranslation(
        task: TranslationTask,
        engineId: String,
        translatedParagraphs: List<String>,
        translatedTitle: String?,
    ) {
        if (translatedParagraphs.isEmpty()) return
        val repo = translatedChapterRepository
        if (repo !is tachiyomi.data.translation.TranslatedChapterRepositoryImpl) return

        val html = buildString {
            if (!translatedTitle.isNullOrBlank()) {
                append("<h1>${translatedTitle.trim()}</h1>")
            }
            translatedParagraphs.forEach { paragraph ->
                append("<p>${paragraph.trim().replace("\n", "<br/>")}</p>")
            }
        }

        val partial = TranslatedChapter(
            chapterId = task.chapterId,
            mangaId = task.mangaId,
            targetLanguage = task.targetLanguage,
            engineId = engineId,
            translatedContent = html,
            dateTranslated = System.currentTimeMillis(),
        )
        try {
            repo.upsertTmpTranslation(partial)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to save partial translation" }
        }
    }

    /**
     * Get chapter content either from downloaded files or directly from source.
     * Checks both directory-based downloads and CBZ archives.
     */
    private suspend fun getChapterContent(
        chapter: Chapter,
        manga: Manga,
        source: eu.kanade.tachiyomi.source.Source,
    ): String {
        // First try to get content from downloaded chapter
        val chapterDirOrFile = downloadProvider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            source,
        )

        if (chapterDirOrFile != null) {
            val isCbz = chapterDirOrFile.name?.endsWith(".cbz") == true

            if (isCbz) {
                logcat(LogPriority.DEBUG) { "Reading content from CBZ archive for chapter: ${chapter.name}" }
                try {
                    val inputStream = context.contentResolver.openInputStream(chapterDirOrFile.uri)
                    if (inputStream != null) {
                        val entries = mutableListOf<Pair<String, String>>()
                        java.util.zip.ZipInputStream(inputStream).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && (entry.name.endsWith(".html") || entry.name.endsWith(".txt"))) {
                                    val bytes = zis.readBytes()
                                    val text = bytes.toString(Charsets.UTF_8)
                                    entries.add(entry.name to text)
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                        entries.sortBy { it.first }
                        if (entries.isNotEmpty()) {
                            val content = StringBuilder()
                            entries.forEachIndexed { index, (_, text) ->
                                content.append(text)
                                if (index < entries.size - 1) content.append("\n\n")
                            }
                            return content.toString()
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Failed to read CBZ for chapter: ${chapter.name}" }
                }
            } else {
                // Read from directory
                val htmlFiles = chapterDirOrFile.listFiles()?.filter {
                    it.isFile && (it.name?.endsWith(".html") == true || it.name?.endsWith(".txt") == true)
                }?.sortedBy { it.name } ?: emptyList()

                if (htmlFiles.isNotEmpty()) {
                    logcat(LogPriority.DEBUG) { "Reading content from downloaded chapter directory" }
                    val content = StringBuilder()
                    htmlFiles.forEachIndexed { index, file ->
                        val fileContent = context.contentResolver.openInputStream(file.uri)?.use {
                            it.bufferedReader().readText()
                        } ?: ""
                        content.append(fileContent)
                        if (index < htmlFiles.size - 1) {
                            content.append("\n\n")
                        }

                        // Update progress
                        _progressState.update { current ->
                            current.copy(currentChapterProgress = (index + 1f) / (htmlFiles.size * 2))
                        }
                    }
                    return content.toString()
                }
            }
        }

        // Also try the download cache as a secondary check
        val isDownloaded = downloadCache.isChapterDownloaded(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            manga.source,
            skipCache = false,
        )

        if (isDownloaded && chapterDirOrFile == null) {
            logcat(LogPriority.WARN) { "Download cache says chapter is downloaded but findChapterDir returned null for: ${chapter.name}" }
        }

        // Fall back to fetching from source
        logcat(LogPriority.DEBUG) { "Fetching content from source for chapter: ${chapter.name}" }

        val source = sourceManager.get(manga.source)
            ?: throw IllegalStateException("Source not found for id=${manga.source}")

        if (!source.isNovelSource()) {
            throw IllegalStateException("Source ${source.name} is not a novel source")
        }

        // Create page object for the chapter
        val page = Page(0, chapter.url, chapter.url)

        // Update progress
        _progressState.update { current ->
            current.copy(currentChapterProgress = 0.3f)
        }

        // Fetch content from source
        val content = source.fetchNovelPageText(page)

        // Update progress
        _progressState.update { current ->
            current.copy(currentChapterProgress = 0.5f)
        }

        return content
    }

    private fun updateProgress() {
        _progressState.update { current ->
            current.copy(
                totalChapters = queue.size + current.completedChapters,
            )
        }
        _queueState.value = queue.toList()
    }

    /**
     * Translate text using the configured engine.
     */
    suspend fun translateText(
        text: String,
        sourceLanguage: String = translationPreferences.sourceLanguage().get(),
        targetLanguage: String = translationPreferences.targetLanguage().get(),
    ): TranslationResult {
        val engine = translationEngineManager.getEngine()
            ?: return TranslationResult.Error("No translation engine available")

        return engine.translateSingle(text, sourceLanguage, targetLanguage)
    }

    /**
     * Translate chapter content in real-time (for reader).
     * Extracts text from HTML, translates it, and reconstructs the HTML structure.
     * Saves translations to database for future use.
     */
    suspend fun translateChapterContent(
        content: String,
        chapterId: Long? = null,
        mangaId: Long? = null,
        sourceLanguage: String? = null,
        targetLanguage: String? = null,
    ): String {
        if (!translationPreferences.translationEnabled().get()) {
            return content
        }

        val srcLang = sourceLanguage ?: translationPreferences.sourceLanguage().get()
        val tgtLang = targetLanguage ?: translationPreferences.targetLanguage().get()

        // Don't translate if source and target are the same
        if (srcLang == tgtLang) {
            return content
        }

        // Check for existing translation in database
        if (chapterId != null) {
            val existingTranslation = translatedChapterRepository.getTranslatedChapter(chapterId, tgtLang)
            if (existingTranslation != null) {
                logcat(LogPriority.DEBUG) { "Using cached translation for chapter $chapterId (lang: $tgtLang)" }
                return existingTranslation.translatedContent
            }
        }

        // Extract and preserve image tags before translation
        val (contentWithoutImages, preservedImages) = extractImages(content)

        // Extract plain text from HTML for translation (more efficient and accurate)
        val plainText = extractTextFromHtml(contentWithoutImages)

        // Translate the plain text
        return when (val result = translateText(plainText, srcLang, tgtLang)) {
            is TranslationResult.Success -> {
                val translatedText = result.translatedTexts.firstOrNull() ?: return content
                // Wrap translated text in proper HTML paragraphs
                var translatedHtml = wrapTextInHtml(translatedText)

                // Reinsert preserved image tags
                if (preservedImages.isNotEmpty()) {
                    translatedHtml = reinsertImages(translatedHtml, preservedImages)
                }

                // Save translation to database
                if (chapterId != null && mangaId != null) {
                    val engine = translationEngineManager.getEngine()
                    val translatedChapter = TranslatedChapter(
                        chapterId = chapterId,
                        mangaId = mangaId,
                        targetLanguage = tgtLang,
                        engineId = engine?.id?.toString() ?: "unknown",
                        translatedContent = translatedHtml,
                        dateTranslated = System.currentTimeMillis(),
                    )
                    try {
                        translatedChapterRepository.upsertTranslation(translatedChapter)
                        logcat(LogPriority.DEBUG) { "Saved translation for chapter $chapterId (lang: $tgtLang)" }
                    } catch (e: CancellationException) {
                        logcat(LogPriority.DEBUG) { "Translation save was cancelled for chapter $chapterId" }
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to save translation to database" }
                    }
                }

                translatedHtml
            }
            is TranslationResult.Error -> {
                logcat(LogPriority.WARN) { "Translation failed: ${result.message}" }
                content // Return original content on error
            }
        }
    }

    /**
     * Get translated content from database if available.
     */
    suspend fun getTranslatedContent(
        chapterId: Long,
        targetLanguage: String? = null,
    ): String? {
        val tgtLang = targetLanguage ?: translationPreferences.targetLanguage().get()
        return translatedChapterRepository.getTranslatedChapter(chapterId, tgtLang)?.translatedContent
    }

    /**
     * Check if a translation exists for a chapter.
     */
    suspend fun hasTranslation(
        chapterId: Long,
        targetLanguage: String? = null,
    ): Boolean {
        val tgtLang = targetLanguage ?: translationPreferences.targetLanguage().get()
        return translatedChapterRepository.hasTranslation(chapterId, tgtLang)
    }

    /**
     * Get all available translation languages for a manga.
     */
    suspend fun getTranslatedLanguages(chapterIds: Collection<Long>): List<String> {
        return translatedChapterRepository.getTranslatedLanguagesForChapters(chapterIds)
    }

    /**
     * Delete a translation.
     */
    suspend fun deleteTranslation(chapterId: Long, targetLanguage: String) {
        translatedChapterRepository.deleteTranslation(chapterId, targetLanguage)
    }

    /**
     * Get last used target language (for quick translate).
     */
    fun getLastTargetLanguage(): String {
        return translationPreferences.targetLanguage().get()
    }

    /**
     * Set target language (for language picker).
     */
    fun setTargetLanguage(language: String) {
        translationPreferences.targetLanguage().set(language)
    }

    /**
     * Detect language of text content. Uses LibreTranslate's detection endpoint if available,
     * otherwise falls back to using the manga source's language setting.
     */
    suspend fun detectLanguage(text: String, mangaId: Long? = null): String? {
        val engine = translationEngineManager.getSelectedEngine()

        if (engine is LibreTranslateEngine) {
            val sample = text.take(500)
            return engine.detectLanguage(sample)
        }

        // For non-Libre engines, use the source's language as a heuristic
        if (mangaId != null) {
            val manga = getManga.await(mangaId)
            if (manga != null) {
                val source = sourceManager.get(manga.source)
                if (source != null) {
                    // Source lang is typically a 2-letter ISO code like "en", "ja", "ko"
                    val sourceLang = source.lang
                    if (sourceLang.isNotBlank() && sourceLang != "all" && sourceLang != "other") {
                        return sourceLang
                    }
                }
            }
        }

        // If source language preference is set (not "auto"), use that
        val configuredLang = translationPreferences.sourceLanguage().get()
        if (configuredLang.isNotBlank() && configuredLang != "auto") {
            return configuredLang
        }

        return null
    }

    /**
     * Regex to match image-like HTML elements that should be preserved through translation.
     * Matches <img>, <figure>, <picture>, and their closing tags.
     */
    private val imageTagRegex = Regex(
        """<(?:img|figure|picture|video|source|svg)[^>]*(?:/>|>(?:.*?</(?:figure|picture|video|svg)>)?)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * Extract image tags from HTML, replacing them with unique placeholders.
     * Returns the modified HTML and a map of placeholder -> original tag.
     */
    private fun extractImages(html: String): Pair<String, Map<String, String>> {
        val images = mutableMapOf<String, String>()
        var index = 0
        val result = imageTagRegex.replace(html) { match ->
            val placeholder = "\n[IMG_PLACEHOLDER_$index]\n"
            images[placeholder.trim()] = match.value
            index++
            placeholder
        }
        return result to images
    }

    /**
     * Reinsert preserved image tags into translated content.
     */
    private fun reinsertImages(translatedHtml: String, images: Map<String, String>): String {
        var result = translatedHtml
        for ((placeholder, originalTag) in images) {
            result = result.replace(placeholder, originalTag)
        }
        return result
    }

    /**
     * Extract plain text from HTML content.
     * Preserves paragraph structure by converting to newlines.
     */
    private fun extractTextFromHtml(html: String): String {
        return html
            // Strip base64 data URIs (embedded images) before text extraction
            .replace(Regex("data:[a-zA-Z0-9/+.-]+;base64,[A-Za-z0-9+/=\\s]+"), "")
            // Convert paragraph and line breaks to newlines
            .replace(Regex("</p>\\s*<p[^>]*>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            // Remove all HTML tags
            .replace(Regex("<[^>]+>"), "")
            // Decode common HTML entities
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            // Clean up excessive whitespace
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Wrap plain text back into HTML paragraphs.
     */
    private fun wrapTextInHtml(text: String): String {
        return text
            .split("\n\n")
            .filter { it.isNotBlank() }
            .joinToString("") { paragraph ->
                "<p>${paragraph.trim().replace("\n", "<br/>")}</p>"
            }
    }

    /**
     * Group paragraphs into translation chunks based on the configured mode.
     * Each chunk is a single string with paragraphs separated by double newlines.
     * This keeps context together so LLMs produce better translations and don't skip content.
     */
    private fun buildChunks(paragraphs: List<String>, chunkMode: String, chunkSize: Int): List<String> {
        if (paragraphs.isEmpty()) return emptyList()
        // Always group by paragraphs
        return paragraphs.chunked(chunkSize.coerceAtLeast(1)).map { group ->
            group.joinToString("\n\n")
        }
    }

    companion object {
        /**
         * Priority values for translation queue.
         */
        const val PRIORITY_LOW = 0
        const val PRIORITY_NORMAL = 50
        const val PRIORITY_HIGH = 100
        const val PRIORITY_MANUAL_READ = 200 // User manually opened chapter
    }
}
