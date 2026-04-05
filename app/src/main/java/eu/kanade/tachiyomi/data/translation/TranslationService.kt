package eu.kanade.tachiyomi.data.translation

import android.content.Context
import eu.kanade.tachiyomi.data.download.ChapterContentReader
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.translation.engine.DeepSeekTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.GeminiTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.LibreTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.OllamaTranslateEngine
import eu.kanade.tachiyomi.data.translation.engine.OpenAITranslateEngine
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for managing translation queue and executing translations.
 *
 * Thread-safe: uses [ConcurrentHashMap] keyed by chapterId to avoid
 * the check-then-act race that existed with the old ConcurrentLinkedQueue.
 *
 * HTML utilities are delegated to [TranslationHtmlUtils].
 * Chapter content reading is delegated to [ChapterContentReader].
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
    private val chapterContentReader: ChapterContentReader by lazy { ChapterContentReader(context, downloadProvider) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Thread-safe queue keyed by chapterId — atomic putIfAbsent replaces check-then-act (fix 4.1)
    private val queueMap = ConcurrentHashMap<Long, TranslationTask>()

    /**
     * The chapter ID currently being translated.
     * Exposed so the queue UI can highlight it (fix 6.2).
     */
    private val _currentTranslatingChapterId = MutableStateFlow<Long?>(null)
    val currentTranslatingChapterId = _currentTranslatingChapterId.asStateFlow()

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
            chapterName = chapter.name,
            sourceLanguage = translationPreferences.sourceLanguage().get(),
            targetLanguage = translationPreferences.targetLanguage().get(),
            engineId = 0L,
            priority = priority,
            status = TranslationStatus.QUEUED,
            retryCount = 0,
            forceRetranslate = forceRetranslate,
        )

        // Atomic: only inserts if the key is absent (fix 4.1)
        if (queueMap.putIfAbsent(chapter.id, task) == null) {
            publishQueueState()
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
        queueMap.remove(chapterId)
        publishQueueState()
    }

    /**
     * Clear all items from the queue.
     */
    fun clearQueue() {
        queueMap.clear()
        publishQueueState()
    }

    /**
     * Start processing the translation queue.
     */
    fun start() {
        if (translationJob?.isActive == true) return

        _isPaused.value = false

        translationJob = scope.launch {
            while (isActive && queueMap.isNotEmpty()) {
                if (_isPaused.value) {
                    delay(PAUSE_POLL_DELAY_MS)
                    continue
                }

                // Pick highest-priority task (fix 4.3)
                val task = pollHighestPriority() ?: break

                _currentTranslatingChapterId.value = task.chapterId

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
                    if (retryCount < MAX_TASK_RETRIES) {
                        queueMap[task.chapterId] = task.copy(
                            status = TranslationStatus.FAILED,
                            errorMessage = e.message,
                            retryCount = retryCount,
                        )
                    } else {
                        logcat(LogPriority.ERROR) { "Max retries reached for chapter ${task.chapterId}, skipping" }
                    }
                } finally {
                    _currentTranslatingChapterId.value = null
                    publishQueueState()
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

    /** Atomically remove and return the highest-priority task from the queue. */
    private fun pollHighestPriority(): TranslationTask? {
        if (queueMap.isEmpty()) return null
        val best = queueMap.entries.maxByOrNull { it.value.priority } ?: return null
        return queueMap.remove(best.key)
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
        if (translationJob?.isActive != true && queueMap.isNotEmpty()) {
            start()
        }
    }

    /**
     * Stop translation processing.
     */
    fun stop() {
        translationJob?.cancel()
        translationJob = null
        _currentTranslatingChapterId.value = null
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
    fun queueSize(): Int = queueMap.size

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
            val existingTranslation = translatedChapterRepository.getTranslatedChapter(
                task.chapterId,
                task.targetLanguage,
            )
            if (existingTranslation != null) {
                logcat(LogPriority.DEBUG) { "Chapter ${chapter.name} already translated, skipping" }
                return@withContext
            }
        }

        if (translationPreferences.smartAutoTranslate().get()) {
            val sourceLang = source.lang
            if (sourceLang.isNotBlank() && sourceLang != "all" && sourceLang != "other") {
                if (TranslationHtmlUtils.languageCodesMatch(sourceLang, task.targetLanguage)) {
                    logcat(LogPriority.DEBUG) { "Source language ($sourceLang) matches target language, skipping" }
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
            if (detected != null && TranslationHtmlUtils.languageCodesMatch(detected, task.targetLanguage)) {
                logcat(LogPriority.DEBUG) { "Detected language ($detected) matches target language, skipping" }
                return@withContext
            }
        }

        // Extract and preserve media tags (delegated — fix SRP)
        val (contentWithoutImages, _) = TranslationHtmlUtils.extractImages(allContent)

        // Extract text from HTML (delegated — fix DRY 2.4)
        val plainText = TranslationHtmlUtils.extractTextFromHtml(contentWithoutImages)
        val paragraphs = plainText.split("\n\n").filter { it.isNotBlank() }

        logcat(LogPriority.DEBUG) { "Translating ${paragraphs.size} paragraphs for chapter ${chapter.name}" }

        // Group paragraphs into chunks to improve translation quality
        val chunkSize = translationPreferences.translationChunkSize().get()
        val chunks = TranslationHtmlUtils.buildChunks(paragraphs, chunkSize)

        logcat(LogPriority.DEBUG) { "Grouped into ${chunks.size} chunks (size=$chunkSize)" }

        // Update progress with chunk info
        _progressState.update { current ->
            current.copy(totalChunks = chunks.size, currentChunkIndex = 0)
        }

        // Check for existing partial translation to resume from
        val existingTmpParagraphs = run {
            val tmp = translatedChapterRepository.getTmpTranslation(task.chapterId, task.targetLanguage)
            if (tmp != null) {
                TranslationHtmlUtils.extractTextFromHtml(tmp.translatedContent)
                    .split("\n\n").filter { it.isNotBlank() }
            } else {
                emptyList()
            }
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
                logcat(LogPriority.INFO) { "Resuming from chunk $resumeFromChunk/${chunks.size}" }
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
        // Contextual anchoring: send previous raw + translated paragraphs as context for LLM engines
        val isLlmEngine = engine.id in LLM_ENGINE_IDS
        val anchoringEnabled = translationPreferences.contextualAnchoringEnabled().get()
        val anchoringParagraphs = translationPreferences.contextualAnchoringParagraphs().get()
        val useAnchoring = isLlmEngine && anchoringEnabled && anchoringParagraphs > 0

        // Build per-chunk paragraph lists for context tracking
        val chunkParagraphsList = chunks.map { c -> c.split("\n\n").filter { it.isNotBlank() } }
        // Track the raw paragraphs of the previously translated chunk
        var previousRawParagraphs = emptyList<String>()
        // Track the translated paragraphs of the previous chunk
        var previousTranslatedParagraphs = emptyList<String>()

        for ((chunkIndex, chunk) in chunks.withIndex()) {
            // Skip already-translated chunks
            if (chunkIndex < resumeFromChunk) {
                logcat(LogPriority.DEBUG) { "Skipping chunk ${chunkIndex + 1}/${chunks.size} (already translated)" }
                // Track the last skipped chunk's paragraphs for contextual anchoring
                if (useAnchoring) {
                    previousRawParagraphs = chunkParagraphsList[chunkIndex]
                    // Estimate translated paragraphs from existing tmp data
                    val startIdx = chunkParagraphsList.take(chunkIndex).sumOf { it.size }
                    val endIdx = startIdx + chunkParagraphsList[chunkIndex].size
                    previousTranslatedParagraphs = existingTmpParagraphs.drop(startIdx).take(endIdx - startIdx)
                }
                _progressState.update { current ->
                    current.copy(
                        currentChapterProgress =
                        PROGRESS_TRANSLATE_START + PROGRESS_TRANSLATE_RANGE * (chunkIndex + 1f) / chunks.size,
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

            logcat(LogPriority.DEBUG) {
                "Sending chunk ${chunkIndex + 1}/${chunks.size} for translation (${chunk.length} chars)"
            }

            // Build the text to send, optionally wrapping with context for LLM engines
            val textToSend = if (useAnchoring && chunkIndex > 0 && previousRawParagraphs.isNotEmpty()) {
                buildContextualAnchoringPrompt(
                    rawContext = previousRawParagraphs.takeLast(anchoringParagraphs),
                    translatedContext = previousTranslatedParagraphs.takeLast(anchoringParagraphs),
                    chunk = chunk,
                    expectedParagraphs = chunkParagraphsList[chunkIndex].size,
                )
            } else {
                chunk
            }

            var chunkSuccess = false
            for (attempt in 1..MAX_CHUNK_RETRIES) {
                try {
                    val result = engine.translate(listOf(textToSend), task.sourceLanguage, task.targetLanguage)
                    when (result) {
                        is TranslationResult.Success -> {
                            val translated = result.translatedTexts.firstOrNull() ?: ""
                            // Strip any contextual anchoring markers that the LLM might echo back
                            val cleanedTranslation = if (useAnchoring && chunkIndex > 0) {
                                TranslationHtmlUtils.stripContextLeakage(translated)
                            } else {
                                translated
                            }
                            val translatedParagraphs = cleanedTranslation.split("\n\n").filter { it.isNotBlank() }
                            // Update previous chunk tracking for next iteration
                            previousRawParagraphs = chunkParagraphsList[chunkIndex]
                            previousTranslatedParagraphs = translatedParagraphs.ifEmpty { listOf(cleanedTranslation) }
                            allTranslated.addAll(previousTranslatedParagraphs)
                            chunkSuccess = true
                        }
                        is TranslationResult.Error -> {
                            logcat(LogPriority.ERROR) {
                                "Translation error chunk ${chunkIndex + 1}/${chunks.size} (attempt $attempt): ${result.message}"
                            }
                            if (attempt < MAX_CHUNK_RETRIES) {
                                delay(RETRY_DELAY_MS)
                                continue
                            }
                        }
                    }
                    break
                } catch (e: CancellationException) {
                    savePartialTranslation(task, engine.id.toString(), allTranslated, translatedTitle)
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) {
                        "Translation exception chunk ${chunkIndex + 1}/${chunks.size} (attempt $attempt)"
                    }
                    if (attempt < MAX_CHUNK_RETRIES) {
                        delay(RETRY_DELAY_MS)
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
                    currentChapterProgress =
                    PROGRESS_TRANSLATE_START + PROGRESS_TRANSLATE_RANGE * (chunkIndex + 1f) / chunks.size,
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
                logcat(LogPriority.WARN) {
                    "Saved partial translation (${allTranslated.size} paragraphs) for chapter ${chapter.name}"
                }
            }
            throw Exception(
                "Translation incomplete: failed at chunk ${failedChunkIndex + 1}/${chunks.size}. " +
                    "${allTranslated.size} paragraphs translated, will resume on next attempt.",
            )
        }

        if (allTranslated.isEmpty()) throw IllegalStateException("No translation returned")

        // Build final HTML (single source of truth — fix DRY 2.1, HTML escaping — fix 6.5)
        val translatedHtml = TranslationHtmlUtils.buildTranslatedHtml(translatedTitle, allTranslated)

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

    /** Build the contextual-anchoring prompt sent to LLM engines. */
    private fun buildContextualAnchoringPrompt(
        rawContext: List<String>,
        translatedContext: List<String>,
        chunk: String,
        expectedParagraphs: Int,
    ): String = buildString {
        appendLine("You are translating a novel.")
        appendLine("Below is previous context. Do NOT translate it.")
        appendLine("=== PREVIOUS RAW ===")
        appendLine(rawContext.joinToString("\n\n"))
        appendLine("=== PREVIOUS TRANSLATION ===")
        appendLine(translatedContext.joinToString("\n\n"))
        appendLine("=== TEXT TO TRANSLATE (RETURN ONLY THIS SECTION) ===")
        appendLine(chunk)
        appendLine("Translate ONLY the section under \"TEXT TO TRANSLATE\".")
        appendLine("Preserve paragraph breaks exactly.")
        appendLine("Do not merge or split paragraphs.")
        append("Return exactly $expectedParagraphs paragraphs.")
    }

    /**
     * Save a partial translation as a .tmp file for later resume.
     * Uses [TranslationHtmlUtils.buildTranslatedHtml] (fix DRY 2.1).
     */
    private suspend fun savePartialTranslation(
        task: TranslationTask,
        engineId: String,
        translatedParagraphs: List<String>,
        translatedTitle: String?,
    ) {
        if (translatedParagraphs.isEmpty()) return

        val html = TranslationHtmlUtils.buildTranslatedHtml(translatedTitle, translatedParagraphs)

        val partial = TranslatedChapter(
            chapterId = task.chapterId,
            mangaId = task.mangaId,
            targetLanguage = task.targetLanguage,
            engineId = engineId,
            translatedContent = html,
            dateTranslated = System.currentTimeMillis(),
        )
        try {
            translatedChapterRepository.upsertTmpTranslation(partial)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to save partial translation" }
        }
    }

    /**
     * Get chapter content from downloaded files or source.
     * Delegates filesystem reading to the shared [ChapterContentReader] (fix 1.2 DRY).
     */
    private suspend fun getChapterContent(
        chapter: Chapter,
        manga: Manga,
        source: eu.kanade.tachiyomi.source.Source,
    ): String {
        // Try downloaded content first via shared reader
        val downloadedContent = chapterContentReader.readDownloadedContent(manga, chapter, source)
        if (!downloadedContent.isNullOrBlank()) {
            return downloadedContent
        }

        // Check download cache for inconsistency logging
        val isDownloaded = downloadCache.isChapterDownloaded(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            manga.source,
            skipCache = false,
        )
        if (isDownloaded) {
            logcat(LogPriority.WARN) {
                "Download cache says chapter is downloaded but no content found: ${chapter.name}"
            }
        }

        // Fall back to fetching from source
        logcat(LogPriority.DEBUG) { "Fetching content from source for chapter: ${chapter.name}" }

        if (!source.isNovelSource()) {
            throw IllegalStateException("Source ${source.name} is not a novel source")
        }

        val page = Page(0, chapter.url, chapter.url)

        _progressState.update { it.copy(currentChapterProgress = PROGRESS_FETCH_START) }

        val content = try {
            source.fetchNovelPageText(page)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to fetch novel page text for chapter: ${chapter.name}" }
            throw e
        }

        _progressState.update { it.copy(currentChapterProgress = PROGRESS_TRANSLATE_START) }

        return content
    }

    private fun publishQueueState() {
        val snapshot = queueMap.values.sortedByDescending { it.priority }
        _progressState.update { current ->
            current.copy(totalChapters = snapshot.size + current.completedChapters)
        }
        _queueState.value = snapshot
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
        if (TranslationHtmlUtils.languageCodesMatch(srcLang, tgtLang)) {
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
        val (contentWithoutImages, preservedImages) = TranslationHtmlUtils.extractImages(content)

        // Extract plain text from HTML for translation
        val plainText = TranslationHtmlUtils.extractTextFromHtml(contentWithoutImages)

        // Translate the plain text
        return when (val result = translateText(plainText, srcLang, tgtLang)) {
            is TranslationResult.Success -> {
                val translatedText = result.translatedTexts.firstOrNull() ?: return content
                var translatedHtml = TranslationHtmlUtils.wrapTextInHtml(translatedText)

                // Reinsert preserved image tags
                if (preservedImages.isNotEmpty()) {
                    translatedHtml = TranslationHtmlUtils.reinsertImages(translatedHtml, preservedImages)
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
            val sample = text.take(DETECT_SAMPLE_SIZE)
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

    companion object {
        /** Priority values for translation queue. */
        const val PRIORITY_LOW = 0
        const val PRIORITY_NORMAL = 50
        const val PRIORITY_HIGH = 100
        const val PRIORITY_MANUAL_READ = 200

        /** Engine IDs for LLM-based engines that support contextual anchoring. */
        val LLM_ENGINE_IDS = setOf(
            OpenAITranslateEngine.ENGINE_ID,
            DeepSeekTranslateEngine.ENGINE_ID,
            OllamaTranslateEngine.ENGINE_ID,
            GeminiTranslateEngine.ENGINE_ID,
        )

        /** Max retries per chunk before giving up. */
        private const val MAX_CHUNK_RETRIES = 2

        /** Max retries per task at the queue level before dropping. */
        private const val MAX_TASK_RETRIES = 2

        /** Delay between chunk retries in milliseconds. */
        private const val RETRY_DELAY_MS = 2000L

        /** Delay between pause polling iterations. */
        private const val PAUSE_POLL_DELAY_MS = 500L

        /** Progress fraction at which source fetch completes. */
        private const val PROGRESS_FETCH_START = 0.3f

        /** Progress fraction at which translation begins. */
        private const val PROGRESS_TRANSLATE_START = 0.5f

        /** Range of progress bar dedicated to translation chunks. */
        private const val PROGRESS_TRANSLATE_RANGE = 0.5f

        /** Number of characters to sample for language detection. */
        private const val DETECT_SAMPLE_SIZE = 500
    }
}
