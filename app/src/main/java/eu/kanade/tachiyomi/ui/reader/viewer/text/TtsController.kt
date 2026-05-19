package eu.kanade.tachiyomi.ui.reader.viewer.text

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import java.util.Locale
import kotlin.math.roundToInt

class TtsController(
    private val context: Context,
    private val preferences: ReaderPreferences,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
) : TextToSpeech.OnInitListener {

    interface Callbacks {
        fun onInitialized(pendingRequest: StartRequest?)
        fun getCurrentPage(): ReaderPage?
        fun onHighlightChunk(chunkIndex: Int, chunk: String, startOffset: Int, paragraphIndex: Int)
        fun onClearHighlights()
        fun onLastChunkDone()
        fun runOnUiThread(action: () -> Unit)
    }

    enum class StartRequest { NORMAL, VIEWPORT }

    private var tts: TextToSpeech? = null
    var ttsInitialized = false
        private set

    var isTtsAutoPlay = false
    var ttsPaused = false

    var ttsChunks: List<String> = emptyList()
        private set
    var ttsChunkParagraphIndexes: List<Int> = emptyList()
        private set
    var ttsChunkStartOffsets: List<Int> = emptyList()
        private set
    var ttsCurrentParagraphs: List<NovelViewerTextUtils.ParagraphInfo> = emptyList()
        private set

    var ttsPlaybackChapterIndex: Int = 0
        private set
    var ttsPlaybackChapterId: Long? = null
        private set

    @Volatile var ttsCurrentChunkIndex = 0
    var ttsResumeChunkIndex: Int = 0
    var ttsViewportParagraphIndex: Int = 0
    var hasViewportStartOverride: Boolean = false
    var pendingStartRequest: StartRequest? = null

    fun ensureInitialized() {
        if (tts == null) {
            try {
                tts = TextToSpeech(context, this)
                logcat(LogPriority.DEBUG) { "TTS: Initialization started" }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "TTS: Failed to create instance: ${e.message}" }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsInitialized = true
            applySettings()
            setupListener()
            val pending = pendingStartRequest
            pendingStartRequest = null
            callbacks.runOnUiThread {
                callbacks.onInitialized(pending)
            }
        } else {
            ttsInitialized = false
            logcat(LogPriority.ERROR) { "TTS initialization failed with status: $status" }
        }
    }

    private fun setupListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.removePrefix("tts_utterance_")?.toIntOrNull()?.let { chunkIndex ->
                    ttsCurrentChunkIndex = chunkIndex
                    if (preferences.novelTtsEnableHighlight.get()) {
                        val chunk = ttsChunks.getOrNull(chunkIndex) ?: return
                        val startOffset = ttsChunkStartOffsets.getOrElse(chunkIndex) { 0 }
                        val paragraphIndex = ttsChunkParagraphIndexes.getOrElse(chunkIndex) { chunkIndex }
                        callbacks.runOnUiThread {
                            callbacks.onHighlightChunk(chunkIndex, chunk, startOffset, paragraphIndex)
                        }
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                val finishedIndex = utteranceId?.removePrefix("tts_utterance_")?.toIntOrNull() ?: -1
                val isLastChunk = finishedIndex >= ttsChunks.size - 1
                if (isLastChunk && isTtsAutoPlay && preferences.novelTtsAutoNextChapter.get()) {
                    callbacks.runOnUiThread {
                        scope.launch {
                            delay(LAST_CHUNK_DONE_DELAY_MS)
                            if (!isSpeaking()) {
                                callbacks.onLastChunkDone()
                            }
                        }
                    }
                } else if (isLastChunk) {
                    callbacks.runOnUiThread {
                        callbacks.onClearHighlights()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                logcat(LogPriority.ERROR) { "TTS error on utterance: $utteranceId" }
            }
        })
    }

    fun speak(text: String, chapterIndex: Int = 0, chapterId: Long? = null) {
        if (!ttsInitialized || tts == null) {
            logcat(LogPriority.WARN) { "TTS not initialized, cannot speak" }
            return
        }
        ttsPlaybackChapterIndex = chapterIndex
        ttsPlaybackChapterId = chapterId
        applySettings()
        ttsPaused = false

        val maxLength = TextToSpeech.getMaxSpeechInputLength().takeIf { it > 0 } ?: 4000
        val paragraphs = text.split("\n").filter { it.isNotBlank() }
        val chunkParagraphIndexes = mutableListOf<Int>()

        val chunks = if (paragraphs.size > 1) {
            paragraphs.flatMapIndexed { paragraphIndex, para ->
                val c = if (para.length <= maxLength) listOf(para)
                        else NovelViewerTextUtils.splitTextForTts(para, maxLength)
                repeat(c.size) { chunkParagraphIndexes.add(paragraphIndex) }
                c
            }
        } else if (text.length <= maxLength) {
            chunkParagraphIndexes.add(0)
            listOf(text)
        } else {
            val c = NovelViewerTextUtils.splitTextForTts(text, maxLength)
            repeat(c.size) { chunkParagraphIndexes.add(0) }
            c
        }
        ttsChunks = chunks
        ttsChunkParagraphIndexes = chunkParagraphIndexes

        // Compute char offset per chunk so Spannable-based highlight can locate the right occurrence.
        val offsets = mutableListOf<Int>()
        var searchFrom = 0
        for (chunk in ttsChunks) {
            val idx = text.indexOf(chunk, searchFrom)
            if (idx >= 0) { offsets.add(idx); searchFrom = idx + chunk.length }
            else offsets.add(searchFrom)
        }
        ttsChunkStartOffsets = offsets

        ttsCurrentParagraphs = NovelViewerTextUtils.findParagraphs(text)

        ttsCurrentChunkIndex = 0
        val startIndex = if (hasViewportStartOverride) {
            ttsChunkParagraphIndexes.indexOfFirst { it >= ttsViewportParagraphIndex }
                .takeIf { it >= 0 } ?: 0
        } else {
            0
        }
        hasViewportStartOverride = false

        speakChunksFrom(startIndex.coerceIn(0, (ttsChunks.size - 1).coerceAtLeast(0)))
    }

    fun speakChunksFrom(startIndex: Int) {
        if (ttsChunks.isEmpty() || startIndex >= ttsChunks.size) return
        ttsChunks.drop(startIndex).forEachIndexed { i, chunk ->
            val actualIndex = startIndex + i
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(chunk, mode, null, "tts_utterance_$actualIndex")
        }
    }

    fun pause() {
        if (ttsInitialized && tts?.isSpeaking == true) {
            ttsPaused = true
            ttsResumeChunkIndex = ttsCurrentChunkIndex
            tts?.stop()
        }
    }

    fun resume() {
        if (ttsPaused && ttsChunks.isNotEmpty()) {
            ttsPaused = false
            speakChunksFrom(ttsResumeChunkIndex)
        }
    }

    fun stop() {
        isTtsAutoPlay = false
        ttsPaused = false
        pendingStartRequest = null
        ttsChunks = emptyList()
        ttsChunkParagraphIndexes = emptyList()
        ttsChunkStartOffsets = emptyList()
        ttsCurrentChunkIndex = 0
        ttsResumeChunkIndex = 0
        hasViewportStartOverride = false
        if (ttsInitialized) tts?.stop()
        ttsPlaybackChapterIndex = 0
        ttsPlaybackChapterId = null
        callbacks.runOnUiThread { callbacks.onClearHighlights() }
    }

    fun stepParagraph(delta: Int, onEmpty: () -> Unit) {
        if (delta == 0) return
        if (ttsChunks.isEmpty()) { onEmpty(); return }

        val target = NovelViewerTextUtils.computeTtsStepTargetChunk(
            delta, ttsPaused, ttsResumeChunkIndex, ttsCurrentChunkIndex, ttsChunks, ttsChunkParagraphIndexes,
        )
        ttsResumeChunkIndex = target
        ttsCurrentChunkIndex = target
        ttsPaused = false
        tts?.stop()
        speakChunksFrom(target)
        if (preferences.novelTtsEnableHighlight.get()) {
            val chunk = ttsChunks.getOrNull(target) ?: return
            callbacks.onHighlightChunk(
                target, chunk,
                ttsChunkStartOffsets.getOrElse(target) { 0 },
                ttsChunkParagraphIndexes.getOrElse(target) { 0 },
            )
        }
    }

    fun isSpeaking(): Boolean = ttsInitialized && tts?.isSpeaking == true
    fun isPaused(): Boolean = ttsPaused
    fun isStarting(): Boolean =
        pendingStartRequest != null || (!ttsInitialized && tts != null) || (ttsChunks.isEmpty() && isTtsAutoPlay)

    fun getProgressPercent(): Int {
        if (ttsChunks.isEmpty()) return 0
        val current = (if (ttsPaused) ttsResumeChunkIndex else ttsCurrentChunkIndex)
            .coerceIn(0, ttsChunks.size - 1)
        return (((current + 1) * 100f) / ttsChunks.size).roundToInt().coerceIn(0, 100)
    }

    fun applySettings() {
        tts?.let { engine ->
            val voicePref = preferences.novelTtsVoice.get()
            if (voicePref.isNotEmpty()) {
                val selected = engine.voices?.find { it.name == voicePref }
                if (selected != null) {
                    engine.voice = selected
                } else {
                    try {
                        engine.language = Locale.forLanguageTag(voicePref)
                    } catch (e: Exception) {
                        engine.language = Locale.getDefault()
                    }
                }
            } else {
                engine.language = Locale.getDefault()
            }
            engine.setSpeechRate(preferences.novelTtsSpeed.get())
            engine.setPitch(preferences.novelTtsPitch.get())
        }
    }

    fun getAvailableVoices(): List<Pair<String, String>> =
        tts?.voices?.map { voice ->
            Pair(voice.name, "${voice.locale.displayLanguage} (${voice.name})")
        }?.sortedBy { it.second } ?: emptyList()

    fun getCurrentVoiceName(): String =
        tts?.voice?.name ?: preferences.novelTtsVoice.get()

    fun destroy() {
        tts?.stop()
        callbacks.runOnUiThread { callbacks.onClearHighlights() }
        tts?.shutdown()
        tts = null
        ttsInitialized = false
    }

    companion object {
        private const val LAST_CHUNK_DONE_DELAY_MS = 500L
    }
}
