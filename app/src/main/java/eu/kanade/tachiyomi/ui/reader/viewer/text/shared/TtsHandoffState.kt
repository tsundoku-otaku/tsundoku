package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

sealed class TtsHandoffState<out P> {

    object Idle : TtsHandoffState<Nothing>()
    data class PreFetching(val anchorChapterId: Long?) : TtsHandoffState<Nothing>()
    data class Cached<out P>(val payload: P) : TtsHandoffState<P>()

    val isIdle: Boolean get() = this is Idle
    val isPreFetching: Boolean get() = this is PreFetching
    val cachedOrNull: P? get() = (this as? Cached<P>)?.payload
}
