package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import kotlinx.coroutines.Job

sealed class TtsHandoffState<out P> {

    object Idle : TtsHandoffState<Nothing>()
    data class PreFetching(val anchorChapterId: Long?) : TtsHandoffState<Nothing>()
    data class Cached<out P>(val payload: P) : TtsHandoffState<P>()

    /** Caller must cancel [watchdog] before transitioning away from this state. */
    data class Appending(val watchdog: Job) : TtsHandoffState<Nothing>()

    val isIdle: Boolean get() = this is Idle
    val isPreFetching: Boolean get() = this is PreFetching
    val isAppending: Boolean get() = this is Appending
    val cachedOrNull: P? get() = (this as? Cached<P>)?.payload

    val timeoutJob: Job?
        get() = (this as? Appending)?.watchdog
}
